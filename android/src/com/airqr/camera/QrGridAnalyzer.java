package com.airqr.camera;

import android.util.Log;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * NV21 frame → multiple QR payloads, via an escalation ladder.
 *
 * v1.12 rewrite (measured on desktop harness, 2560x1440 NV21, best-of-5):
 *  - PlanarYUV in zxing-java 3.5.3 does NOT support rotation (verified by
 *    probe) — the old rotateCounterClockwise() call was dead code. The buffer
 *    is landscape and QR decode is rotation-invariant, so decode as-is.
 *  - Old stage-2 quadrants cut side-by-side codes in half (0 codes, pure
 *    overhead). Replaced by sender-grid-aware cell splits with overlap.
 *  - Rungs (persistent level, +1 per 4 consecutive zero-code frames, reset
 *    to 0 on any decode):
 *      R0 cells @1/2 subsample, single reader, Global, no TRY_HARDER (~2ms)
 *      R1 cells full-res,       single reader, Global, no TRY_HARDER (~7ms)
 *      R2 full frame multi,     Global, no TRY_HARDER (~14ms)
 *      R3 full frame multi,     Hybrid, no TRY_HARDER (~20ms, shadows/glare)
 *      R4 full frame multi,     Global, TRY_HARDER (small/far codes only;
 *         TRY_HARDER only narrows the finder row-skip, useless for big codes)
 *  - Sharpness gate: frames with subsampled-Y variance below VAR_FLOOR are
 *    skipped before any decode (defocus/motion/covered lens).
 */
public final class QrGridAnalyzer {
    private static final String TAG = "AirQR";
    private final MultiFormatReader reader = new MultiFormatReader();
    private final QRCodeReader single = new QRCodeReader();
    private final Map<DecodeHintType, Object> hintsTH = new EnumMap<>(DecodeHintType.class);
    private final Map<DecodeHintType, Object> hintsFast = new EnumMap<>(DecodeHintType.class);
    private long lastLog = 0;
    // Field-diagnostics counters (read from the UI thread to show on screen)
    public volatile long framesAnalyzed = 0;
    public volatile long payloadCount = 0;
    public volatile String lastStage = "init";
    public volatile double lastMs = 0;
    public volatile double lastVar = 0;
    public volatile int rung = 0;
    private volatile boolean busy = false; // drop frames while decoding
    private int failStreak = 0;

    /** Subsampled-Y variance below this → skip frame (bench: clean ~12000,
     * blurred ~10300, 25%-dark ~728 and still decodable, flat gray = 0). */
    static final double VAR_FLOOR = 40.0;
    private static final int RUNG_UP_AFTER = 4;
    private static final int MAX_RUNG = 4;
    private static final int CELL_OVERLAP_PCT = 8;

    /** True if this frame should be skipped (previous still decoding). */
    public boolean shouldSkip() {
        if (busy) {
            return true;
        }
        busy = true;
        return false;
    }

    public void release() {
        busy = false;
    }

