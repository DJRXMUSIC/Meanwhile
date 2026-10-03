package app.meanwhile.bridge.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Extracts a glucose value from the text of a CGM app's notification.
 *
 * Ported from xDrip+ {@code services/UiBasedCollector} (filterString, arrowFilterString,
 * basicFilterString, filterUnicodeRange, isValidMmol, tryExtractString and the
 * "exactly one match" rule in processRemote). Differences from xDrip+:
 * <ul>
 *   <li>Units are auto-detected (integer = mg/dL, decimal = mmol/L) instead of read from
 *       a global preference; MGDL and MMOL force xDrip's per-unit behaviour.</li>
 *   <li>Identical values repeated in several views count once, so a value shown twice in
 *       the same layout is not rejected as ambiguous; differing values still are.</li>
 *   <li>If the custom view has no value, the standard title/text extras are tried as well
 *       (xDrip only reads the title when there is no custom view at all).</li>
 * </ul>
 */
public final class NotificationParser {

    /** xDrip+ {@code Constants.MMOLL_TO_MGDL}. */
    public static final double MMOL_TO_MGDL = 18.0182;

    public enum Units {
        AUTO, MGDL, MMOL;

        public static Units parse(String s) {
            if (s == null) return AUTO;
            switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "mgdl":
                case "mg/dl":
                    return MGDL;
                case "mmol":
                case "mmol/l":
                    return MMOL;
                default:
                    return AUTO;
            }
        }
    }

    public enum Failure {
        /** Nothing readable in the notification. */
        NO_TEXT,
        /** Text present but none of it is a glucose value. */
        NO_VALUE,
        /** More than one different number looked like a glucose value. */
        AMBIGUOUS
    }

    public static final class Result {
        /** Parsed value in mg/dL, or -1 on failure. */
        public final int mgdl;
        /** The original text the value came from (null on failure). */
        public final String matchedText;
        /** Null on success. */
        public final Failure failure;
        /** "LO" or "HI" if the app showed an out-of-range marker instead of a number. */
        public final String rangeMarker;

        Result(int mgdl, String matchedText, Failure failure, String rangeMarker) {
            this.mgdl = mgdl;
            this.matchedText = matchedText;
            this.failure = failure;
            this.rangeMarker = rangeMarker;
        }

        public boolean ok() {
            return failure == null;
        }
    }

    private NotificationParser() {
    }

    /**
     * @param viewTexts   texts of the visible TextViews in the notification's custom view,
     *                    or null if it has none (xDrip's {@code contentView != null} branch)
     * @param extrasTexts standard notification extras, most specific first (title, text, ...)
     */
    public static Result parse(List<String> viewTexts, List<String> extrasTexts, Units units) {
        boolean anyText = false;
        String marker = null;
        for (List<String> tier : tiers(viewTexts, extrasTexts)) {
            final Match m = match(tier, units);
            anyText |= m.anyText;
            if (marker == null) marker = m.rangeMarker;
            if (m.distinct > 1) {
                return new Result(-1, null, Failure.AMBIGUOUS, marker);
            }
            if (m.distinct == 1) {
                return new Result(m.mgdl, m.text, null, null);
            }
        }
        return new Result(-1, null, anyText ? Failure.NO_VALUE : Failure.NO_TEXT, marker);
    }

    private static List<List<String>> tiers(List<String> viewTexts, List<String> extrasTexts) {
        final List<List<String>> tiers = new ArrayList<>(2);
        if (viewTexts != null) tiers.add(viewTexts);
        if (extrasTexts != null) tiers.add(extrasTexts);
        return tiers;
    }

    private static final class Match {
        int distinct;
        int mgdl = -1;
        String text;
        boolean anyText;
        String rangeMarker;
    }

    private static Match match(List<String> texts, Units units) {
        final Match m = new Match();
        for (String text : texts) {
            if (text == null || text.trim().isEmpty()) continue;
            m.anyText = true;
            final int mgdl = extractMgdl(text, units);
            if (mgdl > 0) {
                if (m.distinct == 0) {
                    m.mgdl = mgdl;
                    m.text = text;
                    m.distinct = 1;
                } else if (mgdl != m.mgdl) {
                    m.distinct++;
                }
            } else if (m.rangeMarker == null) {
                m.rangeMarker = rangeMarker(text);
            }
        }
        return m;
    }

    /**
     * xDrip+ {@code tryExtractString}: the whole (filtered) text must be the number.
     *
     * @return mg/dL (possibly out of physiological range), or -1 if the text is not a value
     */
    public static int extractMgdl(String text, Units units) {
        if (text == null) return -1;
        final String f = filterString(text);
        try {
            switch (units) {
                case MGDL:
                    return Integer.parseInt(f);
                case MMOL:
                    return isValidMmol(f) ? mmolToMgdl(f) : -1;
                case AUTO:
                default:
                    if (f.matches("[0-9]{1,4}")) return Integer.parseInt(f);
                    if (isValidMmol(f)) return mmolToMgdl(f);
                    return -1;
            }
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int mmolToMgdl(String f) {
        return (int) Math.round(Double.parseDouble(f.replace(',', '.')) * MMOL_TO_MGDL);
    }

    /** xDrip+ {@code isValidMmol}. */
    public static boolean isValidMmol(String text) {
        return text.matches("[0-9]+[.,][0-9]+");
    }

    /** xDrip+ {@code filterString}: strip arrows, units and invisible characters. */
    public static String filterString(String value) {
        return basicFilterString(arrowFilterString(value)).trim();
    }

    /** xDrip+ {@code basicFilterString}. */
    static String basicFilterString(String value) {
        return value
                .replace(" ", " ")
                .replace("⁠", "")
                .replace("\\", "/")
                .replace("当前血糖:", "")
                .replace("mmol/L", "")
                .replace("mmol/l", "")
                .replace("mg/dL", "")
                .replace("mg/dl", "")
                .replace("≤", "")
                .replace("≥", "");
    }

    /** xDrip+ {@code arrowFilterString}: drop the Unicode arrow blocks. */
    static String arrowFilterString(String value) {
        return filterUnicodeRange(filterUnicodeRange(filterUnicodeRange(filterUnicodeRange(value,
                '←', '⇿'),
                '✀', '➿'),
                '⤀', '⥿'),
                '⬀', '⯿');
    }

    /** xDrip+ {@code filterUnicodeRange}. */
    static String filterUnicodeRange(String input, char bottom, char top) {
        if (bottom > top) {
            throw new IllegalArgumentException("bottom and top of character range invalid");
        }
        final StringBuilder filtered = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            if (c < bottom || c > top) {
                filtered.append(c);
            }
        }
        return filtered.toString();
    }

    /** Eversense shows "LO"/"HI" outside 40-400 mg/dL; recorded for diagnostics only. */
    static String rangeMarker(String text) {
        final String f = filterString(text).toUpperCase(Locale.ROOT);
        if (f.equals("LO") || f.equals("LOW")) return "LO";
        if (f.equals("HI") || f.equals("HIGH")) return "HI";
        return null;
    }
}
