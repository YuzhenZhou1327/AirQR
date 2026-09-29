import com.airqr.camera.GridCells;

/**
 * Rotation-chain simulator (pure JVM, no Android).
 *
 * Grounding: ONE field fact — portrait hold + display 90 shows an upright,
 * correct preview, and the legacy tap formula (bx=viewY, by=1-viewX) works.
 * Everything else is derived first-principles from it:
 *  - display D rotates the buffer image visually-clockwise by D/90 turns
 *    (checked: D=90 maps (ix,iy)->buffer(iy,1-ix) == legacy formula);
 *  - world->buffer rotation for a hold = ccw^(D/90) with D from the AOSP
 *    formula D=(mount-devDeg+360)%360 (transpose ⟺ D in {90,270} then holds
 *    for ANY mount — proven in comments, asserted for mounts 90/270).
 *
 * Ported EXACTLY from shipped code (do not "improve", this tests what's shipped):
 *  - letterbox math ← CameraController.applyLetterbox
 *  - focus inverse ← CameraController.focusPoint
 *  - transpose rule ← QrGridAnalyzer.decodeLadder
 * Real GridCells.java is used (not a copy).
 *
 * Run (JDK 11+, headless OK), from android/desktop-test:
 *   javac -encoding UTF-8 -cp "../libs/core-3.5.3.jar" -d /tmp/rotsim \
 *     RotationSim.java ../src/com/airqr/camera/GridCells.java
 *   java -cp "/tmp/rotsim;../libs/core-3.5.3.jar" RotationSim
 * (Windows 用 ; 分隔 classpath；/tmp/rotsim 换成任意 scratch 目录)
 */
public final class RotationSim {
    static int failures = 0;

    // ---- visual quarter turns on normalized [0,1]^2 (y-down) ----
    static double[] cw(double x, double y) { return new double[]{1 - y, x}; }
    static double[] ccw(double x, double y) { return new double[]{y, 1 - x}; }

    // ---- PORTED: letterbox (CameraController.applyLetterbox) ----
    // returns {scale, tx, ty, dispW, dispH}
    static double[] letterbox(int vw, int vh, int pw, int ph, int disp) {
        double dispW = (disp == 0 || disp == 180) ? pw : ph;
        double dispH = (disp == 0 || disp == 180) ? ph : pw;
        double scale = Math.min(vw / dispW, vh / dispH);
        double tx = (vw - dispW * scale) / 2.0, ty = (vh - dispH * scale) / 2.0;
        return new double[]{scale, tx, ty, dispW, dispH};
    }

    // ---- PORTED: focus inverse (CameraController.focusPoint) ----
    // lb = {scale, tx, ty, imgW, imgH, viewW, viewH}; returns sensor [-1000,1000]^2
    static int[] focusPoint(float normX, float normY, int disp, double[] lb) {
        float ix = normX, iy = normY;
        if (lb[5] > 0 && lb[3] > 0 && lb[0] > 0) {
            ix = (float) ((normX * lb[5] - lb[1]) / (lb[3] * lb[0]));
            iy = (float) ((normY * lb[6] - lb[2]) / (lb[4] * lb[0]));
            ix = Math.max(0f, Math.min(1f, ix));
            iy = Math.max(0f, Math.min(1f, iy));
        }
        int turns = (((disp / 90) % 4) + 4) % 4;
        float bx = ix, by = iy;
        for (int i = 0; i < turns; i++) {
            double[] q = ccw(bx, by);
            bx = (float) q[0];
            by = (float) q[1];
        }
        int sx = (int) (bx * 2000 - 1000);
        int sy = (int) (by * 2000 - 1000);
        sx = Math.max(-1000, Math.min(1000, sx));
        sy = Math.max(-1000, Math.min(1000, sy));
        return new int[]{sx, sy};
    }

    // ---- PORTED: transpose rule (QrGridAnalyzer.decodeLadder) ----
    static boolean transposeRule(int disp) { return disp == 90 || disp == 270; }

    static void check(boolean cond, String name) {
        System.out.println((cond ? "PASS " : "FAIL ") + name);
        if (!cond) failures++;
    }

    // image coords (px) of buffer point under display rotation D (visual CW turns)
    static double[] imgOfBuf(double bx, double by, int bw, int bh, int D) {
        int m = ((D / 90) % 4 + 4) % 4;
        double u = bx / bw - 0.5, v = by / bh - 0.5;
        for (int i = 0; i < m; i++) { double[] q = cw(u + 0.5, v + 0.5); u = q[0] - 0.5; v = q[1] - 0.5; }
        int iw = (D == 0 || D == 180) ? bw : bh;
        int ih = (D == 0 || D == 180) ? bh : bw;
        return new double[]{(u + 0.5) * iw, (v + 0.5) * ih};
    }

