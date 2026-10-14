package de.axkirillov.ublotse;

import java.util.List;
import java.util.Map;

/**
 * The in-app updater's pure half: reading a GitHub release payload and deciding whether it is
 * newer than what is installed.
 *
 * <p>Deliberately free of Android types — {@code android/tests} compiles without {@code
 * android.jar}, so everything here is exercised by the JVM suite. The download, the install
 * intent and the dialogs live in {@link Updater}.
 */
public final class Update {

  /** The asset a release must carry; {@code ublotse.apk} is what {@code build.mjs} produces. */
  public static final String APK_ASSET = "ublotse.apk";

  private Update() {}

  /** A published release, reduced to what the updater needs. */
  public static final class Release {
    /** The tag without its leading {@code v}: {@code v0.1.1} becomes {@code 0.1.1}. */
    public final String version;
    public final String apkUrl;
    /** Bytes, as the API reported them, or 0 when it did not. */
    public final long apkSize;
    /** The release page, for anyone who would rather read before installing. */
    public final String pageUrl;

    Release(String version, String apkUrl, long apkSize, String pageUrl) {
      this.version = version;
      this.apkUrl = apkUrl;
      this.apkSize = apkSize;
      this.pageUrl = pageUrl;
    }

    /** The name the APK is cached under, so a version is never downloaded twice. */
    public String fileName() {
      return "ublotse-" + version + ".apk";
    }
  }

  /**
   * The latest release, or null when the payload is not a release, carries no {@code ublotse.apk}
   * asset, or does not parse — a rate-limited or intercepted answer is not an error worth
   * reporting, it just means there is nothing to offer.
   */
  public static Release parse(String json) {
    Object parsed;
    try {
      parsed = Json.parse(json);
    } catch (RuntimeException malformed) {
      return null;
    }
    if (!(parsed instanceof Map)) return null;
    Map<?, ?> release = (Map<?, ?>) parsed;

    String version = normalize(string(release.get("tag_name")));
    if (version == null) return null;

    String apkUrl = null;
    long apkSize = 0;
    if (release.get("assets") instanceof List) {
      for (Object item : (List<?>) release.get("assets")) {
        if (!(item instanceof Map)) continue;
        Map<?, ?> asset = (Map<?, ?>) item;
        if (!APK_ASSET.equals(string(asset.get("name")))) continue;
        apkUrl = string(asset.get("browser_download_url"));
        apkSize = number(asset.get("size"));
        break;
      }
    }
    if (apkUrl == null) return null;

    return new Release(version, apkUrl, apkSize, string(release.get("html_url")));
  }

  /** {@code v0.1.1} to {@code 0.1.1}; anything that is not dotted digits is rejected. */
  static String normalize(String tag) {
    if (tag == null) return null;
    String value = tag;
    if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
    if (value.isEmpty()) return null;
    for (int i = 0; i < value.length(); i += 1) {
      char c = value.charAt(i);
      if (c != '.' && (c < '0' || c > '9')) return null;
    }
    return value;
  }

  /** Component-by-component, numerically: {@code 0.1} equals {@code 0.1.0}, {@code 0.10} beats {@code 0.9}. */
  public static int compare(String a, String b) {
    String[] left = a.split("\\.");
    String[] right = b.split("\\.");
    int longest = Math.max(left.length, right.length);
    for (int i = 0; i < longest; i += 1) {
      long x = i < left.length ? digit(left[i]) : 0;
      long y = i < right.length ? digit(right[i]) : 0;
      if (x != y) return x < y ? -1 : 1;
    }
    return 0;
  }

  /** True when {@code candidate} is a version worth installing over {@code installed}. */
  public static boolean isNewer(String candidate, String installed) {
    String left = normalize(candidate);
    String right = normalize(installed);
    if (left == null || right == null) return false;
    return compare(left, right) > 0;
  }

  private static long digit(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException notADigit) {
      return 0;
    }
  }

  private static String string(Object value) {
    return value instanceof String ? (String) value : null;
  }

  private static long number(Object value) {
    return value instanceof Number ? ((Number) value).longValue() : 0L;
  }
}
