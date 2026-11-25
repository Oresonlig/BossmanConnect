// Skapar BossmanConnects EGEN signeringsnyckel (PKCS12) — samma metod som MK2
// (node-forge), anpassad kopia utan hemligheter. Körs av Niklas, EN gång:
//
//   node C:\Projects\BossmanConnect\tool\make-keystore.cjs C:\Proj_Claudegates\Sec\Bossman
//
// Skriver i den angivna mappen (utanför repot):
//   bossman-release.p12  — nyckeln (säkerhetskopiera!)
//   SECRETS.txt          — de 4 GitHub-secrets, kopiera därifrån till GitHub
// Lösenordet skrivs ALDRIG till konsolen. Vägrar skriva över en befintlig nyckel.
// PKCS12 i Java kräver samma lösenord för store och nyckel.
const forge = require('node-forge');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const OUT = process.argv[2];
if (!OUT) { console.error('Ange mappen: node make-keystore.cjs <mapp>'); process.exit(1); }
if (path.resolve(OUT).toLowerCase().startsWith(path.resolve(__dirname, '..').toLowerCase())) {
  console.error('Vägrar: nyckeln får inte ligga i BossmanConnect-repot.'); process.exit(1);
}
fs.mkdirSync(OUT, { recursive: true });
const P12 = path.join(OUT, 'bossman-release.p12');
const SECRETS = path.join(OUT, 'SECRETS.txt');
if (fs.existsSync(P12)) { console.error('Nyckel finns redan — skriver inte över: ' + P12); process.exit(1); }

const ALIAS = 'bossman';
const PASSWORD = crypto.randomBytes(24).toString('base64url'); // samma för store + nyckel

const keys = forge.pki.rsa.generateKeyPair({ bits: 2048, e: 0x10001 });
const cert = forge.pki.createCertificate();
cert.publicKey = keys.publicKey;
cert.serialNumber = '01' + crypto.randomBytes(8).toString('hex');
cert.validity.notBefore = new Date();
cert.validity.notAfter = new Date();
cert.validity.notAfter.setFullYear(cert.validity.notBefore.getFullYear() + 30);
const attrs = [
  { name: 'commonName', value: 'BossmanConnect' },
  { name: 'organizationName', value: 'Oresonlig' },
  { name: 'countryName', value: 'SE' },
];
cert.setSubject(attrs);
cert.setIssuer(attrs);
cert.sign(keys.privateKey, forge.md.sha256.create());

const asn1 = forge.pkcs12.toPkcs12Asn1(keys.privateKey, [cert], PASSWORD, {
  algorithm: '3des', friendlyName: ALIAS, generateLocalKeyId: true,
});
fs.writeFileSync(P12, Buffer.from(forge.asn1.toDer(asn1).getBytes(), 'binary'));

// Självkontroll: läs tillbaka med lösenordet, nyckel + certifikat måste finnas.
const back = forge.pkcs12.pkcs12FromAsn1(forge.asn1.fromDer(fs.readFileSync(P12).toString('binary')), PASSWORD);
const kb = back.getBags({ friendlyName: ALIAS });
if (!kb.friendlyName.some((b) => b.type === forge.pki.oids.pkcs8ShroudedKeyBag) ||
    !kb.friendlyName.some((b) => b.type === forge.pki.oids.certBag)) {
  console.error('Självkontroll FAILADE'); process.exit(1);
}

const b64 = fs.readFileSync(P12).toString('base64');
const sha256 = crypto.createHash('sha256')
  .update(Buffer.from(forge.asn1.toDer(forge.pki.certificateToAsn1(cert)).getBytes(), 'binary'))
  .digest('hex').toUpperCase().match(/../g).join(':');

fs.writeFileSync(SECRETS, [
  'GitHub → Oresonlig/BossmanConnect → Settings → Secrets and variables → Actions → New repository secret',
  'Namn på första raden, värde på raden under. Kopiera EXAKT.',
  '',
  'ANDROID_KEY_ALIAS', ALIAS, '',
  'ANDROID_KEYSTORE_PASSWORD', PASSWORD, '',
  'ANDROID_KEY_PASSWORD', PASSWORD, '',
  'ANDROID_KEYSTORE_BASE64', b64, '',
  '--- Säkerhetskopiera bossman-release.p12 + lösenordet ovan. ---',
  'Cert SHA-256: ' + sha256, '',
].join('\r\n'));

console.log('OK: ' + P12);
console.log('OK: ' + SECRETS);
console.log('Cert SHA-256: ' + sha256);
