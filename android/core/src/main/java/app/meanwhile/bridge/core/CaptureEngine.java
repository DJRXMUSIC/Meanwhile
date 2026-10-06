package app.meanwhile.bridge.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns one observed notification into (at most) one stored reading.
 *
 * Pipeline: package filter, then {@link NotificationParser}, {@link TimestampResolver},
 * {@link ReadingGate} and finally the {@link ReadingStore}. Every attempt is recorded in the
 * {@link CaptureLog}, whatever the outcome, so problems can be diagnosed on the phone.
 */
public final class CaptureEngine {

    /**
     * Eversense app package names, as listed in xDrip+ UiBasedCollector:
     * Eversense 365 (US build), Eversense E3/Gen12, and the original Eversense app.
     */
    public static final List<String> DEFAULT_PACKAGES = Collections.unmodifiableList(Arrays.asList(
            "com.senseonics.eversense365.us",
            "com.senseonics.gen12androidapp",
            "com.senseonics.androidapp"));

    public enum Outcome {
        STORED,
        /** A reading already exists at (about) this time. */
        DUPLICATE,
        /** The app posted the same reading again (same {@code when}, same value). */
        REPOST,
        /** The same value has repeated too many times; the source looks stuck. */
        JAMMED,
        OUT_OF_RANGE,
        NO_VALUE,
        AMBIGUOUS,
        IGNORED_PACKAGE
    }

    /** What the listener saw. Built by the Android layer; plain data so it is testable. */
    public static final class Input {
        public final String pkg;
        public final long when;
        public final long postTime;
        public final long receivedAt;
        public final boolean ongoing;
        /** Visible TextView texts of the custom content view; null if there is none. */
        public final List<String> viewTexts;
        /** Title, text, big text, sub text, info text. */
        public final List<String> extrasTexts;
        /** Why the custom view could not be read, if it could not (diagnostics only). */
        public final String viewError;

        public Input(String pkg, long when, long postTime, long receivedAt, boolean ongoing,
                     List<String> viewTexts, List<String> extrasTexts) {
            this(pkg, when, postTime, receivedAt, ongoing, viewTexts, extrasTexts, null);
        }

        public Input(String pkg, long when, long postTime, long receivedAt, boolean ongoing,
                     List<String> viewTexts, List<String> extrasTexts, String viewError) {
            this.pkg = pkg;
            this.when = when;
            this.postTime = postTime;
            this.receivedAt = receivedAt;
            this.ongoing = ongoing;
            this.viewTexts = viewTexts;
            this.extrasTexts = extrasTexts;
            this.viewError = viewError;
        }

        public String describeTexts() {
            return "views=" + (viewError != null ? "<" + viewError + ">" : viewTexts) + " extras=" + extrasTexts;
        }
    }

    public static final class Result {
        public final Outcome outcome;
        public final Input input;
        /** Stored reading (STORED) or the rejected candidate (DUPLICATE/REPOST/JAMMED/OUT_OF_RANGE). */
        public final Reading reading;
        public final String detail;

        Result(Outcome outcome, Input input, Reading reading, String detail) {
            this.outcome = outcome;
            this.input = input;
            this.reading = reading;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return outcome + (reading != null ? " " + reading : "") + (detail != null ? " - " + detail : "");
        }
    }

    /** Called after a reading is stored, outside the engine lock. */
    public interface Listener {
        void onStored(Reading reading);
    }

    private final ReadingStore store;
    private final ReadingGate gate;
    private final TimestampResolver resolver;
    private final CaptureLog log;
    private volatile Set<String> packages = new LinkedHashSet<>(DEFAULT_PACKAGES);
    private volatile NotificationParser.Units units = NotificationParser.Units.AUTO;
    private volatile Listener listener;

    public CaptureEngine(ReadingStore store, KeyValueStore state, CaptureLog log) {
        this.store = store;
        this.gate = new ReadingGate(store, state);
        this.resolver = new TimestampResolver(state);
        this.log = log;
    }

    public void setPackages(Set<String> packages) {
        this.packages = new LinkedHashSet<>(packages);
    }

    public Set<String> getPackages() {
        return Collections.unmodifiableSet(packages);
    }

    public boolean isMonitored(String pkg) {
        return pkg != null && packages.contains(pkg);
    }

    public void setUnits(NotificationParser.Units units) {
        this.units = units;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public TimestampResolver timestamps() {
        return resolver;
    }

    public CaptureLog log() {
        return log;
    }

    public Result process(Input in) {
        if (!isMonitored(in.pkg)) {
            // not logged: every other app's notifications would flood the diagnostics
            return new Result(Outcome.IGNORED_PACKAGE, in, null, null);
        }
        final Result result;
        synchronized (this) {
            result = processLocked(in);
        }
        log.add(result);
        final Listener l = listener;
        if (result.outcome == Outcome.STORED && l != null) {
            l.onStored(result.reading);
        }
        return result;
    }

    private Result processLocked(Input in) {
        final NotificationParser.Result parsed = NotificationParser.parse(in.viewTexts, in.extrasTexts, units);
        if (!parsed.ok()) {
            final Outcome o = parsed.failure == NotificationParser.Failure.AMBIGUOUS ? Outcome.AMBIGUOUS : Outcome.NO_VALUE;
            String detail = parsed.failure + " in " + in.describeTexts();
            if (parsed.rangeMarker != null) detail = "app shows " + parsed.rangeMarker + "; " + detail;
            return new Result(o, in, null, detail);
        }

        final TimestampResolver.Resolution ts =
                resolver.resolve(in.pkg, in.when, in.postTime, in.receivedAt, parsed.mgdl);
        final Reading candidate = new Reading(ts.timestamp, parsed.mgdl, ts.source, in.when,
                in.postTime, in.receivedAt, in.pkg, parsed.matchedText);
        if (ts.repost) {
            return new Result(Outcome.REPOST, in, candidate, "same when and value as previous post");
        }

        switch (gate.check(candidate.timestamp, candidate.mgdl)) {
            case OUT_OF_RANGE:
                return new Result(Outcome.OUT_OF_RANGE, in, candidate,
                        "outside " + ReadingGate.MIN_MGDL + "-" + ReadingGate.MAX_MGDL + " mg/dL");
            case DUPLICATE:
                return new Result(Outcome.DUPLICATE, in, candidate, "reading already stored near this time");
            case JAMMED:
                return new Result(Outcome.JAMMED, in, candidate,
                        "same value more than " + ReadingGate.JAM_THRESHOLD + " times in a row");
            case ACCEPT:
            default:
                store.insert(candidate);
                return new Result(Outcome.STORED, in, candidate, null);
        }
    }
}
