package app.meanwhile.bridge.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Insulin on board, computed on the phone so the watch has it even when Meanwhile's
 * page isn't open. A line-for-line port of {@code src/lib/insulin.ts} ({@code iobFraction},
 * {@code isRapidActing}, {@code totalIOB}): the Loop / oref0 exponential curve with an
 * absorption delay. The page reports the recent doses and the profile's curve through
 * {@link #parse}; {@link #total} then decays them at any time.
 */
public final class InsulinOnBoard {

    /** One logged dose. {@code kind} as Meanwhile stores it ("bolus", "basal", ...). */
    public static final class Dose {
        public final long ts;
        public final double units;
        public final String kind;

        public Dose(long ts, double units, String kind) {
            this.ts = ts;
            this.units = units;
            this.kind = kind;
        }
    }

    public final List<Dose> doses;
    public final double diaHours;
    public final double peakMin;
    public final double delayMin;
    /** When the page last reported, phone clock ms. */
    public final long reportedAt;

    public InsulinOnBoard(List<Dose> doses, double diaHours, double peakMin, double delayMin, long reportedAt) {
        this.doses = Collections.unmodifiableList(new ArrayList<>(doses));
        this.diaHours = diaHours;
        this.peakMin = peakMin;
        this.delayMin = delayMin;
        this.reportedAt = reportedAt;
    }

    /** insulin.ts {@code iobFraction}. */
    public static double fraction(double tMin, double diaHours, double peakMin, double delayMin) {
        if (tMin <= 0) return 1;
        if (tMin <= delayMin) return 1;
        final double t = tMin - delayMin;
        final double dia = diaHours * 60;
        if (t >= dia) return 0;
        final double peak = Math.max(20, Math.min(peakMin, dia * 0.45));
        final double tau = (peak * (1 - peak / dia)) / (1 - (2 * peak) / dia);
        final double a = (2 * tau) / dia;
        final double s = 1 / (1 - a + (1 + a) * Math.exp(-dia / tau));
        final double iob = 1 - s * (1 - a) * (((t * t) / (tau * dia * (1 - a)) - t / tau - 1) * Math.exp(-t / tau) + 1);
        return Math.max(0, Math.min(1, iob));
    }

    /** insulin.ts {@code totalIOB}: rapid-acting only, rounded to 2 decimals. */
    public double total(long atMs) {
        double iob = 0;
        for (Dose d : doses) {
            if ("basal".equals(d.kind)) continue;
            final double tMin = (atMs - d.ts) / 60_000.0;
            if (tMin < 0 || tMin > delayMin + diaHours * 60) continue;
            iob += d.units * fraction(tMin, diaHours, peakMin, delayMin);
        }
        return Math.round(iob * 100) / 100.0;
    }

    /**
     * The page's report: {@code {"dia":5.5,"peak":64,"delay":15,"doses":[{"ts":..,"units":..,"kind":".."}]}}.
     * Anything malformed throws {@link IllegalArgumentException}; a dose without a usable
     * time or amount is skipped.
     */
    public static InsulinOnBoard parse(String json, long reportedAt) {
        final Object root = JsonReader.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("not an object");
        final Map<?, ?> m = (Map<?, ?>) root;
        final double dia = number(m.get("dia"), 5.5);
        final double peak = number(m.get("peak"), 64);
        final double delay = number(m.get("delay"), 15);
        if (!(dia > 0 && dia <= 24) || !(peak > 0) || !(delay >= 0 && delay <= 120)) {
            throw new IllegalArgumentException("curve out of range");
        }
        final Object list = m.get("doses");
        if (!(list instanceof List)) throw new IllegalArgumentException("no doses list");
        final List<Dose> doses = new ArrayList<>();
        for (Object o : (List<?>) list) {
            if (!(o instanceof Map)) continue;
            final Map<?, ?> d = (Map<?, ?>) o;
            final double ts = number(d.get("ts"), Double.NaN);
            final double units = number(d.get("units"), Double.NaN);
            if (Double.isNaN(ts) || Double.isNaN(units) || units < 0 || units > 100) continue;
            final Object kind = d.get("kind");
            doses.add(new Dose((long) ts, units, kind instanceof String ? (String) kind : "bolus"));
        }
        return new InsulinOnBoard(doses, dia, peak, delay, reportedAt);
    }

    /** The report again (stored so the phone keeps IOB across restarts). */
    public String toJson() {
        final Json j = new Json().beginObject().field("dia", diaHours).field("peak", peakMin).field("delay", delayMin);
        j.name("doses").beginArray();
        for (Dose d : doses) {
            j.beginObject().field("ts", d.ts).field("units", d.units).field("kind", d.kind).endObject();
        }
        return j.endArray().endObject().toString();
    }

    private static double number(Object v, double fallback) {
        return v instanceof Number ? ((Number) v).doubleValue() : fallback;
    }
}
