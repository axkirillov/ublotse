#!/usr/bin/env node
// Compiles and runs the JVM test suite.
//
// There is no emulator on this machine, so the app cannot be launched. What can be verified is the
// entire backend, and verified *against the Node server it was ported from*: this script imports
// server.js itself to produce expected CQL strings and expected parsed records, starts a throwaway
// Node server to record live answers, and hands all of it to android/tests/…/Tests.java, which
// exercises the Java side and compares.
//
// Everything runs against a throwaway database under android/build/. The developer's wishlist is
// never opened — see the note about unscoped DELETEs in the README.

import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..', '..');
const BUILD = join(ROOT, 'android/build');
const CLASSES = join(BUILD, 'classes');
const TEST_CLASSES = join(BUILD, 'test-classes');
const PARITY = join(BUILD, 'parity');

const die = (message) => {
  console.error(`\n  ✗ ${message}\n`);
  process.exit(1);
};

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { stdio: 'inherit', ...options });
  if (result.status !== 0) die(`Command failed (${result.status}): ${command} ${args.join(' ')}`);
}

const freePort = () =>
  new Promise((done) => {
    const probe = createServer();
    probe.listen(0, '127.0.0.1', () => {
      const { port } = probe.address();
      probe.close(() => done(port));
    });
  });

// ---------------------------------------------------------------------------
// 1. The backend under test, compiled by the same script that builds the APK.
// ---------------------------------------------------------------------------

// Rebuild whenever the sources are newer than the last build: a test run that silently exercises
// yesterday's classes is worse than no test run.
const built = join(CLASSES, 'de/axkirillov/ublotse/UblotseApi.class');
const stale = !existsSync(built)
  || spawnSync('find', [join(ROOT, 'android/app/src/main/java'), '-name', '*.java', '-newer', built], { encoding: 'utf8' })
      .stdout.trim().length > 0;
if (stale) {
  console.log('  building the backend first');
  run(process.execPath, [join(ROOT, 'android/build.mjs')]);
}

rmSync(PARITY, { recursive: true, force: true });
mkdirSync(PARITY, { recursive: true });

// ---------------------------------------------------------------------------
// 2. Fixtures straight out of server.js.
// ---------------------------------------------------------------------------

// Importing server.js opens its database, so point that at a throwaway file first. It does not
// listen: the listen call is guarded by an isMain check.
process.env.UBLOTSE_DB = join(PARITY, 'parity-node.db');
const server = await import(pathToFileURL(join(ROOT, 'server.js')).href);

const QUERIES = [
  'Lolita Nabokov',
  'Progress and Poverty Henry George',
  'Faust',
  'Der Zauberberg Thomas Mann',
  'Goethe, Johann Wolfgang von',
  'a b c d e f g h',
  'the and of', // all stopwords: must fall back to the broad query
  'Nabokov', // a single word: no anchors at all
  '"quoted phrase"',
  'Schön!',
  'C++ Primer',
  'Café (Bruchstück)',
  "L'Étranger",
  'Über die Zeit',
  '1234',
  'x=y AND z=1',
  'Gesellschaft für Informatik e.V.',
  'Vom Ursprung der Dinge und der Frage nach dem Sinn des Ganzen',
  'Lolita\u00A0Nabokov', // a non-breaking space, the reason Js.java exists
  '\u00A0 Faust \u2003',
  'Международная книга',
  '日本 文学',
  '  ',
  '',
  'a\\b"c',
];

const INDEXES = [
  'serverChoice',
  'anywhere',
  'title',
  'creator',
  'subject',
  'publisher',
  'date',
  'identifier',
  'issn',
  'corporateName',
  'conferenceName',
  'nonsense',
];

const cql = [];
for (const index of INDEXES) {
  for (const query of QUERIES) {
    for (const anchors of [true, false]) {
      cql.push({ index, q: query, anchors, expected: server.buildCql({ index, q: query, anchors }) });
    }
  }
}
cql.push(
  { index: 'serverChoice', q: 'x', raw: 'dc.title="Faust"', anchors: true, expected: 'dc.title="Faust"' },
  { index: 'title', q: 'x', raw: '  dc.title="Faust"  ', anchors: false, expected: 'dc.title="Faust"' },
  { index: 'serverChoice', q: '', raw: '', anchors: true, expected: '' },
);

