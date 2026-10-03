package app.meanwhile.bridge.core;

import java.util.List;

/**
 * Nightscout {@code /api/v1/entries/sgv.json} entries, in the shape xDrip+'s local web
 * service ({@code webservices/WebServiceSgv}) produces on port 17580, so existing clients
 * (Meanwhile, watch faces, etc.) work unchanged.
 */
public final class SgvJson {

    public static final String DEVICE = "Eversense-Notification-Bridge";

    private SgvJson() {
    }

    /**
     * @param newestFirst readings newest first; pass one more than {@code count} so the
     *                    oldest returned entry still gets a delta/direction
     * @param unitsHint   "mgdl" or "mmol", added to the first entry like xDrip does
     */
    public static String entries(List<Reading> newestFirst, int count, String unitsHint) {
        final Json j = new Json().beginArray();
        final int n = Math.min(count, newestFirst.size());
        for (int i = 0; i < n; i++) {
            final Reading r = newestFirst.get(i);
            final Reading prev = i + 1 < newestFirst.size() ? newestFirst.get(i + 1) : null;
            final double slope = Trend.slope(r, prev);
            j.beginObject()
                    .field("_id", "ev-" + r.timestamp)
                    .field("device", DEVICE)
                    .field("dateString", Json.isoUtc(r.timestamp))
                    .field("sysTime", Json.isoUtc(r.timestamp))
                    .field("date", r.timestamp)
                    .field("sgv", r.mgdl)
                    .field("delta", Trend.delta5min(slope))
                    .field("direction", Trend.direction(slope))
                    .field("noise", 1)
                    .field("type", "sgv")
                    .field("ts_source", r.timestampSource);
            if (i == 0 && unitsHint != null) j.field("units_hint", unitsHint);
            j.endObject();
        }
        return j.endArray().toString();
    }
}
