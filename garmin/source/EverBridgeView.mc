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

// How much of a font's ascent its digits and capitals actually fill, in percent
// (measured on the Instinct E in the simulator). Text is placed by its glyphs,
// not by the fonts' much taller boxes.
const NUM_GLYPH = 62;
const TEXT_GLYPH = 70;

// Width of the screen the layout is designed on (Instinct E 40 mm).
const DESIGN = 166;

// Modelled on the Instinct E's own "Instinct" face:
//
//   ┌──────────────┐   (steps)    <- subscreen
//   │   123        │
//   │ ///////////  /
//   │   ↗  3      /
//   │ //////////// ____________
//   ────────────    │  ♥
//      15 00     /  │  71
//   ────────────    ////
//         24 OCT
//
// Glucose sits in the top-left panel where that face says INSTINCT, with the trend
// arrow and the minutes since the reading on the row below, in place of its arrow.
//
// Coordinates are designed on the 40 mm screen (166 px) and scaled to the 45 mm.
class EverBridgeView extends WatchUi.WatchFace {

    private var _w as Number = 0;
    private var _h as Number = 0;
    private var _sx as Number = 0; // subscreen box
    private var _sy as Number = 0;
    private var _sw as Number = 0;
    private var _sh as Number = 0;

    // Vertical offsets that centre each font's glyphs on a y, and the time's glyph
    // height: worked out once here rather than on every redraw.
    private var _dyNum as Number = 0;   // FONT_NUMBER_MEDIUM (glucose)
    private var _dyTime as Number = 0;  // FONT_NUMBER_THAI_HOT (time)
    private var _dySmall as Number = 0; // FONT_SMALL
    private var _dyMed as Number = 0;   // FONT_MEDIUM
    private var _gTime as Number = 0;

    function initialize() {
        WatchFace.initialize();
    }

    function onLayout(dc as Graphics.Dc) as Void {
        _w = dc.getWidth();
        _h = dc.getHeight();
        var sub = null;
        if (WatchUi has :getSubscreen) {
            sub = WatchUi.getSubscreen();
        }
        if (sub != null) {
            _sx = sub.x;
            _sy = sub.y;
            _sw = sub.width;
            _sh = sub.height;
        } else {
            _sx = p(113);
            _sy = 0;
            _sw = p(52);
            _sh = p(52);
        }
        _dyNum = dy(Graphics.FONT_NUMBER_MEDIUM, NUM_GLYPH);
        _dyTime = dy(Graphics.FONT_NUMBER_THAI_HOT, NUM_GLYPH);
        _dySmall = dy(Graphics.FONT_SMALL, TEXT_GLYPH);
        _dyMed = dy(Graphics.FONT_MEDIUM, TEXT_GLYPH);
        _gTime = glyph(Graphics.FONT_NUMBER_THAI_HOT, NUM_GLYPH);
        logLayout();
    }

    (:debug)
    private function logLayout() as Void {
        System.println("layout " + _w + "x" + _h + " sub " + _sx + "," + _sy + " " + _sw + "x" + _sh);
    }

    (:release)
    private function logLayout() as Void {
    }

    // A design coordinate (40 mm screen) on this screen.
    private function p(v as Number) as Number {
        return v * _w / DESIGN;
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        Sched.ensureArmed();

        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(1);

        var now = Time.now();
        drawFrame(dc);
        drawGlucose(dc, now.value());
        drawTime(dc, Gregorian.info(now, Time.FORMAT_MEDIUM));
        drawSteps(dc);
        drawHeartRate(dc);
    }

    // ---------------------------------------------------------------- panels

    // The lines and hatching the Instinct face is built from.
    private function drawFrame(dc as Graphics.Dc) as Void {
        // top-left panel: diagonal right edge, hatch under it
        dc.drawLine(p(106), p(10), p(94), p(60));
        hatch(dc, p(30), p(91), p(60));
        // rule above the time, stepping up into the right panel
        dc.drawLine(p(14), p(64), p(116), p(64));
        dc.drawLine(p(128), p(68), p(152), p(68));
        // diagonal between the time and the right panel
        dc.drawLine(p(130), p(68), p(118), p(112));
        // rule under the time; hatch under the heart rate
        dc.drawLine(p(14), p(115), p(116), p(115));
        hatch(dc, p(124), p(150), p(115));
        // short hatch under the date
        hatch(dc, p(62), p(104), p(146));
    }

    // A row of short diagonal ticks from x0 to x1, its bottom on y.
    private function hatch(dc as Graphics.Dc, x0 as Number, x1 as Number, y as Number) as Void {
        var t = p(3);
        if (t < 3) {
            t = 3;
        }
        for (var x = x0; x + t <= x1; x += t) {
            dc.drawLine(x, y, x + t - 1, y - t + 1);
        }
    }

