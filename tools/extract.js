#!/usr/bin/env node
// Plockar ut RFCOMM-data (t.ex. Bose Connect-kommandon) ur en Android Bluetooth-logg.
// Användning:  node tools/extract.js <bugreport.zip | btsnoop_hci.log>
'use strict';
const fs = require('fs');
const zlib = require('zlib');

// ---------- zip (för bugreport.zip) ----------
function zipEntries(buf) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('Inte en giltig zip-fil');
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  const out = [];
  for (let n = 0; n < count && buf.readUInt32LE(p) === 0x02014b50; n++) {
    const nlen = buf.readUInt16LE(p + 28), elen = buf.readUInt16LE(p + 30), clen = buf.readUInt16LE(p + 32);
    out.push({
      name: buf.toString('utf8', p + 46, p + 46 + nlen),
      method: buf.readUInt16LE(p + 10),
      csize: buf.readUInt32LE(p + 20),
      lho: buf.readUInt32LE(p + 42),
    });
    p += 46 + nlen + elen + clen;
  }
  return out;
}

function zipRead(buf, e) {
  const start = e.lho + 30 + buf.readUInt16LE(e.lho + 26) + buf.readUInt16LE(e.lho + 28);
  const data = buf.subarray(start, start + e.csize);
  if (e.method === 0) return data;
  if (e.method === 8) return zlib.inflateRawSync(data);
  throw new Error('Okänd komprimering i zip: ' + e.method);
}

// ---------- RFCOMM ----------
const CRC = new Uint8Array(256);
for (let i = 0; i < 256; i++) {
  let c = i;
  for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xe0 : c >>> 1;
  CRC[i] = c;
}
function fcs(bytes) {
  let f = 0xff;
  for (const b of bytes) f = CRC[f ^ b];
  return 0xff - f;
}

function parseRfcomm(b) {
  if (b.length < 4) return null;
  const addr = b[0], ctrl = b[1];
  if (!(addr & 1) || (ctrl & 0xef) !== 0xef) return null; // måste vara UIH-ram
  let p, len;
  if (b[2] & 1) { len = b[2] >> 1; p = 3; }
  else { if (b.length < 5) return null; len = (b[2] >> 1) | (b[3] << 7); p = 4; }
  const dlci = addr >> 2;
  if ((ctrl & 0x10) && dlci !== 0) p += 1; // kreditbyte
  if (p + len + 1 !== b.length) return null;
  if (b[b.length - 1] !== fcs([addr, ctrl])) return null;
  return { dlci, info: b.subarray(p, p + len) };
}

// ---------- btsnoop / HCI / L2CAP ----------
const EPOCH_DELTA = 0x00dcddb30f2f8000n;
const hex = (b) => Array.from(b, (x) => x.toString(16).padStart(2, '0').toUpperCase()).join(' ');

function analyze(buf) {
  if (buf.toString('latin1', 0, 8) !== 'btsnoop\0') throw new Error('Inte en btsnoop-fil');
  const datalink = buf.readUInt32BE(12);
  const handles = new Map();  // handle -> bdaddr
  const pending = new Map();  // handle:dir -> Buffer
  const frames = [];
  let p = 16;
  while (p + 24 <= buf.length) {
    const incl = buf.readUInt32BE(p + 4);
    const flags = buf.readUInt32BE(p + 8);
    const ts = buf.readBigUInt64BE(p + 16);
    let data = buf.subarray(p + 24, p + 24 + incl);
    p += 24 + incl;
    if (data.length < incl) break;

    let type;
    if (datalink === 1002) { type = data[0]; data = data.subarray(1); }
    else type = flags & 2 ? (flags & 1 ? 4 : 1) : 2;
    const sent = (flags & 1) === 0;

    if (type === 4 && data[0] === 0x03 && data.length >= 11 && data[2] === 0) {
      // Connection Complete
      handles.set(data.readUInt16LE(3) & 0xfff, hex(Buffer.from(data.subarray(5, 11)).reverse()).replace(/ /g, ':'));
    }
    if (type !== 2 || data.length < 4) continue;

    const hdr = data.readUInt16LE(0);
    const handle = hdr & 0xfff, pb = (hdr >> 12) & 3;
    const key = handle + ':' + sent;
    const chunk = data.subarray(4, 4 + data.readUInt16LE(2));
    let l2 = pb === 1 ? (pending.has(key) ? Buffer.concat([pending.get(key), chunk]) : null) : Buffer.from(chunk);
    if (!l2) continue;
    if (l2.length < 4 || l2.length < 4 + l2.readUInt16LE(0)) { pending.set(key, l2); continue; }
    pending.delete(key);

    const cid = l2.readUInt16LE(2);
    if (cid < 0x40) continue;
    const r = parseRfcomm(l2.subarray(4, 4 + l2.readUInt16LE(0)));
    if (!r || r.dlci === 0 || r.info.length === 0) continue;
    frames.push({ ts, sent, dev: handles.get(handle) || 'handle ' + handle, channel: r.dlci >> 1, info: r.info });
  }
  return frames;
}

function fmtTime(ts) {
  const d = new Date(Number((ts - EPOCH_DELTA) / 1000n));
  return d.toLocaleTimeString('sv-SE') + '.' + String(d.getMilliseconds()).padStart(3, '0');
}

function main() {
  const file = process.argv[2];
  if (!file) {
    console.log('Användning: node tools/extract.js <bugreport.zip | btsnoop_hci.log>');
    process.exit(1);
  }
  const buf = fs.readFileSync(file);
  let logs;
  if (buf.readUInt32LE(0) === 0x04034b50) {
    logs = zipEntries(buf)
      .filter((e) => /btsnoop_hci\.log/.test(e.name))
      .map((e) => ({ name: e.name, data: zipRead(buf, e) }));
    if (logs.length === 0) {
      console.log('Hittade ingen btsnoop_hci.log i zip-filen. Var HCI-snooploggen påslagen?');
      process.exit(1);
    }
  } else {
    logs = [{ name: file, data: buf }];
  }

  for (const log of logs) {
    console.log('\n=== ' + log.name + ' ===');
    let frames;
    try { frames = analyze(log.data); } catch (e) { console.log('  (hoppar över: ' + e.message + ')'); continue; }
    if (frames.length === 0) { console.log('  Inga RFCOMM-data hittades.'); continue; }
    for (const f of frames) {
      console.log(`${fmtTime(f.ts)}  ${f.sent ? '→ TILL ' : '← FRÅN '} ${f.dev}  kanal ${f.channel}  ${hex(f.info)}`);
    }
  }
  console.log('\n"→ TILL" = det telefonen skickade. Det är de raderna som ska in i appen.');
}

if (require.main === module) main();
module.exports = { analyze, parseRfcomm, fcs };
