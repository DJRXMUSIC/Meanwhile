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

// How much of a font's ascent its digits and capitals actually fill, in percent,
// measured pixel by pixel on Instinct E simulator screenshots (number fonts 77%,
// e.g. the time's 44 px ascent draws 34 px digits; text fonts 72-75%), rounded up
// so nothing is placed tighter than it really is. Text is placed by its glyphs,
// not by the fonts' much taller boxes.
const NUM_GLYPH = 78;
const TEXT_GLYPH = 75;

// Width of the screen the layout is designed on (Instinct E 40 mm).
const DESIGN = 166;

// After this long without a new reading the age tag fills in, so it catches the eye.
const LATE_SEC = 360;

// Layout, design px (40 mm screen). Rows are stacked in onLayout from the Instinct E's
// own fonts and its subscreen, so the glucose block sits just clear of the heart rate
// and the gaps open up as far as the round screen allows the bottom row to go.
const GLUCOSE_X = 22;     // left edge of the widest glucose ("388"); shorter ones right-align to it
const COL_GAP = 5;        // glucose to the change / age column
const GAP_OUT = 3;        // glucose block to the top line, bottom line to IOB and steps
const GAP_MIN = 3;        // the least room above and below the time
const ROW_GAP_DESIGN = 5; // IOB to the steps

