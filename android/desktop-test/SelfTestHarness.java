import com.airqr.camera.Nv21;
import com.airqr.core.Wire;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public class SelfTestHarness {
    public static void main(String[] a) throws Exception {
        BufferedImage img = ImageIO.read(new File(a[0]));
        System.out.println("src=" + img.getWidth() + "x" + img.getHeight());
        int maxW = a.length > 1 ? Integer.parseInt(a[1]) : 480;
        if (img.getWidth() > maxW) {
            int nh = Math.round((float) img.getHeight() * maxW / img.getWidth());
            java.awt.Image s = img.getScaledInstance(maxW, nh, java.awt.Image.SCALE_SMOOTH);
            BufferedImage b2 = new BufferedImage(maxW, nh, BufferedImage.TYPE_INT_ARGB);
            b2.getGraphics().drawImage(s, 0, 0, null);
            img = b2;
        }
        int w = img.getWidth(), h = img.getHeight();
        System.out.println("scaled=" + w + "x" + h);
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] nv21 = Nv21.fromArgb(argb, w, h);
        System.out.println("nv21 len=" + nv21.length + " expect=" + (w * h * 3 / 2));
        int mn = 255, mx = 0;
        for (int i = 0; i < w * h; i++) {
            int v = nv21[i] & 0xFF;
            if (v < mn) mn = v;
            if (v > mx) mx = v;
        }
        System.out.println("Y min=" + mn + " max=" + mx);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.CHARACTER_SET, "ISO-8859-1");
        PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(nv21, w, h, 0, 0, w, h, false);
        LuminanceSource rot = src.isRotateSupported() ? src.rotateCounterClockwise() : src;
        System.out.println("rot=" + rot.getWidth() + "x" + rot.getHeight());
        MultiFormatReader reader = new MultiFormatReader();
        String dump = a.length > 2 ? a[2] : null;
        decodeStage(reader, new GlobalHistogramBinarizer(rot), hints, "full-global", dump);
        int cw = rot.getWidth() / 2, ch = rot.getHeight() / 2;
        int[][] org = {{0, 0}, {cw, 0}, {0, ch}, {cw, ch}};
        for (int i = 0; i < 4; i++) {
            try {
                decodeStage(reader, new GlobalHistogramBinarizer(rot.crop(org[i][0], org[i][1], cw, ch)), hints, "quad" + i + "-global", null);
            } catch (Exception e) {
                System.out.println("quad" + i + ": CROP-EX " + e);
            }
        }
        decodeStage(reader, new HybridBinarizer(rot), hints, "full-hybrid", null);
    }

    static void decodeStage(MultiFormatReader reader, com.google.zxing.Binarizer bin, Map<DecodeHintType, Object> hints, String name, String dump) {
        long t0 = System.currentTimeMillis();
        try {
            BinaryBitmap bmp = new BinaryBitmap(bin);
            GenericMultipleBarcodeReader multi = new GenericMultipleBarcodeReader(reader);
            Result[] rs = multi.decodeMultiple(bmp, hints);
            System.out.println(name + ": results=" + (rs == null ? 0 : rs.length) + " in " + (System.currentTimeMillis() - t0) + "ms");
            if (rs != null) {
                for (Result r : rs) {
                    byte[] rawBytes = r.getRawBytes();
                    byte[] textBytes = r.getText() != null ? r.getText().getBytes(StandardCharsets.ISO_8859_1) : null;
                    System.out.println("  rawBytes len=" + (rawBytes == null ? -1 : rawBytes.length)
                            + " textBytes len=" + (textBytes == null ? -1 : textBytes.length));
                    byte[] raw = (textBytes != null && textBytes.length > 0) ? textBytes : rawBytes;
                    System.out.println("  payload len=" + raw.length + " head=" + hex(raw, 18));
                    if (dump != null) {
                        java.nio.file.Files.write(java.nio.file.Paths.get(dump), raw);
                        System.out.println("  dumped to " + dump);
                    }
                    try {
                        Wire.Block b = Wire.parseBlock(raw);
                        System.out.println("  Wire OK: tid=" + Long.toHexString(b.tid) + " id=" + Long.toHexString(b.id)
                                + " TEST=" + (b.tid == 0xFFFFFFFFL && b.id == 0xFFFFFFFFL));
                    } catch (Exception e) {
                        System.out.println("  Wire REJECT: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println(name + ": NO-DECODE (" + e.getClass().getSimpleName() + ") in " + (System.currentTimeMillis() - t0) + "ms");
        } finally {
            reader.reset();
        }
    }

    static String hex(byte[] b, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) s.append(String.format("%02x", b[i]));
        return s.toString();
    }
}
