package com.airqr.camera;

import android.util.Log;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * NV21 frame → multiple QR payloads.
 *
 * Strategy (v1.2, field-fix for "preview fine but zero decodes"):
 *  1. rotate luminance 90° CCW for portrait;
 *  2. try whole-frame decodeMultiple with GlobalHistogramBinarizer (more
 *     reliable than Hybrid on full frames that are mostly black canvas);
 *  3. if nothing: quadrant sub-regions (2×2) each with decodeMultiple —
 *     GenericMultipleBarcodeReader's recursive scan is weak on dense full
 *     frames; giving it a small crop with one code makes it hit;
 *  4. if still nothing: HybridBinarizer pass as last resort.
 * Every stage logs to logcat (tag AirQR) so field diagnosis is possible.
 */
public final class QrGridAnalyzer {
    private static final String TAG = "AirQR";
    private final MultiFormatReader reader = new MultiFormatReader();
    private final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    private long lastLog = 0;
    // Field-diagnostics counters (read from the UI thread to show on screen)
    public volatile long framesAnalyzed = 0;
    public volatile long payloadCount = 0;
    public volatile String lastStage = "init";
    private volatile boolean busy = false; // drop frames while decoding

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
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(
                com.google.zxing.BarcodeFormat.QR_CODE));
        // CRITICAL: our BLOCK payloads are arbitrary binary. Without this hint
        // zxing-java decodes byte-mode QR as UTF-8 and replaces invalid
        // sequences with U+FFFD — bytes are lost and every CRC fails on the
        // phone (the "preview fine but zero decodes" field report).
        // ISO-8859-1 is byte-transparent: getText().getBytes(ISO_8859_1)
        // recovers the exact original bytes.
        hints.put(DecodeHintType.CHARACTER_SET, "ISO-8859-1");
    }

    public interface Sink {
        void onPayload(byte[] payload);
    }

    /** Analyzes one NV21 frame; calls sink per decoded QR payload. Returns count. */
    public int analyze(byte[] nv21, int width, int height, Sink sink) {
        PlanarYUVLuminanceSource src;
        try {
            src = new PlanarYUVLuminanceSource(nv21, width, height, 0, 0, width, height, false);
        } catch (Exception e) {
            logThrottled("luminance source failed: " + e);
            return 0;
        }
        com.google.zxing.LuminanceSource rot = src.isRotateSupported()
                ? src.rotateCounterClockwise() : src;

        framesAnalyzed++;
        int total = 0;
        try {
            total = decodeStages(rot, sink);
        } finally {
            busy = false;
        }
        payloadCount += total;
        if (total == 0) {
            logThrottled("no codes; rotSize=" + rot.getWidth() + "x" + rot.getHeight()
                    + " luma[min=" + lumaMin(rot) + " max=" + lumaMax(rot) + "]");
        } else {
            logThrottled("decoded " + total + " payload(s)");
        }
        return total;
    }

    private int decodeStages(com.google.zxing.LuminanceSource rot, Sink sink) {
        int total = 0;
        // Stage 1: whole frame, global histogram binarizer
        lastStage = "full-global";
        total += scanAll(rot, false, sink, "full-global");
        // Stage 2: quadrants, global
        if (total == 0) {
            lastStage = "quadrants";
            for (LuminanceSource q : quadrants(rot)) {
                total += scanAll(q, false, sink, "quad-global");
            }
        }
        // Stage 3: whole frame, hybrid
        if (total == 0) {
            lastStage = "full-hybrid";
            total += scanAll(rot, true, sink, "full-hybrid");
        }
        payloadCount += total;
        if (total == 0) {
            logThrottled("no codes; rotSize=" + rot.getWidth() + "x" + rot.getHeight()
                    + " luma[min=" + lumaMin(rot) + " max=" + lumaMax(rot) + "]");
        } else {
            logThrottled("decoded " + total + " payload(s)");
        }
        return total;
    }

    private List<LuminanceSource> quadrants(LuminanceSource full) {
        List<LuminanceSource> out = new ArrayList<>(4);
        int w = full.getWidth(), h = full.getHeight();
        int cw = w / 2, ch = h / 2;
        int[][] origins = {{0, 0}, {cw, 0}, {0, ch}, {cw, ch}};
        for (int[] o : origins) {
            try {
                if (full.isCropSupported()) {
                    out.add(full.crop(o[0], o[1], cw, ch));
                }
            } catch (Exception ignore) { /* skip quadrant */ }
        }
        return out;
    }

    private int scanAll(LuminanceSource src, boolean hybrid, Sink sink, String stage) {
        int n = 0;
        try {
            BinaryBitmap bmp = new BinaryBitmap(hybrid
                    ? new HybridBinarizer(src) : new GlobalHistogramBinarizer(src));
            GenericMultipleBarcodeReader multi = new GenericMultipleBarcodeReader(reader);
            Result[] results = multi.decodeMultiple(bmp, hints);
            if (results != null) {
                for (Result r : results) {
                    byte[] raw = r.getRawBytes();
                    if (raw == null || raw.length == 0) {
                        String text = r.getText();
                        if (text != null) raw = text.getBytes(StandardCharsets.ISO_8859_1);
                    }
                    if (raw != null && raw.length > 0) {
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
        if (n > 0) logThrottled(stage + ": " + n);
        return n;
    }

    private int lumaMin(LuminanceSource s) {
        try {
            byte[] m = s.getMatrix();
            int mn = 255;
            for (int i = 0; i < m.length; i += 97) {
                int v = m[i] & 0xFF;
                if (v < mn) mn = v;
            }
            return mn;
        } catch (Exception e) { return -1; }
    }

    private int lumaMax(LuminanceSource s) {
        try {
            byte[] m = s.getMatrix();
            int mx = 0;
            for (int i = 0; i < m.length; i += 97) {
                int v = m[i] & 0xFF;
                if (v > mx) mx = v;
            }
            return mx;
        } catch (Exception e) { return -1; }
    }

    private void logThrottled(String msg) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastLog > 2000) {
            lastLog = now;
            Log.i(TAG, msg);
        }
    }
}