    public QrGridAnalyzer() {
        hintsTH.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hintsTH.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(
                com.google.zxing.BarcodeFormat.QR_CODE));
        // CRITICAL: our BLOCK payloads are arbitrary binary. Without this hint
        // zxing-java decodes byte-mode QR as UTF-8 and replaces invalid
        // sequences with U+FFFD — bytes are lost and every CRC fails on the
        // phone (the "preview fine but zero decodes" field report).
        // ISO-8859-1 is byte-transparent: getText().getBytes(ISO_8859_1)
        // recovers the exact original bytes.
        hintsTH.put(DecodeHintType.CHARACTER_SET, "ISO-8859-1");
        hintsFast.putAll(hintsTH);
        hintsFast.remove(DecodeHintType.TRY_HARDER);
    }

    public interface Sink {
        void onPayload(byte[] payload);
    }

    /**
     * Analyzes one NV21 frame; calls sink per decoded QR payload (deduped
     * within the frame). Returns unique payload count.
     *
     * @param grid manifest grid (0 = unknown → 2x2 sender default)
     * @param displayOrientation camera display rotation (0/90/180/270); at
     *        90/270 the phone is held portrait so the world (and the sender
     *        grid) appears transposed in the landscape buffer → split rows/cols
     *        swapped. QR decode itself is rotation-invariant.
     */
    public int analyze(byte[] nv21, int width, int height, int grid,
                       int displayOrientation, Sink sink) {
        PlanarYUVLuminanceSource full;
        try {
            full = new PlanarYUVLuminanceSource(nv21, width, height, 0, 0, width, height, false);
        } catch (Exception e) {
            logThrottled("luminance source failed: " + e);
            busy = false;
            return 0;
        }
        HalfSampleLuminanceSource sub;
        try {
            sub = new HalfSampleLuminanceSource(nv21, width, height);
        } catch (Exception e) {
            sub = null; // non-even dims (never from a real camera): skip R0
        }
        framesAnalyzed++;
        int total;
        try {
            total = decodeLadder(full, sub, grid, displayOrientation, sink);
        } finally {
            busy = false;
        }
        payloadCount += total;
        return total;
    }

    private int decodeLadder(PlanarYUVLuminanceSource full, HalfSampleLuminanceSource sub,
                             int grid, int displayOrientation, Sink sink) {
        LuminanceSource varSrc = sub != null ? sub : full;
        double var = GridCells.lumaVariance(varSrc);
        lastVar = var;
        if (var < VAR_FLOOR) {
            lastStage = "gated";
            logThrottled("gated: var=" + (int) var);
            return 0;
        }
        // portrait hold (display 90/270) → world grid transposed in buffer
        boolean transpose = displayOrientation == 90 || displayOrientation == 270;
        String hold = transpose ? "P" : "L";
        Set<Long> seen = new HashSet<>();
        Sink dedup = payload -> {
            if (payload == null || payload.length == 0) return;
            long key = (((long) java.util.Arrays.hashCode(payload)) << 32)
                    | (payload.length & 0xFFFFFFFFL);
            if (seen.add(key)) sink.onPayload(payload);
        };
        long t0 = System.nanoTime();
        int n;
        String stage;
        int r = (rung == 0 && sub == null) ? 1 : rung; // R0 needs subsample
        switch (r) {
            case 0:
                n = scanCells(sub, grid, transpose, hintsFast, dedup);
                stage = "r0/cell-sub";
                break;
            case 1:
                n = scanCells(full, grid, transpose, hintsFast, dedup);
                stage = "r1/cell-full";
                break;
            case 2:
                n = scanMulti(full, false, hintsFast, dedup);
                stage = "r2/full-global";
                break;
            case 3:
                n = scanMulti(full, true, hintsFast, dedup);
                stage = "r3/full-hybrid";
                break;
            default:
                n = scanMulti(full, false, hintsTH, dedup);
                stage = "r4/full-TH";
                break;
        }
        lastMs = (System.nanoTime() - t0) / 1e6;
        if (n > 0) {
            rung = 0;
            failStreak = 0;
        } else if (++failStreak >= RUNG_UP_AFTER && rung < MAX_RUNG) {
            rung++;
            failStreak = 0;
        }
        lastStage = stage + " " + hold + " " + String.format("%.1fms", lastMs);
        if (n == 0) {
            logThrottled("no codes @" + lastStage + " var=" + (int) var
                    + " frame=" + full.getWidth() + "x" + full.getHeight());
        } else {
            logThrottled("decoded " + n + " @" + lastStage);
        }
        return seen.size();
    }

    /** Grid-aware cells (with overlap), one single-reader decode per cell. */
    private int scanCells(LuminanceSource src, int grid, boolean transpose,
                          Map<DecodeHintType, Object> hints, Sink sink) {
        int n = 0;
        try {
            int[] rc = GridCells.rowsColsFor(grid);
            int rows = transpose ? rc[1] : rc[0];
            int cols = transpose ? rc[0] : rc[1];
            for (int[] rect : GridCells.splitRC(src.getWidth(), src.getHeight(),
                    rows, cols, CELL_OVERLAP_PCT)) {
                if (!src.isCropSupported()) break;
                LuminanceSource cell;
                try {
                    cell = src.crop(rect[0], rect[1], rect[2], rect[3]);
                } catch (Exception ignore) {
                    continue;
                }
                try {
                    Result res = single.decode(
                            new BinaryBitmap(new GlobalHistogramBinarizer(cell)), hints);
                    byte[] raw = payloadBytes(res);
                    if (raw != null) {
                        sink.onPayload(raw);
                        n++;
                    }
                } catch (Exception ignore) {
                    // no code in this cell
                } finally {
                    single.reset();
                }
            }
        } finally {
            reader.reset();
        }
        return n;
    }

    private int scanMulti(LuminanceSource src, boolean hybrid,
                          Map<DecodeHintType, Object> hints, Sink sink) {
        int n = 0;
        try {
            BinaryBitmap bmp = new BinaryBitmap(hybrid
                    ? new HybridBinarizer(src) : new GlobalHistogramBinarizer(src));
            GenericMultipleBarcodeReader multi = new GenericMultipleBarcodeReader(reader);
            Result[] results = multi.decodeMultiple(bmp, hints);
            if (results != null) {
                for (Result res : results) {
                    byte[] raw = payloadBytes(res);
                    if (raw != null) {
                        sink.onPayload(raw);
                        n++;
                    }
                }
            }
        } catch (Exception ignore) {
            // no codes in this pass
        } finally {
            reader.reset();
        }
        return n;
    }

    /**
     * NOTE: for QR, getRawBytes() returns ALL data codewords INCLUDING
     * mode/length headers (e.g. 274B for a 43B payload), so it must NOT be
     * used as the payload. getText() with the ISO-8859-1 CHARACTER_SET hint
     * round-trips byte-mode content losslessly (verified byte-identical vs
     * zxing-cpp). Text first, rawBytes only as fallback.
     */
    private static byte[] payloadBytes(Result r) {
        byte[] raw = null;
        if (r.getText() != null) {
            raw = r.getText().getBytes(StandardCharsets.ISO_8859_1);
        }
        if (raw == null || raw.length == 0) {
            raw = r.getRawBytes();
        }
        return (raw != null && raw.length > 0) ? raw : null;
    }

    /** Variance of the (subsampled) luminance; see GridCells.lumaVariance. */
    static double variance(LuminanceSource src) {
        return GridCells.lumaVariance(src);
    }

    private void logThrottled(String msg) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastLog > 2000) {
            lastLog = now;
            Log.i(TAG, msg);
        }
    }
}
