package app.meanwhile.bridge.core;

/**
 * Rate of change and Nightscout direction names.
 *
 * Both come from the reading and the one before it. The delta is the plain change between
 * them in mg/dL (xDrip+ instead scales the slope to 5 minutes, which turns a 10 mg/dL rise
 * over 5:06 into 9.8). The arrow is that change divided by the time between the readings,
 * against the xDrip+ / Dexcom thresholds in mg/dL per minute
 * ({@code Dex_Constants.TREND_ARROW_VALUES.getTrend}). Neither is reported across a gap
 * longer than {@link #MAX_GAP_MS}.
 */
public final class Trend {

    public static final long MAX_GAP_MS = 4 * ReadingGate.SAMPLE_PERIOD_MS;
    public static final String NOT_COMPUTABLE = "NotComputable";

    private Trend() {
    }

    /** mg/dL per ms, or NaN if there is no usable previous reading. */
    public static double slope(Reading current, Reading previous) {
        if (previous == null) return Double.NaN;
        final long dt = current.timestamp - previous.timestamp;
        if (dt <= 0 || dt > MAX_GAP_MS) return Double.NaN;
        if (current.mgdl == previous.mgdl) return 0;
        return (double) (current.mgdl - previous.mgdl) / dt;
    }

    /**
     * Change since the previous reading, mg/dL; null if there is no usable previous reading
     * (none, or more than {@link #MAX_GAP_MS} before).
     */
    public static Integer change(Reading current, Reading previous) {
        if (previous == null) return null;
        final long dt = current.timestamp - previous.timestamp;
        if (dt <= 0 || dt > MAX_GAP_MS) return null;
        return current.mgdl - previous.mgdl;
    }

    /** Nightscout direction for a slope in mg/dL per ms. */
    public static String direction(double slopePerMs) {
        if (Double.isNaN(slopePerMs)) return NOT_COMPUTABLE;
        final double perMinute = slopePerMs * 60_000;
        if (perMinute > 40) return "NONE";
        if (perMinute > 3.5) return "DoubleUp";
        if (perMinute > 2) return "SingleUp";
        if (perMinute > 1) return "FortyFiveUp";
        if (perMinute > -1) return "Flat";
        if (perMinute > -2) return "FortyFiveDown";
        if (perMinute > -3.5) return "SingleDown";
        return "DoubleDown";
    }

    public static String arrow(String direction) {
        switch (direction) {
            case "DoubleUp": return "⇈";
            case "SingleUp": return "↑";
            case "FortyFiveUp": return "↗";
            case "Flat": return "→";
            case "FortyFiveDown": return "↘";
            case "SingleDown": return "↓";
            case "DoubleDown": return "⇊";
            default: return "";
        }
    }
}
