import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// Draws 0-4 and 5-9 in FONT_NUMBER_THAI_HOT, apart from each other, for CI to read.
class GlyphsApp extends Application.AppBase {
    function initialize() {
        AppBase.initialize();
    }

    function getInitialView() {
        return [new GlyphsView()];
    }
}

class GlyphsView extends WatchUi.WatchFace {
    function initialize() {
        WatchFace.initialize();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var f = Graphics.FONT_NUMBER_THAI_HOT;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        var asc = Graphics.getFontAscent(f);
        for (var i = 0; i < 10; i++) {
            var x = 10 + (i % 5) * 30;
            var top = (i < 5 ? 60 : 112) - asc;
            dc.drawText(x, top, f, i.toString(), Graphics.TEXT_JUSTIFY_LEFT);
            System.println("glyph " + i + " x " + x + " top " + top + " width " + dc.getTextWidthInPixels(i.toString(), f));
        }
        System.println("glyph ascent " + asc + " height " + Graphics.getFontHeight(f) + " descent " + Graphics.getFontDescent(f));
    }
}
