package com.airqr.core;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Receiver session: pools blocks by transfer id, feeds the LT decoder,
 * verifies SHA-256, reports progress. UI-agnostic.
 */
public final class FountainSession {

    public interface Listener {
        void onManifest(ManifestInfo info);

        void onProgress(int solved, int k);

        void onComplete(byte[] file, ManifestInfo info, String sha256Hex);
    }

    public static final class ManifestInfo {
        public long tid;
        public String name;
        public long size;
        public int blen;
        public int k;
        public int zstd;
    }

    private ManifestInfo info;
    private LtDecoder decoder;
    private String sha256Hex; // from decoded completion (computed locally)
    private final Map<Long, byte[]> pendingTid0 = new HashMap<>(); // blocks before manifest
    private final Listener listener;
    private int lastReported = -1;

    public FountainSession(Listener listener) {
        this.listener = listener;
    }

    public ManifestInfo info() {
        return info;
    }

    /** Feeds one MANIFEST JSON payload. */
    public void onManifest(byte[] json) {
        try {
            Wire.Manifest m = Wire.parseManifest(json);
            if (info != null) {
                if (info.tid != m.tid) return; // different session on same screen: ignore
                return; // idempotent
            }
            info = new ManifestInfo();
            info.tid = m.tid;
            info.name = m.name;
            info.size = m.size;
            info.blen = m.blen;
            info.k = m.k;
            info.zstd = m.zstd;
            decoder = new LtDecoder(m.k, m.blen, (int) m.size);
            listener.onManifest(info);
            // flush buffered blocks belonging to this tid
            Map<Long, byte[]> buf = pendingTid0;
            pendingTid0.clear();
            for (Map.Entry<Long, byte[]> e : buf.entrySet()) {
                feedBlock(e.getKey(), e.getValue());
            }
        } catch (Wire.RejectException ignore) {
            // corrupt manifest: drop
        }
    }

    /** Feeds one BLOCK payload (full QR bytes incl. 14B header). */
    public void onBlock(byte[] payload) {
        try {
            Wire.Block b = Wire.parseBlock(payload);
            if (info == null) {
                if (pendingTid0.size() < 16384) {
                    pendingTid0.put(b.id, payload);
                }
                return;
            }
            if (b.tid != info.tid) return;
            feedBlock(b.id, payload);
        } catch (Wire.RejectException ignore) {
            // drop
        }
    }

    private void feedBlock(long id, byte[] payload) {
        if (decoder == null || decoder.solved()) return;
        try {
            if (id < decoder.k) {
                byte[] data = Wire.parseBlock(payload).data;
                if (data.length < 4) return;
                byte[] src = new byte[data.length - 4];
                System.arraycopy(data, 4, src, 0, src.length);
                decoder.addSource((int) id, src);
            } else {
                byte[] data = Wire.parseBlock(payload).data;
                if (data.length < 4) return;
                long seed = u32le(data, 0);
                byte[] ltPayload = new byte[decoder.blen];
                System.arraycopy(data, 4, ltPayload, 0, Math.min(decoder.blen, data.length - 4));
                decoder.addLt(seed, ltPayload);
            }
        } catch (Wire.RejectException ignore) {
            return;
        } catch (IllegalArgumentException ignore) {
            return;
        }
        if (decoder.solvedCount() != lastReported) {
            lastReported = decoder.solvedCount();
            listener.onProgress(decoder.solvedCount(), decoder.k);
        }
        if (decoder.solved()) {
            try {
                byte[] file = decoder.file();
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(file);
                sha256Hex = hex(d);
                listener.onComplete(file, info, sha256Hex);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static long u32le(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
