# UBLotse

A faster, cleaner frontend for the catalogue of the **Universitätsbibliothek Leipzig**
(UBL) — as a web page and as an Android app. The prototype is a plain HTML/JS page on
`localhost` backed by a small Node proxy; the app wraps that same page and runs the same
proxy on the phone, so there is one frontend and one API to maintain.

The official catalogue at `katalog.ub.uni-leipzig.de` is slow because of its *frontend*
architecture, not because the data is hard to reach. UBLotse bypasses the VuFind theme and
talks to the library's open **SRU** endpoint directly.

```
browser  ──/api/search · /api/cover · /api/wishlist──▶  server.js  ──SRU──▶  sru.finc.info/de15
                                                              │
                                                       data/ublotse.db (SQLite)
```

## Run it

Requires Node 22.5+ (built-in `fetch` and `node:sqlite`). No dependencies, no install step.

```sh
cd ublotse
npm start          # or: node server.js
```

Then open <http://127.0.0.1:5173>.

Environment overrides: `PORT`, `HOST`, `SRU_BASE`, `SRU_DATABASE`, `UBLOTSE_DB`.

## Why there is a backend at all

`sru.finc.info` does not send `Access-Control-Allow-Origin`, so a browser cannot call it
directly. A single-file, zero-dependency Node server fills that gap and, more importantly, is
where the interesting work happens:

- **Parse once, serve JSON.** The SRU returns whole catalogue records; the UI needs a dozen
  fields. The proxy normalises records, formats and holdings so the client stays dumb.
- **Cache.** The index is refreshed daily, so responses are cached in memory for 30 minutes.
  The UI shows whether a result came from SRU or from the cache.
- **Rate-limit discipline.** finc limits automated scripts to 100 records per query; the
  server clamps `maximumRecords` accordingly.
- **Covers.** The catalogue's cover service is behind a cookie gate a browser cannot pass
  (see below), so the proxy fetches covers server-side, falls back to OpenLibrary and caches
  the bytes.
