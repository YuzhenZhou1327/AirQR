import com.airqr.camera.GridCells;
import com.airqr.camera.HalfSampleLuminanceSource;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.EnumMap;
import java.util.Map;

/**
 * Proves the hold-transpose rule on REAL rendered frames: a 90-degree-rotated
 * frame (= what the landscape sensor sees when the phone is held portrait)
 * must decode 2/2 with TRANSPOSED cells (grid2 -> 2 rows x 1 col) and 0/2
 * without.
 *
 * Run (JDK 11+, needs a rendered frame PNG from `airqr render ... --out frames`),
 * from android/desktop-test:
 *   javac -encoding UTF-8 -cp "../libs/core-3.5.3.jar" -d /tmp/rotbench \
 *     RotBench.java ../src/com/airqr/camera/GridCells.java \
 *     ../src/com/airqr/camera/HalfSampleLuminanceSource.java
 *   java -Djava.awt.headless=true -cp "/tmp/rotbench;../libs/core-3.5.3.jar" \
 *     RotBench <frame.png>
 */
public final class RotBench {
    static Map<DecodeHintType, Object> hints() {
        Map<DecodeHintType, Object> h = new EnumMap<>(DecodeHintType.class);
        h.put(DecodeHintType.POSSIBLE_FORMATS,
                java.util.Collections.singletonList(com.google.zxing.BarcodeFormat.QR_CODE));
        h.put(DecodeHintType.CHARACTER_SET, "ISO-8859-1");
        return h;
    }

    static byte[] toNV21(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] nv = new byte[w * h * 3 / 2];
        int y = 0;
        for (int p : px) {
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            nv[y++] = (byte) ((((66 * r + 129 * g + 25 * b + 128) >> 8) + 16) & 0xFF);
        }
        for (int j = 0; j < h; j += 2) {
            for (int i = 0; i < w; i += 2) {
                int p = px[j * w + i];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                nv[y++] = (byte) (((((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128)) & 0xFF);
                nv[y++] = (byte) (((((112 * r - 94 * g - 18 * b + 128) >> 8) + 128)) & 0xFF);
            }
        }
        return nv;
    }

    static int decodeRC(LuminanceSource src, int rows, int cols) {
        int n = 0;
        QRCodeReader single = new QRCodeReader();
        for (int[] rc : GridCells.splitRC(src.getWidth(), src.getHeight(), rows, cols, 8)) {
            try {
                single.decode(new BinaryBitmap(
                        new GlobalHistogramBinarizer(src.crop(rc[0], rc[1], rc[2], rc[3]))), hints());
                n++;
            } catch (Exception ignore) { } finally { single.reset(); }
        }
        return n;
    }

    public static void main(String[] a) throws Exception {
        BufferedImage png = ImageIO.read(new File(a[0]));
        // sensor view in landscape hold: 2560x1440 as-is
        BufferedImage land = new BufferedImage(2560, 1440, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = land.createGraphics();
        g.drawImage(png, 0, 0, 2560, 1440, null);
        g.dispose();
        // sensor view in portrait hold: world rotated 90 (sensor is landscape-native)
        BufferedImage port = new BufferedImage(1440, 2560, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = port.createGraphics();
        g2.translate(720, 1280);
        g2.rotate(Math.PI / 2);
        g2.drawImage(land, -1280, -720, null);
        g2.dispose();

        boolean ok = true;
        for (Object[] scene : new Object[][]{{"landscape-hold", land}, {"portrait-hold", port}}) {
            BufferedImage img = (BufferedImage) scene[1];
            byte[] nv = toNV21(img);
            HalfSampleLuminanceSource sub = new HalfSampleLuminanceSource(nv, img.getWidth(), img.getHeight());
            int straight = decodeRC(sub, 1, 2);   // grid2 as-is
            int transposed = decodeRC(sub, 2, 1); // grid2 transposed
            System.out.println(scene[0] + " (" + img.getWidth() + "x" + img.getHeight() + "): "
                    + "as-is(1x2)=" + straight + "/2 transposed(2x1)=" + transposed + "/2");
            if (scene[0].equals("landscape-hold") && straight != 2) { ok = false; System.out.println("FAIL: landscape as-is"); }
            if (scene[0].equals("portrait-hold") && transposed != 2) { ok = false; System.out.println("FAIL: portrait transposed"); }
            if (scene[0].equals("portrait-hold") && straight != 0) { ok = false; System.out.println("FAIL: portrait as-is should miss"); }
        }
        System.out.println(ok ? "TRANSPOSE-RULE VERIFIED" : "TRANSPOSE-RULE BROKEN");
        if (!ok) System.exit(1);
    }
}
