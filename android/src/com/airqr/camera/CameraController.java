package com.airqr.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.ImageReader;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal camera2-style capture pipeline via the legacy ImageReader API on a
 * SurfaceView preview: ImageReader acts as the analysis target; the preview
 * Surface renders simultaneously. Chosen over CameraX/MLKit to keep the APK
 * dependency-free (no-Gradle chain).
 *
 * Framing: camera2 API needs API 21+; we use the classic android.hardware
 * camera API for maximal device coverage and simpler exposure control.
 */
public final class CameraController {

    public interface FrameCallback {
        /** Called on the decoder thread with NV21 data; return after processing. */
        void onFrame(byte[] nv21, int width, int height);
    }

    private android.hardware.Camera camera;
    private ImageReader reader; // unused when using preview-callback path
    private final SurfaceView previewView;
    private final FrameCallback callback;
    private final int previewWidth = 1280;
    private final int previewHeight = 960;
    private volatile boolean analyzing = true;
    private final List<Integer> supportedZoom = new ArrayList<>();

    public CameraController(SurfaceView previewView, FrameCallback callback) {
        this.previewView = previewView;
        this.callback = callback;
    }

    @SuppressLint("Deprecation")
    public void start() throws SecurityException {
        camera = android.hardware.Camera.open();
        android.hardware.Camera.Parameters p = camera.getParameters();
        // pick a preview size close to 1280x960 (4:3) for decode quality
        List<android.hardware.Camera.Size> sizes = p.getSupportedPreviewSizes();
        android.hardware.Camera.Size best = sizes.get(0);
        int bestScore = Integer.MAX_VALUE;
        for (android.hardware.Camera.Size s : sizes) {
            int score = Math.abs(s.width - previewWidth) + Math.abs(s.height - previewHeight);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        p.setPreviewSize(best.width, best.height);
        p.setPreviewFormat(ImageFormat.NV21);
        try {
            p.setFocusMode(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        } catch (Exception ignore) { /* some devices lack it */ }
        camera.setParameters(p);

        final int w = best.width, h = best.height;
        camera.setPreviewCallbackWithBuffer((data, cam) -> {
            if (!analyzing) {
                cam.addCallbackBuffer(data);
                return;
            }
            // decode off the camera thread? For v1, process inline: the callback
            // thread is dedicated and the decoder is fast (ms per frame).
            callback.onFrame(data, w, h);
            cam.addCallbackBuffer(data);
        });
        SurfaceHolder holder = previewView.getHolder();
        try {
            camera.setPreviewDisplay(holder);
        } catch (Exception e) {
            throw new RuntimeException("setPreviewDisplay", e);
        }
        camera.setDisplayOrientation(90); // portrait
        int bufSize = w * h * 3 / 2;
        for (int i = 0; i < 3; i++) {
            camera.addCallbackBuffer(new byte[bufSize]);
        }
        camera.startPreview();
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

    public void setTorch(boolean on) {
        if (camera == null) return;
        android.hardware.Camera.Parameters p = camera.getParameters();
        p.setFlashMode(on ? android.hardware.Camera.Parameters.FLASH_MODE_TORCH
                : android.hardware.Camera.Parameters.FLASH_MODE_OFF);
        camera.setParameters(p);
    }
}
