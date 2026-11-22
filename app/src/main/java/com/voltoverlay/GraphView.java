package com.voltoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Line graph of the battery percentage over the last few hours. Green while charging, red when low. */
public class GraphView extends View {

    private static final int BLUE = 0xFF42A5F5;
    private static final int GREEN = 0xFF4CAF50;
    private static final int RED = 0xFFE53935;

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint warn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint yText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint leftText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rightText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private int hours = 6;

    public GraphView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;

        grid.setColor(0x33FFFFFF);
        grid.setStrokeWidth(1 * d);

        line.setStrokeWidth(2.5f * d);
        line.setStrokeCap(Paint.Cap.ROUND);

        warn.setColor(0x66E53935);
        warn.setStrokeWidth(1 * d);

        yText.setColor(0xFF9AA3B2);
        yText.setTextSize(11 * d);
        yText.setTextAlign(Paint.Align.RIGHT);

        leftText.setColor(0xFF9AA3B2);
        leftText.setTextSize(11 * d);
        leftText.setTextAlign(Paint.Align.LEFT);

        rightText.setColor(0xFF9AA3B2);
        rightText.setTextSize(11 * d);
        rightText.setTextAlign(Paint.Align.RIGHT);
    }

    void setHours(int h) {
        hours = h;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(190 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float left = 34 * d;
        float right = w - 6 * d;
        float top = 8 * d;
        float bottom = h - 22 * d;

        for (int p = 0; p <= 100; p += 25) {
            float y = bottom - (bottom - top) * p / 100f;
            c.drawLine(left, y, right, y, grid);
            c.drawText(String.valueOf(p), left - 5 * d, y + 4 * d, yText);
        }
        float y15 = bottom - (bottom - top) * 15 / 100f;
        c.drawLine(left, y15, right, y15, warn);

        long now = System.currentTimeMillis();
        long span = hours * 3600000L;
        long start = now - span;

        float prevX = 0;
        float prevY = 0;
        long prevT = 0;
        boolean havePrev = false;
        for (int i = 0; i < History.count; i++) {
            int k = History.index(i);
            long t = History.time[k];
            if (t < start || t > now + 60000L) continue;
            float x = left + (right - left) * (t - start) / (float) span;
            float y = bottom - (bottom - top) * Math.max(0, Math.min(100, History.pct[k])) / 100f;
            if (havePrev && t - prevT <= 15 * 60000L) {
                int color = BLUE;
                if (History.plug[k]) color = GREEN;
                else if (History.pct[k] <= 15) color = RED;
                line.setColor(color);
                c.drawLine(prevX, prevY, x, y, line);
            }
            prevX = x;
            prevY = y;
            prevT = t;
            havePrev = true;
        }

        c.drawText("-" + hours + " h", left, h - 5 * d, leftText);
        c.drawText("now", right, h - 5 * d, rightText);
    }
}
