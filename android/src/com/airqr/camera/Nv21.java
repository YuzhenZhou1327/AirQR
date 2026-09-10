package com.airqr.camera;

/**
 * Pure-Java ARGB → NV21 conversion. No Android imports, so the exact code
 * that ships in the APK is unit-testable on a desktop JVM against the same
 * zxing core jar (android/libs/core-3.5.3.jar).
 */
public final class Nv21 {
    private Nv21() {}

    /** ARGB int array (0xAARRGGBB, row-major) → NV21 bytes. w,h must be even. */
    public static byte[] fromArgb(int[] argb, int w, int h) {
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