    // Glucose in the top-left panel; arrow and minutes since the reading below it.
    private function drawGlucose(dc as Graphics.Dc, nowSec as Number) as Void {
        var cx = p(62);
        var r = Sched.reading();
        var sgv = (r == null) ? null : r[Bg.K_SGV];
        var at = (r == null) ? null : r[Bg.K_AT];
        if (!(sgv instanceof String) || !(at instanceof Number)) {
            text(dc, cx, p(28), Graphics.FONT_MEDIUM, _dyMed, "---", Graphics.TEXT_JUSTIFY_CENTER);
            return;
        }
        text(dc, cx, p(28), Graphics.FONT_NUMBER_MEDIUM, _dyNum, sgv, Graphics.TEXT_JUSTIFY_CENTER);

        var dir = r[Bg.K_DIR];
        var minStr = minutesSince(at, nowSec).format("%d");
        var font = Graphics.FONT_SMALL;
        var size = p(15);
        var arrowW = arrowWidth(dir, size);
        var gap = arrowW > 0 ? p(7) : 0;
        var total = arrowW + gap + dc.getTextWidthInPixels(minStr, font);
        var x = cx - total / 2;
        var y = p(47);
        drawArrow(dc, x + arrowW / 2, y, size, dir);
        text(dc, x + arrowW + gap, y, font, _dySmall, minStr, Graphics.TEXT_JUSTIFY_LEFT);
    }

    // "15 00": hours solid, minutes striped, as on the Instinct face.
    private function drawTime(dc as Graphics.Dc, info as Gregorian.Info) as Void {
        var hour = info.hour;
        var hStr;
        if (System.getDeviceSettings().is24Hour) {
            hStr = hour.format("%02d");
        } else {
            hour = hour % 12;
            hStr = (hour == 0 ? 12 : hour).format("%d");
        }
        var mStr = info.min.format("%02d");
        var font = Graphics.FONT_NUMBER_THAI_HOT;
        var gap = p(3);
        var hw = dc.getTextWidthInPixels(hStr, font);
        var mw = dc.getTextWidthInPixels(mStr, font);
        var x = p(66) - (hw + gap + mw) / 2;
        var y = p(90);
        text(dc, x, y, font, _dyTime, hStr, Graphics.TEXT_JUSTIFY_LEFT);
        var mx = x + hw + gap;
        text(dc, mx, y, font, _dyTime, mStr, Graphics.TEXT_JUSTIFY_LEFT);

        // Stripe the minutes: blank every other row of their glyphs.
        var g = _gTime;
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        for (var row = y - g / 2 + 1; row < y + g / 2; row += 2) {
            dc.drawLine(mx, row, mx + mw, row);
        }
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);

        // date
        text(dc, _w / 2, p(132), Graphics.FONT_SMALL, _dySmall,
            info.day.format("%d") + " " + info.month.toString().toUpper(), Graphics.TEXT_JUSTIFY_CENTER);
    }

    // Steps in the subscreen: footprints over the count.
    private function drawSteps(dc as Graphics.Dc) as Void {
        var steps = ActivityMonitor.getInfo().steps;
        var cx = _sx + _sw / 2;
        var cy = _sy + _sh / 2;
        drawFootprints(dc, cx, cy - _sh / 5, _sh / 11);
        text(dc, cx, cy + _sh / 6, Graphics.FONT_SMALL, _dySmall,
            steps == null ? "--" : steps.format("%d"), Graphics.TEXT_JUSTIFY_CENTER);
    }

    // Heart rate in the right panel.
    private function drawHeartRate(dc as Graphics.Dc) as Void {
        var hr = heartRate();
        var cx = p(140);
        drawHeart(dc, cx, p(80), p(5));
        text(dc, cx, p(100), Graphics.FONT_SMALL, _dySmall, hr == null ? "--" : hr.format("%d"), Graphics.TEXT_JUSTIFY_CENTER);
    }

    // ---------------------------------------------------------------- text

    // Glyph height of a font, px.
    private function glyph(font, pct as Number) as Number {
        return Graphics.getFontAscent(font) * pct / 100;
    }

    // Offset from a glyph centre line to where drawText puts the font's top.
    private function dy(font, pct as Number) as Number {
        return glyph(font, pct) / 2 - Graphics.getFontAscent(font);
    }

    // Draws text with its glyphs (not its font box) centred on y; dy from dy().
    private function text(dc as Graphics.Dc, x as Number, y as Number, font, off as Number, str as String, justify as Number) as Void {
        dc.drawText(x, y + off, font, str, justify);
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
        var head = size * 0.45;
        var shaft = size * 0.07 + 0.5; // half the shaft's thickness
        var wing = size * 0.3;         // half the head's width
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

    // A heart about 4r wide, centred on (cx, cy).
    private function drawHeart(dc as Graphics.Dc, cx as Number, cy as Number, r as Number) as Void {
        var top = cy - r / 2;
        dc.fillCircle(cx - r, top, r);
        dc.fillCircle(cx + r, top, r);
        dc.fillPolygon([[cx - 2 * r, top + r / 3], [cx + 2 * r, top + r / 3], [cx, cy + 2 * r]]);
    }

    // Two footprints about 4r wide, centred on (cx, cy).
    private function drawFootprints(dc as Graphics.Dc, cx as Number, cy as Number, r as Number) as Void {
        var fw = r * 2 / 3;
        if (fw < 2) {
            fw = 2;
        }
        // left foot lower, right foot higher; each a sole and a heel
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
}

// Whole minutes since the reading, 0-999.
function minutesSince(at as Number, nowSec as Number) as Number {
    var m = (nowSec - at) / 60;
    if (m < 0) {
        return 0;
    }
    return m > 999 ? 999 : m;
}