// The layout:
//
//      142   +4           ( 68 )   <- subscreen: heart rate
//            [2 MIN]      ( BPM)   <- change from the reading before, age tag
//    ─────────────────────
//       10:47  21 Nov               <- time and date
//    ─────────────────────
//     IOB 2.4U  ⁚⁚ 8,412            <- insulin on board, steps
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

    // Where things go on this screen, px (centres of each row's glyphs), set in onLayout.
    private var _bgX as Number = 0;    // left edge of the widest glucose
    private var _colX as Number = 0;   // left edge of the change / age column
    private var _bgY as Number = 0;
    private var _chY as Number = 0;
    private var _tagY as Number = 0;
    private var _line1Y as Number = 0;
    private var _timeY as Number = 0;
    private var _line2Y as Number = 0;
    private var _rowY as Number = 0;

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
        // glucose: clearly smaller than the time (about two thirds of its height)
        _fBg = pick(dc, [Graphics.FONT_NUMBER_HOT, Graphics.FONT_NUMBER_MEDIUM, Graphics.FONT_NUMBER_MILD, Graphics.FONT_LARGE], NUM_GLYPH, p(24), "388", p(50));
        _fDate = pick(dc, [Graphics.FONT_SMALL, Graphics.FONT_TINY, g], TEXT_GLYPH, p(13), "28 Sep", p(50));
        _fDelta = pick(dc, [Graphics.FONT_LARGE, Graphics.FONT_MEDIUM, Graphics.FONT_SMALL, Graphics.FONT_TINY], TEXT_GLYPH, p(13), "-12", p(30));
        _fTag = pick(dc, [Graphics.FONT_TINY, g], TEXT_GLYPH, p(12), "99+ MIN", p(52));
        _fLabel = pick(dc, [Graphics.FONT_TINY, g], TEXT_GLYPH, p(12), "IOB", p(24));
        _fHr = pick(dc, [Graphics.FONT_NUMBER_MEDIUM, Graphics.FONT_NUMBER_MILD, Graphics.FONT_MEDIUM, Graphics.FONT_SMALL], NUM_GLYPH, p(15), "188", _sw * 3 / 4);

        // Top: the glucose, with the change over the age tag to its right, as high as the
        // subscreen's circle and the round screen allow.
        _bgX = p(GLUCOSE_X);
        _colX = _bgX + dc.getTextWidthInPixels("388", _fBg) + p(COL_GAP);
        var cg = glyph(_fDelta, TEXT_GLYPH);
        var cw = dc.getTextWidthInPixels("-12", _fDelta);
        var th = tagHeight();
        var tw = dc.getTextWidthInPixels("99+ MIN", _fTag) + 2 * p(3);
        var bg = glyph(_fBg, NUM_GLYPH);
        var chTop = p(10);
        while (chTop < _h / 2) {
            var tTop = chTop + cg + p(3);
            var bgTop = (chTop + tTop + th) / 2 - bg / 2;
            if (clearOfSub(_colX, chTop, cw, cg) && clearOfSub(_colX, tTop, tw, th)
                    && inRound(_colX, chTop) && inRound(_colX + cw, chTop) && inRound(_bgX, bgTop)) {
                break;
            }
            chTop++;
        }
        var tagTop = chTop + cg + p(3);
        _chY = chTop + cg / 2;
        _tagY = tagTop + th / 2;
        _bgY = (chTop + tagTop + th) / 2;
        var blockBottom = tagTop + th;
        if (_bgY + bg / 2 > blockBottom) {
            blockBottom = _bgY + bg / 2;
        }

        // Bottom: IOB and steps as low as the round screen lets their widest row go.
        var lt = lineThickness();
        _fRow = Graphics.FONT_TINY;
        var rh = glyph(_fRow, TEXT_GLYPH);
        var rw = rowWidth(dc, _fRow, "12.5U", "18,412");
        var rowBottom = _h - 1;
        while (rowBottom > _h / 2 && !fitsRound(_w / 2 - rw / 2, rowBottom, rw)) {
            rowBottom--;
        }
        _rowY = rowBottom - rh + rh / 2; // glyphs rowBottom - rh .. rowBottom
        _line2Y = rowBottom - rh - p(GAP_OUT) - lt / 2;
        _line1Y = blockBottom + p(GAP_OUT) + lt / 2;

        // Middle: the time in the biggest number font that fits between the two lines,
        // centred between them.
        var room = (_line2Y - lt / 2) - (_line1Y + lt / 2) - 2 * p(GAP_MIN);
        var timeFonts = [Graphics.FONT_NUMBER_THAI_HOT, Graphics.FONT_NUMBER_HOT, Graphics.FONT_NUMBER_MEDIUM, Graphics.FONT_NUMBER_MILD];
        _fTime = timeFonts[timeFonts.size() - 1];
        for (var t = 0; t < timeFonts.size(); t++) {
            if (glyph(timeFonts[t], NUM_GLYPH) <= room && timeWidth(dc, timeFonts[t]) <= p(146)) {
                _fTime = timeFonts[t];
                break;
            }
        }
        _timeY = (_line1Y + _line2Y) / 2;
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

    // Width of the middle row "23:58  28 Sep" with the time in the given font.
    private function timeWidth(dc as Graphics.Dc, font) as Number {
        return dc.getTextWidthInPixels("23", font) + colonWidth(font) + dc.getTextWidthInPixels("58", font)
            + p(8) + dc.getTextWidthInPixels("28 Sep", _fDate);
    }

    // The square colon: its dots with a little space either side.
    private function colonWidth(font) as Number {
        return colonDot(font) + 2 * p(2);
    }

    private function colonDot(font) as Number {
        var d = glyph(font, NUM_GLYPH) / 7;
        return d < 2 ? 2 : d;
    }

    private function tagHeight() as Number {
        return glyph(_fTag, TEXT_GLYPH) + 2 * p(2);
    }

    private function lineThickness() as Number {
        return p(2) < 2 ? 2 : p(2);
    }

    // True if a row w wide, centred, with its bottom at y, stays inside the round screen
    // (2 px in from the edge, as the fit check measures).
    private function fitsRound(x as Number, y as Number, w as Number) as Boolean {
        return inRound(x, y) && inRound(x + w, y);
    }

    // True if the point is inside the round screen, 2 px in from the edge.
    private function inRound(x as Number, y as Number) as Boolean {
        var dx = x - _w / 2.0;
        var dy = y - _h / 2.0;
        var r = _w / 2.0 - 2;
        return dx * dx + dy * dy <= r * r;
    }

    // True if the box keeps at least 2 px from the subscreen's circle.
    private function clearOfSub(x as Number, y as Number, w as Number, h as Number) as Boolean {
        var scx = _sx + _sw / 2.0;
        var scy = _sy + _sh / 2.0;
        var nx = scx < x ? x : (scx > x + w ? x + w : scx);
        var ny = scy < y ? y : (scy > y + h ? y + h : scy);
        var dx = nx - scx;
        var dy = ny - scy;
        var r = _sw / 2.0 + 2;
        return dx * dx + dy * dy >= r * r;
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
    // by the phone is drawn straight away instead of at the next minute. Most seconds
    // nothing has changed and nothing is drawn, which keeps well inside the power budget.
    function onPartialUpdate(dc as Graphics.Dc) as Void {
        if (Sched.version() == _drawn) {
            return;
        }
        onUpdate(dc);
    }

    // "10:47  21 Nov" across the middle: hours, square colon, minutes, then the date
    // sitting on the same baseline.
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
        var dStr = info.day.format("%d") + " " + info.month.toString();
        var cy = _timeY;
        var gh = glyph(_fTime, NUM_GLYPH);
        var dot = colonDot(_fTime);
        var w = dc.getTextWidthInPixels(hStr, _fTime) + colonWidth(_fTime) + dc.getTextWidthInPixels(mStr, _fTime)
            + p(8) + dc.getTextWidthInPixels(dStr, _fDate);
        var x = _w / 2 - w / 2;
        ink(dc);
        text(dc, x, cy, _fTime, NUM_GLYPH, hStr, Graphics.TEXT_JUSTIFY_LEFT);
        x += dc.getTextWidthInPixels(hStr, _fTime) + p(2);
        dc.fillRectangle(x, cy - gh / 4 - dot / 2, dot, dot);
        dc.fillRectangle(x, cy + gh / 4 - dot / 2, dot, dot);
        x += dot + p(2);
        text(dc, x, cy, _fTime, NUM_GLYPH, mStr, Graphics.TEXT_JUSTIFY_LEFT);
        x += dc.getTextWidthInPixels(mStr, _fTime) + p(8);
        var dg = glyph(_fDate, TEXT_GLYPH);
        text(dc, x, cy + gh / 2 - dg / 2, _fDate, TEXT_GLYPH, dStr, Graphics.TEXT_JUSTIFY_LEFT);
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

    // Glucose with the change and age tag beside it, the lines, IOB and steps.
    private function drawReading(dc as Graphics.Dc, nowSec as Number) as Void {
        var r = Sched.reading();
        var sgv = (r == null) ? null : r[Bg.K_SGV];
        var at = (r == null) ? null : r[Bg.K_AT];
        drawLines(dc);
        ink(dc);
        var right = glucoseRight(dc);
        if (!(sgv instanceof String) || !(at instanceof Number)) {
            text(dc, right, _bgY, Graphics.FONT_MEDIUM, TEXT_GLYPH, "---", Graphics.TEXT_JUSTIFY_RIGHT);
        } else {
            // right-aligned to where the widest reading ends, so the column beside it never moves
            text(dc, right, _bgY, _fBg, NUM_GLYPH, sgv, Graphics.TEXT_JUSTIFY_RIGHT);
            var d = r[Bg.K_DELTA];
            drawDeltaAndAge(dc, _colX, (d instanceof String) ? d : "--", nowSec - at);
        }
        var iob = (r == null) ? null : r[Bg.K_IOB];
        drawBottomRow(dc, _w / 2, _rowY, iobText(iob), stepsText());
    }

    // Where the glucose ends on the right: the widest reading ("388") from GLUCOSE_X.
    private function glucoseRight(dc as Graphics.Dc) as Number {
        return _bgX + dc.getTextWidthInPixels("388", _fBg);
    }

    // A line over the time and one under it, each a little shorter than the screen is wide there.
    private function drawLines(dc as Graphics.Dc) as Void {
        ink(dc);
        var t = lineThickness();
        var w1 = lineWidth(_line1Y);
        var w2 = lineWidth(_line2Y);
        dc.fillRectangle(_w / 2 - w1 / 2, _line1Y - t / 2, w1, t);
        dc.fillRectangle(_w / 2 - w2 / 2, _line2Y - t / 2, w2, t);
    }

    private function lineWidth(y as Number) as Number {
        var w = chord(y + lineThickness()) - p(28);
        return w > p(134) ? p(134) : w;
    }

    // To the right of the glucose: the change ("+4") over the age tag ("2 MIN"). The tag is
    // outlined while fresh and solid once LATE_SEC has passed.
    private function drawDeltaAndAge(dc as Graphics.Dc, x as Number, delta as String, ageSec as Number) as Void {
        ink(dc);
        text(dc, x, _chY, _fDelta, TEXT_GLYPH, delta, Graphics.TEXT_JUSTIFY_LEFT);
        var tag = ageText(ageSec);
        var padX = p(3);
        var tw = dc.getTextWidthInPixels(tag, _fTag) + 2 * padX;
        var th = tagHeight();
        var cy = _tagY;
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
        var bg = glyph(_fBg, NUM_GLYPH);
        var bw = dc.getTextWidthInPixels("388", _fBg);
        fails += box(dc, "glucose", _bgX, _bgY - bg / 2, bw, bg, false);
        var cg = glyph(_fDelta, TEXT_GLYPH);
        fails += box(dc, "change", _colX, _chY - cg / 2, dc.getTextWidthInPixels("-12", _fDelta), cg, false);
        var th = tagHeight();
        var tagW = dc.getTextWidthInPixels("99+ MIN", _fTag) + 2 * p(3);
        fails += box(dc, "age tag", _colX, _tagY - th / 2, tagW, th, false);
        var lt = lineThickness();
        fails += box(dc, "top line", _w / 2 - lineWidth(_line1Y) / 2, _line1Y - lt / 2, lineWidth(_line1Y), lt, false);
        var tg = glyph(_fTime, NUM_GLYPH);
        var tw = timeWidth(dc, _fTime);
        fails += box(dc, "time and date", _w / 2 - tw / 2, _timeY - tg / 2, tw, tg, false);
        fails += box(dc, "bottom line", _w / 2 - lineWidth(_line2Y) / 2, _line2Y - lt / 2, lineWidth(_line2Y), lt, false);
        var bw2 = rowWidth(dc, _fRow, "12.5U", "18,412");
        var bh = glyph(_fRow, TEXT_GLYPH);
        fails += box(dc, "iob+steps", _w / 2 - bw2 / 2, _rowY - bh / 2, bw2, bh, false);
        var hg = glyph(_fHr, NUM_GLYPH);
        var hw = dc.getTextWidthInPixels("188", _fHr);
        fails += box(dc, "heart rate", _sx + _sw / 2 - hw / 2, _sy + _sh / 2 - _sh / 10 - hg / 2, hw, hg, true);
        var lg = glyph(_fLabel, TEXT_GLYPH);
        var lw = dc.getTextWidthInPixels("BPM", _fLabel);
        fails += box(dc, "BPM", _sx + _sw / 2 - lw / 2, _sy + _sh / 2 + _sh / 4 - lg / 2, lw, lg, true);
        System.println("rows: glucose " + _bgY + " change " + _chY + " tag " + _tagY + " line " + _line1Y + " time " + _timeY
            + " line " + _line2Y + " iob+steps " + _rowY + "; time font ascent " + Graphics.getFontAscent(_fTime)
            + ", row font ascent " + Graphics.getFontAscent(_fRow));
        // top to bottom, nothing may touch: glucose block, top line, time, bottom line, IOB+steps
        var blockTop = _bgY - bg / 2 < _chY - cg / 2 ? _bgY - bg / 2 : _chY - cg / 2;
        var blockBottom = _bgY + bg / 2 > _tagY + th / 2 ? _bgY + bg / 2 : _tagY + th / 2;
        var tops = [blockTop, _line1Y - lt / 2, _timeY - tg / 2, _line2Y - lt / 2, _rowY - bh / 2];
        var bottoms = [blockBottom, _line1Y + lt / 2, _timeY + tg / 2, _line2Y + lt / 2, _rowY + bh / 2];
        var rows = ["glucose block", "top line", "time and date", "bottom line", "iob+steps"];
        for (var i = 1; i < 5; i++) {
            if (tops[i] - bottoms[i - 1] < 2) {
                fails++;
                System.println("FIT FAIL " + rows[i - 1] + " and " + rows[i] + " overlap (gap " + (tops[i] - bottoms[i - 1]) + " px)");
            }
        }
        // the change and the tag must not touch each other, nor the glucose
        if (_tagY - th / 2 - (_chY + cg / 2) < 2) {
            fails++;
            System.println("FIT FAIL change and age tag overlap");
        }
        if (_colX - (_bgX + bw) < 3) {
            fails++;
            System.println("FIT FAIL glucose runs into the change / age column");
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
