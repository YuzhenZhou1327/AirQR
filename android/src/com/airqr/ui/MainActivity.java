package com.airqr.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.TextureView;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.airqr.camera.CameraController;
import com.airqr.camera.MockCamera;
import com.airqr.camera.QrGridAnalyzer;
import com.airqr.core.FountainSession;
import com.airqr.core.Wire;
import com.airqr.R;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * AirQR receiver, v1.4 UI state machine:
 *   WELCOME (start button) → SCANNING (preview + progress) → DONE (save/again).
 * Camera only opens after the user presses 开始传输, AND only after the
 * SurfaceView actually has a surface (v1.3 bug: setPreviewDisplay raced the
 * GONE→VISIBLE transition, camera silently failed to start).
 * Test QR: a BLOCK frame with tid=0xFFFFFFFF & id=0xFFFFFFFF (wire-valid but
 * impossible in a real session since id < cycle ≤ 8192) shows "test received".
 */
public class MainActivity extends Activity implements FountainSession.Listener {

    private CameraController camera;
    private QrGridAnalyzer analyzer;
    private FountainSession session;
    private TextureView preview;
    private View startPanel, scanPanel, donePanel;
    private TextView statusText, fileText, doneText;
    private ProgressBar progress;
    private byte[] doneFile;
    private String doneName = "airqr.bin";
    private boolean completed = false;
    private boolean cameraRequested = false;
    private boolean surfaceReady = false;
    private boolean scanning = false; // user pressed start; camera should run
    private long lastTestToast = 0;

