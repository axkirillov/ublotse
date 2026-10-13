package de.axkirillov.ublotse;

import java.io.UnsupportedEncodingException;

/** One HTTP response, whatever produced it — the API or a bundled asset. */
public final class WebResponse {

  public final int status;
  /** Null means "send no Content-Type at all", as with a 204. */
  public final String contentType;
  /** Null means "no body" and therefore no Content-Length. */
  public final byte[] body;
  public final String cacheControl;

  private WebResponse(int status, String contentType, byte[] body, String cacheControl) {
    this.status = status;
    this.contentType = contentType;
    this.body = body;
    this.cacheControl = cacheControl;
  }

  public static WebResponse json(int status, Object payload) {
    return new WebResponse(
        status, "application/json; charset=utf-8", utf8(Json.write(payload)), "no-store");
  }

  public static WebResponse text(int status, String body) {
    return new WebResponse(
        status, "text/plain; charset=utf-8", utf8(body), "public, max-age=86400");
  }

  public static WebResponse bytes(int status, String contentType, byte[] body, String cacheControl) {
    return new WebResponse(status, contentType, body, cacheControl);
  }

  public static WebResponse empty(int status) {
    return new WebResponse(status, null, null, null);
  }

  private static byte[] utf8(String text) {
    try {
      return text.getBytes("UTF-8");
    } catch (UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
