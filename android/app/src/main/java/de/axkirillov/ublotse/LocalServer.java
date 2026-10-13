package de.axkirillov.ublotse;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * A loopback HTTP server on {@code 127.0.0.1:0}.
 *
 * <p>This exists because a WebView cannot host one: {@code shouldInterceptRequest} hands over a
 * {@code WebResourceRequest} whose streaming happens after the fact, and it never exposes a POST
 * body — so the wishlist could be read but never written. Serving the frontend over loopback keeps
 * the whole {@code /api/*} contract working, byte for byte, with the browser doing ordinary
 * same-origin requests. {@code 127.0.0.1} also counts as a secure context, so
 * {@code crypto.randomUUID()} keeps working in the page.
 */
public class LocalServer {

  /** Where the bundled frontend comes from — assets on the device, a directory in tests. */
  public interface ResourceSource {
    /** Bytes for a URL path such as {@code /app.js}, or null when there is no such file. */
    byte[] read(String path);

    String contentType(String path);
  }

  public interface ApiHandler {
    WebResponse handle(
        String method, String path, Map<String, String> query, byte[] body, Map<String, String> headers);
  }

  private final ApiHandler api;
  private final ResourceSource resources;
  private final ExecutorService workers;

  private ServerSocket socket;
  private Thread acceptor;
  private volatile boolean running;

  public LocalServer(ApiHandler api, ResourceSource resources) {
    this.api = api;
    this.resources = resources;
    this.workers =
        Executors.newCachedThreadPool(
            new ThreadFactory() {
              @Override
              public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "ublotse-http");
                thread.setDaemon(true);
                return thread;
              }
            });
  }

  /** Bind and start accepting; returns the port the OS handed out. */
  public int start() throws IOException {
    socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    running = true;
    acceptor = new Thread(this::acceptLoop, "ublotse-accept");
    acceptor.setDaemon(true);
    acceptor.start();
    return socket.getLocalPort();
  }

  public int port() {
    return socket == null ? -1 : socket.getLocalPort();
  }

  public void stop() {
    running = false;
    if (socket != null) {
      try {
        socket.close();
      } catch (IOException ignored) {
        // Nothing left to close.
      }
    }
    workers.shutdownNow();
    try {
      workers.awaitTermination(500, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void acceptLoop() {
    while (running) {
      final Socket client;
      try {
        client = socket.accept();
      } catch (IOException stopped) {
        return; // stop() closed the socket, which is how this loop ends
      }
      workers.execute(
          new Runnable() {
            @Override
            public void run() {
              try {
                serve(client);
              } catch (Exception ignored) {
                // A dropped connection is not worth a crash.
              } finally {
                try {
                  client.close();
                } catch (IOException ignored) {
                  // Already gone.
                }
              }
            }
          });
    }
  }

  // -------------------------------------------------------------------------
  // One request
  // -------------------------------------------------------------------------

  private void serve(Socket client) throws IOException {
    InputStream in = new BufferedInputStream(client.getInputStream(), 8192);
    OutputStream out = new BufferedOutputStream(client.getOutputStream(), 8192);

    String requestLine = readLine(in);
    if (requestLine == null || requestLine.isEmpty()) return;
    int firstSpace = requestLine.indexOf(' ');
    int lastSpace = requestLine.lastIndexOf(' ');
    if (firstSpace <= 0 || lastSpace <= firstSpace) return;
    String method = requestLine.substring(0, firstSpace).toUpperCase(Locale.ROOT);
    String target = requestLine.substring(firstSpace + 1, lastSpace);

    Map<String, String> headers = readHeaders(in);

    // The body size is settled before the client is invited to send anything: answering
    // "100 Continue" and only then refusing the request would have Chromium upload the whole
    // thing to no purpose, and leaving those bytes unread means the close sends a reset that can
    // destroy the error response.
    long declared = 0;
    String contentLength = headers.get("content-length");
    if (contentLength != null) {
      try {
        declared = Long.parseLong(contentLength.trim());
      } catch (NumberFormatException nonsense) {
        declared = 0;
      }
    }
    if (declared > UblotseApi.MAX_BODY_BYTES) {
      write(out, WebResponse.json(502, Json.obj("error", "Request body too large")), false);
      return;
    }

    String expect = headers.get("expect");
    if (expect != null && expect.toLowerCase(Locale.ROOT).contains("100-continue")) {
      // Chromium announces any body over ~1 KB this way and waits for the go-ahead;
      // ignoring it costs a one-second stall on every wishlist save.
      out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes("US-ASCII"));
      out.flush();
    }

    byte[] body = declared > 0 ? readFully(in, (int) declared) : new byte[0];

    int question = target.indexOf('?');
    String path = question < 0 ? target : target.substring(0, question);
    String rawQuery = question < 0 ? "" : target.substring(question + 1);

    WebResponse response;
    boolean isApi = path.startsWith("/api/");
    if (isApi) {
      response = api.handle(method, path, parseQuery(rawQuery), body, headers);
      if (response == null) response = WebResponse.json(404, Json.obj("error", "Not found"));
    } else {
      response = asset(path);
    }
    write(out, response, isApi);
  }

  private WebResponse asset(String path) {
    if (path.contains("..")) {
      return WebResponse.bytes(403, "text/plain; charset=utf-8", "Forbidden".getBytes(), null);
    }
    String wanted = "/".equals(path) ? "/index.html" : path;
    byte[] bytes = resources.read(wanted);
    if (bytes == null) {
      return WebResponse.bytes(404, "text/plain; charset=utf-8", "Not found".getBytes(), null);
    }
    return WebResponse.bytes(200, resources.contentType(wanted), bytes, "no-cache");
  }

  private void write(OutputStream out, WebResponse response, boolean withCors) throws IOException {
    StringBuilder head = new StringBuilder(256);
    head.append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n");
    if (response.contentType != null) {
      head.append("Content-Type: ").append(header(response.contentType)).append("\r\n");
    }
    if (response.body != null) {
      head.append("Content-Length: ").append(response.body.length).append("\r\n");
    }
    if (response.cacheControl != null) {
      head.append("Cache-Control: ").append(header(response.cacheControl)).append("\r\n");
    }
    if (withCors) {
      // Pinned to our own loopback origin rather than "*": nothing else on the device, and no web
      // page that happens to find the port, has any business reading this list.
      head.append("Access-Control-Allow-Origin: http://127.0.0.1:").append(port()).append("\r\n");
      head.append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n");
      head.append("Access-Control-Allow-Headers: Content-Type, X-Client-Id\r\n");
      head.append("Access-Control-Max-Age: 86400\r\n");
    }
    head.append("Connection: close\r\n\r\n");
    out.write(head.toString().getBytes("ISO-8859-1"));
    if (response.body != null && response.status != 204) out.write(response.body);
    out.flush();
  }

  private static String header(String value) {
    StringBuilder safe = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i += 1) {
      char c = value.charAt(i);
      if (c == '\r' || c == '\n') continue; // no response splitting
      safe.append(c > 0xFF ? '?' : c);
    }
    return safe.toString();
  }

  private static String reason(int status) {
    switch (status) {
      case 200:
        return "OK";
      case 204:
        return "No Content";
      case 400:
        return "Bad Request";
      case 403:
        return "Forbidden";
      case 404:
        return "Not Found";
      case 405:
        return "Method Not Allowed";
      case 502:
        return "Bad Gateway";
      default:
        return "OK";
    }
  }

  // -------------------------------------------------------------------------
  // Wire helpers
  // -------------------------------------------------------------------------

  /** Reads one CRLF line; null at a clean end of stream. */
  static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
    int read;
    while ((read = in.read()) != -1) {
      if (read == '\n') break;
      buffer.write(read);
    }
    if (read == -1 && buffer.size() == 0) return null;
    byte[] bytes = buffer.toByteArray();
    int length = bytes.length;
    if (length > 0 && bytes[length - 1] == '\r') length -= 1;
    return new String(bytes, 0, length, "ISO-8859-1");
  }

  private static Map<String, String> readHeaders(InputStream in) throws IOException {
    Map<String, String> headers = new LinkedHashMap<>();
    String line;
    while ((line = readLine(in)) != null && !line.isEmpty()) {
      int colon = line.indexOf(':');
      if (colon <= 0) continue;
      headers.put(
          line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
    }
    return headers;
  }

  private static byte[] readFully(InputStream in, int length) throws IOException {
    byte[] buffer = new byte[length];
    int filled = 0;
    while (filled < length) {
      int read = in.read(buffer, filled, length - filled);
      if (read == -1) break;
      filled += read;
    }
    if (filled == length) return buffer;
    byte[] short_ = new byte[filled];
    System.arraycopy(buffer, 0, short_, 0, filled);
    return short_;
  }

  private static final Map<String, String> MIME_TYPES;

  static {
    Map<String, String> types = new LinkedHashMap<>();
    types.put("html", "text/html; charset=utf-8");
    types.put("js", "text/javascript; charset=utf-8");
    types.put("css", "text/css; charset=utf-8");
    types.put("svg", "image/svg+xml");
    types.put("json", "application/json; charset=utf-8");
    types.put("ico", "image/x-icon");
    types.put("png", "image/png");
    types.put("woff2", "font/woff2");
    MIME_TYPES = types;
  }

  /** The server's extension table, verbatim. */
  public static String contentTypeFor(String path) {
    int dot = path.lastIndexOf('.');
    if (dot < 0) return "application/octet-stream";
    String type = MIME_TYPES.get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    return type == null ? "application/octet-stream" : type;
  }

  /** Query-string semantics: '+' is a space, exactly as URLSearchParams has it. */
  static Map<String, String> parseQuery(String raw) {
    Map<String, String> query = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) return query;
    for (String pair : raw.split("&")) {
      if (pair.isEmpty()) continue;
      int equals = pair.indexOf('=');
      String key = equals < 0 ? pair : pair.substring(0, equals);
      String value = equals < 0 ? "" : pair.substring(equals + 1);
      try {
        query.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
      } catch (Exception malformed) {
        query.put(key, value);
      }
    }
    return query;
  }
}
