package de.axkirillov.ublotse;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JVM test suite. No JUnit, no dependencies — the same discipline as the rest of the project.
 *
 * <p>What it can and cannot do on a machine with no emulator: it cannot launch the app. It can run
 * the entire backend, and it does so in two ways — against fixtures generated from the Node server
 * it was ported from, and against that server's live answers for the same three queries.
 *
 * <p>Run it with {@code node android/tests/run.mjs}, which produces the fixtures and the command
 * line.
 */
public final class Tests {

  private static final String TEST_CLIENT = "test-client-0001";
  private static int passed;
  private static final List<String> failures = new ArrayList<>();
  private static final List<String> notes = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    File parityDirectory = new File(args[0]);
    File publicDirectory = new File(args[1]);
    Map<String, Object> parity =
        (Map<String, Object>) Json.parse(readFile(new File(parityDirectory, "parity.json")));

    section("CQL parity with server.js");
    cqlParity(parity);

    section("wishlist key parity with server.js");
    keyParity(parity);

    section("JSON encoder parity with JSON.stringify");
    jsonParity(parity);

    section("SRU parse and record mapping parity with server.js");
    sruParity(parity);

    section("cover proxy");
    coverProxy();

    section("in-app updates");
    updateCheck();

    section("wishlist SQL");
    wishlistSql(parityDirectory);
    wishlistSchema();

    section("wishlist API");
    wishlistApi();

    section("account parser parity with account.js");
    accountParserParity(parity);

    section("account API");
    accountApi();
    accountRecordList();

    section("loopback server");
    loopbackServer(publicDirectory, parity);

