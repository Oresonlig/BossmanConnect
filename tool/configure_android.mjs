// Körs i CI efter `flutter create --platforms android .` (samma upplägg som MK2).
// Kopplar release-signering till miljövariablerna om nyckeln finns och slår på
// desugaring (krävs av ota_update).
//
//   node tool/configure_android.mjs
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
process.chdir(REPO);
if (!existsSync('pubspec.yaml') || !readFileSync('pubspec.yaml', 'utf8').includes('name: bossman_connect')) {
  console.error('Vägrar: ' + REPO + ' är inte BossmanConnect-projektet');
  process.exit(1);
}

const kts = 'android/app/build.gradle.kts';
const groovy = 'android/app/build.gradle';
const gradlePath = existsSync(kts) ? kts : groovy;
const isKts = gradlePath === kts;
let gradle = readFileSync(gradlePath, 'utf8');

const signed = !!process.env.ANDROID_KEYSTORE_PATH;
if (signed && !gradle.includes('ANDROID_KEYSTORE_PATH')) {
  const block = isKts
    ? `    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("ANDROID_KEYSTORE_PATH"))
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }

`
    : `    signingConfigs {
        release {
            storeFile file(System.getenv("ANDROID_KEYSTORE_PATH"))
            storePassword System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias System.getenv("ANDROID_KEY_ALIAS")
            keyPassword System.getenv("ANDROID_KEY_PASSWORD")
        }
    }

`;
  if (!/^\s*buildTypes\s*\{/m.test(gradle)) { console.error('buildTypes block not found'); process.exit(1); }
  gradle = gradle.replace(/^(\s*)buildTypes\s*\{/m, block + '$1buildTypes {');
  const dbgRe = isKts ? /signingConfigs\.getByName\("debug"\)/ : /signingConfigs\.debug/;
  if (!dbgRe.test(gradle)) { console.error('release signingConfig line not found'); process.exit(1); }
  gradle = gradle.replace(dbgRe, isKts ? 'signingConfigs.getByName("release")' : 'signingConfigs.release');
}

// Java-desugaring (krävs av ota_update ≥ 7, självuppdateringen) — som i MK2.
if (!gradle.includes('oreLibraryDesugaringEnabled')) {
  if (!/compileOptions\s*\{/.test(gradle)) { console.error('compileOptions block not found'); process.exit(1); }
  gradle = gradle.replace(/compileOptions\s*\{/, isKts
    ? 'compileOptions {\n        isCoreLibraryDesugaringEnabled = true'
    : 'compileOptions {\n        coreLibraryDesugaringEnabled true');
  gradle += isKts
    ? '\ndependencies {\n    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")\n}\n'
    : "\ndependencies {\n    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.1.4'\n}\n";
}
writeFileSync(gradlePath, gradle);

console.log('BossmanConnect: ' + (signed ? 'release-signed' : 'DEBUG-signed (no keystore secret)'));