    private static final int REQ_PERMS = 1;
    private static final long TEST_TID = 0xFFFFFFFFL;
    private static final long TEST_ID = 0xFFFFFFFFL;
    private MockCamera mockCamera; // non-null during self-test

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        preview = findViewById(R.id.preview);
        preview.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN && camera != null) {
                camera.focusAt(ev.getX() / preview.getWidth(), ev.getY() / preview.getHeight());
            }
            return true;
        });
        startPanel = findViewById(R.id.start_panel);
        scanPanel = findViewById(R.id.scan_panel);
        statusText = findViewById(R.id.status);
        fileText = findViewById(R.id.fileinfo);
        progress = findViewById(R.id.progress);
        donePanel = findViewById(R.id.done_panel);
        doneText = findViewById(R.id.done_text);
        Button startBtn = findViewById(R.id.start_btn);
        Button saveBtn = findViewById(R.id.save_btn);
        Button againBtn = findViewById(R.id.again_btn);
        Button selftestBtn = findViewById(R.id.selftest_btn);
        startBtn.setOnClickListener(v -> beginTransfer());
        saveBtn.setOnClickListener(v -> saveCompleted());
        againBtn.setOnClickListener(v -> resetSession());
        selftestBtn.setOnClickListener(v -> runSelfTest());
        session = new FountainSession(this);
        analyzer = new QrGridAnalyzer();
        statusText.setText(R.string.scanning);
        // adb-driven diagnostic: am start ... --ez selftest true (auto self-test)
        if (getIntent() != null && getIntent().getBooleanExtra("selftest", false)) {
            android.util.Log.i("AirQR", "auto self-test requested via intent");
            startPanel.postDelayed(this::autoSelfTest, 500);
        }
        // adb-driven diagnostic: am start ... --ez scan true (enter scanning, no taps)
        if (getIntent() != null && getIntent().getBooleanExtra("scan", false)) {
            android.util.Log.i("AirQR", "auto scan requested via intent");
            startPanel.postDelayed(() -> {
                if (checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    enterScanning();
                } else {
                    android.util.Log.e("AirQR", "auto scan: camera permission not granted");
                }
            }, 500);
        }
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                surfaceReady = true;
                if (scanning && camera == null) startCamera();
            }
            @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                surfaceReady = false;
                stopCamera();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) { }
        });
    }

    private void beginTransfer() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            enterScanning();
        } else if (!cameraRequested) {
            cameraRequested = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_PERMS) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                enterScanning();
            } else {
                Toast.makeText(this, R.string.need_camera, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void enterScanning() {
        startPanel.setVisibility(View.GONE);
        scanPanel.setVisibility(View.VISIBLE);
        preview.setVisibility(View.VISIBLE);
        scanning = true;
        if (surfaceReady) startCamera(); // else: surfaceCreated will fire
    }

    private final android.os.Handler diagHandler = new android.os.Handler();

    /** One sink used by BOTH the real camera and the mock self-test feed. */
    private QrGridAnalyzer.Sink makeSink() {
        return payload -> {
            if (payload.length > 1 && payload[0] == Wire.MAGIC
                    && u32le(payload, 2) == TEST_TID && u32le(payload, 10) == TEST_ID) {
                onTestQr();
                return;
            }
            if (payload.length > 0 && payload[0] == '{') {
                session.onManifest(payload);
            } else if (payload.length > 1 && payload[0] == Wire.MAGIC) {
                session.onBlock(payload);
            }
        };
    }

    private void startCamera() {
        if (camera != null || !surfaceReady) return; // wait for surface
        if (mockCamera != null) return; // self-test owns the analyzer
        diagHandler.postDelayed(diagTick, 3000); // on-screen decode diagnostics
        camera = new CameraController(preview, (nv21, w, h) -> {
            if (completed || analyzer.shouldSkip()) return;
            analyzer.analyze(nv21, w, h, sessionGrid(), makeSink());
        });
        try {
            camera.start();
            findViewById(R.id.frame_guide).setVisibility(View.VISIBLE);
            startLightSensor();
        } catch (SecurityException e) {
            statusText.setText(R.string.need_camera);
        }
    }

    /**
     * Self-test: shows the bundled test QR on the TextureView (simulating the
     * PC screen) and feeds its pixels through the SAME analyzer path. If this
     * fails, the bug is in the APK (logcat AirQR); if it passes, the decode
     * pipeline is fine and real-camera issues are hardware/focus.
     */
    /** adb path: welcome → (simulate start) → self-test without any taps. */
    private void autoSelfTest() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            enterScanning();
            // scan panel is now visible; run the self-test directly
            runSelfTest();
        } else {
            android.util.Log.e("AirQR", "auto self-test: camera permission not granted");
        }
    }

    private void runSelfTest() {
        try {
            stopCamera();
            android.graphics.Bitmap bmp = loadTestBitmap();
            android.widget.ImageView iv = findViewById(R.id.selftest_image);
            iv.setImageBitmap(bmp);
            iv.setVisibility(View.VISIBLE);
            mockCamera = new MockCamera(bmp, (nv21, w, h) -> {
                if (analyzer.shouldSkip()) return;
                analyzer.analyze(nv21, w, h, sessionGrid(), makeSink());
            });
            statusText.setText(R.string.selftest_running);
            android.util.Log.i("AirQR", "self-test started: bitmap "
                    + bmp.getWidth() + "x" + bmp.getHeight());
            mockCamera.start();
            final long startFrames = analyzer.payloadCount;
            diagHandler.postDelayed(() -> {
                android.util.Log.i("AirQR", "self-test 5s check: frames=" + analyzer.framesAnalyzed
                        + " payloads=" + analyzer.payloadCount + " (start=" + startFrames + ")");
                if (mockCamera != null && analyzer.payloadCount == startFrames) {
                    statusText.setText(R.string.selftest_fail);
                    android.util.Log.e("AirQR", "SELF-TEST FAILED: no payload decoded in 5s; "
                            + "stage=" + analyzer.lastStage + " frames=" + analyzer.framesAnalyzed);
                }
            }, 5000);
        } catch (Exception e) {
            android.util.Log.e("AirQR", "selftest setup", e);
            statusText.setText(getString(R.string.save_failed, e.toString()));
        }
    }

    private android.graphics.Bitmap loadTestBitmap() {
        try (java.io.InputStream is = getAssets().open("testqr.png")) {
            return android.graphics.BitmapFactory.decodeStream(is);
        } catch (Exception e) {
            throw new RuntimeException("testqr asset missing", e);
        }
    }

    private final Runnable diagTick = new Runnable() {
        @Override public void run() {
            if (!completed && camera != null && mockCamera == null) {
                long fa = analyzer.framesAnalyzed;
                long pc = analyzer.payloadCount;
                if (fa > 0 && pc == 0 && session.info() == null) {
                    statusText.setText(getString(R.string.diag_fmt, fa, analyzer.lastStage));
                }
                diagHandler.postDelayed(this, 3000);
            }
        }
    };

    /** Manifest grid for cell splits (0 = unknown → 2x2 sender default). */
    private int sessionGrid() {
        FountainSession.ManifestInfo i = session.info();
        return i == null ? 0 : i.grid;
    }

    // ---- ambient-light auto-torch (dark rooms / night) ----
    private android.hardware.SensorManager sensorManager;
    private android.hardware.Sensor lightSensor;
    private boolean torchOn = false;
    private final android.hardware.SensorEventListener lightListener =
            new android.hardware.SensorEventListener() {
                @Override public void onSensorChanged(android.hardware.SensorEvent e) {
                    if (camera == null || completed) return;
                    float lux = e.values[0];
                    try {
                        if (!torchOn && lux < 45) {
                            torchOn = true;
                            camera.setTorch(true);
                            android.util.Log.i("AirQR", "auto-torch ON (lux=" + lux + ")");
                        } else if (torchOn && lux > 450) {
                            torchOn = false;
                            camera.setTorch(false);
                            android.util.Log.i("AirQR", "auto-torch OFF (lux=" + lux + ")");
                        }
                    } catch (Exception ignore) { }
                }
                @Override public void onAccuracyChanged(android.hardware.Sensor s, int a) { }
            };

    private void startLightSensor() {
        try {
            if (sensorManager == null) {
                sensorManager = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
            }
            if (sensorManager == null) return;
            lightSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT);
            if (lightSensor != null) {
                sensorManager.registerListener(lightListener, lightSensor,
                        android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
            }
        } catch (Exception e) {
            android.util.Log.i("AirQR", "light sensor unavailable: " + e);
        }
    }

    private void stopLightSensor() {
        torchOn = false;
        try {
            if (sensorManager != null) sensorManager.unregisterListener(lightListener);
        } catch (Exception ignore) { }
    }

    private static long u32le(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private void onTestQr() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastTestToast < 3000) return; // throttle
        lastTestToast = now;
        runOnUiThread(() -> {
            statusText.setText(R.string.test_ok);
            beep();
            Toast.makeText(this, R.string.test_ok, Toast.LENGTH_SHORT).show();
            if (mockCamera != null) { // self-test passed → restart real camera
                mockCamera.stop();
                mockCamera = null;
                findViewById(R.id.selftest_image).setVisibility(View.GONE);
                if (surfaceReady) startCamera();
            }
        });
    }

    private void stopCamera() {
        stopLightSensor();
        try {
            findViewById(R.id.frame_guide).setVisibility(View.GONE);
        } catch (Exception ignore) { }
        if (mockCamera != null) {
            mockCamera.stop();
            mockCamera = null;
        }
        if (camera != null) {
            camera.stop();
            camera = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCamera(); // surface is also destroyed → surfaceDestroyed handles state
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Only resume scanning when the user is past the welcome screen.
        // Camera start is driven by surfaceCreated (surface re-arrives after
        // onResume in the Activity lifecycle, so nothing to do here).
    }

    // ---- FountainSession.Listener ----

    @Override
    public void onManifest(FountainSession.ManifestInfo info) {
        runOnUiThread(() -> {
            fileText.setText(info.name + " · " + human(info.size) + " · K=" + info.k);
            progress.setMax(info.k);
        });
    }

    @Override
    public void onProgress(int solved, int k) {
        runOnUiThread(() -> {
            progress.setMax(k);
            progress.setProgress(solved);
            statusText.setText(getString(R.string.progress_fmt, solved, k));
        });
    }

    @Override
    public void onComplete(byte[] file, FountainSession.ManifestInfo info, String sha256Hex) {
        completed = true;
        doneFile = file;
        doneName = info.name;
        runOnUiThread(() -> {
            if (camera != null) {
                camera.stop();
                camera = null;
            }
            progress.setProgress(progress.getMax());
            statusText.setText(R.string.done);
            donePanel.setVisibility(View.VISIBLE);
            doneText.setText(getString(R.string.done_fmt, info.name, human(file.length),
                    sha256Hex.substring(0, 8)));
            beep();
        });
    }

    private void beep() {
        try {
            android.media.ToneGenerator tg = new android.media.ToneGenerator(
                    android.media.AudioManager.STREAM_NOTIFICATION, 100);
            tg.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 300);
        } catch (Exception ignore) {
        }
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(200);
        } catch (Exception ignore) {
        }
    }

    private void saveCompleted() {
        if (doneFile == null) return;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, doneName);
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            Uri uri = getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                os.write(doneFile);
            }
            Toast.makeText(this, getString(R.string.saved_to, doneName), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void resetSession() {
        completed = false;
        doneFile = null;
        session = new FountainSession(this);
        donePanel.setVisibility(View.GONE);
        statusText.setText(R.string.scanning);
        fileText.setText("");
        progress.setProgress(0);
        if (surfaceReady && camera == null) startCamera();
        // if surface not ready, surfaceCreated will restart the camera
    }

    private static String human(long n) {
        if (n >= 1 << 20) return String.format("%.1fMB", n / 1048576.0);
        if (n >= 1 << 10) return String.format("%.1fKB", n / 1024.0);
        return n + "B";
    }
}