const keyCases = [
  { id: '0-1610511743', title: 'Lolita: Roman', year: '1989', isbn: ['978-3-16-148410-0'] },
  { title: 'Progress and poverty', year: '1881', isbn: ['978-1-62686-136-2'] },
  { title: 'No year', isbn: [] },
  { title: 'No isbn', year: '2001' },
  { isbn: ['9783161484100'] },
  { year: '1980' },
  {},
  { id: '', title: 'Empty id falls through', year: '1999' },
  { id: 'ai-49-MTAuMjMwNy85MTA4NTI', title: 'Something' },
];
const keys = keyCases.map((record) => ({ record, expected: server.wishlistKey(record) }));

// Strings and numbers whose JavaScript encoding is easy to get subtly wrong.
const JSON_VALUES = [
  'plain',
  '',
  'tab\there',
  'nl\nhere',
  'cr\rhere',
  'quote"here',
  'back\\slash',
  '\\"both',
  '\u0000\u0001\u001f',
  '\u007f',
  'äöüÄÖÜß',
  '😀🎉',
  '\u2028\u2029',
  'Solidarit\u00e4t & \u201eAnf\u00fchrung\u201c <tag>',
  0,
  1,
  -17,
  1234567890123,
  1.5,
  -0.25,
  true,
  false,
  null,
  [1, [2, [3]]],
  { a: 1, b: 'two', c: [true, null], d: { e: 2.5 } },
  { '\u0001key': 'value', 'emoji 😀': 'ä' },
];
const json = JSON_VALUES.map((value) => ({ value, expected: JSON.stringify(value) }));

process.stdout.write('  sru fixture  ');
const fixtureCql = server.buildCql({ index: 'serverChoice', q: 'Lolita Nabokov' });
const fixtureUrl =
  'https://sru.finc.info/de15?operation=searchRetrieve&version=1.1' +
  `&query=${encodeURIComponent(fixtureCql)}&startRecord=1&maximumRecords=10&recordSchema=solr`;
const fixtureXml = await (await fetch(fixtureUrl, { headers: { 'User-Agent': 'ublotse-tests/0.1' } })).text();
const fixture = server.parseSruResponse(fixtureXml);
console.log(`${fixture.records.length} records, total ${fixture.total}`);

// ---------------------------------------------------------------------------
// 3. Live answers from a throwaway Node server, for a same-question A/B.
// ---------------------------------------------------------------------------

const LIVE_QUERIES = ['Lolita Nabokov', 'Progress and Poverty Henry George', 'Faust Goethe'];

const port = await freePort();
const nodeServer = spawn(process.execPath, ['server.js'], {
  cwd: ROOT,
  env: { ...process.env, PORT: String(port), HOST: '127.0.0.1', UBLOTSE_DB: join(PARITY, 'parity-node.db') },
  stdio: ['ignore', 'pipe', 'pipe'],
});
nodeServer.stdout.on('data', () => {});
nodeServer.stderr.on('data', (chunk) => process.stderr.write(chunk));

const base = `http://127.0.0.1:${port}`;
process.stdout.write('  node server  ');
let ready = false;
for (let attempt = 0; attempt < 100 && !ready; attempt += 1) {
  try {
    const probe = await fetch(`${base}/api/health`);
    ready = probe.ok;
  } catch {
    await new Promise((done) => setTimeout(done, 100));
  }
}
if (!ready) die('The reference Node server never came up.');
console.log(`up on ${port}`);

const answers = {};
for (const query of LIVE_QUERIES) {
  const response = await fetch(`${base}/api/search?q=${encodeURIComponent(query)}`);
  const data = await response.json();
  answers[query] = {
    total: data.total,
    cql: data.cql,
    cqlDisplay: data.cqlDisplay,
    facets: data.facets,
    records: data.records.map((record) => ({
      id: record.id,
      title: record.title,
      authors: record.authors.map((author) => `${author.name}|${author.roleLabel}`),
      year: record.year,
      isbn: record.isbn,
    })),
  };
  console.log(`    ${query}: ${data.total} hits, first "${data.records[0]?.title}"`);
}

nodeServer.kill('SIGTERM');
await new Promise((done) => setTimeout(done, 300));

