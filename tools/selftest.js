'use strict';
const assert = require('assert');
const { analyze, fcs } = require('./extract.js');

// Känt värde från GSM 07.10: SABM på DLCI 0 = 03 3F 01 1C (FCS över adress, kontroll, längd)
assert.strictEqual(fcs([0x03, 0x3f, 0x01]), 0x1c);

function rec(flags, payload) {
  const h = Buffer.alloc(24);
  h.writeUInt32BE(payload.length, 0);
  h.writeUInt32BE(payload.length, 4);
  h.writeUInt32BE(flags, 8);
  h.writeBigUInt64BE(0x00dcddb30f2f8000n + 1700000000000000n, 16);
  return Buffer.concat([h, payload]);
}
function rfcomm(dlci, info, credit) {
  const addr = (dlci << 2) | 0b11, ctrl = credit ? 0xff : 0xef;
  const parts = [addr, ctrl, (info.length << 1) | 1];
  if (credit) parts.push(5);
  return Buffer.from([...parts, ...info, fcs([addr, ctrl])]);
}
function acl(handle, l2payload, cid) {
  const l2 = Buffer.alloc(4 + l2payload.length);
  l2.writeUInt16LE(l2payload.length, 0); l2.writeUInt16LE(cid, 2); l2payload.copy(l2, 4);
  const a = Buffer.alloc(5);
  a[0] = 2; a.writeUInt16LE(handle | (2 << 12), 1); a.writeUInt16LE(l2.length, 3);
  return Buffer.concat([a, l2]);
}

const header = Buffer.alloc(16);
header.write('btsnoop\0', 0, 'latin1'); header.writeUInt32BE(1, 8); header.writeUInt32BE(1002, 12);
// Connection Complete: handle 0x0b, addr 04:52:C7:11:22:33
const cc = Buffer.from([4, 0x03, 11, 0, 0x0b, 0x00, 0x33, 0x22, 0x11, 0xc7, 0x52, 0x04, 1, 0]);
const file = Buffer.concat([
  header,
  rec(3, cc),
  rec(0, acl(0x0b, rfcomm(16, [0x01, 0x05, 0x02, 0x00], false), 0x41)),
  rec(1, acl(0x0b, rfcomm(16, [0x01, 0x05, 0x03, 0x01, 0x01], true), 0x40)),
  rec(0, acl(0x0b, Buffer.from([1, 2, 3, 4, 5, 6]), 0x42)), // skräp, ska ignoreras
]);
const frames = analyze(file);
assert.strictEqual(frames.length, 2);
assert.strictEqual(frames[0].sent, true);
assert.strictEqual(frames[0].dev, '04:52:C7:11:22:33');
assert.strictEqual(frames[0].channel, 8);
assert.deepStrictEqual([...frames[0].info], [1, 5, 2, 0]);
assert.strictEqual(frames[1].sent, false);
assert.deepStrictEqual([...frames[1].info], [1, 5, 3, 1, 1]);
console.log('OK');
