package app.meanwhile.bridge.core;

/**
 * Rate of change and Nightscout direction names.
 *
 * Slope is xDrip+ {@code BgReading.calculateSlope} (two-point), delta is xDrip's web
 * service convention (slope x 5 minutes) and the direction thresholds (mg/dL per minute) are
 * xDrip+ {@code Dex_Constants.TREND_ARROW_VALUES.getTrend}. Unlike xDrip, no slope is
 * reported across a gap longer than {@link #MAX_GAP_MS}.
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

    /** Change per 5 minutes, rounded to 3 decimals (xDrip's {@code delta}); NaN if unknown. */
    public static double delta5min(double slopePerMs) {
        if (Double.isNaN(slopePerMs)) return Double.NaN;
        return Math.round(slopePerMs * 5 * 60_000 * 1000) / 1000.0;
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
