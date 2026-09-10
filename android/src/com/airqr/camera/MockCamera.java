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

    /** ARGB bitmap → NV21 (BT.601 full-range, standard camera conversion). */
    static byte[] bitmapToNV21(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] argb = new int[w * h];
        bmp.getPixels(argb, 0, w, 0, 0, w, h);
        byte[] out = new byte[w * h * 3 / 2];
        int yIdx = 0, uvIdx = w * h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int px = argb[y * w + x];
                int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
                int yv = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                out[yIdx++] = (byte) (yv > 255 ? 255 : (yv < 0 ? 0 : yv));
                if ((y & 1) == 0 && (x & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    out[uvIdx++] = (byte) (v > 255 ? 255 : (v < 0 ? 0 : v));
                    out[uvIdx++] = (byte) (u > 255 ? 255 : (u < 0 ? 0 : u));
                }
            }
        }
        return out;
    }
}
