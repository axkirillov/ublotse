package de.axkirillov.ublotse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Mein Konto" — the loans of the signed-in library account. Ported from {@code account.js}.
 *
 * <p>The SRU index carries no per-user data, so this talks to the catalogue's VuFind account
 * instead: it fetches the login form, posts the library card number and password, keeps the session
 * cookie in memory and parses the "Checked Out" table into JSON.
 *
 * <p>Nothing about the account is written to disk, and the card number and password exist only for
 * the duration of one login POST — kept in memory, they would otherwise outlive the request that
 * carried them. The session itself (a cookie, not a credential) lives per client id and is dropped
 * after 30 idle minutes or on logout.
 *
 * <p>Deliberately Android-free: like the rest of {@code /api/*}, this runs on a plain JVM, which is
 * the only way to test it without an emulator or a real library card. The network is behind {@link
 * Transport} so the tests can drive a canned catalogue.
 */
public class Account {

  public static final String DEFAULT_CATALOGUE = Solr.CATALOGUE_BASE;
  public static final long IDLE_MS = 30L * 60 * 1000;
  public static final int DEFAULT_TIMEOUT_MS = Sru.DEFAULT_TIMEOUT_MS;
  /** Matches {@code account.js}: enough hops for a login redirect chain, bounded against a loop. */
  static final int MAX_HOPS = 6;
  static final int MAX_PAGE_BYTES = 4 * 1024 * 1024;

  static final String USER_AGENT = "UBLotse/0.2 (library catalogue frontend)";

  private static final Pattern TABLE_RE = Pattern.compile("<table\\b[\\s\\S]*?</table>", Pattern.CASE_INSENSITIVE);
  private static final Pattern ROW_RE = Pattern.compile("<tr\\b[\\s\\S]*?</tr>", Pattern.CASE_INSENSITIVE);
  private static final Pattern CELL_RE =
      Pattern.compile("<(t[dh])\\b[^>]*>([\\s\\S]*?)</\\1>", Pattern.CASE_INSENSITIVE);
  private static final Pattern ANCHOR_RE =
      Pattern.compile(
          "<a\\b[^>]*href=\"([^\"]*/Record/[^\"]*)\"[^>]*>([\\s\\S]*?)</a>", Pattern.CASE_INSENSITIVE);
  private static final Pattern RENEW_RE =
      Pattern.compile(
          "<a\\b[^>]*href=\"([^\"]*(?:renew|verlaengern|verl(?:ä|&auml;)ngern)[^\"]*)\"[^>]*>",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern RECORD_ID_RE = Pattern.compile("(?:/Record/)([^?\"'/]+)");
  private static final Pattern TABLE_HINT_RE =
      Pattern.compile(
          "f(?:ä|&auml;|ae)llig|due date|ausgeliehen|checked out|r(?:ü|&uuml;|ue)ckgabe",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern DUE_HEADER_RE =
      Pattern.compile(
          "f(?:ä|&auml;|ae)llig|due|r(?:ü|&uuml;|ue)ckgabe", Pattern.CASE_INSENSITIVE);
  private static final Pattern BORROWED_HEADER_RE =
      Pattern.compile("ausgeliehen|borrowed|seit", Pattern.CASE_INSENSITIVE);
  private static final Pattern DATE_RE = Pattern.compile("\\b\\d{1,2}\\.\\d{1,2}\\.\\d{4}\\b");
  private static final Pattern GERMAN_DATE_RE = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})");
  private static final Pattern ALERT_RE =
      Pattern.compile(
          "class=\"[^\"]*(?:alert|error|fehler)[^\"]*\"[^>]*>([\\s\\S]{0,600}?)<",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern CSRF_FIRST_RE =
      Pattern.compile("name=\"csrf\"\\s+value=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
  private static final Pattern CSRF_SECOND_RE =
      Pattern.compile("value=\"([^\"]+)\"\\s+name=\"csrf\"", Pattern.CASE_INSENSITIVE);
  private static final Pattern LOGOUT_RE = Pattern.compile("/MyResearch/Logout");
  private static final Pattern LOGIN_PAGE_RE =
      Pattern.compile("/MyResearch/UserLogin|name=\"loginForm\"", Pattern.CASE_INSENSITIVE);
  private static final Pattern RENEWED_RE =
      Pattern.compile("verl(?:ä|&auml;)ngert|renewed|success", Pattern.CASE_INSENSITIVE);
  private static final Pattern EXPIRED_COOKIE_RE =
      Pattern.compile("expires=Thu, 01 Jan 1970", Pattern.CASE_INSENSITIVE);

  // -------------------------------------------------------------------------
  // Transport
  // -------------------------------------------------------------------------

  /** One page from the catalogue. Redirects are followed by {@link Account}, not by the client. */
  public static final class Reply {
    public final int status;
    public final String body;
    private final Map<String, List<String>> headers;

    public Reply(int status, Map<String, List<String>> headers, String body) {
      this.status = status;
      this.headers = headers == null ? new LinkedHashMap<String, List<String>>() : headers;
      this.body = body == null ? "" : body;
    }

    public Reply(int status, String body) {
      this(status, null, body);
    }

    /** The first value of a header, looked up case-insensitively; null when absent. */
    public String header(String name) {
      List<String> values = headers(name);
      return values.isEmpty() ? null : values.get(0);
    }

    public List<String> headers(String name) {
      List<String> found = new ArrayList<>();
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
        if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null) {
          found.addAll(entry.getValue());
        }
      }
      return found;
    }
  }

  /** One request/response exchange. Swappable so the tests need neither network nor a card. */
  public interface Transport {
    Reply send(String method, String url, Map<String, String> headers, String formBody)
        throws IOException;
  }

  /** {@link HttpURLConnection} without redirect handling, because cookies have to survive them. */
  public static final class HttpTransport implements Transport {
    private final int timeoutMs;

    public HttpTransport() {
      this(DEFAULT_TIMEOUT_MS);
    }

    public HttpTransport(int timeoutMs) {
      this.timeoutMs = timeoutMs;
    }

    @Override
    public Reply send(String method, String url, Map<String, String> headers, String formBody)
        throws IOException {
      HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
      connection.setInstanceFollowRedirects(false); // Account follows them so cookies survive
      connection.setConnectTimeout(timeoutMs);
      connection.setReadTimeout(timeoutMs);
      connection.setRequestMethod(method);
      if (headers != null) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
          connection.setRequestProperty(header.getKey(), header.getValue());
        }
      }

      if (formBody != null) {
        byte[] payload = utf8(formBody);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(payload.length);
        OutputStream out = connection.getOutputStream();
        try {
          out.write(payload);
        } finally {
          out.close();
        }
      }

      int status = connection.getResponseCode();
      Map<String, List<String>> received = new LinkedHashMap<>();
      for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
        if (entry.getKey() == null) continue; // the status line comes back under a null key
        received.put(entry.getKey(), new ArrayList<>(entry.getValue()));
      }

      InputStream stream = status >= 400 ? connection.getErrorStream() : null;
      if (stream == null) {
        try {
          stream = connection.getInputStream();
        } catch (IOException noBody) {
          stream = null; // a 30x usually has no body at all
        }
      }
      String body;
      try {
        body = utf8(readPage(stream));
      } finally {
        if (stream != null) {
          try {
            stream.close();
          } catch (IOException ignored) {
            // The body has already been read.
          }
        }
      }
      return new Reply(status, received, body);
    }

    private static byte[] readPage(InputStream stream) throws IOException {
      if (stream == null) return new byte[0];
      ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
      byte[] buffer = new byte[8192];
      int read;
      while ((read = stream.read(buffer)) != -1) {
        out.write(buffer, 0, read);
        if (out.size() > MAX_PAGE_BYTES) throw new IOException("Antwort des Katalogs zu groß.");
      }
      return out.toByteArray();
    }
  }

  // -------------------------------------------------------------------------
  // Session
  // -------------------------------------------------------------------------

  private static final class Session {
    final Map<String, String> cookies = new LinkedHashMap<>();
    boolean loggedIn;
    String username = "";
    long touchedAt;
  }

  /** The catalogue says the cookie is no longer a session; the route turns this into a 401. */
  private static final class SessionExpired extends RuntimeException {
    SessionExpired(String message) {
      super(message);
    }
  }

  private final String catalogueBase;
  private final String origin;
  private final Transport transport;
  private final long idleMs;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  public Account() {
    this(DEFAULT_CATALOGUE, new HttpTransport(), IDLE_MS);
  }

  public Account(String catalogueBase) {
    this(catalogueBase, new HttpTransport(), IDLE_MS);
  }

  public Account(String catalogueBase, Transport transport) {
    this(catalogueBase, transport, IDLE_MS);
  }

  Account(String catalogueBase, Transport transport, long idleMs) {
    this.catalogueBase = catalogueBase.replaceAll("/$", "");
    this.origin = originOf(this.catalogueBase);
    this.transport = transport;
    this.idleMs = idleMs;
  }

  // -------------------------------------------------------------------------
  // Routes
  // -------------------------------------------------------------------------

  /**
   * Route one {@code /api/account*} request. {@code UblotseApi} only calls this for that prefix, so
   * an unknown subpath is a 404 here rather than a fall-through to the bundled frontend.
   */
  public WebResponse handle(String method, String path, byte[] body, String clientId) {
    boolean known =
        "/api/account".equals(path)
            || "/api/account/login".equals(path)
            || "/api/account/logout".equals(path)
            || "/api/account/loans".equals(path)
            || "/api/account/renew".equals(path);
    if (!known) {
      return WebResponse.json(404, Json.obj("error", "Unbekannter Konto-Endpunkt."));
    }

    Session session = sessionFor(clientId);

    if ("/api/account".equals(path) && "GET".equals(method)) {
      return WebResponse.json(
          200,
          Json.obj(
              "loggedIn",
              session.loggedIn,
              "username",
              session.loggedIn && !session.username.isEmpty() ? session.username : null));
    }

    // Every route below reads and rewrites the shared cookie jar, and LocalServer serves requests
    // on several threads; one session therefore does one catalogue conversation at a time.
    synchronized (session) {
      if ("/api/account/login".equals(path) && "POST".equals(method)) {
        return login(session, body);
      }

      if ("/api/account/logout".equals(path) && "POST".equals(method)) {
        logout(session, clientId);
        return WebResponse.json(200, Json.obj("loggedIn", false));
      }

      if ("/api/account/loans".equals(path) && "GET".equals(method)) {
        if (!session.loggedIn) {
          return WebResponse.json(401, Json.obj("error", "Nicht angemeldet.", "loggedIn", false));
        }
        try {
          return WebResponse.json(200, loansPayload(session, loadLoans(session)));
        } catch (SessionExpired expired) {
          return WebResponse.json(401, Json.obj("error", expired.getMessage(), "loggedIn", false));
        } catch (IOException failed) {
          return WebResponse.json(502, Json.obj("error", messageOf(failed), "loggedIn", true));
        }
      }

      if ("/api/account/renew".equals(path) && "POST".equals(method)) {
        if (!session.loggedIn) {
          return WebResponse.json(401, Json.obj("error", "Nicht angemeldet.", "loggedIn", false));
        }
        return renew(session, body);
      }
    }

    return WebResponse.json(404, Json.obj("error", "Unbekannter Konto-Endpunkt."));
  }

  private WebResponse login(Session session, byte[] body) {
    Map<String, Object> request = jsonBody(body);
    String username = request == null ? "" : Js.trim(String.valueOf(valueOr(request, "username", "")));
    String password = request == null ? "" : String.valueOf(valueOr(request, "password", ""));
    if (username.isEmpty() || password.isEmpty()) {
      return WebResponse.json(
          400,
          Json.obj("error", "Ausweisnummer und Passwort erforderlich.", "loggedIn", false));
    }

    try {
      Reply home = request(session, "/MyResearch/Home", "GET", null);
      String csrf = firstGroup(CSRF_FIRST_RE, home.body);
      if (csrf == null) csrf = firstGroup(CSRF_SECOND_RE, home.body);
      if (csrf == null) {
        return WebResponse.json(
            502,
            Json.obj(
                "error", "Das Anmeldeformular des Katalogs war nicht lesbar.", "loggedIn", false));
      }

      Map<String, String> form = new LinkedHashMap<>();
      form.put("username", username);
      form.put("password", password);
      form.put("auth_method", "ILS");
      form.put("csrf", csrf);
      form.put("processLogin", "Mein Konto");

      Reply result = request(session, "/MyResearch/Home", "POST", formEncode(form));

      boolean loggedIn = LOGOUT_RE.matcher(result.body).find();
      session.loggedIn = loggedIn;
      session.username = loggedIn ? username : "";
      if (!loggedIn) {
        String alert = extractAlert(result.body);
        return WebResponse.json(
            401,
            Json.obj(
                "error",
                alert.isEmpty()
                    ? "Anmeldung fehlgeschlagen. Ausweisnummer oder Passwort prüfen."
                    : alert,
                "loggedIn",
                false));
      }

      return WebResponse.json(200, loansPayload(session, loadLoans(session)));
    } catch (IOException failed) {
      // The catalogue could not be reached. The session is left exactly as it was: a timeout in
      // the middle of a login must not sign a working session out.
      return WebResponse.json(502, Json.obj("error", messageOf(failed)));
    } catch (SessionExpired expired) {
      session.loggedIn = false;
      return WebResponse.json(401, Json.obj("error", expired.getMessage(), "loggedIn", false));
    }
  }

  private WebResponse renew(Session session, byte[] body) {
    Map<String, Object> request = jsonBody(body);
    String target = safeRenewTarget(request == null ? "" : String.valueOf(valueOr(request, "href", "")));
    if (target == null) {
      return WebResponse.json(400, Json.obj("error", "Ungültiger Verlängerungslink."));
    }
    try {
      Reply result = request(session, target, "GET", null);
      boolean renewed = RENEWED_RE.matcher(result.body).find();
      // The same shape account.js spreads here: { renewed, message, ...loans }.
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("renewed", renewed);
      payload.put("message", renewed ? "Ausleihe verlängert." : null);
      payload.putAll(itemsPayload(loadLoans(session)));
      return WebResponse.json(200, payload);
    } catch (IOException failed) {
      return WebResponse.json(502, Json.obj("error", messageOf(failed)));
    } catch (SessionExpired expired) {
      session.loggedIn = false;
      return WebResponse.json(401, Json.obj("error", expired.getMessage(), "loggedIn", false));
    }
  }

  private void logout(Session session, String clientId) {
    try {
      if (session.loggedIn || !session.cookies.isEmpty()) {
        request(session, "/MyResearch/Logout", "GET", null);
      }
    } catch (Exception ignored) {
      // A failed upstream logout must not keep the local session alive.
    } finally {
      sessions.remove(clientId == null || clientId.isEmpty() ? "local" : clientId);
    }
  }

  private static Map<String, Object> itemsPayload(List<Map<String, Object>> items) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("items", new ArrayList<Object>(items));
    payload.put("fetchedAt", WishlistSql.nowIso());
    return payload;
  }

  private static Map<String, Object> loansPayload(
      Session session, List<Map<String, Object>> items) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("loggedIn", true);
    payload.put("username", session.username);
    payload.putAll(itemsPayload(items));
    return payload;
  }

  private List<Map<String, Object>> loadLoans(Session session) throws IOException {
    Reply page = request(session, "/MyResearch/CheckedOut", "GET", null);
    if (LOGIN_PAGE_RE.matcher(page.body).find() && !page.body.contains("/MyResearch/Logout")) {
      session.loggedIn = false;
      throw new SessionExpired("Sitzung abgelaufen. Bitte erneut anmelden.");
    }
    return parseLoans(page.body);
  }

  // -------------------------------------------------------------------------
  // Catalogue requests
  // -------------------------------------------------------------------------

  private Session sessionFor(String clientId) {
    long now = System.currentTimeMillis();
    for (Map.Entry<String, Session> entry : sessions.entrySet()) {
      if (now - entry.getValue().touchedAt > idleMs) sessions.remove(entry.getKey());
    }
    String key = clientId == null || clientId.isEmpty() ? "local" : clientId;
    Session session = sessions.get(key);
    if (session == null) {
      session = new Session();
      Session raced = sessions.putIfAbsent(key, session);
      if (raced != null) session = raced;
    }
    session.touchedAt = now;
    return session;
  }

  /** Fetch a catalogue page, following redirects by hand so cookies survive. */
  private Reply request(Session session, String pathOrUrl, String method, String form)
      throws IOException {
    String target = pathOrUrl.startsWith("http") ? pathOrUrl : catalogueBase + pathOrUrl;
    String verb = method;
    String body = form;

    for (int hop = 0; hop < MAX_HOPS; hop += 1) {
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("Cookie", cookieHeader(session));
      headers.put("User-Agent", USER_AGENT);
      headers.put("Accept", "text/html,application/xhtml+xml");
      headers.put("Accept-Encoding", "identity"); // HttpURLConnection cannot decompress gzip
      if (body != null) headers.put("Content-Type", "application/x-www-form-urlencoded");

      Reply reply = transport.send(verb, target, headers, body);
      absorbCookies(session, reply);

      int status = reply.status;
      if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
        String location = reply.header("Location");
        if (location == null || location.isEmpty()) return reply;
        String next = resolve(target, location);
        if (next == null) throw new IOException("Ungültige Weiterleitung beim Katalog.");
        target = next;
        if (status != 307 && status != 308) {
          verb = "GET";
          body = null;
        }
        continue;
      }
      return reply;
    }

    throw new IOException("Zu viele Weiterleitungen beim Katalog.");
  }

  private void absorbCookies(Session session, Reply reply) {
    for (String line : reply.headers("Set-Cookie")) {
      if (line == null) continue;
      int semicolon = line.indexOf(';');
      String pair = semicolon < 0 ? line : line.substring(0, semicolon);
      int equals = pair.indexOf('=');
      if (equals < 1) continue;
      String name = pair.substring(0, equals).trim();
      String value = pair.substring(equals + 1).trim();
      if (value.isEmpty() || EXPIRED_COOKIE_RE.matcher(line).find()) session.cookies.remove(name);
      else session.cookies.put(name, value);
    }
  }

  private static String cookieHeader(Session session) {
    StringBuilder header = new StringBuilder("finc_open=1");
    for (Map.Entry<String, String> cookie : session.cookies.entrySet()) {
      header.append("; ").append(cookie.getKey()).append('=').append(cookie.getValue());
    }
    return header.toString();
  }

  /**
   * A renew link is only followed when it stays on the catalogue and inside the account area. The
   * path is dot-segment-normalised first, because {@code java.net.URL} keeps {@code /MyResearch/../
   * Search} verbatim where a browser (and account.js's {@code new URL}) would already have collapsed
   * it — without that step the prefix check could be walked past.
   */
  String safeRenewTarget(String href) {
    String resolved = resolve(catalogueBase, href == null ? "" : href);
    if (resolved == null) return null;
    if (!originOf(resolved).equals(origin)) return null;

    String path = pathOf(resolved);
    String normalized = normalizePath(path);
    if (!path.equals(normalized)) {
      resolved = rebuild(resolved, normalized);
      if (resolved == null) return null;
    }
    if (!normalized.startsWith("/MyResearch/")) return null;
    if (normalized.toLowerCase(Locale.ROOT).contains("%2e")) return null; // encoded dot segments
    return resolved;
  }

  /** RFC 3986 {@code remove_dot_segments}, which {@code java.net.URL} does not do. */
  static String normalizePath(String path) {
    if (path == null || path.isEmpty()) return "/";
    if (!path.startsWith("/")) return path;
    List<String> segments = new ArrayList<>();
    String[] parts = path.split("/", -1);
    for (int i = 1; i < parts.length; i += 1) {
      String part = parts[i];
      boolean last = i == parts.length - 1;
      if (".".equals(part)) {
        if (last) segments.add("");
        continue;
      }
      if ("..".equals(part)) {
        if (!segments.isEmpty()) segments.remove(segments.size() - 1);
        if (last) segments.add("");
        continue;
      }
      segments.add(part);
    }
    StringBuilder normalized = new StringBuilder();
    for (String segment : segments) normalized.append('/').append(segment);
    return normalized.length() == 0 ? "/" : normalized.toString();
  }

  private static String rebuild(String url, String path) {
    try {
      URL parsed = new URL(url);
      String query = parsed.getQuery();
      String authority = parsed.getAuthority();
      return parsed.getProtocol()
          + "://"
          + (authority == null ? "" : authority)
          + path
          + (query == null ? "" : "?" + query);
    } catch (IOException malformed) {
      return null;
    }
  }

  // -------------------------------------------------------------------------
  // Parsing — ported from parseLoans() in account.js
  // -------------------------------------------------------------------------

  private static final class Cell {
    final boolean header;
    final String text;

    Cell(boolean header, String text) {
      this.header = header;
      this.text = text;
    }
  }

  /**
   * Parse the VuFind "Checked Out" page. The theme renders one table with a title link per row and
   * date columns; header names differ between VuFind versions, so the column lookup accepts English
   * and German labels and falls back to the first date found in the row.
   */
  public static List<Map<String, Object>> parseLoans(String html) {
    List<Map<String, Object>> loans = new ArrayList<>();
    String page = html == null ? "" : html;

    List<String> tables = all(TABLE_RE, page);
    String table = null;
    for (String candidate : tables) {
      if (TABLE_HINT_RE.matcher(candidate).find()) {
        table = candidate;
        break;
      }
    }
    if (table == null) {
      for (String candidate : tables) {
        if (candidate.contains("/Record/")) {
          table = candidate;
          break;
        }
      }
    }
    if (table == null) return loans;

    List<Cell> headers = new ArrayList<>();

    for (String row : all(ROW_RE, table)) {
      List<Cell> cells = cellsOf(row);
      if (cells.isEmpty()) continue;

      boolean allHeaders = true;
      for (Cell cell : cells) {
        if (!cell.header) {
          allHeaders = false;
          break;
        }
      }
      if (allHeaders) {
        headers = cells;
        continue;
      }

      boolean empty = true;
      for (Cell cell : cells) {
        if (!cell.text.isEmpty()) {
          empty = false;
          break;
        }
      }
      if (empty) continue;

      Matcher anchor = ANCHOR_RE.matcher(row);
      String anchorHref = null;
      String anchorTitle = null;
      if (anchor.find()) {
        anchorHref = decodeEntities(anchor.group(1));
        anchorTitle = textOf(anchor.group(2));
      }

      String title = anchorTitle != null ? anchorTitle : null;
      if (title == null) {
        for (Cell cell : cells) {
          if (!cell.text.isEmpty() && firstDate(cell.text) == null) {
            title = cell.text;
            break;
          }
        }
      }
      if (title == null || title.isEmpty()) continue;

      String recordHref = anchorHref;
      String recordId = null;
      if (recordHref != null) {
        Matcher id = RECORD_ID_RE.matcher(recordHref);
        if (id.find()) recordId = id.group(1);
      }

      int dueIndex = headerIndex(headers, DUE_HEADER_RE);
      int borrowedIndex = headerIndex(headers, BORROWED_HEADER_RE);

      String due = dueIndex >= 0 && dueIndex < cells.size() ? firstDate(cells.get(dueIndex).text) : null;
      String borrowed =
          borrowedIndex >= 0 && borrowedIndex < cells.size()
              ? firstDate(cells.get(borrowedIndex).text)
              : null;

      List<String> dates = new ArrayList<>();
      for (Cell cell : cells) {
        String date = firstDate(cell.text);
        if (date != null) dates.add(date);
      }
      if (due == null && !dates.isEmpty()) due = dates.get(0);
      if (borrowed == null) {
        for (String date : dates) {
          if (date.equals(due)) continue;
          borrowed = date;
          break;
        }
      }

      Matcher renew = RENEW_RE.matcher(row);
      String renewHref = renew.find() ? decodeEntities(renew.group(1)) : null;

      String author = "";
      for (int i = 1; i < cells.size(); i += 1) {
        String text = cells.get(i).text;
        if (!text.isEmpty()
            && firstDate(text) == null
            && text.length() > 2
            && !text.equals(title)) {
          author = text;
          break;
        }
      }

      Map<String, Object> loan = new LinkedHashMap<>();
      loan.put("title", title);
      loan.put("author", author);
      loan.put("due", due);
      loan.put("dueISO", germanToIso(due == null ? "" : due));
      loan.put("borrowed", borrowed);
      loan.put("recordId", recordId);
      loan.put("recordHref", recordHref);
      loan.put("renewHref", renewHref);
      loans.add(loan);
    }

    return loans;
  }

  /** {@code 12.3.2024} → {@code 2024-03-12}; null when the cell holds no such date. */
  public static String germanToIso(String value) {
    Matcher match = GERMAN_DATE_RE.matcher(value == null ? "" : value);
    if (!match.find()) return null;
    return match.group(3) + "-" + pad(match.group(2)) + "-" + pad(match.group(1));
  }

  private static String pad(String digits) {
    return digits.length() >= 2 ? digits : "0" + digits;
  }

  private static List<Cell> cellsOf(String row) {
    List<Cell> cells = new ArrayList<>();
    Matcher match = CELL_RE.matcher(row);
    while (match.find()) {
      cells.add(new Cell("th".equalsIgnoreCase(match.group(1)), textOf(match.group(2))));
    }
    return cells;
  }

  private static int headerIndex(List<Cell> headers, Pattern wanted) {
    for (int i = 0; i < headers.size(); i += 1) {
      if (wanted.matcher(headers.get(i).text).find()) return i;
    }
    return -1;
  }

  private static String firstDate(String text) {
    if (text == null) return null;
    Matcher match = DATE_RE.matcher(text);
    return match.find() ? match.group() : null;
  }

  private static String extractAlert(String html) {
    Matcher match = ALERT_RE.matcher(html == null ? "" : html);
    String text = match.find() ? textOf(match.group(1)) : "";
    return text.length() > 3 ? text : "";
  }

  /** Visible text of an HTML fragment, as {@code textOf()} in account.js renders it. */
  static String textOf(String html) {
    String text =
        (html == null ? "" : html)
            .replaceAll("(?i)<br\\s*/?>", " ")
            .replaceAll("(?i)</(?:p|div|li|td|th)>", " ")
            .replaceAll("<[^>]*>", " ");
    return collapse(decodeEntities(text));
  }

  /**
   * The handful of entities the catalogue emits. Replacement order matters and matches the JS: a
   * double-escaped {@code &amp;nbsp;} must stay literal, never become a space.
   */
  static String decodeEntities(String input) {
    if (input == null) return "";
    String text = replaceNumeric(input, "&#x([0-9a-f]+);", 16);
    text = replaceNumeric(text, "&#(\\d+);", 10);
    return text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'");
  }

  private static String replaceNumeric(String input, String pattern, int radix) {
    Matcher match = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(input);
    StringBuffer out = new StringBuffer(input.length());
    while (match.find()) {
      String replacement = match.group();
      try {
        int codePoint = Integer.parseInt(match.group(1), radix);
        if (codePoint > 0 && codePoint <= 0x10FFFF) {
          replacement = new String(Character.toChars(codePoint));
        }
      } catch (NumberFormatException overflow) {
        // Leave the entity alone rather than lose the text around it.
      }
      match.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    match.appendTail(out);
    return out.toString();
  }

  /** {@code value.replace(/\s+/g, ' ').trim()}, with JavaScript's whitespace set. */
  private static String collapse(String text) {
    return Js.collapse(text);
  }

  private static List<String> all(Pattern pattern, String input) {
    List<String> found = new ArrayList<>();
    Matcher match = pattern.matcher(input);
    while (match.find()) found.add(match.group());
    return found;
  }

  private static String firstGroup(Pattern pattern, String input) {
    Matcher match = pattern.matcher(input == null ? "" : input);
    return match.find() ? match.group(1) : null;
  }

  // -------------------------------------------------------------------------
  // Small helpers
  // -------------------------------------------------------------------------

  private static Map<String, Object> jsonBody(byte[] body) {
    if (body == null || body.length == 0) return null;
    Object parsed;
    try {
      parsed = Json.parse(utf8(body));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Invalid JSON body");
    }
    return parsed instanceof Map ? castMap(parsed) : null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object value) {
    return (Map<String, Object>) value;
  }

  private static Object valueOr(Map<String, Object> map, String key, Object fallback) {
    Object value = map.get(key);
    return value == null ? fallback : value;
  }

  /** {@code URLSearchParams} encoding, which is {@code URLEncoder} for these values. */
  static String formEncode(Map<String, String> form) {
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, String> field : form.entrySet()) {
      if (out.length() > 0) out.append('&');
      out.append(UTF8.encode(field.getKey())).append('=').append(UTF8.encode(field.getValue()));
    }
    return out.toString();
  }

  /** Encodes a string as a form value without the checked exception. */
  private static final class UTF8 {
    static String encode(String value) {
      try {
        return URLEncoder.encode(value, "UTF-8");
      } catch (UnsupportedEncodingException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
  }

  private static String resolve(String base, String reference) {
    try {
      return new URL(new URL(base), reference).toString();
    } catch (IOException malformed) {
      return null;
    }
  }

  private static String originOf(String url) {
    try {
      URL parsed = new URL(url);
      String authority = parsed.getAuthority();
      return parsed.getProtocol() + "://" + (authority == null ? "" : authority.toLowerCase());
    } catch (IOException malformed) {
      return "";
    }
  }

  private static String pathOf(String url) {
    try {
      String path = new URL(url).getPath();
      return path == null ? "" : path;
    } catch (IOException malformed) {
      return "";
    }
  }

  private static String messageOf(IOException error) {
    String message = error.getMessage();
    return message == null || message.isEmpty() ? "Upstream request failed" : message;
  }

  private static byte[] utf8(String text) {
    try {
      return text.getBytes("UTF-8");
    } catch (UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String utf8(byte[] bytes) {
    try {
      return new String(bytes, "UTF-8");
    } catch (UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