writeFileSync(
  join(PARITY, 'parity.json'),
  JSON.stringify({
    cql,
    keys,
    json,
    sru: {
      xml: fixtureXml,
      total: fixture.total,
      next: fixture.nextRecordPosition,
      // Compared as strings, so the Java encoder has to agree with JSON.stringify byte for byte.
      expected: fixture.records.map((record) => JSON.stringify(record)),
    },
    live: { queries: LIVE_QUERIES, answers },
  }),
);

// ---------------------------------------------------------------------------
// 4. The wishlist SQL, run for real by the sqlite3 CLI. Defined here and called after the JVM
// tests, because the DDL it runs is written out by them — so these are the statements the app will
// execute, never a retyped copy of them.
// ---------------------------------------------------------------------------
const verifyWishlistSql = () => {
const sqlite = spawnSync('sqlite3', ['--version'], { encoding: 'utf8' });
if (sqlite.error) die('sqlite3 is not on PATH.');

const nodeDb = join(PARITY, 'schema-node.db');
rmSync(nodeDb, { force: true });
const schemaSeed = spawnSync(
  process.execPath,
  ['-e', `import(${JSON.stringify(pathToFileURL(join(ROOT, 'server.js')).href)})`],
  { cwd: ROOT, env: { ...process.env, UBLOTSE_DB: nodeDb }, stdio: ['ignore', 'ignore', 'inherit'] },
);
if (schemaSeed.status !== 0) die('Could not create the reference database from server.js.');

const appDb = join(PARITY, 'schema-app.db');
rmSync(appDb, { force: true });
const ddl = readFileSync(join(PARITY, 'wishlist.sql'), 'utf8');
const created = spawnSync('sqlite3', [appDb], { input: ddl, encoding: 'utf8' });
if (created.status !== 0) die(`The app's DDL was rejected by sqlite3: ${created.stderr}`);

const sql = (database, statement) => {
  const result = spawnSync('sqlite3', ['-batch', '-noheader', database, statement], { encoding: 'utf8' });
  if (result.status !== 0) die(`sqlite3 failed: ${result.stderr}`);
  return result.stdout.trim();
};

const runDb = (database, statement) => {
  const result = spawnSync('sqlite3', [database], { input: `${statement.trim().replace(/;$/, '')};\n`, encoding: 'utf8' });
  if (result.status !== 0) die(`sqlite3 failed: ${result.stderr}`);
};

const sqlFailures = [];
const expect = (label, body) => {
  try {
    const detail = body();
    if (detail === false) throw new Error('assertion failed');
    console.log(`    ok   ${label}`);
    if (typeof detail === 'string' && detail) console.log(`           ${detail.replace(/\n/g, '\n           ')}`);
  } catch (error) {
    sqlFailures.push(label);
    console.log(`    FAIL ${label}\n           ${String(error.message).replace(/\n/g, '\n           ')}`);
  }
};

console.log('  wishlist SQL (through the sqlite3 CLI)');

expect("the app's schema matches the server's", () => {
  // The two servers must agree on columns, types, nullability, defaults and keys.
  const shape = (database) => {
    const tables = sql(database, "SELECT sql FROM sqlite_master WHERE name IN ('wishlist','wishlist_by_client') ORDER BY name");
    return tables
      .split('\n')
      .map((line) => line.replace(/\s+/g, ' ').trim())
      .filter(Boolean)
      .join('\n');
  };
  const app = shape(appDb);
  if (!/CREATE TABLE wishlist/.test(app)) throw new Error(`no wishlist table in the app db:\n${app}`);
  // Compare the column definitions rather than the whitespace of the original statement.
  const columns = (database) => sql(database, "PRAGMA table_info('wishlist')").split('\n').join(' | ');
  const indexes = (database) => sql(database, "PRAGMA index_list('wishlist')").split('\n').map((line) => line.split('|').slice(1, 4).join('|')).sort().join(', ');
  const mine = `${columns(appDb)}\n${indexes(appDb)}`;
  const theirs = `${columns(nodeDb)}\n${indexes(nodeDb)}`;
  if (mine !== theirs) throw new Error(`app:\n${mine}\nnode:\n${theirs}`);
  return `columns and index agree:\n${columns(appDb)}\n${indexes(appDb)}`;
});

expect('the upsert keeps created_at and replaces the record', () => {
  const statement = [
    "INSERT INTO wishlist (client_id, record_id, record, note, created_at) VALUES ('c1','r1','{\"title\":\"first\"}','','2026-01-01T00:00:00.000Z') ON CONFLICT(client_id, record_id) DO UPDATE SET record = excluded.record, note = excluded.note;",
    "INSERT INTO wishlist (client_id, record_id, record, note, created_at) VALUES ('c1','r1','{\"title\":\"second\"}','', '2026-02-02T00:00:00.000Z') ON CONFLICT(client_id, record_id) DO UPDATE SET record = excluded.record, note = excluded.note;",
  ].join('\n');
  runDb(appDb, statement);
  const row = sql(appDb, "SELECT record, created_at FROM wishlist WHERE client_id='c1'");
  if (row !== '{"title":"second"}|2026-01-01T00:00:00.000Z') throw new Error(`unexpected row: ${row}`);
  return `record replaced, created_at kept: ${row}`;
});

expect('the list order is newest first, then by record id', () => {
  runDb(appDb, [
    "INSERT INTO wishlist VALUES ('c2','b','{\"title\":\"b\"}','','2026-03-01T00:00:00.000Z');",
    "INSERT INTO wishlist VALUES ('c2','a','{\"title\":\"a\"}','','2026-03-01T00:00:00.000Z');",
    "INSERT INTO wishlist VALUES ('c2','c','{\"title\":\"c\"}','','2026-04-01T00:00:00.000Z');",
  ].join('\n'));
  const order = sql(appDb, "SELECT record_id FROM wishlist WHERE client_id='c2' ORDER BY created_at DESC, record_id").split('\n').join(',');
  if (order !== 'c,a,b') throw new Error(`unexpected order: ${order}`);
  return `newest first, ties broken by id: ${order}`;
});

expect('a delete is scoped to one client and one record', () => {
  runDb(appDb, [
    "INSERT INTO wishlist VALUES ('c3','keep','{}','','2026-05-01T00:00:00.000Z');",
    "INSERT INTO wishlist VALUES ('c4','other','{}','','2026-05-01T00:00:00.000Z');",
  ].join('\n'));
  const removed = sql(appDb, "DELETE FROM wishlist WHERE client_id = 'c3' AND record_id = 'keep'; SELECT changes();");
  const left = sql(appDb, "SELECT COUNT(*) FROM wishlist WHERE client_id='c3'") + '/' + sql(appDb, "SELECT COUNT(*) FROM wishlist WHERE client_id='c4'");
  if (removed !== '1' || left !== '0/1') throw new Error(`removed=${removed} left=${left}`);
  return `removed 1; c3 now 0 rows, c4 untouched (${left})`;
});

if (sqlFailures.length) die(`${sqlFailures.length} SQL check(s) failed: ${sqlFailures.join(', ')}`);
};

