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
        int[] rc = rowsColsFor(grid);
        return splitRC(w, h, rc[0], rc[1], overlapPct);
    }

    /** Maps manifest grid → {rows, cols} (PROTOCOL §2 GridGeom). */
    public static int[] rowsColsFor(int grid) {
        switch (grid) {
            case 1: return new int[]{1, 1};
            case 2: return new int[]{1, 2};
            case 4: return new int[]{2, 2};
            case 6: return new int[]{2, 3};
            case 8: return new int[]{2, 4};
            default: return new int[]{2, 2}; // unknown → sender default
        }
    }

    /** Row-major split with overlap margin (clamped to the buffer). */
    public static int[][] splitRC(int w, int h, int rows, int cols, int overlapPct) {
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
