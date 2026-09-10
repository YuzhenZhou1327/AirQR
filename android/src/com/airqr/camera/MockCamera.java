package com.airqr.camera;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

/**
 * Mock camera for self-test: converts a Bitmap (the test QR image or a
 * rendered frame) into NV21 frames and feeds the SAME analyzer path as the
 * real camera. Decouples "camera hardware" from "decode pipeline":
 *   self-test passes  → pipeline OK, suspect camera/focus hardware
 *   self-test fails   → bug inside the APK (and logcat shows the stage)
 */
public final class MockCamera {
    public interface FrameCallback {
        void onFrame(byte[] nv21, int width, int height);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Bitmap bitmap;
    private final FrameCallback callback;
    private boolean running;
    private int ticks;

    public MockCamera(Bitmap bitmap, FrameCallback callback) {
        this.bitmap = bitmap;
        this.callback = callback;
    }

    /** Starts emitting the bitmap as NV21 frames at ~5 fps. */
    public void start() {
        running = true;
        handler.postDelayed(this::tick, 100);
    }

    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void tick() {
        if (!running) return;
        ticks++;
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        byte[] nv21 = bitmapToNV21(bitmap);
        callback.onFrame(nv21, w, h);
        handler.postDelayed(this::tick, 200); // ~5 fps
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
                    out[uvIdx++] = (byte) (v > 255 ? 255 : (v < 0 ? 0 : v)); // V first
                    out[uvIdx++] = (byte) (u > 255 ? 255 : (u < 0 ? 0 : u)); // then U
                }
            }
        }
        return out;
    }
}
