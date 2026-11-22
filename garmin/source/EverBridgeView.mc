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

// After this long without a new reading the age tag fills in, so it catches the eye.
const LATE_SEC = 360;

// Layout, design px (40 mm screen). Placed from the Instinct E's own fonts as measured
// in the simulator (time 18 px, date 11, glucose 27, change 12, tag 15, bottom row 11),
// so every row clears its neighbours and the bottom row sits where the round screen is
// wide enough for it.
const TIME_Y = 33;
const DATE_Y = 53;
const LINE1_Y = 62;      // under the time and date
const GLUCOSE_X = 30;    // left edge of the widest glucose ("388"); shorter ones right-align to it
const GLUCOSE_Y = 86;
const CHANGE_Y = 79;     // change from the reading before, right of the glucose
const TAG_Y = 95;        // age tag, under the change
const LINE2_Y = 109;     // under the glucose
const ROW_Y_DESIGN = 124; // IOB and steps
const ROW_GAP_DESIGN = 5;

// Layout A, as approved in the design:
//
//      10:47          ( 68 )   <- subscreen: heart rate
//      21 Nov         ( BPM)
//    ─────────────────────
//      142   +4
//            [2 MIN]           <- change from the reading before, age tag
//    ─────────────────────
//     IOB 2.4U  ⁚⁚ 8,412        <- insulin on board, steps
//
// The glucose number is never altered, however old: only the age tag changes
// (outlined while fresh, solid after 6 minutes).
//
// Coordinates are designed on the 40 mm screen (166 px) and scaled to the 45 mm.
class EverBridgeView extends WatchUi.WatchFace {

    private var _w as Number = 0;
    private var _h as Number = 0;
    private var _sx as Number = 0; // subscreen box
    private var _sy as Number = 0;
    private var _sw as Number = 0;
    private var _sh as Number = 0;

    // Fonts, chosen at layout from the device's own: the largest that fits each slot.
    private var _fBg = Graphics.FONT_NUMBER_HOT;
    private var _fTime = Graphics.FONT_NUMBER_MILD;
    private var _fDate = Graphics.FONT_TINY;
    private var _fDelta = Graphics.FONT_SMALL;
    private var _fTag = Graphics.FONT_XTINY;
    private var _fRow = Graphics.FONT_SMALL;
    private var _fLabel = Graphics.FONT_XTINY;
    private var _fHr = Graphics.FONT_NUMBER_MILD;

