package de.axkirillov.ublotse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON encoder/decoder.
 *
 * The app needs neither Android's org.json (unavailable to the JVM tests) nor a
 * dependency, and it must emit byte-compatible output to the Node server so the
 * shared frontend cannot tell the two apart. Values are modelled as plain
 * {@code Map}/{@code List}/{@code String}/{@code Long}/{@code Boolean}/{@code null}.
 */
public final class Json {

  private Json() {}

  /** Build an ordered object: {@code obj("a", 1, "b", "x")}. */
  public static Map<String, Object> obj(Object... keyValues) {
    if (keyValues.length % 2 != 0) throw new IllegalArgumentException("obj() needs key/value pairs");
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
    }
    return map;
  }

  /** Build a list: {@code arr(a, b, c)}. */
  public static List<Object> arr(Object... items) {
    List<Object> list = new ArrayList<>(items.length);
    for (Object item : items) list.add(item);
    return list;
  }

  // -------------------------------------------------------------------------
  // Encoding
  // -------------------------------------------------------------------------

  public static String write(Object value) {
    StringBuilder out = new StringBuilder(256);
    writeValue(out, value);
    return out.toString();
  }

  private static void writeValue(StringBuilder out, Object value) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String) {
      writeString(out, (String) value);
    } else if (value instanceof Boolean) {
      out.append(((Boolean) value).booleanValue() ? "true" : "false");
    } else if (value instanceof Number) {
      writeNumber(out, (Number) value);
    } else if (value instanceof Map) {
      writeObject(out, (Map<?, ?>) value);
    } else if (value instanceof List) {
      writeArray(out, (List<?>) value);
    } else if (value instanceof Object[]) {
      writeArray(out, java.util.Arrays.asList((Object[]) value));
    } else {
      writeString(out, String.valueOf(value));
    }
  }

  private static void writeObject(StringBuilder out, Map<?, ?> map) {
    out.append('{');
    boolean first = true;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!first) out.append(',');
      first = false;
      writeString(out, String.valueOf(entry.getKey()));
      out.append(':');
      writeValue(out, entry.getValue());
    }
    out.append('}');
  }

  private static void writeArray(StringBuilder out, List<?> list) {
    out.append('[');
    for (int i = 0; i < list.size(); i += 1) {
      if (i > 0) out.append(',');
      writeValue(out, list.get(i));
    }
    out.append(']');
  }

  private static void writeNumber(StringBuilder out, Number number) {
    if (number instanceof Double || number instanceof Float) {
      double d = number.doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        out.append("null"); // JSON has no NaN/Infinity, and JSON.stringify agrees
        return;
      }
      if (d == Math.rint(d) && Math.abs(d) < 1e15) {
        out.append((long) d);
        return;
      }
    }
    out.append(number.toString());
  }

  /** Escapes exactly what {@code JSON.stringify} escapes; non-ASCII stays raw. */
  private static void writeString(StringBuilder out, String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i += 1) {
      char c = value.charAt(i);
      switch (c) {
        case '"': out.append("\\\""); break;
        case '\\': out.append("\\\\"); break;
        case '\b': out.append("\\b"); break;
        case '\f': out.append("\\f"); break;
        case '\n': out.append("\\n"); break;
        case '\r': out.append("\\r"); break;
        case '\t': out.append("\\t"); break;
        default:
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
      }
    }
    out.append('"');
  }

  // -------------------------------------------------------------------------
  // Decoding
  // -------------------------------------------------------------------------

  public static Object parse(String text) {
    Parser parser = new Parser(text);
    parser.skipSpace();
    Object value = parser.value();
    parser.skipSpace();
    if (!parser.done()) throw parser.error("Trailing content");
    return value;
  }

  private static final class Parser {
    private final String text;
    private int at;

    Parser(String text) {
      this.text = text;
    }

    boolean done() {
      return at >= text.length();
    }

    IllegalArgumentException error(String message) {
      return new IllegalArgumentException(message + " at offset " + at);
    }

    void skipSpace() {
      while (at < text.length()) {
        char c = text.charAt(at);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') at += 1;
        else break;
      }
    }

    Object value() {
      skipSpace();
      if (done()) throw error("Unexpected end of input");
      char c = text.charAt(at);
      switch (c) {
        case '{': return object();
        case '[': return array();
        case '"': return string();
        case 't':
          expect("true");
          return Boolean.TRUE;
        case 'f':
          expect("false");
          return Boolean.FALSE;
        case 'n':
          expect("null");
          return null;
        default:
          return number();
      }
    }

    private void expect(String literal) {
      if (!text.startsWith(literal, at)) throw error("Expected " + literal);
      at += literal.length();
    }

    private Map<String, Object> object() {
      Map<String, Object> map = new LinkedHashMap<>();
      at += 1; // '{'
      skipSpace();
      if (!done() && text.charAt(at) == '}') {
        at += 1;
        return map;
      }
      while (true) {
        skipSpace();
        if (done() || text.charAt(at) != '"') throw error("Expected object key");
        String key = string();
        skipSpace();
        if (done() || text.charAt(at) != ':') throw error("Expected ':'");
        at += 1;
        map.put(key, value());
        skipSpace();
        if (done()) throw error("Unterminated object");
        char c = text.charAt(at);
        at += 1;
        if (c == '}') return map;
        if (c != ',') throw error("Expected ',' or '}'");
      }
    }

    private List<Object> array() {
      List<Object> list = new ArrayList<>();
      at += 1; // '['
      skipSpace();
      if (!done() && text.charAt(at) == ']') {
        at += 1;
        return list;
      }
      while (true) {
        list.add(value());
        skipSpace();
        if (done()) throw error("Unterminated array");
        char c = text.charAt(at);
        at += 1;
        if (c == ']') return list;
        if (c != ',') throw error("Expected ',' or ']'");
      }
    }

    private String string() {
      at += 1; // '"'
      StringBuilder out = new StringBuilder();
      while (true) {
        if (done()) throw error("Unterminated string");
        char c = text.charAt(at);
        at += 1;
        if (c == '"') return out.toString();
        if (c != '\\') {
          out.append(c);
          continue;
        }
        if (done()) throw error("Unterminated escape");
        char e = text.charAt(at);
        at += 1;
        switch (e) {
          case '"': out.append('"'); break;
          case '\\': out.append('\\'); break;
          case '/': out.append('/'); break;
          case 'b': out.append('\b'); break;
          case 'f': out.append('\f'); break;
          case 'n': out.append('\n'); break;
          case 'r': out.append('\r'); break;
          case 't': out.append('\t'); break;
          case 'u':
            if (at + 4 > text.length()) throw error("Truncated \\u escape");
            out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
            at += 4;
            break;
          default:
            throw error("Unknown escape \\" + e);
        }
      }
    }

    private Object number() {
      int start = at;
      if (!done() && (text.charAt(at) == '-' || text.charAt(at) == '+')) at += 1;
      boolean fractional = false;
      while (!done()) {
        char c = text.charAt(at);
        if (c >= '0' && c <= '9') {
          at += 1;
        } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
          fractional = fractional || c == '.' || c == 'e' || c == 'E';
          at += 1;
        } else {
          break;
        }
      }
      String raw = text.substring(start, at);
      if (raw.isEmpty()) throw error("Expected value");
      if (!fractional) {
        try {
          return Long.valueOf(raw);
        } catch (NumberFormatException ignored) {
          // Falls through to double for values outside long range.
        }
      }
      return Double.valueOf(raw);
    }
  }
}
