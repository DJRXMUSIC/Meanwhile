package app.meanwhile.bridge.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Minimal JSON writer: the bridge only ever emits flat objects and arrays of them. */
public final class Json {

    private final StringBuilder sb = new StringBuilder();
    private boolean needComma;

    public static String isoUtc(long ms) {
        final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    public Json beginObject() {
        comma();
        sb.append('{');
        needComma = false;
        return this;
    }

    public Json endObject() {
        sb.append('}');
        needComma = true;
        return this;
    }

    public Json beginArray() {
        comma();
        sb.append('[');
        needComma = false;
        return this;
    }

    public Json endArray() {
        sb.append(']');
        needComma = true;
        return this;
    }

    /** Starts a key inside an object; follow with a value or begin* call. */
    public Json name(String key) {
        comma();
        quote(key);
        sb.append(':');
        needComma = false;
        return this;
    }

    public Json value(String s) {
        comma();
        if (s == null) sb.append("null");
        else quote(s);
        needComma = true;
        return this;
    }

    public Json value(long n) {
        comma();
        sb.append(n);
        needComma = true;
        return this;
    }

    public Json value(boolean b) {
        comma();
        sb.append(b);
        needComma = true;
        return this;
    }

    /** NaN/infinite become null, as JSON has no representation for them. */
    public Json value(double d) {
        comma();
        if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
        else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
        else sb.append(d);
        needComma = true;
        return this;
    }

    public Json field(String key, String v) {
        return name(key).value(v);
    }

    public Json field(String key, long v) {
        return name(key).value(v);
    }

    public Json field(String key, double v) {
        return name(key).value(v);
    }

    public Json field(String key, boolean v) {
        return name(key).value(v);
    }

    @Override
    public String toString() {
        return sb.toString();
    }

    private void comma() {
        if (needComma) sb.append(',');
    }

    private void quote(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