    if (!notes.isEmpty()) {
      System.out.println();
      for (String note : notes) System.out.println("  note: " + note);
    }
    System.out.println();
    if (failures.isEmpty()) {
      System.out.println("  " + passed + " checks passed");
    } else {
      System.out.println("  " + passed + " passed, " + failures.size() + " failed");
      for (String failure : failures) System.out.println("    ✗ " + failure);
      System.exit(1);
    }
  }

  // -------------------------------------------------------------------------
  // Parity against the Node server
  // -------------------------------------------------------------------------

  private static void cqlParity(Map<String, Object> parity) {
    List<Object> cases = (List<Object>) parity.get("cql");
    int identical = 0;
    int anchored = 0;
    int longest = 0;
    for (Object item : cases) {
      Map<String, Object> testCase = (Map<String, Object>) item;
      String index = (String) testCase.get("index");
      String q = (String) testCase.get("q");
      String raw = (String) testCase.get("raw");
      boolean anchors = Boolean.TRUE.equals(testCase.get("anchors"));
      String expected = (String) testCase.get("expected");
      String actual = Cql.build(index, q, raw, anchors);
      if (expected.equals(actual)) identical += 1;
      else {
        check(
            "cql[" + index + "|" + abbreviate(q, 24) + "|anchors=" + anchors + "]",
            false,
            describeDifference(expected, actual));
      }
      if (actual.contains("dc.creator")) anchored += 1;
      longest = Math.max(longest, actual.length());
    }
    check(cases.size() + " CQL queries identical to server.js", identical == cases.size(), "");
    System.out.println("         (" + anchored + " of them anchored, longest " + longest + " chars)");
  }

  private static void keyParity(Map<String, Object> parity) {
    for (Object item : (List<Object>) parity.get("keys")) {
      Map<String, Object> testCase = (Map<String, Object>) item;
      Map<String, Object> record = (Map<String, Object>) testCase.get("record");
      eq(
          "wishlistKey(" + Json.write(record) + ")",
          (String) testCase.get("expected"),
          WishlistSql.keyOf(record));
    }
  }

  private static void jsonParity(Map<String, Object> parity) {
    for (Object item : (List<Object>) parity.get("json")) {
      Map<String, Object> testCase = (Map<String, Object>) item;
      String expected = (String) testCase.get("expected");
      String text = Json.write(testCase.get("value"));
      eq("stringify " + abbreviate(expected, 32), expected, text);
      // And back again: parse JSON.stringify's output and re-emit it.
      eq("round-trip " + abbreviate(expected, 32), expected, Json.write(Json.parse(text)));
    }
  }

  private static void sruParity(Map<String, Object> parity) throws IOException {
    Map<String, Object> fixture = (Map<String, Object>) parity.get("sru");
    String xml = (String) fixture.get("xml");
    Sru.Page page = Sru.parse(xml);

    eq("total", number(fixture.get("total")), (Long) page.total);
    eq("nextRecordPosition", number(fixture.get("next")), (Long) page.nextRecordPosition);

    List<Object> expected = (List<Object>) fixture.get("expected");
    eq("record count", (long) expected.size(), (long) page.records.size());

    int byteIdentical = 0;
    for (int i = 0; i < Math.min(expected.size(), page.records.size()); i += 1) {
      String wanted = (String) expected.get(i);
      String emitted = Json.write(page.records.get(i));
      if (wanted.equals(emitted)) byteIdentical += 1;
      if (!deepEquals(Json.parse(wanted), page.records.get(i))) {
        check("record " + i + " matches server.js", false, describeDifference(wanted, emitted));
      }
    }
    if (byteIdentical != expected.size()) {
      notes.add(
          byteIdentical
              + "/"
              + expected.size()
              + " records are byte-identical to JSON.stringify; the rest differ only in key order"
              + " or escaping, which no client observes");
    }
    System.out.println("         (" + byteIdentical + "/" + expected.size() + " byte-identical)");
  }

  // -------------------------------------------------------------------------
  // Covers
  // -------------------------------------------------------------------------

  private static void coverProxy() throws Exception {
    // The "no cover" answer: a 410-byte GIF, content-type image/gif, HTTP 200.
    byte[] placeholder = new byte[410];
    System.arraycopy("GIF89a".getBytes("US-ASCII"), 0, placeholder, 0, 6);
    byte[] jpeg = new byte[1024];
    jpeg[0] = (byte) 0xFF;
    jpeg[1] = (byte) 0xD8;

    FakeServer catalogue = new FakeServer(placeholder, "image/gif", 200);
    FakeServer openLibrary = new FakeServer(jpeg, "image/jpeg", 200);

    try {
      File cache = Files.createTempDirectory("ublotse-covers").toFile();
      Covers covers =
          new Covers(cache, catalogue.url() + "/Cover/Show", openLibrary.url() + "/b/isbn", 5000);

      Covers.Cover cover = covers.get("978-3-16-148410-0", "medium");
      check("placeholder GIF is not a cover, OpenLibrary is used", cover.ok && "image/jpeg".equals(cover.type), cover.type);
      eq("OpenLibrary bytes", 1024, cover.body.length);
      check("the UBL request carried the finc_open cookie", catalogue.cookies.contains("finc_open=1"), String.valueOf(catalogue.cookies));
      check("the first source was tried", catalogue.paths.get(0).contains("isbn=9783161484100"), String.valueOf(catalogue.paths));
      check("size is passed through", catalogue.paths.get(0).contains("size=medium"), String.valueOf(catalogue.paths));

      int before = catalogue.hits + openLibrary.hits;
      covers.get("978-3-16-148410-0", "medium");
      eq("a second lookup is served from memory", before, catalogue.hits + openLibrary.hits);

      // A fresh instance over the same directory must not touch the network at all.
      Covers afterRestart =
          new Covers(cache, catalogue.url() + "/Cover/Show", openLibrary.url() + "/b/isbn", 5000);
      Covers.Cover fromDisk = afterRestart.get("9783161484100", "medium");
      check("a third lookup is served from disk", fromDisk.ok && fromDisk.body.length == 1024, String.valueOf(fromDisk.body.length));
      eq("and cost no requests", before, catalogue.hits + openLibrary.hits);

      // A catalogue that does have the cover short-circuits before OpenLibrary.
      FakeServer realCatalogue = new FakeServer(jpeg, "image/jpeg", 200);
      FakeServer unusedLibrary = new FakeServer(jpeg, "image/jpeg", 200);
      Covers direct =
          new Covers(null, realCatalogue.url() + "/Cover/Show", unusedLibrary.url() + "/b/isbn", 5000);
      Covers.Cover real = direct.get("9783161484100", "large");
      check("a real cover is used directly", real.ok && real.body.length == 1024, "");
      eq("and OpenLibrary is never asked", 0, unusedLibrary.hits);

      // Neither source has it.
      FakeServer empty = new FakeServer(placeholder, "image/gif", 200);
      Covers nothing = new Covers(null, empty.url() + "/Cover/Show", empty.url() + "/b/isbn", 5000);
      check("no cover anywhere is reported as none", !nothing.get("9783161484100", "small").ok, "");

      // And through the API: a bad ISBN is a 400, a missing cover a 404.
      UblotseApi api = new UblotseApi(new Sru(), nothing, new MemoryWishlist());
      eq("invalid isbn -> 400", 400, api.handle("GET", "/api/cover", params("isbn", "not-an-isbn"), null, TEST_CLIENT).status);
      eq("missing cover -> 404", 404, api.handle("GET", "/api/cover", params("isbn", "9783161484100"), null, TEST_CLIENT).status);
      WebResponse served = new UblotseApi(new Sru(), direct, new MemoryWishlist())
          .handle("GET", "/api/cover", params("isbn", "978-3-16-148410-0", "size", "large"), null, TEST_CLIENT);
      eq("cover -> 200", 200, served.status);
      eq("with the upstream type", "image/jpeg", served.contentType);
      check("and an immutable cache header", "public, max-age=604800, immutable".equals(served.cacheControl), "");
      eq("an unknown size falls back to medium", 200,
          new UblotseApi(new Sru(), direct, new MemoryWishlist())
              .handle("GET", "/api/cover", params("isbn", "9783161484100", "size", "enormous"), null, TEST_CLIENT)
              .status);

      catalogue.stop();
      openLibrary.stop();
      realCatalogue.stop();
      unusedLibrary.stop();
      empty.stop();
    } catch (IOException failure) {
      check("cover proxy", false, failure.toString());
    }
  }

  // -------------------------------------------------------------------------
  // In-app updates
  // -------------------------------------------------------------------------

  private static void updateCheck() {
    // The shape of GET /repos/axkirillov/ublotse/releases/latest, with a second asset so that
    // picking the APK is actually tested rather than assumed.
    String payload =
        "{\"tag_name\":\"v0.1.1\",\"html_url\":\"https://github.com/axkirillov/ublotse/releases/tag/v0.1.1\","
            + "\"draft\":false,\"prerelease\":false,\"assets\":["
            + "{\"name\":\"checksums.txt\",\"size\":80,\"browser_download_url\":\"https://example.invalid/checksums.txt\"},"
            + "{\"name\":\"ublotse.apk\",\"size\":62227,"
            + "\"browser_download_url\":\"https://github.com/axkirillov/ublotse/releases/download/v0.1.1/ublotse.apk\"}]}";

    Update.Release release = Update.parse(payload);
    check("a release payload is read", release != null, "the payload did not parse");
    if (release != null) {
      eq("tag_name becomes the version", "0.1.1", release.version);
      eq(
          "the APK asset is chosen, not whatever comes first",
          "https://github.com/axkirillov/ublotse/releases/download/v0.1.1/ublotse.apk",
          release.apkUrl);
      eq("the asset size comes along", 62227L, release.apkSize);
      eq("the download is cached per version", "ublotse-0.1.1.apk", release.fileName());
    }

    Update.Release noSize =
        Update.parse(
            "{\"tag_name\":\"v2\",\"assets\":[{\"name\":\"ublotse.apk\",\"browser_download_url\":\"https://example.invalid/x.apk\"}]}");
    check("a download without a reported size is still usable", noSize != null && noSize.apkSize == 0, "size handling");

    eq("v0.1.1 normalises", "0.1.1", Update.normalize("v0.1.1"));
    check("a nightly tag is not a version", Update.normalize("nightly") == null, "accepted 'nightly'");
    check("a bare v is not a version", Update.normalize("v") == null, "accepted 'v'");

    eq("0.2.0 is newer than 0.1.9", 1, Update.compare("0.2.0", "0.1.9"));
    eq("0.1.9 is older than 0.2.0", -1, Update.compare("0.1.9", "0.2.0"));
    eq("0.10 beats 0.9 numerically, not as text", 1, Update.compare("0.10", "0.9"));
    eq("0.1 and 0.1.0 are the same version", 0, Update.compare("0.1", "0.1.0"));
    check("0.1.1 is an update over 0.1.0", Update.isNewer("0.1.1", "0.1.0"), "not newer");
    check("the same version is not an update", !Update.isNewer("0.1.0", "0.1.0"), "updated itself");
    check("an older release is not an update", !Update.isNewer("0.1.0", "0.2.0"), "offered a downgrade");

    check("a release without an APK asset is ignored", Update.parse("{\"tag_name\":\"v0.1.2\",\"assets\":[]}") == null, "");
    check("an APK under another name is ignored", Update.parse("{\"tag_name\":\"v0.1.2\",\"assets\":[{\"name\":\"app.apk\",\"browser_download_url\":\"https://example.invalid/a.apk\"}]}") == null, "");
    check("a payload without a tag is ignored", Update.parse("{\"assets\":[]}") == null, "");
    check("malformed JSON is ignored", Update.parse("<html>rate limited</html>") == null, "");
    check("a JSON array is ignored", Update.parse("[]") == null, "");
  }

  // -------------------------------------------------------------------------
  // Wishlist
  // -------------------------------------------------------------------------

  private static void wishlistApi() {
    MemoryWishlist store = new MemoryWishlist();
    UblotseApi api = new UblotseApi(new Sru(), new Covers(null), store);

    Map<String, Object> record = recordJson("0-1610511743", "Lolita: Roman", "1955");
    record.put("_uid", "u7"); // a client-only DOM handle, which must never be stored

    WebResponse saved = api.handle("POST", "/api/wishlist", empty(), body(Json.obj("record", record)), TEST_CLIENT);
    eq("POST /api/wishlist -> 200", 200, saved.status);
    Map<String, Object> savedBody = json(saved);
    eq("saved", Boolean.TRUE, savedBody.get("saved"));
    eq("key is the record id", "0-1610511743", savedBody.get("key"));
    eq("count", 1L, savedBody.get("count"));

    WebResponse listed = api.handle("GET", "/api/wishlist", empty(), null, TEST_CLIENT);
    Map<String, Object> listBody = json(listed);
    eq("GET count", 1L, listBody.get("count"));
    eq("clientId echoed", TEST_CLIENT, listBody.get("clientId"));
    Map<String, Object> item = (Map<String, Object>) ((List<Object>) listBody.get("items")).get(0);
    eq("item key", "0-1610511743", item.get("key"));
    eq("note defaults to empty", "", item.get("note"));
    check("addedAt is an ISO string", String.valueOf(item.get("addedAt")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"), String.valueOf(item.get("addedAt")));
    Map<String, Object> stored = (Map<String, Object>) item.get("record");
    check("_uid is stripped from the snapshot", !stored.containsKey("_uid"), Json.write(stored));
    eq("title survives", "Lolita: Roman", stored.get("title"));

    // Saving the same record again updates it and keeps the original timestamp and the count.
    String addedAt = String.valueOf(item.get("addedAt"));
    Map<String, Object> renamed = recordJson("0-1610511743", "Lolita (neu)", "1955");
    Map<String, Object> second = json(api.handle("POST", "/api/wishlist", empty(), body(Json.obj("record", renamed)), TEST_CLIENT));
    eq("upsert keeps the count at 1", 1L, second.get("count"));
    Map<String, Object> afterUpsert =
        (Map<String, Object>) ((List<Object>) json(api.handle("GET", "/api/wishlist", empty(), null, TEST_CLIENT)).get("items")).get(0);
    eq("upsert replaces the record", "Lolita (neu)", ((Map<String, Object>) afterUpsert.get("record")).get("title"));
    eq("upsert keeps created_at", addedAt, afterUpsert.get("addedAt"));

    // A second record, added later, is listed as well.
    api.handle("POST", "/api/wishlist", empty(), body(Json.obj("record", recordJson("0-2", "Second", "2001"))), TEST_CLIENT);
    List<Object> items = (List<Object>) json(api.handle("GET", "/api/wishlist", empty(), null, TEST_CLIENT)).get("items");
    List<String> keys = new ArrayList<>();
    for (Object listedItem : items) keys.add(String.valueOf(((Map<String, Object>) listedItem).get("key")));
    Collections.sort(keys);
    eq("both records are listed", Arrays.asList("0-1610511743", "0-2"), keys);

    // Records without an id still need a stable key.
    Map<String, Object> anonymous = Json.obj("title", "Ohne ID", "year", "1881", "isbn", Json.arr("9783161484100"));
    eq("anonymous key", "t:Ohne ID~1881~9783161484100", json(api.handle("POST", "/api/wishlist", empty(), body(Json.obj("record", anonymous)), TEST_CLIENT)).get("key"));

    // Removal, including a percent-encoded key.
    Map<String, Object> removed = json(api.handle("DELETE", "/api/wishlist/0-2", empty(), null, TEST_CLIENT));
    eq("DELETE removes", Boolean.TRUE, removed.get("removed"));
    eq("and reports the new count", 2L, removed.get("count"));
    eq("DELETE again reports nothing removed", Boolean.FALSE, json(api.handle("DELETE", "/api/wishlist/0-2", empty(), null, TEST_CLIENT)).get("removed"));
    Map<String, Object> encoded = json(api.handle("DELETE", "/api/wishlist/t%3AOhne%20ID~1881~9783161484100", empty(), null, TEST_CLIENT));
    eq("DELETE decodes percent escapes", Boolean.TRUE, encoded.get("removed"));

    // Bad input.
    eq("a record with no title is rejected", 400, api.handle("POST", "/api/wishlist", empty(), body("{\"record\":{\"id\":\"x\"}}"), TEST_CLIENT).status);
    eq("a bare body is rejected", 400, api.handle("POST", "/api/wishlist", empty(), body("7"), TEST_CLIENT).status);
    eq("invalid JSON is a 502", 502, api.handle("POST", "/api/wishlist", empty(), body("{oops"), TEST_CLIENT).status);
    check("with the server's message", json(api.handle("POST", "/api/wishlist", empty(), body("{oops"), TEST_CLIENT)).get("error").equals("Invalid JSON body"), "");
    eq("an empty body is rejected", 400, api.handle("POST", "/api/wishlist", empty(), new byte[0], TEST_CLIENT).status);
    eq("GET on a wishlist item is method-not-allowed", 405, api.handle("GET", "/api/wishlist/nope", empty(), null, TEST_CLIENT).status);
    eq("PUT is method-not-allowed", 405, api.handle("PUT", "/api/wishlist", empty(), null, TEST_CLIENT).status);
    eq("OPTIONS is a 204", 204, api.handle("OPTIONS", "/api/wishlist", empty(), null, TEST_CLIENT).status);
    eq("an unknown api path is a 404", 404, api.handle("GET", "/api/nothing", empty(), null, TEST_CLIENT).status);
    check("a non-api path is not the api's business", api.handle("GET", "/app.js", empty(), null, TEST_CLIENT) == null, "");
    eq("a missing query is a 400", 400, api.handle("GET", "/api/search", empty(), null, TEST_CLIENT).status);

    Map<String, Object> health = json(api.handle("GET", "/api/health", empty(), null, TEST_CLIENT));
    eq("health says ok", Boolean.TRUE, health.get("ok"));
    eq("health names the upstream", Sru.DEFAULT_BASE, health.get("upstream"));
    eq("health names the database", "de15", health.get("database"));
    eq("health reports the wishlist backend", "sqlite", ((Map<String, Object>) health.get("wishlist")).get("backend"));
  }

  // -------------------------------------------------------------------------
  // "Mein Konto": the parser, then the routes on a canned catalogue
  // -------------------------------------------------------------------------

  /**
   * Every fixture page is parsed by account.js in run.mjs and by {@link Account#parseLoans} here; the
   * two answers are compared as JSON. That is the only way to be sure the port kept the small
   * decisions — which column is the due date, what a renew link looks like, when a row is skipped —
   * that the frontend depends on.
   */
  private static void accountParserParity(Map<String, Object> parity) {
    Map<String, Object> account = (Map<String, Object>) parity.get("account");
    for (Object item : (List<Object>) account.get("pages")) {
      Map<String, Object> page = (Map<String, Object>) item;
      String name = (String) page.get("name");
      List<Object> expected = (List<Object>) page.get("expected");
      List<Map<String, Object>> actual = Account.parseLoans((String) page.get("html"));
      check(
          "page \"" + name + "\" parses to the same loans as account.js",
          deepEquals(expected, actual),
          describeDifference(Json.write(expected), Json.write(actual)));
    }
    for (Object item : (List<Object>) account.get("dates")) {
      Map<String, Object> testCase = (Map<String, Object>) item;
      String value = (String) testCase.get("value");
      eq("germanToIso(\"" + value + "\")", testCase.get("expected"), Account.germanToIso(value));
    }

    // The parser must not invent loans out of a page without a table, and the fixtures cover the
    // shapes the catalogue serves today; a null page is simply empty rather than a crash.
    eq("a null page has no loans", 0, Account.parseLoans(null).size());
    eq("a page without tables has no loans", 0, Account.parseLoans("<p>Keine Ausleihen.</p>").size());
  }

  /**
   * VuFind 10 (what the Leipzig catalogue runs) has no loans table: the items are a record list and
   * the renewal is a post back to the page carrying the item's id in a hidden field. A parser that
   * only knows tables reports zero loans for a full account, which is how this was found.
   */
  private static void accountRecordList() throws Exception {
    FakeCatalogue catalogue = new FakeCatalogue();
    catalogue.recordList = true;
    Account account = new Account(FakeCatalogue.BASE, catalogue);
    UblotseApi api =
        new UblotseApi(new Sru(), new Covers(null), new MemoryWishlist(), account);
    String client = "account-client-0003";

    WebResponse login =
        api.handle(
            "POST",
            "/api/account/login",
            empty(),
            body(Json.obj("username", "1234567890", "password", "geheim")),
            client);
    eq("login on the record-list catalogue -> 200", 200, login.status);

    Map<String, Object> session = json(login);
    check("login reports the account", Boolean.TRUE.equals(session.get("loggedIn")), Json.write(session));
    List<Object> items = (List<Object>) session.get("items");
    eq("the record list yields both loans", 2, items.size());

    Map<String, Object> first = (Map<String, Object>) items.get(0);
    eq("its title comes from the record link", "Lolita : Roman", first.get("title"));
    eq("its author from the authors span", "Nabokov, Vladimir", first.get("author"));
    eq("its due date from the labelled line", "02.04.2025", first.get("due"));
    eq("normalised to an ISO date", "2025-04-02", first.get("dueISO"));
    eq("its record id is extracted", "0-1610511743", first.get("recordId"));
    eq("there is no renewal link to follow", null, first.get("renewHref"));
    eq("but there is a renewal id in the form", "0-1610511743|1|02.04.2025", first.get("renewId"));
    check("nothing is claimed about a borrowing date", first.containsKey("borrowed") && first.get("borrowed") == null, Json.write(first));

    Map<String, Object> second = (Map<String, Object>) items.get(1);
    eq("an ILS-only title is read from its span", "Der Zauberberg", second.get("title"));
    eq("it has no record id", null, second.get("recordId"));
    eq("it is not renewable", null, second.get("renewId"));
    eq("it has an author of nothing rather than of the date", "", second.get("author"));

    // Renewing posts the catalogue's own form back to the page it came from. The href is supplied
    // too, and must be ignored: the renewal id is what identifies the item.
    WebResponse renewed =
        api.handle(
            "POST",
            "/api/account/renew",
            empty(),
            body(
                Json.obj(
                    "renewId", first.get("renewId"),
                    "href", "https://beispiel.example/MyResearch/Record/0-1/Renew")),
            client);
    eq("renew -> 200", 200, renewed.status);
    Map<String, Object> afterRenew = json(renewed);
    check(
        "renew reports the catalogue's own message",
        Boolean.TRUE.equals(afterRenew.get("renewed"))
            && String.valueOf(afterRenew.get("message")).contains("verl\u00e4ngert"),
        Json.write(afterRenew));
    List<Object> refreshed = (List<Object>) afterRenew.get("items");
    eq("the extended due date is reloaded", "2025-04-20",
        ((Map<String, Object>) refreshed.get(0)).get("dueISO"));

    FakeCatalogue.Call post = catalogue.calls.get(catalogue.calls.size() - 2);
    eq("the renewal is a post to the checked-out page", "POST /MyResearch/CheckedOut", post.describe());
    eq("as a form body", "application/x-www-form-urlencoded", post.contentType);
    check("with the page's csrf hash", post.form.contains("csrf=csrf-hash-9"), post.form);
    check("the renew button the catalogue expects", post.form.contains("renewSelected=1"), post.form);
    check(
        "and the item's renewal id",
        post.form.contains("renewSelectedIDS%5B%5D=0-1610511743%7C1%7C02.04.2025"),
        post.form);
    check("with the session cookie", post.cookie.contains("LBSESSION=ok"), post.cookie);
    int hostile = 0;
    for (FakeCatalogue.Call call : catalogue.calls) {
      if (call.path.startsWith("/MyResearch/Record/")) hostile += 1;
    }
    eq("the supplied href was never requested", 0, hostile);
  }

  private static void accountApi() throws Exception {
    FakeCatalogue catalogue = new FakeCatalogue();
    Account account = new Account(FakeCatalogue.BASE, catalogue);
    UblotseApi api =
        new UblotseApi(new Sru(), new Covers(null), new MemoryWishlist(), account);
    String client = "account-client-0001";

    // Signed out: the status route answers, the loans route refuses.
    Map<String, Object> status = json(api.handle("GET", "/api/account", empty(), null, client));
    check(
        "an account starts signed out and without a name",
        Boolean.FALSE.equals(status.get("loggedIn")) && status.get("username") == null,
        Json.write(status));
    eq("loans before login -> 401", 401, api.handle("GET", "/api/account/loans", empty(), null, client).status);
    eq("renew before login -> 401", 401,
        api.handle("POST", "/api/account/renew", empty(), body(Json.obj("href", "/MyResearch/x")), client).status);

    // Login: form, POST, redirect, loans.
    WebResponse login =
        api.handle(
            "POST",
            "/api/account/login",
            empty(),
            body(Json.obj("username", "1234567890", "password", "geheim")),
            client);
    eq("login -> 200", 200, login.status);
    Map<String, Object> session = json(login);
    check(
        "login reports the account",
        Boolean.TRUE.equals(session.get("loggedIn")) && "1234567890".equals(session.get("username")),
        Json.write(session));
    check("login reports a fetch timestamp", String.valueOf(session.get("fetchedAt")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"), String.valueOf(session.get("fetchedAt")));
    List<Object> items = (List<Object>) session.get("items");
    eq("both loans are parsed", 2, items.size());
    Map<String, Object> first = (Map<String, Object>) items.get(0);
    eq("the first loan's title", "Effi Briest", first.get("title"));
    eq("its due date is normalised", "2025-04-02", first.get("dueISO"));
    eq("its record id is extracted", "0-11", first.get("recordId"));
    eq("and its record link kept relative", "/Record/0-11", first.get("recordHref"));

    eq("login is the form, the POST, the redirect and the loans page", 4, catalogue.calls.size());
    eq("the form is fetched first", "GET /MyResearch/Home", catalogue.calls.get(0).describe());
    FakeCatalogue.Call form = catalogue.calls.get(1);
    eq("the second request is the POST", "POST /MyResearch/Home", form.method + " " + form.path);
    check("the card number is a form field", form.form.contains("username=1234567890"), form.form);
    check("so is the password", form.form.contains("password=geheim"), form.form);
    check("with the ILS method", form.form.contains("auth_method=ILS"), form.form);
    check("and the csrf token from the form", form.form.contains("csrf=tok-1"), form.form);
    check("and the button name the catalogue expects", form.form.contains("processLogin=Mein+Konto"), form.form);
    eq("as a urlencoded body", "application/x-www-form-urlencoded", form.contentType);
    check("the cookie from the login page is sent back", form.cookie.contains("VuFind=first"), form.cookie);
    check("the finc gate cookie is always sent", form.cookie.startsWith("finc_open=1"), form.cookie);
    check("the session cookie follows the redirect", catalogue.calls.get(2).cookie.contains("LBSESSION=ok"), catalogue.calls.get(2).cookie);
    check("and reaches the loans page", catalogue.calls.get(3).cookie.contains("LBSESSION=ok"), catalogue.calls.get(3).cookie);

    // A second client is a second session: the app gives every install its own id.
    eq("another client has no session", 401, api.handle("GET", "/api/account/loans", empty(), null, "account-client-0002").status);
    Map<String, Object> mine = json(api.handle("GET", "/api/account", empty(), null, client));
    eq("the first client is still signed in", Boolean.TRUE, mine.get("loggedIn"));

    // Renewing follows the link the parser extracted.
    WebResponse renewed =
        api.handle("POST", "/api/account/renew", empty(), body(Json.obj("href", first.get("renewHref"))), client);
    eq("renew -> 200", 200, renewed.status);
    Map<String, Object> afterRenew = json(renewed);
    check(
        "renew reports success",
        Boolean.TRUE.equals(afterRenew.get("renewed")) && "Ausleihe verlängert.".equals(afterRenew.get("message")),
        Json.write(afterRenew));
    check("the renew response carries the fresh loans", afterRenew.containsKey("items") && afterRenew.containsKey("fetchedAt"), Json.write(afterRenew));
    List<Object> refreshed = (List<Object>) afterRenew.get("items");
    eq("the new due date is reloaded", "2025-04-20", ((Map<String, Object>) refreshed.get(0)).get("dueISO"));
    FakeCatalogue.Call renewCall = catalogue.calls.get(catalogue.calls.size() - 2);
    check("the renew link is followed on the catalogue", renewCall.path.startsWith("/MyResearch/Record/0-11/Renew"), renewCall.path);
    check("with the session cookie", renewCall.cookie.contains("LBSESSION=ok"), renewCall.cookie);

    // A renew link is an URL handed back by a web page, so it is the one place the app could be
    // steered somewhere else. Everything outside the account area is refused unrequested.
    int before = catalogue.calls.size();
    String[] refused = {
      "https://evil.example/MyResearch/Record/1/Renew",
      "//evil.example/MyResearch/Record/1/Renew",
      "http://katalog.example/MyResearch/Record/1/Renew",
      "/Search/Home",
      "/MyResearch/../Search/Home",
      "/MyResearch/%2e%2e/Search/Home",
      "",
    };
    for (String href : refused) {
      eq("renew refuses " + (href.isEmpty() ? "an empty link" : href), 400,
          api.handle("POST", "/api/account/renew", empty(), body(Json.obj("href", href)), client).status);
    }
    eq("and none of them reached the catalogue", before, catalogue.calls.size());

    // Logout is local even when the catalogue does not answer.
    eq("logout -> 200", 200, api.handle("POST", "/api/account/logout", empty(), null, client).status);
    // The logout redirect is followed too (account.js does the same), so the Logout request is the
    // second-to-last call and following "/" is the last.
    check("logout was forwarded to the catalogue", catalogue.calls.get(catalogue.calls.size() - 2).path.startsWith("/MyResearch/Logout"), catalogue.calls.get(catalogue.calls.size() - 2).path);
    eq("loans after logout -> 401", 401, api.handle("GET", "/api/account/loans", empty(), null, client).status);
    eq("the account is signed out", Boolean.FALSE, json(api.handle("GET", "/api/account", empty(), null, client)).get("loggedIn"));

    // An expired cookie is reported as a session that has to be renewed, not as a server failure.
    api.handle("POST", "/api/account/login", empty(), body(Json.obj("username", "1234567890", "password", "geheim")), client);
    catalogue.expired = true;
    WebResponse expired = api.handle("GET", "/api/account/loans", empty(), null, client);
    catalogue.expired = false;
    eq("an expired session -> 401", 401, expired.status);
    eq("with the session message", "Sitzung abgelaufen. Bitte erneut anmelden.", json(expired).get("error"));
    eq("and the account is signed out again", Boolean.FALSE, json(api.handle("GET", "/api/account", empty(), null, client)).get("loggedIn"));

    // A wrong password keeps the catalogue's own wording.
    WebResponse rejected =
        api.handle("POST", "/api/account/login", empty(), body(Json.obj("username", "1234567890", "password", "falsch")), "account-client-0003");
    eq("a wrong password -> 401", 401, rejected.status);
    eq("with the catalogue's message", "Ausweisnummer oder Passwort ist falsch.", json(rejected).get("error"));
    eq("missing credentials -> 400", 400, api.handle("POST", "/api/account/login", empty(), body(Json.obj()), "account-client-0004").status);
    eq("an empty body -> 400", 400, api.handle("POST", "/api/account/login", empty(), new byte[0], "account-client-0005").status);
    eq("invalid JSON is a 502", 502, api.handle("POST", "/api/account/login", empty(), body("{oops"), "account-client-0006").status);
    eq("with the server's message", "Invalid JSON body", json(api.handle("POST", "/api/account/login", empty(), body("{oops"), "account-client-0006")).get("error"));

    // Routes that do not exist are answered locally, without a catalogue request.
    int routed = catalogue.calls.size();
    eq("an unknown account route -> 404", 404, api.handle("GET", "/api/account/nonsense", empty(), null, client).status);
    eq("a POST on the status route -> 404", 404, api.handle("POST", "/api/account", empty(), null, client).status);
    eq("and neither went upstream", routed, catalogue.calls.size());

    // The idle timeout is what ends a session nobody is using; on the device that is the point at
    // which the cookie stops being held in memory.
    Account shortLived = new Account(FakeCatalogue.BASE, catalogue, 5);
    UblotseApi idleApi = new UblotseApi(new Sru(), new Covers(null), new MemoryWishlist(), shortLived);
    eq("an idle session logs in", 200, idleApi.handle("POST", "/api/account/login", empty(), body(Json.obj("username", "1234567890", "password", "geheim")), "account-client-0007").status);
    Thread.sleep(30);
    eq("after the idle timeout the session is gone", 401, idleApi.handle("GET", "/api/account/loans", empty(), null, "account-client-0007").status);
    eq("and forgotten, not merely refused", Boolean.FALSE, json(idleApi.handle("GET", "/api/account", empty(), null, "account-client-0007")).get("loggedIn"));

    // Routing and the rest of the API are untouched.
    eq("health still answers", Boolean.TRUE, json(api.handle("GET", "/api/health", empty(), null, client)).get("ok"));
    eq("the account prefix is not a search", 400, api.handle("GET", "/api/search", empty(), null, client).status);
  }

  /** Writes the DDL out so that run.mjs can hand the very same statements to the sqlite3 CLI. */
  private static void wishlistSql(File parityDirectory) throws IOException {
    File file = new File(parityDirectory, "wishlist.sql");
    Files.write(
        file.toPath(),
        (WishlistSql.CREATE_TABLE + ";\n" + WishlistSql.CREATE_INDEX + ";\n").getBytes("UTF-8"));
    check("the DDL was handed to sqlite3", file.isFile(), file.getAbsolutePath());
  }

  private static void wishlistSchema() {
    // The statements themselves are exercised through the sqlite3 CLI by android/tests/run.mjs.
    check("the table has a client-first primary key", WishlistSql.CREATE_TABLE.contains("PRIMARY KEY (client_id, record_id)"), "");
    check("the list query orders newest first", WishlistSql.LIST.contains("ORDER BY created_at DESC, record_id"), "");
    check("saving upserts rather than failing", WishlistSql.SAVE.contains("ON CONFLICT(client_id, record_id) DO UPDATE"), "");
    check("saving preserves created_at", !WishlistSql.SAVE.split("DO UPDATE")[1].contains("created_at ="), "");
    check("removal is scoped to one client and one record", WishlistSql.REMOVE.contains("WHERE client_id = ? AND record_id = ?"), "");
    check("an ISO timestamp always has three fractional digits", WishlistSql.nowIso().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"), WishlistSql.nowIso());
  }

  // -------------------------------------------------------------------------
  // The loopback server, the way the WebView will use it
  // -------------------------------------------------------------------------

  private static void loopbackServer(File publicDirectory, Map<String, Object> parity) throws Exception {
    MemoryWishlist store = new MemoryWishlist();
    Covers covers = new Covers(null);
    UblotseApi api = new UblotseApi(new Sru(), covers, store);

    final String installId = "install-0000-1111";
    LocalServer server =
        new LocalServer(
            (method, path, query, body, headers) -> api.handle(method, path, query, body, installId),
            new DirectoryResources(publicDirectory));
    int port = server.start();
    String base = "http://127.0.0.1:" + port;
    check("the server binds a loopback port", port > 0, String.valueOf(port));

    try {
      // The frontend, exactly as bundled.
      Http.Response index = Http.get(base + "/", null, 10000);
      eq("GET / -> 200", 200, index.status);
      eq("with an html type", "text/html; charset=utf-8", index.contentType);
      check("and the real index.html", new String(index.body, "UTF-8").contains("UBLotse"), "");
      eq("GET /app.js -> 200", 200, Http.get(base + "/app.js", null, 10000).status);
      eq("GET /style.css -> 200", 200, Http.get(base + "/style.css", null, 10000).status);
      eq("a missing asset -> 404", 404, Http.get(base + "/nope.js", null, 10000).status);
      eq("a traversal attempt -> 403", 403, Http.get(base + "/../server.js", null, 10000).status);

      Raw get = rawGet(port, "/api/health");
      check("responses carry Connection: close", "close".equalsIgnoreCase(get.headers.get("connection")), String.valueOf(get.headers));
      check("and a Content-Length", get.headers.containsKey("content-length"), String.valueOf(get.headers));
      check("and CORS pinned to our own origin", ("http://127.0.0.1:" + port).equals(get.headers.get("access-control-allow-origin")), String.valueOf(get.headers.get("access-control-allow-origin")));

      // The wishlist, over a raw socket, the way Chromium sends it.
      Map<String, Object> record = recordJson("0-1396347968", "Progress and poverty", "1881");
      // Padded past a kilobyte, which is where Chromium starts announcing the body with
      // `Expect: 100-continue` and waiting for the go-ahead.
      record.put("summary", repeat("Ein ausführlicher Auszug aus dem Katalog. ", 40));
      String payload = Json.write(Json.obj("record", record));
      check("the POST body exceeds the 100-continue threshold", payload.length() > 1024, payload.length() + " bytes");

      Raw expected = rawPost(port, "/api/wishlist", payload, true);
      check("Expect: 100-continue is answered", expected.interim != null && expected.interim.contains("100"), String.valueOf(expected.interim));
      check("the wishlist POST succeeds", expected.statusLine != null && expected.statusLine.contains("200"), String.valueOf(expected.statusLine));
      Map<String, Object> savedBody = (Map<String, Object>) Json.parse(expected.body);
      eq("saved", Boolean.TRUE, savedBody.get("saved"));
      eq("key", "0-1396347968", savedBody.get("key"));
      eq("the app used its own install id, not the header", 1L, store.count(installId));
      eq("and ignored the browser's X-Client-Id", 0L, store.count(TEST_CLIENT));

      eq("the wishlist reads back", 1L, json(Http.get(base + "/api/wishlist", Http.headers("X-Client-Id", TEST_CLIENT), 10000)).get("count"));
      eq("even for a different header, because the install id wins", 1L,
          json(Http.get(base + "/api/wishlist", Http.headers("X-Client-Id", "someone-else-99"), 10000)).get("count"));

      Raw deleted = rawDelete(port, "/api/wishlist/0-1396347968");
      check("DELETE succeeds", deleted.statusLine.contains("200"), deleted.statusLine);
      eq("and empties the list", 0L, json(Http.get(base + "/api/wishlist", null, 10000)).get("count"));

      // A body the app refuses. Sent with Expect:, because a server that has already decided to
      // reject the request should not then invite the upload.
      Raw tooBig = rawPost(port, "/api/wishlist", repeat("x", UblotseApi.MAX_BODY_BYTES + 100), true);
      check("an oversized body is refused", tooBig.statusLine != null && tooBig.statusLine.contains("502"), String.valueOf(tooBig.statusLine));
      check("without being invited first", tooBig.interim == null, String.valueOf(tooBig.interim));
      check("and with the server's message", tooBig.body.contains("Request body too large"), abbreviate(tooBig.body, 120));
      eq("and nothing was stored", 0L, store.count(installId));

      // "Mein Konto" over the same loopback origin the WebView uses. The app starts signed out, so
      // the route, the status code and the JSON shape are checked without touching the catalogue.
      check("the bundled frontend carries the account entry", new String(index.body, "UTF-8").contains("account-toggle"), "");
      Raw accountStatus = rawGet(port, "/api/account");
      check("the account route answers on the loopback server", accountStatus.statusLine.contains("200"), accountStatus.statusLine);
      check("and starts signed out", accountStatus.body.contains("\"loggedIn\":false"), accountStatus.body);
      Raw accountLoans = rawGet(port, "/api/account/loans");
      check("loans need a sign-in", accountLoans.statusLine.contains("401"), accountLoans.statusLine);
      check("with the app's message", accountLoans.body.contains("Nicht angemeldet."), accountLoans.body);
      Raw accountLogout = rawPost(port, "/api/account/logout", "", false);
      check("logging out while signed out is harmless", accountLogout.statusLine != null && accountLogout.statusLine.contains("200"), String.valueOf(accountLogout.statusLine));

      // The whole sign-in, over the socket the WebView uses, against a canned catalogue: this is
      // the request Chromium makes when the card number is typed into the account form.
      FakeCatalogue canned = new FakeCatalogue();
      UblotseApi accountApi =
          new UblotseApi(new Sru(), new Covers(null), new MemoryWishlist(), new Account(FakeCatalogue.BASE, canned));
      LocalServer signIn =
          new LocalServer(
              (method, path, query, body, headers) -> accountApi.handle(method, path, query, body, installId),
              new DirectoryResources(publicDirectory));
      int signInPort = signIn.start();
      try {
        Raw signed =
            rawPost(
                signInPort,
                "/api/account/login",
                Json.write(Json.obj("username", "1234567890", "password", "geheim")),
                false);
        check("a sign-in over the loopback socket -> 200", signed.statusLine != null && signed.statusLine.contains("200"), String.valueOf(signed.statusLine));
        check("and lists the loans", signed.body.contains("Effi Briest") && signed.body.contains("\"dueISO\":\"2025-04-02\""), abbreviate(signed.body, 200));
        Raw listed = rawGet(signInPort, "/api/account/loans");
        check("the session survives between requests", listed.statusLine.contains("200") && listed.body.contains("Buddenbrooks"), listed.statusLine);
      } finally {
        signIn.stop();
      }

      // Live A/B against the answers the reference Node server gave for the same queries.
      Map<String, Object> live = (Map<String, Object>) parity.get("live");
      Map<String, Object> answers = (Map<String, Object>) live.get("answers");
      for (Object queryObject : (List<Object>) live.get("queries")) {
        String query = (String) queryObject;
        Map<String, Object> reference = (Map<String, Object>) answers.get(query);
        String url = base + "/api/search?q=" + formEncode(query);
        Http.Response response = Http.get(url, Http.headers("User-Agent", "ublotse-tests/0.1"), 40000);
        if (response.status != 200) {
          check("search \"" + query + "\"", false, "HTTP " + response.status + " " + new String(response.body, "UTF-8"));
          continue;
        }
        Map<String, Object> data = (Map<String, Object>) Json.parse(new String(response.body, "UTF-8"));
        eq("search \"" + query + "\" total", number(reference.get("total")), (Long) data.get("total"));
        eq("search \"" + query + "\" cql", reference.get("cql"), data.get("cql"));
        eq("search \"" + query + "\" cqlDisplay", reference.get("cqlDisplay"), data.get("cqlDisplay"));

        List<Object> expectedRecords = (List<Object>) reference.get("records");
        List<Object> actualRecords = (List<Object>) data.get("records");
        eq("search \"" + query + "\" record count", (long) expectedRecords.size(), (long) actualRecords.size());

        List<Object> flattened = new ArrayList<>();
        for (Object found : actualRecords) {
          Map<String, Object> got = (Map<String, Object>) found;
          Map<String, Object> flat = new LinkedHashMap<>();
          flat.put("id", got.get("id"));
          flat.put("title", got.get("title"));
          flat.put("year", got.get("year"));
          flat.put("isbn", got.get("isbn"));
          List<Object> authors = new ArrayList<>();
          for (Object author : (List<Object>) got.get("authors")) {
            Map<String, Object> named = (Map<String, Object>) author;
            authors.add(named.get("name") + "|" + named.get("roleLabel"));
          }
          flat.put("authors", authors);
          flattened.add(flat);
        }

        int compared = Math.min(expectedRecords.size(), flattened.size());
        int differences = 0;
        for (int i = 0; i < compared; i += 1) {
          if (deepEquals(expectedRecords.get(i), flattened.get(i))) continue;
          // The index does not order records that score equally consistently: two adjacent records
          // can change places between two identical queries. That is upstream, not a porting bug,
          // so an adjacent transposition is noted rather than failed.
          boolean tieSwap =
              i + 1 < compared
                  && deepEquals(expectedRecords.get(i), flattened.get(i + 1))
                  && deepEquals(expectedRecords.get(i + 1), flattened.get(i));
          if (tieSwap) {
            notes.add("records " + i + " and " + (i + 1) + " of \"" + query + "\" are a score tie and came back swapped");
            i += 1;
            continue;
          }
          differences += 1;
          check("search \"" + query + "\" record " + i, false, describeDifference(Json.write(expectedRecords.get(i)), Json.write(flattened.get(i))));
        }
        if (differences == 0) check("search \"" + query + "\" returns the same records", true, "");
        eq("search \"" + query + "\" facets", canonical(reference.get("facets")), canonical(data.get("facets")));
        if (!Json.write(reference.get("facets")).equals(Json.write(data.get("facets")))) {
          notes.add("facet order differs from the server for \"" + query + "\" where counts tie; the frontend recomputes facets anyway");
        }
      }

      // The relevance fix this whole port exists to preserve, stated directly.
      Map<String, Object> lolita = (Map<String, Object>) Json.parse(new String(Http.get(base + "/api/search?q=Lolita+Nabokov", null, 40000).body, "UTF-8"));
      List<Object> top = (List<Object>) lolita.get("records");
      String topTitle = String.valueOf(((Map<String, Object>) top.get(0)).get("title"));
      // Every contributor, not just the lead: the 1959 edition the index ranks first credits Earl
      // H. Rovit's introduction ahead of Nabokov, and which name comes first is a cataloguing
      // detail, not a relevance one.
      StringBuilder credits = new StringBuilder();
      for (Object author : (List<Object>) ((Map<String, Object>) top.get(0)).get("authors")) {
        credits.append(((Map<String, Object>) author).get("name")).append("; ");
      }
      check("Lolita Nabokov ranks the novel first", topTitle.startsWith("Lolita"), topTitle);
      check("written by Nabokov", credits.toString().contains("Nabokov"), credits.toString());

      Map<String, Object> george = (Map<String, Object>) Json.parse(new String(Http.get(base + "/api/search?q=Progress+and+Poverty+Henry+George", null, 40000).body, "UTF-8"));
      List<Object> georgeTop = (List<Object>) george.get("records");
      // Before the relevance fix these sat around 35th, behind reviews and journal articles about
      // the book. Every one of the first five is now an English edition of George's own work.
      boolean georgesEnglishWork = true;
      StringBuilder opening = new StringBuilder();
      for (int i = 0; i < 5; i += 1) {
        Map<String, Object> candidate = (Map<String, Object>) georgeTop.get(i);
        boolean english = ((List<Object>) candidate.get("languages")).contains("English");
        boolean byGeorge = false;
        for (Object author : (List<Object>) candidate.get("authors")) {
          if ("George, Henry".equals(String.valueOf(((Map<String, Object>) author).get("name")))) byGeorge = true;
        }
        georgesEnglishWork &= english && byGeorge;
        opening.append("\n           ").append(i + 1).append(". ").append(abbreviate(String.valueOf(candidate.get("title")), 70)).append(" (").append(candidate.get("year")).append(")");
      }
      check("Progress and Poverty fills the top five", georgesEnglishWork, opening.toString());
      eq("found by author, not by title alone", "George, Henry", ((Map<String, Object>) ((List<Object>) ((Map<String, Object>) georgeTop.get(0)).get("authors")).get(0)).get("name"));
    } finally {
      server.stop();
    }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static void section(String title) {
    System.out.println("\n  " + title);
  }

  private static void check(String name, boolean ok, String detail) {
    if (ok) {
      passed += 1;
      System.out.println("    ok   " + name);
    } else {
      failures.add(name + (detail == null || detail.isEmpty() ? "" : "\n           " + detail));
      System.out.println("    FAIL " + name + (detail == null || detail.isEmpty() ? "" : "\n           " + detail));
    }
  }

  private static void eq(String name, Object expected, Object actual) {
    boolean ok = expected == null ? actual == null : expected.equals(actual);
    check(name, ok, ok ? "" : "expected <" + abbreviate(String.valueOf(expected), 120) + ">\n           actual   <" + abbreviate(String.valueOf(actual), 120) + ">");
  }

  private static String abbreviate(String value, int limit) {
    if (value == null) return "null";
    return value.length() <= limit ? value : value.substring(0, limit) + "…";
  }

  /** Points at the first differing character, which matters for three-kilobyte CQL strings. */
  private static String describeDifference(String expected, String actual) {
    int at = 0;
    while (at < expected.length() && at < actual.length() && expected.charAt(at) == actual.charAt(at)) at += 1;
    int from = Math.max(0, at - 40);
    return "first difference at "
        + at
        + "\n           expected <"
        + abbreviate(expected.substring(from, Math.min(expected.length(), at + 40)), 90)
        + ">"
        + "\n           actual   <"
        + abbreviate(actual.substring(from, Math.min(actual.length(), at + 40)), 90)
        + ">";
  }

  private static boolean deepEquals(Object a, Object b) {
    if (a == null || b == null) return a == b;
    if (a instanceof Map && b instanceof Map) {
      Map<?, ?> left = (Map<?, ?>) a;
      Map<?, ?> right = (Map<?, ?>) b;
      if (!left.keySet().equals(right.keySet())) return false;
      for (Object key : left.keySet()) {
        if (!deepEquals(left.get(key), right.get(key))) return false;
      }
      return true;
    }
    if (a instanceof List && b instanceof List) {
      List<?> left = (List<?>) a;
      List<?> right = (List<?>) b;
      if (left.size() != right.size()) return false;
      for (int i = 0; i < left.size(); i += 1) {
        if (!deepEquals(left.get(i), right.get(i))) return false;
      }
      return true;
    }
    if (a instanceof Number && b instanceof Number) {
      return ((Number) a).doubleValue() == ((Number) b).doubleValue();
    }
    return a.equals(b);
  }

  /** The facet pairs as a set, so that only tie order — a cosmetic detail — can differ. */
  private static String canonical(Object facets) {
    Map<?, ?> map = (Map<?, ?>) facets;
    List<String> lines = new ArrayList<>();
    for (Object key : map.keySet()) {
      List<String> pairs = new ArrayList<>();
      for (Object entry : (List<?>) map.get(key)) {
        Map<?, ?> pair = (Map<?, ?>) entry;
        pairs.add(pair.get("value") + "=" + pair.get("count"));
      }
      Collections.sort(pairs);
      lines.add(key + "[" + String.join(", ", pairs) + "]");
    }
    Collections.sort(lines);
    return String.join("\n", lines);
  }

  private static Long number(Object value) {
    return value instanceof Number ? (long) ((Number) value).longValue() : null;
  }

  private static Map<String, String> empty() {
    return new LinkedHashMap<>();
  }

  private static Map<String, String> params(String... keyValues) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) map.put(keyValues[i], keyValues[i + 1]);
    return map;
  }

  /** Either a raw body (for malformed input) or a value to be encoded. */
  private static byte[] body(Object payload) {
    try {
      return (payload instanceof String ? (String) payload : Json.write(payload)).getBytes("UTF-8");
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static Map<String, Object> json(WebResponse response) {
    return (Map<String, Object>) Json.parse(new String(response.body, java.nio.charset.Charset.forName("UTF-8")));
  }

  private static Map<String, Object> json(Http.Response response) {
    return json(new String(response.body, java.nio.charset.Charset.forName("UTF-8")));
  }

  private static Map<String, Object> json(String text) {
    return (Map<String, Object>) Json.parse(text);
  }

  private static Map<String, Object> recordJson(String id, String title, String year) {
    return Json.obj("id", id, "title", title, "year", year, "authors", Json.arr(Json.obj("name", "Test, Autor", "roleLabel", "")));
  }

  private static String formEncode(String value) {
    try {
      return java.net.URLEncoder.encode(value, "UTF-8");
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String repeat(String value, int times) {
    StringBuilder out = new StringBuilder(value.length() * times);
    for (int i = 0; i < times; i += 1) out.append(value);
    return out.toString();
  }

  private static String readFile(File file) throws IOException {
    return new String(Files.readAllBytes(file.toPath()), "UTF-8");
  }

  // -------------------------------------------------------------------------
  // Raw HTTP, so that the handshake itself is under test
  // -------------------------------------------------------------------------

  private static final class Raw {
    String statusLine;
    String interim;
    final Map<String, String> headers = new LinkedHashMap<>();
    String body = "";
  }

  private static Raw rawGet(int port, String path) throws IOException {
    Socket socket = new Socket("127.0.0.1", port);
    socket.setSoTimeout(15000);
    try {
      OutputStream out = socket.getOutputStream();
      out.write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
      out.flush();
      return readResponse(socket.getInputStream());
    } finally {
      socket.close();
    }
  }

  private static Raw rawPost(int port, String path, String payload, boolean expectContinue) throws IOException {
    Socket socket = new Socket("127.0.0.1", port);
    socket.setSoTimeout(30000);
    try {
      byte[] bytes = payload.getBytes("UTF-8");
      StringBuilder head = new StringBuilder();
      head.append("POST ").append(path).append(" HTTP/1.1\r\n");
      head.append("Host: 127.0.0.1\r\n");
      head.append("Content-Type: application/json\r\n");
      head.append("X-Client-Id: ").append(TEST_CLIENT).append("\r\n");
      head.append("Content-Length: ").append(bytes.length).append("\r\n");
      if (expectContinue) head.append("Expect: 100-continue\r\n");
      head.append("Connection: close\r\n\r\n");

      OutputStream out = socket.getOutputStream();
      out.write(head.toString().getBytes("ISO-8859-1"));
      out.flush();

      // Without an Expect header a browser sends the body straight away, and so does this. (The
      // wishlist test does the other thing on purpose: it is about the 100-continue handshake.)
      boolean sent = false;
      if (!expectContinue) {
        out.write(bytes);
        out.flush();
        sent = true;
      }

      InputStream in = socket.getInputStream();
      Raw raw = new Raw();
      String statusLine = LocalServer.readLine(in);
      // A 1xx announces the body is welcome: drain it, send the body, read on. Anything else is
      // the final answer — which is what a refusal looks like, and it must not be invited first.
      while (statusLine != null && statusLine.contains(" 1") && !statusLine.contains(" 101")) {
        raw.interim = statusLine;
        String line;
        while ((line = LocalServer.readLine(in)) != null && !line.isEmpty()) {
          // interim headers carry nothing we need
        }
        if (!sent) {
          out.write(bytes);
          out.flush();
          sent = true;
        }
        statusLine = LocalServer.readLine(in);
      }
      if (statusLine == null) return raw;
      Raw answer = finishResponse(in, withStatus(statusLine));
      answer.interim = raw.interim;
      return answer;
    } finally {
      socket.close();
    }
  }

  private static Raw withStatus(String statusLine) {
    Raw raw = new Raw();
    raw.statusLine = statusLine;
    return raw;
  }

  private static Raw rawDelete(int port, String path) throws IOException {
    Socket socket = new Socket("127.0.0.1", port);
    socket.setSoTimeout(15000);
    try {
      OutputStream out = socket.getOutputStream();
      out.write(("DELETE " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
      out.flush();
      return readResponse(socket.getInputStream());
    } finally {
      socket.close();
    }
  }

  private static Raw readResponse(InputStream in) throws IOException {
    return finishResponse(in, new Raw());
  }

  /** Reads headers and body into the given response, whose status line is already set. */
  private static Raw finishResponse(InputStream in, Raw raw) throws IOException {
    if (raw.statusLine == null) raw.statusLine = LocalServer.readLine(in);
    String line;
    while ((line = LocalServer.readLine(in)) != null && !line.isEmpty()) {
      int colon = line.indexOf(':');
      if (colon > 0) raw.headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
    }
    int length = 0;
    if (raw.headers.containsKey("content-length")) {
      length = Integer.parseInt(raw.headers.get("content-length"));
    }
    byte[] body = new byte[length];
    int filled = 0;
    while (filled < length) {
      int read = in.read(body, filled, length - filled);
      if (read == -1) break;
      filled += read;
    }
    raw.body = new String(body, 0, filled, "UTF-8");
    return raw;
  }

  // -------------------------------------------------------------------------
  // Test doubles
  // -------------------------------------------------------------------------

  /** The frontend directory, standing in for the APK's assets. */
  private static final class DirectoryResources implements LocalServer.ResourceSource {
    private final File root;

    DirectoryResources(File root) {
      this.root = root;
    }

    @Override
    public byte[] read(String path) {
      try {
        return Files.readAllBytes(new File(root, path.substring(1)).toPath());
      } catch (IOException missing) {
        return null;
      }
    }

    @Override
    public String contentType(String path) {
      return LocalServer.contentTypeFor(path);
    }
  }

  /** SQLite's semantics in a map, so the API can be tested without SQLite. */
  private static final class MemoryWishlist implements WishlistStore {
    private static final class Row {
      String key;
      String record;
      String note;
      String createdAt;
    }

    private final Map<String, Map<String, Row>> byClient = new LinkedHashMap<>();

    @Override
    public List<Map<String, Object>> list(String clientId) {
      List<Map<String, Object>> rows = new ArrayList<>();
      Map<String, Row> mine = byClient.get(clientId);
      if (mine == null) return rows;
      for (Row row : mine.values()) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("record_id", row.key);
        flat.put("record", row.record);
        flat.put("note", row.note);
        flat.put("created_at", row.createdAt);
        rows.add(flat);
      }
      Collections.sort(
          rows,
          (a, b) -> {
            int byDate = String.valueOf(b.get("created_at")).compareTo(String.valueOf(a.get("created_at")));
            return byDate != 0 ? byDate : String.valueOf(a.get("record_id")).compareTo(String.valueOf(b.get("record_id")));
          });
      return rows;
    }

    @Override
    public long count(String clientId) {
      Map<String, Row> mine = byClient.get(clientId);
      return mine == null ? 0 : mine.size();
    }

    @Override
    public void save(String clientId, String key, String recordJson, String note, String createdAt) {
      Map<String, Row> mine = byClient.get(clientId);
      if (mine == null) {
        mine = new LinkedHashMap<>();
        byClient.put(clientId, mine);
      }
      Row existing = mine.get(key);
      Row row = new Row();
      row.key = key;
      row.record = recordJson;
      row.note = note;
      row.createdAt = existing == null ? createdAt : existing.createdAt; // what ON CONFLICT does
      mine.put(key, row);
    }

    @Override
    public boolean remove(String clientId, String key) {
      Map<String, Row> mine = byClient.get(clientId);
      return mine != null && mine.remove(key) != null;
    }

    @Override
    public String describe() {
      return ":memory:";
    }
  }

  /**
   * A catalogue in a method call: the login form, the csrf handshake, cookies that have to survive a
   * redirect, the loans table and one renew link. Nothing here touches the network, so the account
   * routes are testable on a machine without an emulator or a library card.
   */
  private static final class FakeCatalogue implements Account.Transport {
    static final String BASE = "https://katalog.example";

    static final class Call {
      final String method;
      final String path;
      final String form;
      final String cookie;
      final String contentType;

      Call(String method, String path, String form, String cookie, String contentType) {
        this.method = method;
        this.path = path;
        this.form = form == null ? "" : form;
        this.cookie = cookie == null ? "" : cookie;
        this.contentType = contentType;
      }

      String describe() {
        return method + " " + path;
      }
    }

    final List<Call> calls = Collections.synchronizedList(new ArrayList<Call>());
    volatile boolean expired;
    volatile int dueDay = 2;
    /** Serve VuFind 10's record list instead of the older table, and renew by posting to it. */
    volatile boolean recordList;
    volatile String renewForm = "";

    @Override
    public Account.Reply send(
        String method, String url, Map<String, String> headers, String form) {
      String path = url.startsWith(BASE) ? url.substring(BASE.length()) : url;
      String cookie = headers.get("Cookie");
      calls.add(new Call(method, path, form, cookie, headers.get("Content-Type")));

      if (path.startsWith("/MyResearch/Home")) {
        return "GET".equals(method) ? home(cookie) : login(form);
      }
      if (path.startsWith("/MyResearch/CheckedOut")) {
        if ("POST".equals(method)) {
          renewForm = form == null ? "" : form;
          dueDay = 20;
          return page(
              "<div class=\"alert alert-success\">Die Ausleihe wurde verl\u00e4ngert.</div>"
                  + checkedOutList(cookie));
        }
        return recordList ? checkedOutList(cookie) : checkedOut(cookie);
      }
      if (path.startsWith("/MyResearch/Record/0-11/Renew")) {
        dueDay = 20;
        return page("<div class=\"alert alert-success\">Die Ausleihe wurde verl\u00e4ngert.</div>");
      }
      if (path.startsWith("/MyResearch/Logout")) {
        Map<String, List<String>> redirect = new LinkedHashMap<>();
        redirect.put("Location", Arrays.asList("/"));
        return new Account.Reply(302, redirect, "");
      }
      return new Account.Reply(404, "Unbekannt");
    }

    Call last() {
      return calls.isEmpty() ? null : calls.get(calls.size() - 1);
    }

    private Account.Reply home(String cookie) {
      if (cookie != null && cookie.contains("LBSESSION=ok")) return page(loggedInPage());
      Map<String, List<String>> cookies = new LinkedHashMap<>();
      cookies.put("Set-Cookie", Arrays.asList("VuFind=first; Path=/; HttpOnly"));
      return new Account.Reply(200, cookies, pageHtml(loginForm()));
    }

    private Account.Reply login(String form) {
      Map<String, String> fields = formFields(form);
      boolean valid =
          "1234567890".equals(fields.get("username"))
              && "geheim".equals(fields.get("password"))
              && "ILS".equals(fields.get("auth_method"))
              && "tok-1".equals(fields.get("csrf"))
              && "Mein Konto".equals(fields.get("processLogin"));
      if (!valid) {
        return page(
            "<div class=\"alert alert-danger\">Ausweisnummer oder Passwort ist falsch.</div>");
      }
      Map<String, List<String>> handed = new LinkedHashMap<>();
      handed.put("Set-Cookie", Arrays.asList("LBSESSION=ok; Path=/; HttpOnly"));
      handed.put("Location", Arrays.asList("/MyResearch/Home?loggedIn=1"));
      return new Account.Reply(302, handed, "");
    }

    private Account.Reply checkedOut(String cookie) {
      // The real catalogue sends the login form (or a redirect to it) once the cookie is stale, which
      // is exactly what the session-expiry check has to notice.
      if (expired || cookie == null || !cookie.contains("LBSESSION=ok")) {
        return page(loginForm());
      }
      String day = dueDay < 10 ? "0" + dueDay : String.valueOf(dueDay);
      return page(
          "<table class=\"table\">"
              + "<tr><th>Titel</th><th>Autor</th><th>Ausgeliehen</th><th>F\u00e4llig</th><th>Aktionen</th></tr>"
              + "<tr><td><a href=\"/Record/0-11\">Effi Briest</a></td><td>Fontane, Theodor</td>"
              + "<td>10.01.2025</td><td>"
              + day
              + ".04.2025</td>"
              + "<td><a href=\"/MyResearch/Record/0-11/Renew?csrf=tok-1\">Verl\u00e4ngern</a></td></tr>"
              + "<tr><td><a href=\"/Record/0-12\">Buddenbrooks</a></td><td>Mann, Thomas</td>"
              + "<td>11.01.2025</td><td>05.05.2025</td><td>&nbsp;</td></tr>"
              + "</table>");
    }

    /** VuFind 10's myresearch/checkedout.phtml: a record list plus the csrf the form needs. */
    private Account.Reply checkedOutList(String cookie) {
      if (expired || cookie == null || !cookie.contains("LBSESSION=ok")) {
        return page(loginForm());
      }
      String day = dueDay < 10 ? "0" + dueDay : String.valueOf(dueDay);
      String renewId = "0-1610511743|1|" + day + ".04.2025";
      return page(
          "<form name=\"renewals\" method=\"post\" id=\"renewals\">"
              + "<input type=\"hidden\" value=\"csrf-hash-9\" name=\"csrf\">"
              + "<input type=\"hidden\" id=\"submitType\" name=\"renewSelected\" value=\"1\">"
              + "<ul class=\"record-list\">"
              + "<li id=\"record0-1610511743\" class=\"result\">"
              + "<div class=\"checkbox\"><label><input class=\"checkbox-select-item\" "
              + "type=\"checkbox\" name=\"renewSelectedIDS[]\" value=\""
              + renewId
              + "\" id=\"checkbox_1\" aria-label=\"Ausleihe ausw\u00e4hlen\"></label>"
              + "<input type=\"hidden\" name=\"selectAllIDS[]\" value=\""
              + renewId
              + "\"></div>"
              + "<div class=\"media\"><div class=\"media-body\">"
              + "<h2><a href=\"/Record/0-1610511743\" class=\"title\">Lolita : Roman</a>"
              + "<span class=\"authors\">von: "
              + "<a href=\"/Author/Home?author=Nabokov\">Nabokov, Vladimir</a><br></span></h2>"
              + "<strong>R\u00fcckgabedatum: "
              + day
              + ".04.2025</strong>"
              + "</div></div></li>"
              + "<li id=\"record0-99\" class=\"result\"><div class=\"media-body\">"
              + "<h2><span class=\"title\">Der Zauberberg</span></h2>"
              + "<strong>R\u00fcckgabedatum: 2.5.2025</strong></div></li>"
              + "</ul></form>");
    }

    private static String loggedInPage() {
      return "<div class=\"account\"><a href=\"/MyResearch/Logout\">Abmelden</a></div>";
    }

    private static String loginForm() {
      return "<form method=\"post\" name=\"loginForm\" action=\"/MyResearch/Home\">"
          + "<input type=\"hidden\" name=\"csrf\" value=\"tok-1\"/>"
          + "<input name=\"username\"/><input type=\"password\" name=\"password\"/>"
          + "<button type=\"submit\" name=\"processLogin\" value=\"Mein Konto\">Anmelden</button>"
          + "</form>";
    }

    private static Account.Reply page(String inner) {
      return new Account.Reply(200, pageHtml(inner));
    }

    private static String pageHtml(String inner) {
      return "<html><body>" + inner + "</body></html>";
    }

    private static Map<String, String> formFields(String form) {
      Map<String, String> fields = new LinkedHashMap<>();
      if (form == null) return fields;
      for (String pair : form.split("&")) {
        int equals = pair.indexOf('=');
        if (equals < 0) continue;
        try {
          fields.put(
              URLDecoder.decode(pair.substring(0, equals), "UTF-8"),
              URLDecoder.decode(pair.substring(equals + 1), "UTF-8"));
        } catch (IOException impossible) {
          throw new IllegalStateException(impossible);
        }
      }
      return fields;
    }
  }

  /** A one-endpoint HTTP server that records what it was asked and with which headers. */
  private static final class FakeServer {
    private final HttpServer http;
    final List<String> paths = Collections.synchronizedList(new ArrayList<String>());
    final List<String> cookies = Collections.synchronizedList(new ArrayList<String>());
    volatile int hits;

    FakeServer(byte[] body, String contentType, int status) throws IOException {
      http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      http.createContext(
          "/",
          exchange -> {
            hits += 1;
            paths.add(exchange.getRequestURI().toString());
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            cookies.add(cookie == null ? "" : cookie);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      http.setExecutor(null);
      http.start();
    }

    String url() {
      return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    void stop() {
      http.stop(0);
    }
  }
}