    // The reading version last drawn, for the instant redraw in onPartialUpdate.
    private var _drawn as Number = -1;

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
            _sx = p(110);
            _sy = p(3);
            _sw = p(53);
            _sh = p(53);
        }
        var g = Graphics.FONT_XTINY;
        // glucose: the biggest built-in number font that fits between the two lines
        _fBg = pick(dc, [Graphics.FONT_NUMBER_THAI_HOT, Graphics.FONT_NUMBER_HOT, Graphics.FONT_NUMBER_MEDIUM, Graphics.FONT_NUMBER_MILD], NUM_GLYPH, p(LINE2_Y - LINE1_Y - 14), "388", p(66));
        _fTime = pick(dc, [Graphics.FONT_NUMBER_MEDIUM, Graphics.FONT_NUMBER_MILD, Graphics.FONT_LARGE, Graphics.FONT_MEDIUM], NUM_GLYPH, p(19), "2358", p(60));
        _fDate = pick(dc, [Graphics.FONT_SMALL, Graphics.FONT_TINY, g], TEXT_GLYPH, p(8), "28 Sep", p(40));
        _fDelta = pick(dc, [Graphics.FONT_LARGE, Graphics.FONT_MEDIUM, Graphics.FONT_SMALL, Graphics.FONT_TINY], TEXT_GLYPH, p(12), "-12", p(30));
        _fTag = pick(dc, [Graphics.FONT_TINY, g], TEXT_GLYPH, p(7), "99+ MIN", p(52));
        _fLabel = pick(dc, [Graphics.FONT_TINY, g], TEXT_GLYPH, p(6), "IOB", p(14));
        // the bottom row: the largest font whose widest row fits the round screen at that height
        var rowFonts = [Graphics.FONT_MEDIUM, Graphics.FONT_SMALL, Graphics.FONT_TINY, g];
        _fRow = g;
        for (var i = 0; i < rowFonts.size(); i++) {
            var h = glyph(rowFonts[i], TEXT_GLYPH);
            if (h <= p(12) && rowWidth(dc, rowFonts[i], "12.5U", "18,412") <= chord(ROW_Y_DESIGN * _w / DESIGN + h / 2) - p(8)) {
                _fRow = rowFonts[i];
                break;
            }
        }
        _fHr = pick(dc, [Graphics.FONT_NUMBER_MEDIUM, Graphics.FONT_NUMBER_MILD, Graphics.FONT_MEDIUM, Graphics.FONT_SMALL], NUM_GLYPH, p(15), "188", _sw * 3 / 4);
        checkFit(dc);
    }

    // Width of the round screen at row y, px.
    private function chord(y as Number) as Number {
        var r = _w / 2.0;
        var d = (y - _h / 2.0).abs();
        return d >= r ? 0 : (2 * Math.sqrt(r * r - d * d)).toNumber();
    }

    // Width of the bottom row "IOB <iob>   <footprints> <steps>" in the given font.
    private function rowWidth(dc as Graphics.Dc, font, iob as String, steps as String) as Number {
        return dc.getTextWidthInPixels("IOB", _fLabel) + p(3) + dc.getTextWidthInPixels(iob, font) + ROW_GAP_DESIGN * _w / DESIGN
            + feetWidth() + p(2) + dc.getTextWidthInPixels(steps, font);
    }

    // The footprints drawn before the steps: about 4r wide.
    private function feetWidth() as Number {
        return 4 * feetR();
    }

    private function feetR() as Number {
        var r = p(3);
        return r < 3 ? 3 : r;
    }

    // A design coordinate (40 mm screen) on this screen.
    private function p(v as Number) as Number {
        return v * _w / DESIGN;
    }

    // The first (largest) font whose glyphs are at most maxH tall and whose widest
    // expected text is at most maxW wide; else the last.
    private function pick(dc as Graphics.Dc, fonts as Array, pct as Number, maxH as Number, sample as String, maxW as Number) {
        for (var i = 0; i < fonts.size(); i++) {
            if (glyph(fonts[i], pct) <= maxH && dc.getTextWidthInPixels(sample, fonts[i]) <= maxW) {
                return fonts[i];
            }
        }
        return fonts[fonts.size() - 1];
    }

    // ---------------------------------------------------------------- drawing

    function onUpdate(dc as Graphics.Dc) as Void {
        Sched.ensureArmed();
        dc.clearClip();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var now = Time.now();
        drawTime(dc, Gregorian.info(now, Time.FORMAT_MEDIUM));
        drawHeartRate(dc);
        drawReading(dc, now.value());
        _drawn = Sched.version();
    }

    // Called every second in low-power mode on watches that support it. A reading pushed
    // by the phone is drawn straight away instead of at the next minute; nothing else
    // is drawn here, to stay well inside the power budget.
    function onPartialUpdate(dc as Graphics.Dc) as Void {
        if (Sched.version() == _drawn) {
            return;
        }
        var top = p(LINE1_Y) + 2;
        dc.setClip(0, top, _w, _h - top);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        drawReading(dc, Time.now().value());
        dc.clearClip();
        _drawn = Sched.version();
    }

    // "10:47" top left (hours, square colon, minutes) and "21 Nov" under it.
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
        var cy = p(TIME_Y);
        var gh = glyph(_fTime, NUM_GLYPH);
        var dot = gh / 7 < 2 ? 2 : gh / 7;
        var x = p(32);
        ink(dc);
        text(dc, x, cy, _fTime, NUM_GLYPH, hStr, Graphics.TEXT_JUSTIFY_LEFT);
        x += dc.getTextWidthInPixels(hStr, _fTime) + p(2);
        dc.fillRectangle(x, cy - gh / 4 - dot / 2, dot, dot);
        dc.fillRectangle(x, cy + gh / 4 - dot / 2, dot, dot);
        x += dot + p(2);
        text(dc, x, cy, _fTime, NUM_GLYPH, mStr, Graphics.TEXT_JUSTIFY_LEFT);

        text(dc, p(33), p(DATE_Y), _fDate, TEXT_GLYPH, info.day.format("%d") + " " + info.month.toString(), Graphics.TEXT_JUSTIFY_LEFT);
    }

    // Heart rate in the subscreen: the number, "BPM" under it.
    private function drawHeartRate(dc as Graphics.Dc) as Void {
        var hr = heartRate();
        var cx = _sx + _sw / 2;
        var cy = _sy + _sh / 2;
        ink(dc);
        text(dc, cx, cy - _sh / 10, _fHr, NUM_GLYPH, hr == null ? "--" : hr.format("%d"), Graphics.TEXT_JUSTIFY_CENTER);
        text(dc, cx, cy + _sh / 4, _fLabel, TEXT_GLYPH, "BPM", Graphics.TEXT_JUSTIFY_CENTER);
    }

    // Glucose, the change and age tag under it, then IOB and steps.
    private function drawReading(dc as Graphics.Dc, nowSec as Number) as Void {
        var r = Sched.reading();
        var sgv = (r == null) ? null : r[Bg.K_SGV];
        var at = (r == null) ? null : r[Bg.K_AT];
        drawLines(dc);
        ink(dc);
        var right = glucoseRight(dc);
        if (!(sgv instanceof String) || !(at instanceof Number)) {
            text(dc, right, p(GLUCOSE_Y), Graphics.FONT_MEDIUM, TEXT_GLYPH, "---", Graphics.TEXT_JUSTIFY_RIGHT);
        } else {
            // right-aligned to where the widest reading ends, so the column beside it never moves
            text(dc, right, p(GLUCOSE_Y), _fBg, NUM_GLYPH, sgv, Graphics.TEXT_JUSTIFY_RIGHT);
            var d = r[Bg.K_DELTA];
            drawDeltaAndAge(dc, right + p(7), (d instanceof String) ? d : "--", nowSec - at);
        }
        var iob = (r == null) ? null : r[Bg.K_IOB];
        drawBottomRow(dc, _w / 2, p(ROW_Y_DESIGN), iobText(iob), stepsText());
    }

    // Where the glucose ends on the right: the widest reading ("388") from GLUCOSE_X.
    private function glucoseRight(dc as Graphics.Dc) as Number {
        return p(GLUCOSE_X) + dc.getTextWidthInPixels("388", _fBg);
    }

    // A line under the time and date, and one under the glucose.
    private function drawLines(dc as Graphics.Dc) as Void {
        ink(dc);
        var t = p(2) < 2 ? 2 : p(2);
        dc.fillRectangle(p(16), p(LINE1_Y) - t / 2, p(134), t);
        dc.fillRectangle(p(24), p(LINE2_Y) - t / 2, p(118), t);
    }

    // To the right of the glucose: the change ("+4") over the age tag ("2 MIN"). The tag is
    // outlined while fresh and solid once LATE_SEC has passed.
    private function drawDeltaAndAge(dc as Graphics.Dc, x as Number, delta as String, ageSec as Number) as Void {
        ink(dc);
        text(dc, x, p(CHANGE_Y), _fDelta, TEXT_GLYPH, delta, Graphics.TEXT_JUSTIFY_LEFT);
        var tag = ageText(ageSec);
        var padX = p(3);
        var padY = p(2);
        var tw = dc.getTextWidthInPixels(tag, _fTag) + 2 * padX;
        var th = glyph(_fTag, TEXT_GLYPH) + 2 * padY;
        var cy = p(TAG_Y);
        var ty = cy - th / 2;
        if (ageSec > LATE_SEC) {
            dc.fillRectangle(x, ty, tw, th);
            dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
            text(dc, x + padX, cy, _fTag, TEXT_GLYPH, tag, Graphics.TEXT_JUSTIFY_LEFT);
            ink(dc);
        } else {
            dc.setPenWidth(p(2) < 2 ? 2 : p(2));
            dc.drawRectangle(x, ty, tw, th);
            dc.setPenWidth(1);
            text(dc, x + padX, cy, _fTag, TEXT_GLYPH, tag, Graphics.TEXT_JUSTIFY_LEFT);
        }
    }

    // "IOB 2.4U   <footprints> 8,412"
    private function drawBottomRow(dc as Graphics.Dc, cx as Number, cy as Number, iob as String, steps as String) as Void {
        var lw = dc.getTextWidthInPixels("IOB", _fLabel);
        var iw = dc.getTextWidthInPixels(iob, _fRow);
        var g1 = p(3);
        var g2 = p(ROW_GAP_DESIGN);
        var x = cx - rowWidth(dc, _fRow, iob, steps) / 2;
        ink(dc);
        // the small label sits on the value's baseline
        var rowH = glyph(_fRow, TEXT_GLYPH);
        var labH = glyph(_fLabel, TEXT_GLYPH);
        text(dc, x, cy + (rowH - labH) / 2, _fLabel, TEXT_GLYPH, "IOB", Graphics.TEXT_JUSTIFY_LEFT);
        x += lw + g1;
        text(dc, x, cy, _fRow, TEXT_GLYPH, iob, Graphics.TEXT_JUSTIFY_LEFT);
        x += iw + g2;
        drawFootprints(dc, x + feetWidth() / 2, cy, feetR());
        x += feetWidth() + p(2);
        text(dc, x, cy, _fRow, TEXT_GLYPH, steps, Graphics.TEXT_JUSTIFY_LEFT);
    }

    // Two footprints about 4r wide and 4r tall, centred on (cx, cy): left foot lower,
    // right foot higher, each a sole and a heel.
    private function drawFootprints(dc as Graphics.Dc, cx as Number, cy as Number, r as Number) as Void {
        var fw = r * 2 / 3;
        if (fw < 2) {
            fw = 2;
        }
        dc.fillEllipse(cx - r, cy - r / 3, fw, r);
        dc.fillCircle(cx - r, cy + r, fw * 2 / 3);
        dc.fillEllipse(cx + r, cy - r, fw, r);
        dc.fillCircle(cx + r, cy + r / 3 + 1, fw * 2 / 3);
    }

    private function ink(dc as Graphics.Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
    }

    // ---------------------------------------------------------------- text

    // Glyph height of a font, px.
    private function glyph(font, pct as Number) as Number {
        return Graphics.getFontAscent(font) * pct / 100;
    }

    // Draws text with its glyphs (not its font box) centred on y.
    private function text(dc as Graphics.Dc, x as Number, y as Number, font, pct as Number, str as String, justify as Number) as Void {
        dc.drawText(x, y + glyph(font, pct) / 2 - Graphics.getFontAscent(font), font, str, justify);
    }

    private function stepsText() as String {
        var info = ActivityMonitor.getInfo();
        return (info == null || info.steps == null) ? "--" : groupThousands(info.steps);
    }

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

    // ---------------------------------------------------------------- fit check

    // Debug builds measure every element with the widest text it can show, on this
    // device's own fonts, and report anything that would leave the round screen or run
    // into the subscreen. CI fails the build on "FIT FAIL".
    (:debug)
    private function checkFit(dc as Graphics.Dc) as Void {
        System.println("layout " + _w + "x" + _h + " sub " + _sx + "," + _sy + " " + _sw + "x" + _sh);
        var names = ["XTINY", "TINY", "SMALL", "MEDIUM", "LARGE", "NUM_MILD", "NUM_MEDIUM", "NUM_HOT", "NUM_THAI_HOT"];
        var fonts = [Graphics.FONT_XTINY, Graphics.FONT_TINY, Graphics.FONT_SMALL, Graphics.FONT_MEDIUM, Graphics.FONT_LARGE,
            Graphics.FONT_NUMBER_MILD, Graphics.FONT_NUMBER_MEDIUM, Graphics.FONT_NUMBER_HOT, Graphics.FONT_NUMBER_THAI_HOT];
        for (var i = 0; i < fonts.size(); i++) {
            System.println("font " + names[i] + " ascent " + Graphics.getFontAscent(fonts[i]) + " '388' " + dc.getTextWidthInPixels("388", fonts[i])
                + " '18,412' " + dc.getTextWidthInPixels("18,412", fonts[i]));
        }
        var fails = 0;
        var tg = glyph(_fTime, NUM_GLYPH);
        var tw = dc.getTextWidthInPixels("2358", _fTime) + 2 * p(2) + (tg / 7 < 2 ? 2 : tg / 7);
        fails += box(dc, "time", p(32), p(TIME_Y) - tg / 2, tw, tg, false);
        var dg = glyph(_fDate, TEXT_GLYPH);
        fails += box(dc, "date", p(33), p(DATE_Y) - dg / 2, dc.getTextWidthInPixels("28 Sep", _fDate), dg, false);
        var lt = p(2) < 2 ? 2 : p(2);
        fails += box(dc, "top line", p(16), p(LINE1_Y) - lt / 2, p(134), lt, false);
        fails += box(dc, "bottom line", p(24), p(LINE2_Y) - lt / 2, p(118), lt, false);
        var bg = glyph(_fBg, NUM_GLYPH);
        var bw = dc.getTextWidthInPixels("388", _fBg);
        fails += box(dc, "glucose", p(GLUCOSE_X), p(GLUCOSE_Y) - bg / 2, bw, bg, false);
        var sx = glucoseRight(dc) + p(7);
        var cg = glyph(_fDelta, TEXT_GLYPH);
        fails += box(dc, "change", sx, p(CHANGE_Y) - cg / 2, dc.getTextWidthInPixels("-12", _fDelta), cg, false);
        var th = glyph(_fTag, TEXT_GLYPH) + 2 * p(2);
        var tagW = dc.getTextWidthInPixels("99+ MIN", _fTag) + 2 * p(3);
        fails += box(dc, "age tag", sx, p(TAG_Y) - th / 2, tagW, th, false);
        var bw2 = rowWidth(dc, _fRow, "12.5U", "18,412");
        var bh = glyph(_fRow, TEXT_GLYPH);
        fails += box(dc, "iob+steps", _w / 2 - bw2 / 2, p(ROW_Y_DESIGN) - bh / 2, bw2, bh, false);
        var hg = glyph(_fHr, NUM_GLYPH);
        var hw = dc.getTextWidthInPixels("188", _fHr);
        fails += box(dc, "heart rate", _sx + _sw / 2 - hw / 2, _sy + _sh / 2 - _sh / 10 - hg / 2, hw, hg, true);
        var lg = glyph(_fLabel, TEXT_GLYPH);
        var lw = dc.getTextWidthInPixels("BPM", _fLabel);
        fails += box(dc, "BPM", _sx + _sw / 2 - lw / 2, _sy + _sh / 2 + _sh / 4 - lg / 2, lw, lg, true);
        // top to bottom, nothing may touch: date, top line, glucose block, bottom line, IOB+steps
        var blockTop = p(GLUCOSE_Y) - bg / 2 < p(CHANGE_Y) - cg / 2 ? p(GLUCOSE_Y) - bg / 2 : p(CHANGE_Y) - cg / 2;
        var blockBottom = p(GLUCOSE_Y) + bg / 2 > p(TAG_Y) + th / 2 ? p(GLUCOSE_Y) + bg / 2 : p(TAG_Y) + th / 2;
        var tops = [p(DATE_Y) - dg / 2, p(LINE1_Y) - lt / 2, blockTop, p(LINE2_Y) - lt / 2, p(ROW_Y_DESIGN) - bh / 2];
        var bottoms = [p(DATE_Y) + dg / 2, p(LINE1_Y) + lt / 2, blockBottom, p(LINE2_Y) + lt / 2, p(ROW_Y_DESIGN) + bh / 2];
        var rows = ["date", "top line", "glucose block", "bottom line", "iob+steps"];
        for (var i = 1; i < 5; i++) {
            if (tops[i] - bottoms[i - 1] < 2) {
                fails++;
                System.println("FIT FAIL " + rows[i - 1] + " and " + rows[i] + " overlap (gap " + (tops[i] - bottoms[i - 1]) + " px)");
            }
        }
        // the change and the tag must not touch each other either
        if (p(TAG_Y) - th / 2 - (p(CHANGE_Y) + cg / 2) < 2) {
            fails++;
            System.println("FIT FAIL change and age tag overlap");
        }
        System.println(fails == 0 ? "FIT ok" : "FIT FAIL " + fails + " element(s)");
    }

    (:release)
    private function checkFit(dc as Graphics.Dc) as Void {
    }

    // 1 if the box (glyph ink, px) leaves the screen or touches the subscreen (or, for
    // the subscreen's own text, leaves the subscreen), else 0. Logs the box either way.
    (:debug)
    private function box(dc as Graphics.Dc, name as String, x as Number, y as Number, w as Number, h as Number, inSub as Boolean) as Number {
        var cx = _w / 2.0;
        var cy = _h / 2.0;
        var r = _w / 2.0 - 2;
        var scx = _sx + _sw / 2.0;
        var scy = _sy + _sh / 2.0;
        var sr = _sw / 2.0;
        var corners = [[x, y], [x + w, y], [x, y + h], [x + w, y + h]];
        var ok = true;
        var why = "";
        for (var i = 0; i < 4; i++) {
            var px = corners[i][0];
            var py = corners[i][1];
            if (inSub) {
                if (dist(px, py, scx, scy) > sr - 1) {
                    ok = false;
                    why = "leaves the subscreen";
                }
            } else if (dist(px, py, cx, cy) > r) {
                ok = false;
                why = "leaves the screen";
            }
        }
        if (!inSub && ok) {
            // nearest point of the box to the subscreen's centre
            var nx = scx < x ? x : (scx > x + w ? x + w : scx);
            var ny = scy < y ? y : (scy > y + h ? y + h : scy);
            if (dist(nx, ny, scx, scy) < sr + 2) {
                ok = false;
                why = "runs into the subscreen";
            }
        }
        System.println("FIT " + (ok ? "ok   " : "FAIL ") + name + " box " + x + "," + y + " " + w + "x" + h + (ok ? "" : " " + why));
        return ok ? 0 : 1;
    }

    (:debug)
    private function dist(x1, y1, x2, y2) as Float {
        var dx = (x1 - x2).toFloat();
        var dy = (y1 - y2).toFloat();
        return Math.sqrt(dx * dx + dy * dy).toFloat();
    }
}

// "2 MIN", "14 MIN", "99+ MIN": whole minutes since the reading.
function ageText(ageSec as Number) as String {
    var m = ageSec / 60;
    if (m < 0) {
        m = 0;
    }
    return m > 99 ? "99+ MIN" : m.format("%d") + " MIN";
}

// "2.4U"; "--" when Meanwhile hasn't reported insulin.
function iobText(iob) as String {
    if (iob instanceof Float || iob instanceof Double || iob instanceof Number || iob instanceof Long) {
        return iob.toFloat().format("%.1f") + "U";
    }
    return "--";
}

// 8412 -> "8,412"
function groupThousands(n as Number) as String {
    var s = n.format("%d");
    var out = "";
    var len = s.length();
    for (var i = 0; i < len; i++) {
        if (i > 0 && (len - i) % 3 == 0) {
            out += ",";
        }
        out += s.substring(i, i + 1);
    }
    return out;
}