// ---------------------------------------------------------------------------
// 5. Compile and run the JVM tests.
// ---------------------------------------------------------------------------

// The tests are deliberately compiled *without* android.jar on the classpath: anything they can
// reach must therefore be free of Android imports, which is what makes it testable at all.
const testSources = spawnSync('find', [HERE, '-name', '*.java'], { encoding: 'utf8' })
  .stdout.trim()
  .split('\n')
  .filter(Boolean);
if (!testSources.length) die('No test sources found under android/tests.');

const javaHome = process.env.JAVA_HOME || '/opt/homebrew/opt/jdk17/Contents/Home';
const javac = join(javaHome, 'bin/javac');
const java = join(javaHome, 'bin/java');
for (const binary of [javac, java]) {
  if (!existsSync(binary)) die(`Missing ${binary}. Set JAVA_HOME to a JDK 17+.`);
}

rmSync(TEST_CLASSES, { recursive: true, force: true });
mkdirSync(TEST_CLASSES, { recursive: true });

process.stdout.write('  javac tests  ');
run(javac, [
  '-nowarn',
  '-cp',
  CLASSES,
  '-d',
  TEST_CLASSES,
  ...testSources,
]);
console.log('ok');

console.log('  running\n');
const result = spawnSync(
  java,
  ['-cp', `${CLASSES}:${TEST_CLASSES}`, 'de.axkirillov.ublotse.Tests', PARITY, join(ROOT, 'public')],
  { stdio: 'inherit' },
);

if (result.status !== 0) die('Tests failed.');

verifyWishlistSql();

console.log('\n  ✓ all tests passed\n');
