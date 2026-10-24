import Toybox.Activity;
import Toybox.ActivityMonitor;
import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;
import Toybox.Time;
import Toybox.Time.Gregorian;
import Toybox.WatchUi;

// Time, date, steps, heart rate, and the glucose reading with its trend arrow and
// the minutes since it was taken.
//
// Layout, top to bottom: date (with heart rate in the Instinct's round subscreen,
// or beside the steps on a watch without one), time, glucose row, steps.
// How much of a font's ascent its digits and capitals actually fill, in percent
// (measured on the Instinct E in the simulator). Rows are laid out by these glyph
// heights, not by the fonts' much taller boxes.
const NUM_GLYPH = 62;
const TEXT_GLYPH = 70;

class MeanwhileView extends WatchUi.WatchFace {

    private var _w as Number = 0;
    private var _h as Number = 0;
    private var _sub = null; // Graphics.BoundingBox of the subscreen, or null

    private var _timeFont = Graphics.FONT_NUMBER_HOT;
    private var _bgFont = Graphics.FONT_NUMBER_MEDIUM;
    private var _smallFont = Graphics.FONT_SMALL;
    private var _minFont = Graphics.FONT_MEDIUM;

    // glyph centre lines
    private var _dateX as Number = 0;
    private var _dateY as Number = 0;
    private var _timeY as Number = 0;
    private var _bgY as Number = 0;
    private var _stepsY as Number = 0;

    function initialize() {
        WatchFace.initialize();
    }

    function onLayout(dc as Graphics.Dc) as Void {
        _w = dc.getWidth();
        _h = dc.getHeight();
        _sub = null;
        if (WatchUi has :getSubscreen) {
            _sub = WatchUi.getSubscreen();
        }

        var top;
        if (_sub != null) {
            // Date to the left of the subscreen, level with its centre.
            _dateY = _sub.y + _sub.height / 2;
            _dateX = (chordLeft(_dateY) + _sub.x) / 2;
            top = _sub.y + _sub.height;
        } else {
            _dateY = _h * 17 / 100;
            _dateX = _w / 2;
            top = _h * 25 / 100;
        }
        var avail = _h - _h * 5 / 100 - top;

        // The largest time font, then the largest glucose font, that leave room for
        // the steps row and four gaps of at least 4 px.
        var stepsG = glyph(_smallFont, TEXT_GLYPH);
        var timeFonts = [Graphics.FONT_NUMBER_THAI_HOT, Graphics.FONT_NUMBER_HOT, Graphics.FONT_NUMBER_MEDIUM, Graphics.FONT_NUMBER_MILD];
        var bgFonts = [Graphics.FONT_NUMBER_HOT, Graphics.FONT_NUMBER_MEDIUM, Graphics.FONT_NUMBER_MILD];
        var done = false;
        for (var i = 0; i < timeFonts.size() && !done; i++) {
            for (var j = 0; j < bgFonts.size() && !done; j++) {
                _timeFont = timeFonts[i];
                _bgFont = bgFonts[j];
                done = glyph(_timeFont, NUM_GLYPH) + glyph(_bgFont, NUM_GLYPH) + stepsG + 16 <= avail;
            }
        }
        var timeG = glyph(_timeFont, NUM_GLYPH);
        var bgG = glyph(_bgFont, NUM_GLYPH);
        var gap = (avail - timeG - bgG - stepsG) / 4;
        _timeY = top + gap + timeG / 2;
        _bgY = _timeY + timeG / 2 + gap + bgG / 2;
        _stepsY = _bgY + bgG / 2 + gap + stepsG / 2;
        logLayout(top, avail, gap);
    }

    (:debug)
    private function logLayout(top as Number, avail as Number, gap as Number) as Void {
        System.println("layout " + _w + "x" + _h + " sub " + (_sub == null ? "none" : _sub.x + "," + _sub.y + " " + _sub.width + "x" + _sub.height)
            + " top " + top + " avail " + avail + " gap " + gap + " timeY " + _timeY + " bgY " + _bgY + " stepsY " + _stepsY);
    }

    (:release)
    private function logLayout(top as Number, avail as Number, gap as Number) as Void {
    }

    // Glyph height of a font, px.
    private function glyph(font, pct as Number) as Number {
        return Graphics.getFontAscent(font) * pct / 100;
    }

    // Draws text so its glyphs (not its font box) are centred on y.
    private function text(dc as Graphics.Dc, x as Number, y as Number, font, pct as Number, str as String, justify as Number) as Void {
        var g = glyph(font, pct);
        dc.drawText(x, y + g / 2 - Graphics.getFontAscent(font), font, str, justify);
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        Sched.ensureArmed();

        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);

