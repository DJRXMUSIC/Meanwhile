package app.meanwhile.bridge.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser for the alert settings (the bridge has no JSON library at run time):
 * objects become {@code Map<String,Object>}, arrays {@code List<Object>}, numbers
 * {@code Double}. Also reads Gson's NaN and Infinity, which xDrip+'s export can contain.
 */
public final class JsonReader {

    private final String s;
    private int i;

    private JsonReader(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("no JSON");
        final JsonReader r = new JsonReader(text);
        r.ws();
        final Object v = r.value(0);
        r.ws();
        if (r.i != r.s.length()) throw r.error("trailing characters");
        return v;
    }

    private Object value(int depth) {
        if (depth > 64) throw error("nested too deeply");
        if (i >= s.length()) throw error("unexpected end");
        final char c = s.charAt(i);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': word("true"); return Boolean.TRUE;
            case 'f': word("false"); return Boolean.FALSE;
            case 'n': word("null"); return null;
            case 'N': word("NaN"); return Double.NaN;
            case 'I': word("Infinity"); return Double.POSITIVE_INFINITY;
            default: return number();
        }
    }

    private Map<String, Object> object(int depth) {
        final Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek('}')) {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (!peek('"')) throw error("expected a key");
            final String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value(depth + 1));
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            expect('}');
            return m;
        }
    }

    private List<Object> array(int depth) {
        final List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (peek(']')) {
            i++;
            return a;
        }
        while (true) {
            ws();
            a.add(value(depth + 1));
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            expect(']');
            return a;
        }
    }

    private String string() {
        expect('"');
        final StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("unterminated string");
            final char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw error("bad escape");
            final char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw error("bad \\u escape");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException x) {
                        throw error("bad \\u escape");
                    }
                    i += 4;
                    break;
                default: throw error("bad escape");
            }
        }
    }

    private Double number() {
        final int start = i;
        if (peek('-')) {
            i++;
            if (s.startsWith("Infinity", i)) {
                i += 8;
                return Double.NEGATIVE_INFINITY;
            }
        }
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private void word(String w) {
        if (!s.startsWith(w, i)) throw error("expected " + w);
        i += w.length();
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("expected '" + c + "'");
        i++;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " at " + i);
    }
}
