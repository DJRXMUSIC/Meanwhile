package app.meanwhile.bridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.meanwhile.bridge.core.Reading;

/** Minimal last-hours chart: target band, a dot per reading, hour ticks. */
final class GlucoseChart extends View {

    static final long WINDOW_MS = 3 * 3_600_000L;
    private static final int LOW = 70;
    private static final int HIGH = 180;

    private final Paint band = new Paint();
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Reading> readings = new ArrayList<>();
    private long now;

    GlucoseChart(Context context, int textColor) {
        super(context);
        band.setColor(Color.argb(40, 46, 125, 107));
        line.setColor(Color.argb(90, Color.red(textColor), Color.green(textColor), Color.blue(textColor)));
        line.setStrokeWidth(dp(1));
        label.setColor(textColor);
        label.setAlpha(150);
        label.setTextSize(dp(10));
        setContentDescription("Glucose, last 3 hours");
    }

    void setReadings(List<Reading> newestFirst, long now) {
        this.readings = newestFirst;
        this.now = now;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (int) dp(120));
    }

    @Override
    protected void onDraw(Canvas c) {
        final float w = getWidth();
        final float h = getHeight();
        final float left = dp(28);
        int max = 250;
        for (Reading r : readings) max = Math.max(max, r.mgdl + 10);
        final float lo = 40;
        final float hi = Math.min(max, 410);
        c.drawRect(left, y(HIGH, lo, hi, h), w, y(LOW, lo, hi, h), band);
        for (int v : new int[]{LOW, HIGH}) {
            c.drawText(String.valueOf(v), 0, y(v, lo, hi, h) + dp(4), label);
        }
        for (int hr = 1; hr < 3; hr++) {
            final float x = x(now - hr * 3_600_000L, left, w);
            c.drawLine(x, 0, x, h, line);
            c.drawText(String.format(Locale.ROOT, "-%dh", hr), x + dp(2), h - dp(2), label);
        }
        if (readings.isEmpty()) {
            c.drawText("no readings in the last 3 hours", left + dp(8), h / 2, label);
            return;
        }
        for (Reading r : readings) {
            if (now - r.timestamp > WINDOW_MS) continue;
            dot.setColor(r.mgdl < LOW ? Color.rgb(211, 47, 47) : r.mgdl > HIGH ? Color.rgb(239, 152, 0)
                    : Color.rgb(46, 160, 120));
            c.drawCircle(x(r.timestamp, left, w), y(r.mgdl, lo, hi, h), dp(3), dot);
        }
    }

    private float x(long t, float left, float w) {
        return left + (w - left - dp(4)) * (1f - (now - t) / (float) WINDOW_MS);
    }

    private float y(float v, float lo, float hi, float h) {
        final float clamped = Math.max(lo, Math.min(hi, v));
        return dp(6) + (h - dp(20)) * (1f - (clamped - lo) / (hi - lo));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
