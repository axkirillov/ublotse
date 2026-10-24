#!/usr/bin/env node
// Builds the Android APK without Gradle.
//
// The project has no dependencies and no build step for the frontend, so dragging in Gradle,
// the Kotlin compiler and a network round-trip to Maven Central for a single Activity would be
// out of character. The Android SDK's own tools do the whole job:
//
//   aapt2 compile → aapt2 link → javac → d8 → zip → zipalign → apksigner
//
// Requirements: a JDK 17+ (javac, keytool), the Android SDK build-tools and one platform, and
// /usr/bin/zip. Run `node android/build.mjs`. Output: android/build/ublotse.apk

import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, copyFileSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const APP = join(HERE, 'app');
const MANIFEST = join(APP, 'src/main/AndroidManifest.xml');
const RES_DIR = join(APP, 'src/main/res');
const JAVA_DIR = join(APP, 'src/main/java');
const ASSETS_DIR = join(APP, 'src/main/assets');
const PUBLIC_SRC = join(ROOT, 'public');
const BUILD = join(HERE, 'build');
// The signing key is never in the tree. One key signs every build — CI releases and local test
// builds alike — because Android only replaces an install with an APK carrying the same key. It
// lives in the repository's GitHub secrets (see .github/workflows/android.yml) and in whatever
// local file UBLOTSE_KEYSTORE points at.
const KEYSTORE = process.env.UBLOTSE_KEYSTORE
  ? resolve(process.env.UBLOTSE_KEYSTORE)
  : join(HERE, 'debug.keystore');
const KEYSTORE_PASS = process.env.UBLOTSE_KEYSTORE_PASS || 'android';
const KEYSTORE_ALIAS = process.env.UBLOTSE_KEYSTORE_ALIAS || 'androiddebugkey';

const MIN_SDK = 24;
// SHA-256 of the app's signing certificate. Every build must carry it, so that the in-app
// updater and adb install -r replace existing installs instead of colliding with them.
const EXPECTED_CERT_SHA256 = 'fa24fabdd60272845872dcea5871a9ec2eaa9b9fd6f5d054b2148951405e91c0';
const TARGET_SDK = 34; // 34 on purpose: 35 would make edge-to-edge mandatory

const pkg = JSON.parse(readFileSync(join(ROOT, 'package.json'), 'utf8'));
const VERSION_NAME = pkg.version || '0.1.0';
const VERSION_CODE = Number(process.env.VERSION_CODE || semverCode(VERSION_NAME));

// aapt2 wants a monotonically increasing integer; 0.2.1 -> 201 keeps patch releases above older
// codes without hand-editing this file each time.
function semverCode(name) {
  const [major = 0, minor = 0, patch = 0] = name.split(".").map((part) => Number(part) || 0);
  return major * 10000 + minor * 100 + patch;
}

// ---------------------------------------------------------------------------
// Toolchain discovery
// ---------------------------------------------------------------------------

function firstExisting(candidates) {
  for (const candidate of candidates) {
    if (candidate && existsSync(candidate)) return candidate;
  }
  return null;
}

function findSdk() {
  const fromEnv = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  const sdk = firstExisting([
    fromEnv,
    '/opt/homebrew/share/android-commandlinetools',
    '/usr/local/share/android-commandlinetools',
    join(process.env.HOME || '', 'Library/Android/sdk'),
    join(process.env.HOME || '', 'Android/Sdk'),
  ]);
  if (!sdk) {
    fail('No Android SDK found. Set ANDROID_HOME to a directory holding build-tools/ and platforms/.');
  }
  const buildTools = firstExisting(
    readdirSync(join(sdk, 'build-tools'))
      .sort(compareVersionsDescending)
      .map((name) => join(sdk, 'build-tools', name)),
  );
  const platform = firstExisting(
    readdirSync(join(sdk, 'platforms'))
      .sort(compareVersionsDescending)
      .map((name) => join(sdk, 'platforms', name)),
  );
  if (!buildTools || !platform) fail(`The SDK at ${sdk} has no build-tools or platforms.`);
  return { sdk, buildTools, androidJar: join(platform, 'android.jar') };
}

function compareVersionsDescending(a, b) {
  const left = a.split('.').map(Number);
  const right = b.split('.').map(Number);
  for (let i = 0; i < Math.max(left.length, right.length); i += 1) {
    const diff = (right[i] || 0) - (left[i] || 0);
    if (diff) return diff;
  }
  return 0;
}

