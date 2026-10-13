package de.axkirillov.ublotse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The cover proxy. The catalogue's cover service answers a cookie-less browser with an HTML page
 * that sets {@code finc_open=1} and reloads (HTTP 419), so a cross-origin {@code <img>} never gets
 * an image; with the cookie it serves a JPEG. "No cover" is a 410-byte 67×79 GIF with HTTP 200,
 * not an error status, so a placeholder must be detected by size. OpenLibrary fills the gaps.
 *
 * <p>Fetched bytes are cached in memory and, when a directory is given, on disk — a mobile client
 * should not re-download a cover every time a search page is redrawn.
 */
public class Covers {

  public static final String DEFAULT_COVER_SERVICE =
      Solr.CATALOGUE_BASE + "/Cover/Show";
  public static final String DEFAULT_OPENLIBRARY = "https://covers.openlibrary.org/b/isbn";
  public static final long COVER_TTL_MS = 7L * 24 * 60 * 60 * 1000;
  public static final long COVER_FAILURE_TTL_MS = 10L * 60 * 1000;
  public static final int COVER_CACHE_MAX_ENTRIES = 800;

  public static final class Cover {
    public final boolean ok;
    public final String type;
    public final byte[] body;

    Cover(boolean ok, String type, byte[] body) {
      this.ok = ok;
      this.type = type;
      this.body = body;
    }

    static Cover none() {
      return new Cover(false, "", new byte[0]);
    }
  }

  private static final Map<String, String> EXTENSION_OF_TYPE;
  private static final Map<String, String> TYPE_OF_EXTENSION;

  static {
    Map<String, String> byType = new LinkedHashMap<>();
    byType.put("image/jpeg", "jpg");
    byType.put("image/jpg", "jpg");
    byType.put("image/png", "png");
    byType.put("image/gif", "gif");
    byType.put("image/webp", "webp");
    EXTENSION_OF_TYPE = byType;

    Map<String, String> byExtension = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : byType.entrySet()) {
      if (!byExtension.containsKey(entry.getValue())) {
        byExtension.put(entry.getValue(), entry.getKey());
      }
    }
    TYPE_OF_EXTENSION = byExtension;
  }

  private final TtlCache<Cover> memory = new TtlCache<>(COVER_CACHE_MAX_ENTRIES, COVER_TTL_MS);
  private final File dir;
  private final String coverService;
  private final String openLibrary;
  private final int timeoutMs;

  public Covers(File dir) {
    this(dir, DEFAULT_COVER_SERVICE, DEFAULT_OPENLIBRARY, Sru.DEFAULT_TIMEOUT_MS);
  }

  public Covers(File dir, String coverService, String openLibrary, int timeoutMs) {
    this.dir = dir;
    this.coverService = coverService;
    this.openLibrary = openLibrary;
    this.timeoutMs = timeoutMs;
    if (dir != null) dir.mkdirs();
  }

  public Cover get(String isbn, String size) {
    String digits = isbn.replaceAll("[^0-9Xx]", "");
    String key = digits + "|" + size;

    Cover cached = memory.get(key);
    if (cached != null) return cached;

    Cover onDisk = readDisk(key);
    if (onDisk != null) {
      memory.put(key, onDisk, ttlOf(onDisk));
      return onDisk;
    }

    Cover fetched = fetch(digits, size);
    memory.put(key, fetched, ttlOf(fetched));
    writeDisk(key, fetched);
    return fetched;
  }

  private static long ttlOf(Cover cover) {
    return cover.ok ? COVER_TTL_MS : COVER_FAILURE_TTL_MS;
  }

  Cover fetch(String isbn, String size) {
    String[] sources = {
      coverService + "?isbn=" + isbn + "&size=" + size,
      openLibrary + "/" + isbn + "-M.jpg?default=false",
    };
    for (String source : sources) {
      try {
        Http.Response response =
            Http.get(
                source,
                Http.headers("Cookie", "finc_open=1", "User-Agent", Sru.USER_AGENT),
                timeoutMs);
        String type = response.contentType;
        byte[] body = response.body;
        boolean placeholder = "image/gif".equals(type) && body.length < 2048;
        if (response.status >= 200
            && response.status < 300
            && type.startsWith("image/")
            && !placeholder
            && body.length > 0) {
          return new Cover(true, type, body);
        }
      } catch (IOException ignored) {
        // Off to the next source; a failed cover must never break a search.
      }
    }
    return Cover.none();
  }

  // -------------------------------------------------------------------------
  // Disk cache — optional, and always a copy: memory is authoritative.
  // -------------------------------------------------------------------------

  private String baseName(String key) {
    return key.replace('|', '-');
  }

  private Cover readDisk(String key) {
    if (dir == null) return null;
    String base = baseName(key);

    File negative = new File(dir, base + ".none");
    if (negative.isFile()) {
      if (fresh(negative, COVER_FAILURE_TTL_MS)) return Cover.none();
      negative.delete();
    }

    for (Map.Entry<String, String> entry : TYPE_OF_EXTENSION.entrySet()) {
      File file = new File(dir, base + "." + entry.getKey());
      if (!file.isFile()) continue;
      if (!fresh(file, COVER_TTL_MS)) {
        file.delete();
        continue;
      }
      try {
        return new Cover(true, entry.getValue(), Files.readAllBytes(file.toPath()));
      } catch (IOException unreadable) {
        file.delete();
      }
    }
    return null;
  }

  private static boolean fresh(File file, long ttlMs) {
    return System.currentTimeMillis() - file.lastModified() < ttlMs;
  }

  private void writeDisk(String key, Cover cover) {
    if (dir == null) return;
    String base = baseName(key);
    try {
      if (!cover.ok) {
        touch(new File(dir, base + ".none"));
        return;
      }
      String extension = EXTENSION_OF_TYPE.get(cover.type);
      if (extension == null) return; // unknown type: keep it in memory only
      File target = new File(dir, base + "." + extension);
      File temporary = new File(dir, base + "." + extension + ".part");
      OutputStream out = new FileOutputStream(temporary);
      try {
        out.write(cover.body);
      } finally {
        out.close();
      }
      if (!temporary.renameTo(target)) {
        // renameTo does not overwrite; a concurrent fetch may have won the race.
        temporary.delete();
      }
    } catch (IOException ignored) {
      // The disk cache is a nicety, never a requirement.
    }
  }

  private static void touch(File file) throws IOException {
    if (file.exists()) {
      if (!file.setLastModified(System.currentTimeMillis())) file.delete();
      return;
    }
    if (!file.createNewFile()) throw new IOException("could not create " + file);
  }
}
