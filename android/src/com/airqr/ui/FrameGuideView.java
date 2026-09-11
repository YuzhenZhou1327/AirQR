package com.airqr.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Framing guide overlay: dashed 16:9 rect (sender frames are 16:9 landscape)
 * + hint text. Bigger in-frame codes decode 4-17x faster, so guiding the
 * user to fill the box IS a decode-speed optimization. Decode path itself
 * still scans the full frame (no ROI risk).
 */
public final class FrameGuideView extends View implements com.airqr.camera.CameraController.GuideOverlay {
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    // decoder-visible content rect in view coords (set by CameraController); NaN = unset
    private float rl = Float.NaN, rt, rr, rb;

    public FrameGuideView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(4f);
        box.setColor(0xFF4CAF50);
        box.setPathEffect(new DashPathEffect(new float[]{24f, 16f}, 0));
        text.setColor(0xFF4CAF50);
        text.setTextSize(44f);
        text.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public void setContentRect(float l, float t, float r, float b) {
        rl = l;
        rt = t;
        rr = r;
        rb = b;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float l, t, bw, bh;
        if (!Float.isNaN(rl)) {
            l = rl;
            t = rt;
            bw = rr - rl;
            bh = rb - rt;
        } else {
            float w = getWidth(), h = getHeight();
            bw = w * 0.88f;
            bh = bw * 9f / 16f;
            if (bh > h * 0.6f) {
                bh = h * 0.6f;
                bw = bh * 16f / 9f;
            }
            l = (w - bw) / 2f;
            t = (h - bh) / 2f;
        }
        c.drawRoundRect(l, t, l + bw, t + bh, 18f, 18f, box);
        c.drawText("把电脑屏幕装进框内", l + bw / 2f, t + bh + 70f, text);
    }
}
