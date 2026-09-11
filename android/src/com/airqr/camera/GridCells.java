package com.airqr.camera;

/**
 * Splits a landscape preview buffer into sender-grid cells (PROTOCOL §2
 * GridGeom, row-major) with an overlap margin, so codes straddling a split
 * line are still wholly inside one cell.
 *
 * Pure Java (no Android imports) so the desktop harness can test it.
 */
public final class GridCells {
    private GridCells() { }

    /**
     * @param w,h      buffer size (landscape, as decoded)
     * @param grid     manifest grid (1/2/4/6/8); 0/unknown/invalid → 2x2
     *                 (sender default), the full-frame ladder rung covers misses
     * @param overlapPct expansion per side in percent of cell size (suggest 8)
     * @return array of {x,y,width,height} in buffer coords, row-major
     */
    public static int[][] split(int w, int h, int grid, int overlapPct) {
        int rows, cols;
        switch (grid) {
            case 1: rows = 1; cols = 1; break;
            case 2: rows = 1; cols = 2; break;
            case 4: rows = 2; cols = 2; break;
            case 6: rows = 2; cols = 3; break;
            case 8: rows = 2; cols = 4; break;
            default: rows = 2; cols = 2; break; // unknown → sender default
        }
        int[][] out = new int[rows * cols][];
        int cw = w / cols, ch = h / rows;
        int ox = cw * overlapPct / 100, oy = ch * overlapPct / 100;
        int i = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int x0 = Math.max(0, c * cw - ox);
                int y0 = Math.max(0, r * ch - oy);
                int x1 = Math.min(w, (c + 1) * cw + ox);
                int y1 = Math.min(h, (r + 1) * ch + oy);
                out[i++] = new int[]{x0, y0, x1 - x0, y1 - y0};
            }
        }
        return out;
    }

    /** Variance of luminance (sharpness proxy); reused row buffer, no big alloc. */
    public static double lumaVariance(com.google.zxing.LuminanceSource src) {
        int w = src.getWidth(), h = src.getHeight();
        byte[] row = new byte[w];
        long n = 0, sum = 0, sum2 = 0;
        int stepY = Math.max(1, h / 180); // ~180 rows sampled max
        for (int y = 0; y < h; y += stepY) {
            try {
                src.getRow(y, row);
            } catch (Exception e) {
                return Double.MAX_VALUE; // unreadable → don't gate
            }
            for (int x = 0; x < w; x += 2) {
                int v = row[x] & 0xFF;
                n++;
                sum += v;
                sum2 += (long) v * v;
            }
        }
        if (n == 0) return Double.MAX_VALUE;
        double mean = (double) sum / n;
        return (double) sum2 / n - mean * mean;
    }
}
