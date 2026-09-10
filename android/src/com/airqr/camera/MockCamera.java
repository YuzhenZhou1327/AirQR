package com.airqr.camera;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * Mock camera for self-test: converts a Bitmap (the test QR image) into NV21
 * frames on a BACKGROUND thread and feeds the SAME analyzer path as the real
 * camera. v1.6 bug: ticks ran on the main looper — each analyze() (3-stage
 * multi-barcode decode) took longer than the 200ms tick, so the UI froze.
 * Now: dedicated HandlerThread + analyzer busy-skip + scaled-down bitmap.
 */
public final class MockCamera {
    public interface FrameCallback {
        void onFrame(byte[] nv21, int width, int height);
    }

    private final Bitmap bitmap;
    private final FrameCallback callback;
    private final HandlerThread thread;
    private final Handler handler;
    private volatile boolean running;
    private volatile boolean busy; // previous frame still decoding → skip

    public MockCamera(Bitmap bitmap, FrameCallback callback) {
        // decode cost ~ O(pixels): cap the mock image to 480px wide
        int maxW = 480;
        this.bitmap = (bitmap.getWidth() > maxW)
                ? Bitmap.createScaledBitmap(bitmap, maxW,
                    Math.round((float) bitmap.getHeight() * maxW / bitmap.getWidth()), true)
                : bitmap;
        this.callback = callback;
        thread = new HandlerThread("airqr-mock");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public void start() {
        running = true;
        handler.post(this::tick);
    }

    public void stop() {
        running = false;
        if (thread != null && thread.isAlive()) {
            thread.quitSafely();
        }
    }

    private void tick() {
        if (!running) return;
        if (busy) {
            handler.postDelayed(this::tick, 100);
            return;
        }
        busy = true;
        try {
            int w = bitmap.getWidth(), h = bitmap.getHeight();
            byte[] nv21 = bitmapToNV21(bitmap);
            callback.onFrame(nv21, w, h);
        } finally {
            busy = false;
        }
        handler.postDelayed(this::tick, 300); // ~3 fps, off the UI thread
    }

    /** ARGB bitmap → NV21. Delegates to pure-Java Nv21 (desktop-testable). */
    static byte[] bitmapToNV21(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] argb = new int[w * h];
        bmp.getPixels(argb, 0, w, 0, 0, w, h);
        return Nv21.fromArgb(argb, w, h);
    }
}
