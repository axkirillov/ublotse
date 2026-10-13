package de.axkirillov.ublotse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The handful of JavaScript string semantics that Java does not share.
 *
 * <p>The server this app ports decides which CQL to send by splitting on {@code /\s+/} and trimming
 * with {@code String.prototype.trim}, and both use JavaScript's WhiteSpace set. Java's {@code \s}
 * and {@code String.trim()} stop at U+0020, so a query pasted with a non-breaking space — out of a
 * PDF, a web page, or another catalogue — would tokenise differently on the device than in the
 * browser and quietly search for something else.
 */
public final class Js {

  private Js() {}

  /** JavaScript's WhiteSpace plus LineTerminator, as a character class. */
  private static final String SPACE =
      "[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]";

  private static final Pattern LEADING = Pattern.compile("^" + SPACE + "+");
  private static final Pattern TRAILING = Pattern.compile(SPACE + "+$");
  private static final Pattern RUN = Pattern.compile(SPACE + "+");

  /** {@code String.prototype.trim()}. */
  public static String trim(String value) {
    if (value == null) return "";
    return TRAILING.matcher(LEADING.matcher(value).replaceFirst("")).replaceFirst("");
  }

  /** {@code value.split(/\s+/)}, without the empty tokens Java hands back. */
  public static List<String> splitWhitespace(String value) {
    List<String> parts = new ArrayList<>();
    if (value == null) return parts;
    for (String part : RUN.split(value)) {
      if (!part.isEmpty()) parts.add(part);
    }
    return parts;
  }

  /** {@code value.replace(/\s+/g, ' ').trim()} — the one normalisation applied to catalogue values. */
  public static String collapse(String value) {
    return trim(RUN.matcher(value).replaceAll(" "));
  }
}
