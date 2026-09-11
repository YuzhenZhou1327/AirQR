package com.airqr.camera;

import android.annotation.SuppressLint;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.TextureView;

import java.util.List;

/**
 * Camera pipeline v2 (v1.5): TextureView with aspect-correct letterbox
 * transform (v1.4 used a fullscreen SurfaceView — 4:3 preview stretched to
 * fill ~20:9 screen), tap-to-focus, torch.
 * Uses the classic android.hardware.Camera API (no-Gradle, max coverage).
 */
public final class CameraController {

    public interface FrameCallback {
        void onFrame(byte[] nv21, int width, int height);
    }

    /** Overlay that shows the decoder-visible content rect (framing guide). */
    public interface GuideOverlay {
        void setContentRect(float left, float top, float right, float bottom);
    }

    private static final String TAG = "AirQR";

    private android.hardware.Camera camera;
    private final TextureView textureView;
    private final FrameCallback callback;
    private int previewW = 0, previewH = 0;
    private volatile boolean analyzing = true;
    /** Display rotation for setDisplayOrientation (0/90/180/270), driven by hold. */
    private int displayOrientation = 90;
    private GuideOverlay guide;
    // letterbox params (for tap-to-focus mapping + guide); set in applyLetterbox
    private float lbScale = 0, lbTx = 0, lbTy = 0;
    private int lbImgW = 0, lbImgH = 0, lbViewW = 0, lbViewH = 0;

    public void setDisplayOrientation(int degrees) {
        if (degrees == 0 || degrees == 90 || degrees == 180 || degrees == 270) {
            displayOrientation = degrees;
        }
    }

    public void setGuide(GuideOverlay guide) {
        this.guide = guide;
    }

    /** Re-fit the preview transform to the current view geometry (e.g. after
     * activity rotation). Idempotent; also re-forwards the content rect. */
    public void refreshLetterbox() {
        if (camera != null) applyLetterbox();
    }

    public CameraController(TextureView textureView, FrameCallback callback) {
        this.textureView = textureView;
        this.callback = callback;
    }

    @SuppressLint("Deprecation")
    public void start() throws SecurityException {
        camera = android.hardware.Camera.open();
        android.hardware.Camera.Parameters p = camera.getParameters();
        List<android.hardware.Camera.Size> sizes = p.getSupportedPreviewSizes();
        // Prefer 1280x720 (16:9); fallback to the largest 16:9, else largest 4:3.
        android.hardware.Camera.Size best = pickSize(sizes);
        previewW = best.width;
        previewH = best.height;
        p.setPreviewSize(previewW, previewH);
        p.setPreviewFormat(android.graphics.ImageFormat.NV21);
        try {
            p.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        } catch (Exception ignore) { }
        // Screens are bright light sources: a mild negative bias fights
        // night-time blowout while AE keeps adapting around it.
        try {
            if (p.getMinExposureCompensation() < 0) {
                p.setExposureCompensation(Math.max(p.getMinExposureCompensation(), -1));
                android.util.Log.i(TAG, "exposure compensation set to "
                        + p.getExposureCompensation());
            }
        } catch (Exception ignore) { }
        camera.setParameters(p);

        final int w = previewW, h = previewH;
        camera.setPreviewCallbackWithBuffer((data, cam) -> {
            if (!analyzing) {
                cam.addCallbackBuffer(data);
                return;
            }
            callback.onFrame(data, w, h);
            cam.addCallbackBuffer(data);
        });

        try {
            camera.setPreviewTexture(textureView.getSurfaceTexture());
        } catch (Exception e) {
            throw new RuntimeException("setPreviewTexture", e);
        }
        camera.setDisplayOrientation(displayOrientation); // portrait default 90
        int bufSize = w * h * 3 / 2;
        for (int i = 0; i < 3; i++) {
            camera.addCallbackBuffer(new byte[bufSize]);
        }
        camera.startPreview();
        applyLetterbox();
    }

    /** Picks the largest-area 16:9 preview size (sharp preview + more px per
     * module for decode); falls back to largest area of any aspect. */
    private static android.hardware.Camera.Size pickSize(List<android.hardware.Camera.Size> sizes) {
        android.hardware.Camera.Size best = sizes.get(0);
        double want = 16.0 / 9.0;
        // pass 1: 16:9 within 2% tolerance, max area wins
        boolean found = false;
        for (android.hardware.Camera.Size s : sizes) {
            double aspect = (double) s.width / s.height;
            if (Math.abs(aspect - want) / want > 0.02) continue;
            if (!found || s.width * s.height > best.width * best.height) {
                best = s;
                found = true;
            }
        }
        // pass 2: no 16:9 at all → largest area overall
        if (!found) {
            for (android.hardware.Camera.Size s : sizes) {
                if (s.width * s.height > best.width * best.height) best = s;
            }
        }
        StringBuilder sb = new StringBuilder("preview sizes:");
        for (android.hardware.Camera.Size s : sizes) sb.append(' ').append(s.width).append('x').append(s.height);
        android.util.Log.i(TAG, sb + " → pick " + best.width + "x" + best.height);
        return best;
    }