- **Wishlist.** Saved records live in SQLite on the server, so the Android app and the web
  page see the same list. See [API](#api).

## The one finding that shaped everything

The SRU endpoint offers four record schemas (`operation=explain`): `marcxml`, `turbomarc`,
`dc` and `solr`. We ask for **`solr`**, because it is the only one that carries:

| Field | Why it matters |
| --- | --- |
| `id` (`0-…`, `ai-…`) | the VuFind record id → **exact permalinks** |
| `author2` + `author2_role` | authors with roles — **absent from `marcxml`** |
| `format` | normalised types (Book, CD, Serial, …) for the badge and facets |
| `topic_facet`, `genre_facet` | subjects |
| `branch_de15`, `callnumber_de15` | holdings: branch and call number |
| `url`, `isbn`, `issn`, `oclc_num` | access and identifiers |

Traps worth recording:

- finc's `marcxml` looks authoritative but **drops all author fields**. A `dc.creator=goethe`
  query returns records whose MARC body has no `100`/`700` at all. The `dc` schema has the
  authors but almost nothing else; `solr` has both.
- Empty MARC fields are serialised as **self-closing** tags (`<controlfield tag="001"/>`,
  `<subfield code="a"/>`). A naive lazy regex then swallows the next field. (This bit an
  earlier MARCXML-based version of this parser; the Solr parser sidesteps it.)
- `cql.serverChoice` is a **catch-all with no usable relevance ranking**. A plain
  `serverChoice="Lolita" AND serverChoice="Nabokov"` returns 382 records *about* the novel
  — commentaries, dissertations, reviews — with Nabokov's own *Lolita* nowhere near the
  first page; `Progress and Poverty Henry George` ranks George's book 35th, behind thirty
  reviews of it. Fielded `dc.title` / `dc.creator` clauses, on the other hand, are scored
  the way the catalogue's own frontend scores them. The proxy therefore ORs title/creator
  anchor clauses into catch-all queries; see [SRU notes](#sru-notes-verified-against-the-live-endpoint).
- **`author` and `author2` are two different things.** VuFind puts the work's own author in
  `author` (with `author_role`) and everyone else — translators, editors, performers — in
  `author2`. Reading only `author2` does not fail loudly: it silently returns a record whose
  author list is empty, or is entirely made of translators. Whole searches still *find* those
  records via the catch-all, which is what makes the bug so easy to miss.
- The **cover service sits behind a cookie gate**. `katalog.ub.uni-leipzig.de/Cover/Show`
  answers a cookie-less request with an HTML page that sets `finc_open=1` and reloads
  (HTTP 419) — an `<img>` can never send that cookie cross-origin. Covers are therefore
  proxied server-side. And `Cover/Show` signals "no cover" with a 410-byte 67×79 GIF and
  HTTP 200, not a 404.

## What the prototype does

- Live, debounced search over the de15 SRU index; no page reloads.
- Field selector: all fields, title, author, subject, publisher, ISBN, ISSN, full text.
- Result rows: format icon, title, clickable authors (with roles), imprint, holdings
  (call number + branch), format badges, clickable subject chips, ISBN/ISSN/OCLC, cover.
- The look of the official catalogue — see [Design](#design).
- Exact permalinks to the record in the official catalogue.
- Book covers (proxied around finc's cookie gate, with an OpenLibrary fallback).
- A **wishlist** that survives restarts, stored in SQLite and keyed per client.
- **Mein Konto**: sign in with the library card number to list current loans with due dates
  and a renew button. Credentials are passed through to the catalogue, never stored; the
  session cookie is held in memory per client for 30 idle minutes.
- Client-side facets (type, author, decade, language) and sorting over the loaded results.
- “Cite” dialog with a formatted citation and BibTeX, copy to clipboard.
- In-memory response cache; the UI shows whether a result came from SRU or the cache.
- Shareable URL state (`?q=…&index=…`).
- Keyboard: `/` focuses search, `Enter` searches, `Esc` clears.
- Light and dark colour mode, following the operating system until the user chooses.

## Design

UBLotse deliberately keeps the **overall design of the official catalogue**
(`katalog.ub.uni-leipzig.de`, the VuFind theme `de_15`), so the fast frontend feels like the
site people already know. The tokens below were read out of that theme's stylesheet rather
than guessed:

| Token | Value |
| --- | --- |
| Brand, links, primary button | `#284156` |
| Accent (website, contact, CTA) | `#fd830b` |
| Second shape layer | `#0a6dc1` at 50 % (footer also `#84b6e0`) |
| Header tint / facet panel | `#284156` at 6 % |
| Footer tint | `#f8f6f5` (dark: `#1c1b1a`) |
| Type | `"Source Sans 3"`, 17.5px / 1.5rem |
| Corner radius | 2.5px — square, no pills |
| Widths | search band 85.5rem, content 82.5rem |
| Badges | `rgba(0,0,0,.067)`, 0.8125em |

Layout notes: the angular header shape is 926×778 anchored to the top right (as in the
original, which places that SVG the same way), facets sit in a 20rem column on the **right**,
and result rows are separated by hairlines rather than cards.

Three deliberate deviations:

- **The brand stays UBLotse.** The UBL logo is not copied; only the design system is shared.
- **No fake chrome.** The official header carries a cart, a login and a language switch.
  UBLotse has none of those, so that strip holds real links (SRU endpoint, original catalogue)
  and the colour-mode switch instead.
- **Shapes are re-authored, not hotlinked.** The diagonals are CSS `clip-path` polygons in the
  UBL palette; no assets are fetched from the library's server.

The theme is applied through `light-dark()` (which the original uses too), with an explicit
`data-theme` override set before first paint and stored in `localStorage`.

## SRU notes (verified against the live endpoint)

| What | Result |
| --- | --- |
| Base | `https://sru.finc.info/de15` |
| Schema used | `recordSchema=solr` (also `marcxml`, `dc`, `turbomarc`) |
| Indexes | `cql.serverChoice`, `cql.anywhere`, `dc.title`, `dc.creator`, `dc.subject`, `dc.publisher`, `dc.date`, `dc.identifier` (ISBN), `bath.issn`, `bath.corporateName`, `bath.conferenceName` |
| Paging | `startRecord` + `maximumRecords` (≤ 100) works |
| Sorting | **not supported** (`sortKeys` → diagnostic 80) |
| Date ranges | **not supported** (`dc.date>=…` errors); `dc.date=YYYY` is unreliable |
| Facets | not offered over SRU — the sidebar is computed client-side over loaded results |
| VuFind REST | `/api/v1/search` is currently behind an anti-bot cookie and a maintenance page — hence SRU |

Because SRU exposes no facet index, the sidebar filters the loaded page of results. Clicking
an author or subject, in contrast, runs a real server-side search.

**Relevance.** SRU has no `sortKeys` (`Sort not supported`, diagnostic 80), so ranking is
whatever the underlying Solr returns for the CQL — and the catch-all index barely ranks at
all. `serverChoice="Progress and Poverty Henry George"` puts Henry George's own book in
35th place, behind thirty reviews, journal articles and imitations; `dc.title="Lolita" AND
dc.creator="Nabokov"` puts the novel first. The proxy therefore ORs fielded anchors into
every 2–6 word catch-all query:

```
((dc.creator="George, Henry" AND serverChoice="Progress" AND serverChoice="Poverty")
  OR (dc.title="Progress" AND dc.creator="George" AND …)
  OR …)
OR (serverChoice="Progress" AND serverChoice="and" AND …)
```

Two kinds of anchor are generated, and both are needed:

- `dc.title="<word>" AND dc.creator="<word>"` for every ordered pair of content words —
  this is what finds `Lolita Nabokov`.
- `dc.creator="<Surname>, <Forename>"`, built from every *adjacent* pair and inverted.
  A single word is useless here (`dc.creator="George"` matches 694 000 records) but the
  inverted phrase is highly selective — and `dc.creator` only answers to that form:
  `dc.creator="Henry George"` matches nothing at all.

Each anchor is narrowed by the remaining words through the catch-all index, and the plain
broad query is always OR-ed in last, so recall survives. The fielded clauses are near-subsets
of the broad one, so the total stays close to the plain query's (measured: identical on
`Lolita Nabokov`, `Buddenbrooks Thomas Mann` and five other realistic queries, +2 on
`Der Zauberberg Thomas Mann`). Anchoring applies only to `serverChoice` / `anywhere`, is
skipped for single-term indexes (`identifier`, `issn`), stops at six content words and is
capped at 3 kB of CQL. The broad query — not the anchored one — is what the UI shows
(`cqlDisplay` in the API payload; `cql` is the query actually executed).

Known data quirks: a VuFind document splits names between `author` (the work's own author,
with `author_role`) and `author2` (translators, editors, performers …); some records have
only the latter, and the two role arrays are not always the same length as their name
arrays, so names and roles are paired defensively. ISBN-13 values that live only in MARC
`024` are not found by the `dc.identifier` index (ISBN-10s from `020` are); `publishDate`
can be a range like `2010-`, so the year is taken from `publishDateSort` first.

## API

The HTTP contract is deliberately small, JSON-only and CORS-enabled, so the Android client can
use exactly what the web frontend uses.

| Endpoint | Purpose |
| --- | --- |
| `GET /api/search?q=&index=&page=&limit=` | SRU search; returns records, facets, paging, timing |
| `GET /api/cover?isbn=&size=small\|medium\|large` | Cover image bytes, or 404 when there is none |
| `GET /api/wishlist` | The caller's saved records |
| `POST /api/wishlist` | Body `{"record": {…}}` — upsert by record id |
| `DELETE /api/wishlist/:key` | Remove one saved record |
| `GET /api/account` | Whether this client has a catalogue session |
| `POST /api/account/login` | Body `{"username": … , "password": …}` — VuFind ILS login |
| `POST /api/account/logout` | Drop the session server-side and upstream |
| `GET /api/account/loans` | Parsed loans: title, author, due date, record and renew links |
| `POST /api/account/renew` | Body `{"href": "…"}` — follow a renew link from the loans page |
| `GET /api/health` | Upstream + SQLite reachability |

UBLotse has no user accounts of its own. The client mints one opaque id, stores it, and sends it as the
`X-Client-Id` header (or `?client=`); requests without a valid id share a `local` list, which
keeps `curl` usable. A record's key is its VuFind id (`0-…`, `ai-…`), so the same work has the
same key on every device.

```sh
curl -H 'X-Client-Id: my-device-12345' localhost:5173/api/wishlist
curl -H 'Content-Type: application/json' -H 'X-Client-Id: my-device-12345' \
  -d '{"record":{"id":"0-670351962","title":"Lolita"}}' localhost:5173/api/wishlist
```

### Wishlist storage

`data/ublotse.db` (git-ignored, override with `UBLOTSE_DB`), via Node's built-in
`node:sqlite` — still no npm dependencies:

```sql
CREATE TABLE wishlist (
  client_id  TEXT NOT NULL,
  record_id  TEXT NOT NULL,
  record     TEXT NOT NULL,  -- full record snapshot as JSON
  note       TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL,
  PRIMARY KEY (client_id, record_id)
);
```

Whole record snapshots, not just ids, so the list renders without re-querying SRU. That
deliberately mirrors what the Android client keeps in its own SQLite cache; the server is the
sync point, not the source of truth.

### Mein Konto

The loans view cannot use SRU: the index holds no per-user data. It logs into the catalogue's
VuFind account the way a browser does — `GET /MyResearch/Home` for the form and its `csrf`
token, then a `POST` of `username`, `password`, `auth_method=ILS` and `processLogin` — and
keeps the resulting cookies in memory, keyed by client id. Every request carries `finc_open=1`,
the gate cookie the cover proxy also needs, and redirects are followed by hand so a
`Set-Cookie` on the 302 is not lost. `GET /MyResearch/CheckedOut` is then parsed into JSON.

Column names differ between VuFind versions, so the parser accepts German and English labels
(`Fällig`, `Rückgabe`, `Due Date`, `Ausgeliehen`, `Borrowed`) and falls back to the first date
in the row. `account.js` and `Account.java` are held to the same answers by fixtures taken
from `account.js` itself — see the tests below.

What is deliberately *not* kept:

- the card number and password, which exist only inside the one login `POST`;
- the loans, which are answered with `Cache-Control: no-store` and never written to SQLite;
- the session, which ends after 30 idle minutes or on logout. On the device it is a `Map` in
  the app's process, so it does not survive the process being killed, and it is keyed by the
  same per-install id as the wishlist.

`POST /api/account/renew` follows the link the loans page offered, but only after checking that
it still points at the catalogue and inside `/MyResearch/` — dot segments included, because
`java.net.URL` keeps `/MyResearch/../Search` verbatim where a browser has already collapsed it.
That href comes out of an HTML page, so it is the one place where a response could steer the
app somewhere else.

## The Android app

The app is the same page, the same `server.js` logic and the same `/api/*` contract, compiled
to an APK — `main/assets/public/` is a byte-for-byte copy of `public/`. Nothing branches on
"am I on Android": the Java backend answers the exact requests the browser would have sent.

To get there it binds a **loopback HTTP server** (`127.0.0.1`, ephemeral port) and points the
WebView at it. The tidier-looking alternatives don't work here: `shouldInterceptRequest` and
`WebViewAssetLoader` never hand you a request *body*, so the wishlist could be read but never
written. A real socket keeps `GET`/`POST`/`DELETE` intact — and because `127.0.0.1` counts as
a secure context, `crypto.randomUUID()` and friends keep working.

On the phone the wishlist lives in `SQLiteOpenHelper` (same DDL as the server's) and the
identity is an install id from `SharedPreferences` rather than a browser id, so clearing
WebView data cannot orphan the list.

### Build

No Gradle, no Kotlin, no network — a single Node script drives the SDK's own tools:

```
 aapt2 compile → aapt2 link -A assets → javac --release 8 → d8 → zip → zipalign → apksigner
```

For one `Activity` and three JS/CSS files, Gradle would mean a wrapper download, a Kotlin
compiler and a Maven round-trip; `android/build.mjs` is ~250 lines and reproducible offline.
The Java sources stick to the Java 8 *library* surface (`--release 8`) — no `List.of`, no
streams, no `java.time` — because `d8` does not back-port post-8 APIs for `minSdk 24`.

```sh
npm run android:build     # → android/build/ublotse.apk
npm run android:test      # parity + protocol tests, needs no device
adb install -r android/build/ublotse.apk
```

Requirements: a JDK 17+ (`javac`, `keytool`), the Android SDK with `build-tools` and one
platform (`ANDROID_HOME` is honoured), and `/usr/bin/zip`. `minSdk 24`, `targetSdk 34`.

The build signs with `android/debug.keystore`, generated on first run and git-ignored. It is a
**self-signed local key, not a Play Store key**: fine for sideloading, and Android will refuse
to update an install that a different key signed.

### Updates

There is no store listing, so the app updates itself from GitHub Releases. Once a day on
launch — and on demand from *Nach Updates suchen* in the page footer — it asks
`api.github.com/repos/axkirillov/ublotse/releases/latest`, compares the tag against its own
`versionName`, downloads `ublotse.apk` and hands it to the system installer. The footer entry
appears only where the app provides `window.Ublotse`: the WebView injects it, a browser has no
way to install anything, so the page stays one file.

Two of Android's rules do the awkward parts. Installing an unknown app needs
`REQUEST_INSTALL_PACKAGES` in the manifest *and* the user's consent in Settings (API 26+), so
the first update walks through that screen once and then continues on its own. And since
`targetSdk 34` the installer rejects `file://` URIs, so the downloaded APK is shared through
`UpdateFilesProvider` — a hand-written, read-only, unexported `ContentProvider`, which is the
whole of what this app needs from AndroidX's `FileProvider`.

Android compares signatures, not versions, to decide whether an install may be replaced: the
signing key has to stay the one it was installed with, so `android/debug.keystore` is kept
across builds. And the updater compares `versionName`, so any build worth shipping needs its
`package.json` version bumped, or the installed app will never be offered it.

### Tests

`npm run android:test` needs no emulator. It generates fixtures from `server.js`, then checks
the Java port against them: byte-identical CQL for ~600 query/index combinations, the same
SRU parsing, the same JSON on the wire, the same facets, cover proxying against fake upstreams,
the release payload and version comparison behind the in-app updater, the wishlist API and DDL
(run for real through the `sqlite3` CLI), the loans parser against `account.js` on eight fixture
pages (German, English, header-less, entity-laden, several tables on one page, empty), the
account routes driven end to end against a canned catalogue — csrf handshake, cookies surviving
a redirect, session expiry, logout, a wrong card number, and the renew-link allow-list — and the
loopback server over raw sockets, including the `Expect: 100-continue` handshake Chromium uses
for larger bodies.

The account routes are the one part that cannot be exercised against the real thing: that needs a
library card. `Account.Transport` exists for exactly that reason, and the fake catalogue answers
the same requests the ILS does.

## Roadmap

- Server-side facets (would need the Solr endpoint directly, if it can be reached politely).
- Availability: the Solr holdings are a snapshot; live item status needs the ILS (Libero).
- Accounts or a sync token, so a wishlist follows the person rather than the device.
- Persist the *search* cache (SQLite) and warm it for popular queries.
- Harden the Solr-document parser against schema drift; fall back to `dc` if needed.
- Sign the APK with a real release key and cut a tagged release from CI.

## Layout

```
ublotse/
├── server.js        # zero-dep HTTP server: static files, /api/search, covers, wishlist
├── account.js       # "Mein Konto": VuFind login, loans table parser
├── data/
│   └── ublotse.db   # SQLite wishlist (created on first run, git-ignored)
├── public/          # the frontend — also copied verbatim into the APK's assets
│   ├── index.html   # markup
│   ├── app.js       # frontend (plain ES modules)
│   └── style.css
├── android/
│   ├── build.mjs    # aapt2 → javac → d8 → zip → zipalign → apksigner
│   ├── tests/       # parity + protocol tests, no device needed
│   └── app/src/main/
│       ├── java/de/axkirillov/ublotse/   # the ported backend + MainActivity
│       ├── res/     # icon, theme, network config
│       └── assets/  # generated from public/ at build time, git-ignored
└── package.json
```

## Data source

SRU endpoint operated by finc / UBL: <https://sru.finc.info/de15?operation=explain&version=1.1>.
The index is updated daily from the local library system (Libero). Be polite: this prototype
caches aggressively and never requests more than 100 records at once.
