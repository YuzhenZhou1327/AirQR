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

    private android.hardware.Camera camera;
    private final TextureView textureView;
    private final FrameCallback callback;
    private int previewW = 0, previewH = 0;
    private volatile boolean analyzing = true;

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
        camera.setDisplayOrientation(90); // portrait
        int bufSize = w * h * 3 / 2;
        for (int i = 0; i < 3; i++) {
            camera.addCallbackBuffer(new byte[bufSize]);
        }
        camera.startPreview();
        applyLetterbox();
    }

    /** Picks the preview size with aspect closest to 16:9, area closest to 1280x720. */
    private static android.hardware.Camera.Size pickSize(List<android.hardware.Camera.Size> sizes) {
        android.hardware.Camera.Size best = sizes.get(0);
        double want = 16.0 / 9.0;
        double bestScore = Double.MAX_VALUE;
        for (android.hardware.Camera.Size s : sizes) {
            double aspect = (double) s.width / s.height;
            double aspectScore = Math.abs(aspect - want) * 1000; // aspect dominates
            double areaScore = Math.abs(Math.log((double)(s.width * s.height) / (1280.0 * 720.0)));
            double score = aspectScore + areaScore;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    /**
     * Letterbox: the preview stream arrives already rotated by
     * setDisplayOrientation(90) — displayed size = ph × pw (portrait).
     * Scale uniformly to fit the view, centered: no stretch.
     */
    private void applyLetterbox() {
        final int pw = previewW, ph = previewH;
        textureView.post(() -> {
            int vw = textureView.getWidth(), vh = textureView.getHeight();
            if (vw == 0 || vh == 0) return;
            float dispW = ph, dispH = pw; // rotated display size
            float scale = Math.min(vw / dispW, vh / dispH);
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((vw - dispW * scale) / 2f, (vh - dispH * scale) / 2f);
            textureView.setTransform(m);
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
     * Maps a view-normalized tap to sensor coords with 90° display rotation:
     * sensorX = viewY, sensorY = viewW - viewX. Input: (normX,normY) in [0,1]
     * of the view; returns the sensor-space coordinate in [-1000,1000].
     */
    private android.graphics.Point focusPoint(float normX, float normY) {
        float sx = normY;                 // sensor x from view y
        float sy = 1f - normX;            // sensor y from view x
        return new android.graphics.Point(
                (int) (sx * 2000 - 1000), (int) (sy * 2000 - 1000));
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
