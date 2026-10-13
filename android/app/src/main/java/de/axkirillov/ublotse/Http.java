package de.axkirillov.ublotse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/** A minimal blocking HTTP GET, shared by the SRU client and the cover proxy. */
public final class Http {

  private Http() {}

  public static final class Response {
    public final int status;
    public final String contentType;
    public final byte[] body;

    Response(int status, String contentType, byte[] body) {
      this.status = status;
      this.contentType = contentType;
      this.body = body;
    }
  }

  public static Map<String, String> headers(String... keyValues) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) map.put(keyValues[i], keyValues[i + 1]);
    return map;
  }

  public static Response get(String url, Map<String, String> headers, int timeoutMs) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setInstanceFollowRedirects(true); // the cover service bounces through finc's gate
    connection.setConnectTimeout(timeoutMs);
    connection.setReadTimeout(timeoutMs);
    if (headers != null) {
      for (Map.Entry<String, String> header : headers.entrySet()) {
        connection.setRequestProperty(header.getKey(), header.getValue());
      }
    }

    int status = connection.getResponseCode();
    String contentType = connection.getContentType();
    InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
    try {
      return new Response(status, contentType == null ? "" : contentType, readAll(stream));
    } finally {
      if (stream != null) {
        try {
          stream.close();
        } catch (IOException ignored) {
          // Nothing useful to do; the body has already been read.
        }
      }
    }
  }

  /**
   * JavaScript's {@code decodeURIComponent}: percent-decoding only. {@code URLDecoder} would also
   * turn '+' into a space, which is right for query strings and wrong for path segments.
   */
  public static String decodeUriComponent(String value) {
    StringBuilder out = new StringBuilder(value.length());
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(4);
    int i = 0;
    while (i < value.length()) {
      char c = value.charAt(i);
      if (c != '%') {
        out.append(c);
        i += 1;
        continue;
      }
      bytes.reset();
      while (i < value.length() && value.charAt(i) == '%') {
        if (i + 2 >= value.length()) break;
        int high = Character.digit(value.charAt(i + 1), 16);
        int low = Character.digit(value.charAt(i + 2), 16);
        if (high < 0 || low < 0) break;
        bytes.write((high << 4) | low);
        i += 3;
      }
      if (bytes.size() == 0) {
        out.append(c); // a stray '%' is left alone, as decodeURIComponent does
        i += 1;
        continue;
      }
      try {
        out.append(new String(bytes.toByteArray(), "UTF-8"));
      } catch (UnsupportedEncodingException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
    return out.toString();
  }

  static byte[] readAll(InputStream stream) throws IOException {
    if (stream == null) return new byte[0];
    ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
    byte[] buffer = new byte[8192];
    int read;
    while ((read = stream.read(buffer)) != -1) out.write(buffer, 0, read);
    return out.toByteArray();
  }
}
