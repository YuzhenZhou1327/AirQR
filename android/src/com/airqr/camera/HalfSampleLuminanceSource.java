package com.airqr.camera;

import com.google.zxing.LuminanceSource;

/**
 * Zero-copy 1/2 subsampled view of an NV21 Y plane.
 *
 * Decoding at half resolution is 4-17x faster (measured) and still resolves
 * v25+ codes that fill a reasonable part of the frame; the ladder in
 * QrGridAnalyzer escalates to full resolution when it stops working.
 * Pure Java (no Android imports) so the desktop harness can test it.
 */
public final class HalfSampleLuminanceSource extends LuminanceSource {
    private final byte[] yuv;
    private final int dataW;
    private final int dataH;
    private final int left; // subsampled coords
    private final int top;

    public HalfSampleLuminanceSource(byte[] yuv, int dataW, int dataH) {
        this(yuv, dataW, dataH, 0, 0, dataW / 2, dataH / 2);
    }

    private HalfSampleLuminanceSource(byte[] yuv, int dataW, int dataH,
                                      int left, int top, int sw, int sh) {
        super(sw, sh);
        if (sw < 1 || sh < 1) throw new IllegalArgumentException("empty view");
        if ((left + sw) * 2 > dataW || (top + sh) * 2 > dataH) {
            throw new IllegalArgumentException("view out of bounds");
        }
        this.yuv = yuv;
        this.dataW = dataW;
        this.dataH = dataH;
        this.left = left;
        this.top = top;
    }

    @Override
    public byte[] getRow(int y, byte[] row) {
        if (y < 0 || y >= getHeight()) throw new IllegalArgumentException("row " + y);
        if (row == null || row.length < getWidth()) row = new byte[getWidth()];
        int srcY = (top + y) * 2;
        int base = srcY * dataW + left * 2;
        for (int x = 0; x < getWidth(); x++) {
            row[x] = yuv[base + x * 2];
        }
        return row;
    }

    @Override
    public byte[] getMatrix() {
        int w = getWidth(), h = getHeight();
        byte[] out = new byte[w * h];
        int p = 0;
        for (int y = 0; y < h; y++) {
            int base = ((top + y) * 2) * dataW + left * 2;
            for (int x = 0; x < w; x++) {
                out[p++] = yuv[base + x * 2];
            }
        }
        return out;
    }

    @Override
    public boolean isCropSupported() {
        return true;
    }

    @Override
    public LuminanceSource crop(int l, int t, int w, int h) {
        int nl = Math.max(0, left + l);
        int nt = Math.max(0, top + t);
        int nw = Math.min(w, getWidth() - (nl - left));
        int nh = Math.min(h, getHeight() - (nt - top));
        return new HalfSampleLuminanceSource(yuv, dataW, dataH, nl, nt, nw, nh);
    }

    @Override
    public boolean isRotateSupported() {
        return false; // truthful: QR decode is rotation-invariant, no rotate needed
    }
}