    public static void main(String[] a) {
        int BW = 2560, BH = 1440; // sensor buffer (landscape native)
        int[] mounts = {90, 270};
        int[][] views = {{1080, 2400}, {2400, 1080}}; // portrait / landscape activity
        // holds: {name, devDeg}
        Object[][] holds = {{"P0", 0}, {"L90", 90}, {"P180", 180}, {"L270", 270}};

        // T1: legacy anchor — D=90, full-bleed view, tap(.25,.75) == old formula (500,500)
        {
            double[] lb = letterbox(1440, 2560, BW, BH, 90);
            int[] s = focusPoint(0.25f, 0.75f, 90, new double[]{lb[0], lb[1], lb[2], lb[3], lb[4], 1440, 2560});
            check(s[0] == 500 && s[1] == 500, "T1 legacy anchor D=90 tap(.25,.75)->(500,500), got (" + s[0] + "," + s[1] + ")");
        }

        // T2: focus round-trip — buffer point -> image -> view -> tap -> sensor == direct map
        {
            boolean ok = true;
            for (int mount : mounts) {
                for (Object[] h : holds) {
                    int devDeg = (int) h[1];
                    int D = (mount - devDeg + 360) % 360;
                    for (int[] vw : views) {
                        double[] lb = letterbox(vw[0], vw[1], BW, BH, D);
                        double scale = lb[0], tx = lb[1], ty = lb[2], iw = lb[3], ih = lb[4];
                        double[][] taps = {{0.5, 0.5}, {0.1, 0.1}, {0.9, 0.1}, {0.1, 0.9}, {0.9, 0.9}};
                        for (double[] t : taps) {
                            // tap -> image px (inverse letterbox)
                            double ix = (t[0] * vw[0] - tx) / scale, iy = (t[1] * vw[1] - ty) / scale;
                            // image px -> buffer px: brute force (8px step)
                            double best = 1e18, bbx = 0, bby = 0;
                            for (int yy = 0; yy < BH; yy += 8) {
                                for (int xx = 0; xx < BW; xx += 8) {
                                    double[] q = imgOfBuf(xx, yy, BW, BH, D);
                                    double d = (q[0] - ix) * (q[0] - ix) + (q[1] - iy) * (q[1] - iy);
                                    if (d < best) { best = d; bbx = xx; bby = yy; }
                                }
                            }
                            int[] s = focusPoint((float) t[0], (float) t[1], D,
                                    new double[]{scale, tx, ty, iw, ih, vw[0], vw[1]});
                            int esx = (int) (bbx / BW * 2000 - 1000), esy = (int) (bby / BH * 2000 - 1000);
                            if (Math.abs(s[0] - esx) > 40 || Math.abs(s[1] - esy) > 40) {
                                ok = false;
                                System.out.println("  roundtrip off: mount=" + mount + " hold=" + h[0]
                                        + " view=" + vw[0] + "x" + vw[1] + " tap=(" + t[0] + "," + t[1]
                                        + ") got=(" + s[0] + "," + s[1] + ") want=(" + esx + "," + esy + ")");
                            }
                        }
                    }
                }
            }
            check(ok, "T2 focus round-trip all holds x mounts x views x taps (tol 40/2000)");
        }

        // T3: letterbox never distorts — rect aspect == image aspect, inside view
        {
            boolean ok = true;
            for (int D : new int[]{0, 90, 180, 270}) {
                for (int[] vw : views) {
                    double[] lb = letterbox(vw[0], vw[1], BW, BH, D);
                    double rw = lb[3] * lb[0], rh = lb[4] * lb[0];
                    boolean aspectOK = Math.abs(rw / rh - lb[3] / lb[4]) < 1e-9;
                    boolean inside = lb[1] >= -1e-9 && lb[2] >= -1e-9
                            && lb[1] + rw <= vw[0] + 1e-9 && lb[2] + rh <= vw[1] + 1e-9;
                    if (!aspectOK || !inside || lb[0] <= 0) {
                        ok = false;
                        System.out.println("  letterbox bad: D=" + D + " view=" + vw[0] + "x" + vw[1]);
                    }
                }
            }
            check(ok, "T3 letterbox aspect-preserving + inside view (4D x 2 views)");
        }

        // T4: grid wholeness — world side-by-side codes stay whole in exactly one cell
        // world screen 1600x900; grid g -> world rects; buffer = world rotated ccw^R
        {
            boolean ok = true, negCut = false;
            for (int g : new int[]{2, 4, 6, 8}) {
                int[] rc = GridCells.rowsColsFor(g);
                int rows = rc[0], cols = rc[1];
                int[][] wrects = new int[rows * cols][];
                int i = 0;
                for (int r = 0; r < rows; r++)
                    for (int c = 0; c < cols; c++)
                        wrects[i++] = new int[]{c * 1600 / cols, r * 900 / rows, 1600 / cols, 900 / rows};
                for (int mount : mounts) {
                    for (Object[] h : holds) {
                        int devDeg = (int) h[1];
                        int D = (mount - devDeg + 360) % 360;
                        int R = ((D / 90) % 4 + 4) % 4; // buffer = world rotated ccw^R
                        boolean rule = transposeRule(D);
                        int srows = rule ? rc[1] : rc[0], scols = rule ? rc[0] : rc[1];
                        int[][] cells = GridCells.splitRC(BW, BH, srows, scols, 8);
                        // world->buffer point map (pixels): scale world to the
                        // screen-in-buffer extents, rotate R ccw quarter turns
                        // about the buffer center.
                        double sc = (R % 2 == 0) ? Math.min(BW / 1600.0, BH / 900.0)
                                : Math.min(BW / 900.0, BH / 1600.0);
                        for (int[] wr : wrects) {
                            double[][] corners = {{wr[0], wr[1]}, {wr[0] + wr[2], wr[1]},
                                    {wr[0], wr[1] + wr[3]}, {wr[0] + wr[2], wr[1] + wr[3]}};
                            double x0 = 1e18, y0 = 1e18, x1 = -1e18, y1 = -1e18;
                            for (double[] p : corners) {
                                double dx = (p[0] - 800) * sc, dy = (p[1] - 450) * sc;
                                for (int k = 0; k < R; k++) { double t = dx; dx = dy; dy = -t; } // ccw px
                                double px = dx + BW / 2.0, py = dy + BH / 2.0;
                                x0 = Math.min(x0, px);
                                y0 = Math.min(y0, py);
                                x1 = Math.max(x1, px);
                                y1 = Math.max(y1, py);
                            }
                            int inside = 0;
                            for (int[] cl : cells) {
                                if (x0 >= cl[0] - 1 && y0 >= cl[1] - 1 && x1 <= cl[0] + cl[2] + 1 && y1 <= cl[1] + cl[3] + 1) inside++;
                            }
                            if (inside != 1) {
                                ok = false;
                                System.out.println("  grid break: g=" + g + " mount=" + mount + " hold=" + h[0]
                                        + " R=" + R + " cells=" + inside);
                            }
                        }
                        // negative control: v1.12 rule (never transpose) must cut a code
                        if (g == 2 && mount == 90 && (((String) h[0]).startsWith("P"))) {
                            int[][] cells0 = GridCells.splitRC(BW, BH, rc[0], rc[1], 8);
                            int[] wr = wrects[0];
                            double[][] corners = {{wr[0], wr[1]}, {wr[0] + wr[2], wr[1] + wr[3]}};
                            double x0 = 1e18, y0 = 1e18, x1 = -1e18, y1 = -1e18;
                            for (double[] p : corners) {
                                double dx = (p[0] - 800) * sc, dy = (p[1] - 450) * sc;
                                for (int k = 0; k < R; k++) { double t = dx; dx = dy; dy = -t; }
                                double px = dx + BW / 2.0, py = dy + BH / 2.0;
                                x0 = Math.min(x0, px);
                                y0 = Math.min(y0, py);
                                x1 = Math.max(x1, px);
                                y1 = Math.max(y1, py);
                            }
                            int inside = 0;
                            for (int[] cl : cells0) {
                                if (x0 >= cl[0] - 1 && y0 >= cl[1] - 1 && x1 <= cl[0] + cl[2] + 1 && y1 <= cl[1] + cl[3] + 1) inside++;
                            }
                            if (inside != 1) negCut = true; // cut demonstrated
                        }
                    }
                }
            }
            check(ok, "T4 grid codes whole+separate in exactly one cell (grids x mounts x holds)");
            check(negCut, "T4neg v1.12 no-transpose rule demonstrably cuts codes in portrait");
        }

        // T5: bucket/AOSP/transpose table
        {
            boolean ok = true;
            int[][] cases = {{90, 0, 90, 1}, {90, 90, 0, 0}, {90, 180, 270, 1}, {90, 270, 180, 0},
                    {270, 0, 270, 1}, {270, 90, 180, 0}, {270, 180, 90, 1}, {270, 270, 0, 0}};
            for (int[] c : cases) {
                int D = (c[0] - c[1] + 360) % 360;
                if (D != c[2] || (transposeRule(D) ? 1 : 0) != c[3]) {
                    ok = false;
                    System.out.println("  table off: mount=" + c[0] + " dev=" + c[1]);
                }
            }
            check(ok, "T5 AOSP/bucket/transpose table (mounts 90/270 x 4 holds)");
        }

        System.out.println(failures == 0 ? "SIM ALL PASS" : "SIM FAILURES=" + failures);
        if (failures > 0) System.exit(1);
    }
}
