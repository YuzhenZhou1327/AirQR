package com.airqr.core;

import java.nio.charset.StandardCharsets;

/**
 * Wire format per spec/PROTOCOL.md §2/§3 — mirrors internal/wire exactly.
 * All multi-byte fields are little-endian.
 */
public final class Wire {
    public static final byte MAGIC = 0x51; // 'Q'
    public static final int VERSION = 1;
    public static final int HEADER_LEN = 18;
    public static final String MANIFEST_TAG = "airqr1";

    private Wire() {}

    public static final class Block {
        public long tid;      // unsigned 32 in a long
        public long blen;
        public long id;
        public long crc;      // unsigned 32 in a long
        public byte[] data;

        public int dataLen() { return data.length; }
    }

    /** Rejection with reason; callers drop the frame. */
    public static final class RejectException extends Exception {
        public RejectException(String msg) { super(msg); }
    }

    /**
     * Exact block_data length for slot id (PROTOCOL §4.1): source blocks carry
     * 4B seed + min(blen, size-id*blen); LT blocks 4B seed + blen.
     * Callers that concatenate multiple blocks in one buffer (test vectors,
     * logging) use this to slice; live QR frames are one block per payload.
     */
    public static int blockDataLen(long blen, long id, int k, int size) {
        if (id < k) {
            long rest = size - id * blen;
            return 4 + (int) Math.min(blen, rest);
        }
        return 4 + (int) blen;
    }

    private static final int[] CRC_TABLE = buildCrcTable();

