package com.airqr.core;

import java.util.ArrayList;
import java.util.List;

/**
 * LT fountain decoder per spec/PROTOCOL.md §4 — bit-exact with internal/lt.
 * Degree distribution v2 (ripple), selection stream = splitmix64(seed*φ ^ K).
 */
public final class LtDecoder {
    public static final int MAX_K = 8192;
    static final long DEGREE_MIX = 0x9E3779B97F4A7C15L;

    public final int k;
    public final int blen;
    public final int size;

    private final boolean[] known;
    private final byte[][] src;      // blen-padded, null when unknown
    private final List<Equation> eqs = new ArrayList<>();
    private final List<Integer> queue = new ArrayList<>();
    private int solved = 0;
    public int blocksSeen = 0;      // distinct block feed count (dedup'd by caller)

    private static final class Equation {
        boolean[] sel;
        byte[] data; // blen bytes, peeled of known sources
        int unknowns;
        boolean active = true;
    }

    public LtDecoder(int k, int blen, int size) {
        if (k < 1 || k > MAX_K) throw new IllegalArgumentException("K out of range");
        this.k = k;
        this.blen = blen;
        this.size = size;
        this.known = new boolean[k];
        this.src = new byte[k][];
    }

    public boolean solved() {
        return solved == k;
    }

    public int solvedCount() {
        return solved;
    }

    /** Registers a raw source block (id &lt; K). */
    public void addSource(int id, byte[] payload) {
        if (id < 0 || id >= k) throw new IllegalArgumentException("source id");
        if (known[id]) return;
        int want = Math.min(blen, size - id * blen);
        if (payload.length != want)
            throw new IllegalArgumentException("source " + id + ": " + payload.length + " want " + want);
        byte[] pad = new byte[blen];
        System.arraycopy(payload, 0, pad, 0, payload.length);
        src[id] = pad;
        known[id] = true;
        solved++;
        blocksSeen++;
        cascade(id);
    }

    /** Registers one LT block (id &ge; K): seed (unsigned int in a long) + blen bytes. */
    public void addLt(long seed, byte[] payload) {
        if (payload.length != blen)
            throw new IllegalArgumentException("LT payload " + payload.length + " want " + blen);
        blocksSeen++;
        boolean[] sel = selections(seed, k);
        Equation eq = new Equation();
        eq.sel = sel;
        eq.data = new byte[blen];
        System.arraycopy(payload, 0, eq.data, 0, blen);
        for (int i = 0; i < k; i++) {
            if (sel[i] && known[i]) {
                xorInto(eq.data, src[i]);
                eq.sel[i] = false;
            }
        }
        int unk = 0;
        for (int i = 0; i < k; i++) {
            if (eq.sel[i] && !known[i]) unk++;
        }
        eq.unknowns = unk;
        if (unk == 0) return;
        if (unk == 1) {
            solveFrom(eq);
            return;
        }
        eqs.add(eq);
    }

    /** Degree distribution v2 + selection stream (spec §4.1). Bit-exact with
     * Go selectionsSpec: stream = splitmix64(uint64(seed)*degreeMix ^ uint64(k)).
     * Java long arithmetic already wraps mod 2^64 — NO masking (the old
     * remainderUnsigned(x, 1L<<64) was always 0 since 1L<<64==1L, collapsing
     * every slot to one constant selection set). */
    static public boolean[] selections(long seed, int k) {
        SplitMix64 p = new SplitMix64(seed * DEGREE_MIX ^ (k & 0xFFFFFFFFL));
        long u = p.nextMod(10000);
        int deg;
        if (u < 200) deg = 1;
        else if (u < 1500) deg = 2;
        else if (u < 2900) deg = 3;
        else if (u < 4000) deg = 4;
        else if (u < 6500) deg = 5 + p.nextMod(6);
        else deg = 11 + p.nextMod(54);
        boolean[] sel = new boolean[k];
        for (int j = 0; j < deg; j++) {
            int idx = p.nextMod(k);
            sel[idx] = !sel[idx];
        }
        return sel;
    }

    /** Defensive re-peel; solves only when exactly one true unknown remains. */
    private void solveFrom(Equation eq) {
        for (int i = 0; i < k; i++) {
            if (eq.sel[i] && known[i]) {
                xorInto(eq.data, src[i]);
                eq.sel[i] = false;
            }
        }
        int unknown = -1;
        for (int i = 0; i < k; i++) {
            if (!eq.sel[i] || known[i]) continue;
            if (unknown != -1) return; // >= 2 unknowns
            unknown = i;
        }
        if (unknown == -1) {
            eq.active = false;
            return;
        }
        byte[] pad = new byte[blen];
        System.arraycopy(eq.data, 0, pad, 0, blen);
        src[unknown] = pad;
        known[unknown] = true;
        solved++;
        eq.active = false;
        cascade(unknown);
    }

    private void cascade(int i) {
        queue.add(i);
        while (!queue.isEmpty()) {
            int x = queue.remove(queue.size() - 1);
            for (Equation eq : eqs) {
                if (!eq.active || !eq.sel[x]) continue;
                xorInto(eq.data, src[x]);
                eq.sel[x] = false;
                eq.unknowns--;
                if (eq.unknowns == 0) {
                    eq.active = false;
                } else if (eq.unknowns == 1) {
                    solveFrom(eq);
                }
            }
        }
    }

    /** Assembled file once solved; null otherwise. */
    public byte[] file() {
        if (!solved()) return null;
        byte[] out = new byte[size];
        int off = 0;
        for (int i = 0; i < k; i++) {
            int lo = i * blen;
            int hi = Math.min(lo + blen, size);
            int n = hi - lo;
            System.arraycopy(src[i], 0, out, off, n);
            off += n;
        }
        return out;
    }

    private static void xorInto(byte[] dst, byte[] srcArr) {
        for (int i = 0; i < dst.length; i++) dst[i] ^= srcArr[i];
    }
}
