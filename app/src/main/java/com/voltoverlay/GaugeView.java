package com.voltoverlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** A big round gauge: a 270 degree ring that fills with the battery percentage. */
public class GaugeView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float d;
    private int pct = -1;
    private String line1 = "";
    private String line2 = "";

    public GaugeView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;

        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeWidth(16 * d);
        track.setColor(0xFF2A303B);

        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setStrokeWidth(16 * d);
        arc.setColor(0xFF4CAF50);

        big.setColor(0xFFEDEFF3);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTextSize(56 * d);
        big.setTypeface(Typeface.DEFAULT_BOLD);

        small.setColor(0xFF9AA3B2);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(15 * d);
    }

    void set(int percent, int color, String first, String second) {
        pct = percent;
        arc.setColor(color);
        line1 = first;
        line2 = second;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(250 * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float r = Math.min(w, h) / 2f - 14 * d;
        float cx = w / 2f;
        float cy = h / 2f + 4 * d;
        box.set(cx - r, cy - r, cx + r, cy + r);

        c.drawArc(box, 135f, 270f, false, track);
        if (pct > 0) c.drawArc(box, 135f, 270f * Math.min(100, pct) / 100f, false, arc);

        c.drawText(pct >= 0 ? pct + "%" : "--", cx, cy + 10 * d, big);
        c.drawText(line1, cx, cy + 42 * d, small);
        c.drawText(line2, cx, cy + 64 * d, small);
    }
}