    private static int[] buildCrcTable() {
        int[] t = new int[256];
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) {
                c = ((c & 1) != 0) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            }
            t[n] = c;
        }
        return t;
    }

    /** IEEE CRC-32 (matches java.util.zip.CRC32 / Go crc32.ChecksumIEEE). */
    public static long crc32(byte[] data, int off, int len) {
        long c = 0xFFFFFFFFL;
        for (int i = off; i < off + len; i++) {
            // table entries are 32-bit UNSIGNED patterns stored in int (may be
            // negative): mask to unsigned before widening into the long state.
            c = ((long) CRC_TABLE[(int) ((c ^ data[i]) & 0xFF)] & 0xFFFFFFFFL) ^ (c >>> 8);
        }
        return (c ^ 0xFFFFFFFFL) & 0xFFFFFFFFL;
    }

    public static Block parseBlock(byte[] p) throws RejectException {
        return parseBlock(p, 0, p.length);
    }

    /** Parses one block starting at off within p (length field = len). */
    public static Block parseBlock(byte[] p, int off, int len) throws RejectException {
        if (len - off < HEADER_LEN) throw new RejectException("short payload " + (len - off));
        if (p[off] != MAGIC) throw new RejectException("magic");
        if (p[off + 1] != VERSION) throw new RejectException("version " + p[off + 1]);
        if (len - off < HEADER_LEN + 1) throw new RejectException("no data after header");
        Block b = new Block();
        b.tid = u32le(p, off + 2);
        b.blen = u32le(p, off + 6);
        b.id = u32le(p, off + 10);
        b.crc = u32le(p, off + 14);
        b.data = new byte[len - off - HEADER_LEN];
        System.arraycopy(p, off + HEADER_LEN, b.data, 0, b.data.length);
        if (b.blen == 0) throw new RejectException("blen=0");
        if (b.data.length == 0) throw new RejectException("empty block_data");
        // CRC over block_data (incl. 4B seed); PROTOCOL §2
        if (crc32(b.data, 0, b.data.length) != b.crc) throw new RejectException("crc mismatch");
        // block_data = 4B seed + payload(<=blen); PROTOCOL §2
        if (b.data.length > b.blen + 4) throw new RejectException("data>4+blen");
        return b;
    }

    /** Parses the MANIFEST JSON with strict field checks (mirrors Go). */
    public static final class Manifest {
        public long tid;
        public String name;
        public long size;
        public int blen;
        public int k;
        public int zstd;
    }

    public static Manifest parseManifest(byte[] payload) throws RejectException {
        String s = new String(payload, StandardCharsets.UTF_8);
        java.util.Map<String, String> f = new java.util.LinkedHashMap<>();
        scanJson(s, f);
        if (!MANIFEST_TAG.equals(f.get("fmt"))) throw new RejectException("fmt");
        Manifest m = new Manifest();
        m.tid = parseU32(f.get("tid"), 0xFFFFFFFFL);
        if (m.tid == 0) throw new RejectException("tid=0");
        m.name = f.get("name");
        if (!validName(m.name)) throw new RejectException("name");
        m.size = parseU32(f.get("size"), Long.MAX_VALUE);
        m.blen = (int) parseU32(f.get("blen"), 0xFFFFFFFFL);
        m.k = (int) parseU32(f.get("k"), 0xFFFFFFFFL);
        m.zstd = (int) parseU32(f.get("zstd"), 0xFFFFFFFFL);
        if (m.size < 1 || m.blen < 1 || m.k < 1 || (m.zstd != 0 && m.zstd != 1))
            throw new RejectException("field range");
        if (m.blen > 2939) throw new RejectException("blen too large");
        return m;
    }

    public static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c > 0x7E || c == '"' || c == '\\' || c == '/') return false;
        }
        return true;
    }

    private static long parseU32(String s, long max) throws RejectException {
        if (s == null || s.isEmpty()) throw new RejectException("missing number");
        long v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') throw new RejectException("not a number: " + s);
            v = v * 10 + (c - '0');
            if (v > max) throw new RejectException("number overflow");
        }
        return v;
    }

    /** Flat one-level JSON object scanner; strict (no nesting/escapes/trailing). */
    static void scanJson(String s, java.util.Map<String, String> out) throws RejectException {
        int[] i = {0};
        int n = s.length();
        Skipper sk = () -> {
            while (i[0] < n) {
                char c = s.charAt(i[0]);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i[0]++;
                else break;
            }
        };
        sk.run();
        if (i[0] >= n || s.charAt(i[0]) != '{') throw new RejectException("no brace");
        i[0]++;
        sk.run();
        if (i[0] < n && s.charAt(i[0]) == '}') throw new RejectException("empty object");
        while (true) {
            sk.run();
            if (i[0] >= n || s.charAt(i[0]) != '"') throw new RejectException("key expected");
            i[0]++;
            int ks = i[0];
            while (i[0] < n && s.charAt(i[0]) != '"') i[0]++;
            if (i[0] >= n) throw new RejectException("unterminated key");
            String key = s.substring(ks, i[0]);
            i[0]++;
            sk.run();
            if (i[0] >= n || s.charAt(i[0]) != ':') throw new RejectException(": expected");
            i[0]++;
            sk.run();
            String val;
            if (i[0] < n && s.charAt(i[0]) == '"') {
                i[0]++;
                int vs = i[0];
                while (i[0] < n && s.charAt(i[0]) != '"') i[0]++;
                if (i[0] >= n) throw new RejectException("unterminated value");
                val = s.substring(vs, i[0]);
                i[0]++;
            } else {
                int vs = i[0];
                while (i[0] < n && s.charAt(i[0]) >= '0' && s.charAt(i[0]) <= '9') i[0]++;
                if (i[0] == vs) throw new RejectException("number expected");
                val = s.substring(vs, i[0]);
            }
            out.put(key, val);
            sk.run();
            if (i[0] < n && s.charAt(i[0]) == ',') {
                i[0]++;
                continue;
            }
            if (i[0] < n && s.charAt(i[0]) == '}') {
                i[0]++;
                sk.run();
                if (i[0] != n) throw new RejectException("trailing content");
                return;
            }
            throw new RejectException(", or } expected");
        }
    }

    private interface Skipper {
        void run();
    }

    static long u32le(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }
}