function findJavaHome() {
  const candidates = [
    process.env.JAVA_HOME,
    '/opt/homebrew/opt/jdk17/Contents/Home',
    '/opt/homebrew/opt/jdk21/Contents/Home',
    '/usr/lib/jvm/java-17-openjdk',
    '/usr/lib/jvm/java-21-openjdk',
  ];
  const home = firstExisting(candidates);
  if (home && existsSync(join(home, 'bin/javac'))) return home;
  // Fall back to whatever javac is on PATH, as long as it is not the ancient Apple shim.
  const probe = spawnSync('javac', ['-version'], { encoding: 'utf8' });
  const version = (probe.stderr || probe.stdout || '').trim();
  if (/^(javac )?1[7-9]|^(javac )?2[0-9]/.test(version)) {
    return dirname(dirname(spawnSync('which', ['javac'], { encoding: 'utf8' }).stdout.trim()));
  }
  fail(`No JDK 17+ found (saw "${version || 'nothing'}"). Set JAVA_HOME.`);
}

const { sdk, buildTools, androidJar } = findSdk();
const javaHome = findJavaHome();
const tool = (name) => join(buildTools, name);
const javaTool = (name) => join(javaHome, 'bin', name);
const ZIP = firstExisting(['/usr/bin/zip', '/bin/zip']) || 'zip';

for (const required of ['aapt2', 'd8', 'zipalign', 'apksigner']) {
  if (!existsSync(tool(required))) fail(`Missing ${required} in ${buildTools}.`);
}
if (!existsSync(androidJar)) fail(`Missing android.jar at ${androidJar}.`);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function fail(message) {
  console.error(`\n  ✗ ${message}\n`);
  process.exit(1);
}

// d8 and apksigner are shell wrappers around `java` from PATH and ignore JAVA_HOME, so the JDK we
// picked has to be put in front of it explicitly. Otherwise a stale Java 8 on PATH breaks the build
// with a confusing UnsupportedClassVersionError.
const ENVIRONMENT = {
  ...process.env,
  JAVA_HOME: javaHome,
  PATH: `${join(javaHome, 'bin')}:${process.env.PATH || ''}`,
};

function run(command, args, options = {}) {
  const shown = [command.replace(ROOT + '/', ''), ...args].join(' ');
  if (process.env.VERBOSE) console.log(`  $ ${shown}`);
  const result = spawnSync(command, args, { stdio: 'inherit', env: ENVIRONMENT, ...options });
  if (result.status !== 0) fail(`Command failed (${result.status}): ${shown}`);
}

/** Run a tool and collect its output, for checks that need to read it. */
function capture(command, args) {
  const shown = [command.replace(ROOT + '/', ''), ...args].join(' ');
  if (process.env.VERBOSE) console.log(`  $ ${shown}`);
  const result = spawnSync(command, args, { encoding: 'utf8', env: ENVIRONMENT });
  if (result.status !== 0) fail(`Command failed (${result.status}): ${shown}\n${result.stderr || ''}`);
  return `${result.stdout || ''}${result.stderr || ''}`;
}

function walk(directory, extension) {
  const found = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) found.push(...walk(path, extension));
    else if (path.endsWith(extension)) found.push(path);
  }
  return found;
}

function copyTree(from, to) {
  mkdirSync(to, { recursive: true });
  for (const entry of readdirSync(from, { withFileTypes: true })) {
    const source = join(from, entry.name);
    const target = join(to, entry.name);
    if (entry.isDirectory()) copyTree(source, target);
    else copyFileSync(source, target);
  }
}

// ---------------------------------------------------------------------------
// Build
// ---------------------------------------------------------------------------

console.log(`\nUBLotse ${VERSION_NAME} (${VERSION_CODE})`);
console.log(`  sdk   ${sdk}`);
console.log(`  java  ${javaHome}\n`);

rmSync(BUILD, { recursive: true, force: true });
mkdirSync(join(BUILD, 'classes'), { recursive: true });
mkdirSync(join(BUILD, 'dex'), { recursive: true });
mkdirSync(join(BUILD, 'gen'), { recursive: true });

// The frontend is copied, never duplicated in git: public/ stays the single source of truth.
process.stdout.write('  frontend  ');
rmSync(ASSETS_DIR, { recursive: true, force: true });
copyTree(PUBLIC_SRC, join(ASSETS_DIR, 'public'));
const assetFiles = walk(join(ASSETS_DIR, 'public'), '');
console.log(`${assetFiles.length} files from public/`);

process.stdout.write('  aapt2 compile  ');
run(tool('aapt2'), ['compile', '--dir', RES_DIR, '-o', join(BUILD, 'res.zip')]);
console.log('ok');