        var now = Time.now();
        var info = Gregorian.info(now, Time.FORMAT_MEDIUM);

        // date: "24 OCT"
        text(dc, _dateX, _dateY, _smallFont, TEXT_GLYPH, info.day.format("%d") + " " + info.month.toString().toUpper(),
            Graphics.TEXT_JUSTIFY_CENTER);

        // time
        var hour = info.hour;
        var timeStr;
        if (System.getDeviceSettings().is24Hour) {
            timeStr = hour.format("%02d") + ":" + info.min.format("%02d");
        } else {
            hour = hour % 12;
            if (hour == 0) {
                hour = 12;
            }
            timeStr = hour.format("%d") + ":" + info.min.format("%02d");
        }
        text(dc, _w / 2, _timeY, _timeFont, NUM_GLYPH, timeStr, Graphics.TEXT_JUSTIFY_CENTER);

        drawGlucose(dc, now.value());

        // heart rate and steps
        var hr = heartRate();
        var hrStr = (hr == null) ? "--" : hr.format("%d");
        var steps = ActivityMonitor.getInfo().steps;
        var stepsStr = (steps == null) ? "--" : steps.format("%d");
        if (_sub != null) {
            var scx = _sub.x + _sub.width / 2;
            var scy = _sub.y + _sub.height / 2;
            drawHeart(dc, scx, scy - _sub.height / 6, _sub.height / 11);
            text(dc, scx, scy + _sub.height / 6, _smallFont, TEXT_GLYPH, hrStr, Graphics.TEXT_JUSTIFY_CENTER);
            drawIconText(dc, _w / 2, _stepsY, stepsStr, :steps);
        } else {
            drawIconText(dc, _w * 30 / 100, _stepsY, stepsStr, :steps);
            drawIconText(dc, _w * 70 / 100, _stepsY, hrStr, :heart);
        }
    }

    // "123 ↗ 2": value, arrow, minutes since the reading, centred as one group.
    private function drawGlucose(dc as Graphics.Dc, nowSec as Number) as Void {
        var sgv = Application.Storage.getValue(Bg.K_SGV);
        var at = Application.Storage.getValue(Bg.K_AT);
        var cx = _w / 2;
        if (!(sgv instanceof String) || !(at instanceof Number)) {
            text(dc, cx, _bgY, _minFont, TEXT_GLYPH, "--", Graphics.TEXT_JUSTIFY_CENTER);
            return;
        }
        var dir = Application.Storage.getValue(Bg.K_DIR);
        var minStr = minutesSince(at, nowSec).format("%d");

        var font = _bgFont;
        var size = glyph(font, NUM_GLYPH);
        var arrowW = arrowWidth(dir, size);
        var gap = size / 4;
        var total = dc.getTextWidthInPixels(sgv, font) + gap + arrowW + gap + dc.getTextWidthInPixels(minStr, _minFont);
        if (total > chordWidth(_bgY) * 94 / 100 && font != Graphics.FONT_NUMBER_MILD) {
            font = Graphics.FONT_NUMBER_MILD;
            size = glyph(font, NUM_GLYPH);
            arrowW = arrowWidth(dir, size);
            gap = size / 4;
            total = dc.getTextWidthInPixels(sgv, font) + gap + arrowW + gap + dc.getTextWidthInPixels(minStr, _minFont);
        }
        var x = cx - total / 2;
        text(dc, x, _bgY, font, NUM_GLYPH, sgv, Graphics.TEXT_JUSTIFY_LEFT);
        x += dc.getTextWidthInPixels(sgv, font) + gap;
        drawArrow(dc, x + arrowW / 2, _bgY, size, dir);
        x += arrowW + gap;
        text(dc, x, _bgY, _minFont, TEXT_GLYPH, minStr, Graphics.TEXT_JUSTIFY_LEFT);
    }

    // ---------------------------------------------------------------- arrows

    private function isDouble(dir) as Boolean {
        return "DoubleUp".equals(dir) || "DoubleDown".equals(dir);
    }

    private function arrowWidth(dir, size as Number) as Number {
        if (arrowAngle(dir) == null) {
            return 0;
        }
        return isDouble(dir) ? size * 3 / 2 : size;
    }

    // Degrees clockwise from pointing right (screen y grows downwards), or null for
    // no arrow (trend not computable yet).
    private function arrowAngle(dir) {
        if ("Flat".equals(dir)) {
            return 0;
        }
        if ("FortyFiveUp".equals(dir)) {
            return -45;
        }
        if ("SingleUp".equals(dir) || "DoubleUp".equals(dir)) {
            return -90;
        }
        if ("FortyFiveDown".equals(dir)) {
            return 45;
        }
        if ("SingleDown".equals(dir) || "DoubleDown".equals(dir)) {
            return 90;
        }
        return null;
    }

    private function drawArrow(dc as Graphics.Dc, cx as Number, cy as Number, size as Number, dir) as Void {
        var angle = arrowAngle(dir);
        if (angle == null) {
            return;
        }
        if (isDouble(dir)) {
            var off = size * 3 / 8;
            drawOneArrow(dc, cx - off, cy, size, angle);
            drawOneArrow(dc, cx + off, cy, size, angle);
        } else {
            drawOneArrow(dc, cx, cy, size, angle);
        }
    }

    // A filled arrow of the given length, centred on (cx, cy).
    private function drawOneArrow(dc as Graphics.Dc, cx as Number, cy as Number, size as Number, angle as Number) as Void {
        var h = size / 2.0;
        var head = size * 0.5;
        var shaft = size * 0.09 + 0.6; // half the shaft's thickness
        var wing = size * 0.32;        // half the head's width
        var pts = [
            [-h, -shaft], [h - head, -shaft], [h - head, -wing], [h, 0.0],
            [h - head, wing], [h - head, shaft], [-h, shaft]
        ];
        var a = Math.toRadians(angle);
        var c = Math.cos(a);
        var s = Math.sin(a);
        var out = [];
        for (var i = 0; i < pts.size(); i++) {
            var x = pts[i][0];
            var y = pts[i][1];
            out.add([Math.round(cx + x * c - y * s).toNumber(), Math.round(cy + x * s + y * c).toNumber()]);
        }
        dc.fillPolygon(out);
    }

    // ---------------------------------------------------------------- icons

    private function drawIconText(dc as Graphics.Dc, cx as Number, cy as Number, str as String, icon as Symbol) as Void {
        var r = glyph(_smallFont, TEXT_GLYPH) / 3;
        if (r < 3) {
            r = 3;
        }
        var iconW = 4 * r;
        var gap = r;
        var total = iconW + gap + dc.getTextWidthInPixels(str, _smallFont);
        var x = cx - total / 2;
        if (icon == :heart) {
            drawHeart(dc, x + iconW / 2, cy, r);
        } else {
            drawSteps(dc, x + iconW / 2, cy, r);
        }
        text(dc, x + iconW + gap, cy, _smallFont, TEXT_GLYPH, str, Graphics.TEXT_JUSTIFY_LEFT);
    }

    // A heart about 4r wide, centred on (cx, cy).
    private function drawHeart(dc as Graphics.Dc, cx as Number, cy as Number, r as Number) as Void {
        var top = cy - r / 2;
        dc.fillCircle(cx - r, top, r);
        dc.fillCircle(cx + r, top, r);
        dc.fillPolygon([[cx - 2 * r, top + r / 3], [cx + 2 * r, top + r / 3], [cx, cy + 2 * r]]);
    }

    // Two footprints about 4r wide, centred on (cx, cy).
    private function drawSteps(dc as Graphics.Dc, cx as Number, cy as Number, r as Number) as Void {
        var fw = r * 2 / 3;
        if (fw < 2) {
            fw = 2;
        }
        // left foot, lower; right foot, higher
        dc.fillEllipse(cx - r, cy, fw, r);
        dc.fillCircle(cx - r, cy + r + fw, fw * 2 / 3);
        dc.fillEllipse(cx + r, cy - r, fw, r);
        dc.fillCircle(cx + r, cy + fw, fw * 2 / 3);
    }

    // ---------------------------------------------------------------- helpers

    private function heartRate() as Number? {
        var ai = Activity.getActivityInfo();
        if (ai != null && ai.currentHeartRate != null) {
            return ai.currentHeartRate;
        }
        if (ActivityMonitor has :getHeartRateHistory) {
            var it = ActivityMonitor.getHeartRateHistory(1, true);
            var sample = it.next();
            if (sample != null && sample.heartRate != null && sample.heartRate != ActivityMonitor.INVALID_HR_SAMPLE) {
                return sample.heartRate;
            }
        }
        return null;
    }

    // Width of the round screen at height y.
    private function chordWidth(y as Number) as Number {
        var r = _w / 2.0;
        var d = y - _h / 2.0;
        var sq = r * r - d * d;
        return sq <= 0 ? 0 : (2 * Math.sqrt(sq)).toNumber();
    }

    private function chordLeft(y as Number) as Number {
        return (_w - chordWidth(y)) / 2;
    }
}

// Whole minutes since the reading, 0-999.
function minutesSince(at as Number, nowSec as Number) as Number {
    var m = (nowSec - at) / 60;
    if (m < 0) {
        return 0;
    }
    return m > 999 ? 999 : m;
}
