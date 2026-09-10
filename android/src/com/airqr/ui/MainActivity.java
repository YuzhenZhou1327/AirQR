package com.airqr.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.airqr.camera.CameraController;
import com.airqr.camera.QrGridAnalyzer;
import com.airqr.core.FountainSession;
import com.airqr.core.Wire;
import com.airqr.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * AirQR receiver: fullscreen preview, blocks fed into FountainSession.
 * Completion writes via SAF (Downloads/AirQR) and beeps/vibrates.
 */
public class MainActivity extends Activity implements FountainSession.Listener {

    private CameraController camera;
    private QrGridAnalyzer analyzer;
    private FountainSession session;
    private SurfaceView preview;
    private TextView statusText;
    private TextView fileText;
    private ProgressBar progress;
    private View donePanel;
    private TextView doneText;
    private byte[] doneFile;
    private String doneName = "airqr.bin";
    private boolean completed = false;

    private static final int REQ_PERMS = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        preview = findViewById(R.id.preview);
        statusText = findViewById(R.id.status);
        fileText = findViewById(R.id.fileinfo);
        progress = findViewById(R.id.progress);
        donePanel = findViewById(R.id.done_panel);
        doneText = findViewById(R.id.done_text);
        Button saveBtn = findViewById(R.id.save_btn);
        Button againBtn = findViewById(R.id.again_btn);
        saveBtn.setOnClickListener(v -> saveCompleted());
        againBtn.setOnClickListener(v -> resetSession());
        session = new FountainSession(this);
        analyzer = new QrGridAnalyzer();
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_PERMS) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) startCamera();
            else statusText.setText(R.string.need_camera);
        }
    }

    private void startCamera() {
        camera = new CameraController(preview, (nv21, w, h) -> {
            if (completed) return;
            analyzer.analyze(nv21, w, h, payload -> {
                if (payload.length > 0 && payload[0] == '{') {
                    session.onManifest(payload);
                } else if (payload.length > 1 && payload[0] == Wire.MAGIC) {
                    session.onBlock(payload);
                }
            });
        });
        try {
            camera.start();
        } catch (SecurityException e) {
            statusText.setText(R.string.need_camera);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (camera != null) {
            camera.stop();
            camera = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session != null && !completed && checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED && camera == null) {
            startCamera();
        }
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
            camera.stop();
            camera = null;
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
        startCamera();
    }

    private static String human(long n) {
        if (n >= 1 << 20) return String.format("%.1fMB", n / 1048576.0);
        if (n >= 1 << 10) return String.format("%.1fKB", n / 1024.0);
        return n + "B";
    }
}