process.stdout.write('  aapt2 link     ');
run(tool('aapt2'), [
  'link',
  '-o',
  join(BUILD, 'base.apk'),
  '-I',
  androidJar,
  '--manifest',
  MANIFEST,
  '-A',
  ASSETS_DIR,
  '--java',
  join(BUILD, 'gen'),
  '--min-sdk-version',
  String(MIN_SDK),
  '--target-sdk-version',
  String(TARGET_SDK),
  '--version-code',
  String(VERSION_CODE),
  '--version-name',
  VERSION_NAME,
  join(BUILD, 'res.zip'),
]);
console.log('ok');

const sources = [...walk(JAVA_DIR, '.java'), ...walk(join(BUILD, 'gen'), '.java')];
process.stdout.write(`  javac          ${sources.length} sources  `);
run(javaTool('javac'), [
  '--release',
  '8', // d8 does not back-port Java 9+ library APIs, and --release also guards against them
  '-Xlint:all',
  '-nowarn',
  '-classpath',
  androidJar,
  '-d',
  join(BUILD, 'classes'),
  ...sources,
]);
console.log('ok');

process.stdout.write('  d8             ');
run(tool('d8'), [
  '--min-api',
  String(MIN_SDK),
  '--lib',
  androidJar,
  '--output',
  join(BUILD, 'dex'),
  ...walk(join(BUILD, 'classes'), '.class'),
]);
console.log('ok');

process.stdout.write('  package        ');
const unsigned = join(BUILD, 'unsigned.apk');
copyFileSync(join(BUILD, 'base.apk'), unsigned);
run(ZIP, ['-q', '-j', unsigned, join(BUILD, 'dex/classes.dex')], { cwd: BUILD });
console.log('ok');

process.stdout.write('  zipalign       ');
const aligned = join(BUILD, 'aligned.apk');
run(tool('zipalign'), ['-f', '4', unsigned, aligned]);
console.log('ok');

// Android only lets an install be replaced by a build carrying the same key, so there is exactly
// one key for this app: the one in the repository's secrets. Nobody should be generating a new
// one here — a fresh key orphans every existing install. The only reason this branch exists is
// so a first-time build on a machine without the secret still produces something installable,
// and it says loudly that it did.
if (!existsSync(KEYSTORE)) {
  process.stdout.write('  keystore       ');
  run(javaTool('keytool'), [
    '-genkeypair',
    '-keystore', KEYSTORE,
    '-alias', KEYSTORE_ALIAS,
    '-storepass', KEYSTORE_PASS,
    '-keypass', KEYSTORE_PASS,
    '-keyalg', 'RSA',
    '-keysize', '2048',
    '-validity', '10000',
    '-dname', 'CN=UBLotse, OU=prototype, O=UBLotse, C=DE',
  ]);
  console.log('generated a throwaway key at ' + KEYSTORE);
  console.log(
    '  note: this is NOT the app\'s signing key. The APK will not install over an existing\n' +
      '        build. Point UBLOTSE_KEYSTORE at the real one (see README) to build an upgrade.',
  );
}

process.stdout.write('  apksigner      ');
const output = join(BUILD, 'ublotse.apk');
run(tool('apksigner'), [
  'sign',
  '--ks', KEYSTORE,
  '--ks-pass', `pass:${KEYSTORE_PASS}`,
  '--key-pass', `pass:${KEYSTORE_PASS}`,
  '--ks-key-alias', KEYSTORE_ALIAS,
  '--min-sdk-version', String(MIN_SDK),
  '--out', output,
  aligned,
]);
console.log('ok');

process.stdout.write('  verify         ');
const verified = capture(tool('apksigner'), ['verify', '--print-certs', output]);
const digest = (/Signer #1 certificate SHA-256 digest: ([0-9a-f]+)/.exec(verified) || [])[1] || '';
console.log(`ok   cert ${digest.slice(0, 16)}…`);

if (digest && digest.toLowerCase() !== EXPECTED_CERT_SHA256) {
  console.log(
    `  warn  signed with ${digest.slice(0, 16)}…, not the committed key ` +
      `(${EXPECTED_CERT_SHA256.slice(0, 16)}…).\n` +
      '        This APK will not install over an existing one; uninstall first.\n',
  );
}

const size = statSync(output).size;
console.log(`  apk  ${relative(ROOT, output)}  ${(size / 1024).toFixed(0)} kB\n`);
writeFileSync(join(BUILD, '.gitignore'), '*\n');
