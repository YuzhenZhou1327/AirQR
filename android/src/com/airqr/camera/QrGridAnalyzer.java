package com.airqr.camera;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * NV21 frame -> multiple QR payloads via zxing GenericMultipleBarcodeReader
 * (scans with TRY_HARDER and re-scans remaining regions for further codes).
 * Portrait frames are rotated 90 degrees before decoding.
 */
public final class QrGridAnalyzer {
    private final MultiFormatReader reader = new MultiFormatReader();
    private final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);

    public QrGridAnalyzer() {
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(
                com.google.zxing.BarcodeFormat.QR_CODE));
    }

    public interface Sink {
        void onPayload(byte[] payload);
    }

    /** Analyzes one NV21 frame; calls sink per decoded QR payload. Returns count. */
    public int analyze(byte[] nv21, int width, int height, Sink sink) {
        PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(
                nv21, width, height, 0, 0, width, height, false);
        // rotate 90° CCW for portrait (camera sensor is landscape);
        // rotateCounterClockwise returns LuminanceSource — wrap as-is.
        com.google.zxing.LuminanceSource rot = src.isRotateSupported()
                ? src.rotateCounterClockwise() : src;
        BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(rot));
        GenericMultipleBarcodeReader multi = new GenericMultipleBarcodeReader(reader);
        int n = 0;
        try {
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
            // no codes this frame
        } finally {
            reader.reset();
        }
        return n;
    }
}
