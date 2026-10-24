package app.meanwhile.bridge.core;

import java.util.List;
import java.util.Locale;

/**
 * Nightscout {@code /pebble} reply, in the shape xDrip+'s local web service
 * ({@code webservices/WebServicePebble}) produces on port 17580.
 *
 * Unlike {@code sgv.json}, whose top level is an array, this is a JSON object. Garmin
 * Connect IQ's {@code makeWebRequest} rejects a top-level array, so the Meanwhile watch face
 * reads this endpoint. As in xDrip, {@code sgv} and {@code bgdelta} are in the user's units
 * ({@code sgv} as a string) and {@code status[0].now} is the phone's clock when the reply was
 * built, so a client can measure the reading's age without trusting its own clock.
 */
public final class PebbleJson {

    private static final double MGDL_PER_MMOL = 18.0182;

    private PebbleJson() {
    }

    /**
     * @param newestFirst the latest readings, newest first; the second one (if any) gives the trend
     * @param unitsHint   "mgdl" or "mmol"
     * @param now         phone time, ms since epoch
     */
    public static String reply(List<Reading> newestFirst, String unitsHint, long now) {
        final Json j = new Json().beginObject();
        j.name("status").beginArray().beginObject().field("now", now).endObject().endArray();
        j.name("bgs").beginArray();
        if (!newestFirst.isEmpty()) {
            final Reading r = newestFirst.get(0);
            final Reading prev = newestFirst.size() > 1 ? newestFirst.get(1) : null;
            final double slope = Trend.slope(r, prev);
            final String direction = Trend.direction(slope);
            final double delta = Trend.delta5min(slope);
            final boolean mmol = "mmol".equals(unitsHint);
            j.beginObject()
                    .field("sgv", mmol ? mmol(r.mgdl) : String.valueOf(r.mgdl))
                    .field("trend", ordinal(direction))
                    .field("direction", direction)
                    .field("datetime", r.timestamp);
            if (Double.isNaN(delta)) {
                j.name("bgdelta").value((String) null);
            } else if (mmol) {
                j.field("bgdelta", mmol(delta));
            } else {
                j.field("bgdelta", Math.round(delta));
            }
            j.field("ts_source", r.timestampSource).endObject();
        }
        j.endArray();
        j.name("cals").beginArray().endArray();
        return j.endObject().toString();
    }

    /** xDrip+ {@code BgReading.getSlopeOrdinal}: 1 = DoubleUp … 7 = DoubleDown, 0 = none. */
    static int ordinal(String direction) {
        switch (direction) {
            case "DoubleUp": return 1;
            case "SingleUp": return 2;
            case "FortyFiveUp": return 3;
            case "Flat": return 4;
            case "FortyFiveDown": return 5;
            case "SingleDown": return 6;
            case "DoubleDown": return 7;
            case Trend.NOT_COMPUTABLE: return 8;
            default: return 0;
        }
    }

    private static String mmol(double mgdl) {
        return String.format(Locale.ROOT, "%.1f", mgdl / MGDL_PER_MMOL);
    }
}