    /**
     * Letterbox: the preview stream arrives rotated by setDisplayOrientation —
     * displayed size = ph × pw for 90/270, pw × ph for 0/180. Scale uniformly
     * to fit the view, centered: no stretch. Stores params for tap-to-focus
     * mapping and forwards the content rect to the framing guide.
     */
    private void applyLetterbox() {
        final int pw = previewW, ph = previewH;
        final int disp = displayOrientation;
        textureView.post(() -> {
            int vw = textureView.getWidth(), vh = textureView.getHeight();
            if (vw == 0 || vh == 0) return;
            float dispW = (disp == 0 || disp == 180) ? pw : ph;
            float dispH = (disp == 0 || disp == 180) ? ph : pw;
            float scale = Math.min(vw / dispW, vh / dispH);
            float tx = (vw - dispW * scale) / 2f, ty = (vh - dispH * scale) / 2f;
            android.util.Log.i(TAG, "letterbox view=" + vw + "x" + vh
                    + " content=" + dispW + "x" + dispH + " scale=" + scale
                    + " disp=" + disp);
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate(tx, ty);
            textureView.setTransform(m);
            lbScale = scale;
            lbTx = tx;
            lbTy = ty;
            lbImgW = (int) dispW;
            lbImgH = (int) dispH;
            lbViewW = vw;
            lbViewH = vh;
            if (guide != null) {
                guide.setContentRect(tx, ty, tx + dispW * scale, ty + dispH * scale);
            }
        });
    }

    public void stop() {
        analyzing = false;
        if (camera != null) {
            camera.setPreviewCallbackWithBuffer(null);
            camera.stopPreview();
            camera.release();
            camera = null;
        }
        // Reset the view transform so no stale orientation geometry can leak
        // into the next start (half-live/half-black preview class of bugs).
        try {
            textureView.setTransform(null);
        } catch (Exception ignore) { }
    }

    /** Tap-to-focus at view-relative (x,y) in [0,1]. */
    @SuppressLint("Deprecation")
    public void focusAt(float normX, float normY) {
        if (camera == null) return;
        try {
            android.graphics.Point c = focusPoint(normX, normY);
            camera.cancelAutoFocus();
            android.hardware.Camera.Parameters p = camera.getParameters();
            if (p.getMaxNumFocusAreas() > 0) {
                android.hardware.Camera.Area area = new android.hardware.Camera.Area(
                        new android.graphics.Rect(c.x - 100, c.y - 100, c.x + 100, c.y + 100), 800);
                p.setFocusAreas(java.util.Collections.singletonList(area));
            }
            p.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_AUTO);
            camera.setParameters(p);
            camera.autoFocus((ok, cam) -> {
                try {
                    android.hardware.Camera.Parameters pp = cam.getParameters();
                    pp.setFocusAreas(null);
                    pp.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                    cam.setParameters(pp);
                } catch (Exception ignore) { }
            });
        } catch (Exception ignore) { /* some devices reject focus areas */ }
    }

    /**
     * Maps a view-normalized tap to sensor coords ([-1000,1000]) for the
     * current display orientation.
     *
     * Derivation (do NOT "simplify" without re-verifying on device):
     * view → image via inverse letterbox (clamped), image → buffer by
     * undoing the display rotation. Increasing setDisplayOrientation rotates
     * the image visually clockwise, so buffer = image rotated CCW by D/90
     * quarter-turns. Anchor: at D=90 this yields buffer=(iy,1-ix), which is
     * exactly the old field-tested formula (bx=viewY, by=1-viewX on a full
     * view). buffer → sensor is linear (same orientation, origin top-left).
     */
    private android.graphics.Point focusPoint(float normX, float normY) {
        float ix = normX, iy = normY;
        if (lbViewW > 0 && lbImgW > 0 && lbScale > 0) {
            ix = (normX * lbViewW - lbTx) / (lbImgW * lbScale);
            iy = (normY * lbViewH - lbTy) / (lbImgH * lbScale);
            ix = Math.max(0f, Math.min(1f, ix));
            iy = Math.max(0f, Math.min(1f, iy));
        }
        int turns = (((displayOrientation / 90) % 4) + 4) % 4;
        float bx = ix, by = iy;
        for (int i = 0; i < turns; i++) {
            float nx = by, ny = 1f - bx; // one visual-CCW quarter turn
            bx = nx;
            by = ny;
        }
        int sx = (int) (bx * 2000 - 1000);
        int sy = (int) (by * 2000 - 1000);
        sx = Math.max(-1000, Math.min(1000, sx));
        sy = Math.max(-1000, Math.min(1000, sy));
        return new android.graphics.Point(sx, sy);
    }

    public void setTorch(boolean on) {
        if (camera == null) return;
        try {
            android.hardware.Camera.Parameters p = camera.getParameters();
            p.setFlashMode(on ? android.hardware.Camera.Parameters.FLASH_MODE_TORCH
                    : android.hardware.Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(p);
        } catch (Exception ignore) { }
    }
}
