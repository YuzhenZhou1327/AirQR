package com.airqr.core;

/**
 * splitmix64 per spec/PROTOCOL.md §4 — the ONLY random source.
 * Java long arithmetic wraps modulo 2^64 = Go uint64 arithmetic; >> must be >>>.
 * Bit-exact with internal/lt.PRNG (locked by spec/vectors/cases.json).
 */
public final class SplitMix64 {
    private long st;

    public SplitMix64(long seed) {
        this.st = seed;
    }

    /** Advances the state; returns the mixed LOW 32 bits as an unsigned int in a long. */
    public long next32() {
        st += 0x9E3779B97F4A7C15L;
        long z = st;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return z & 0xFFFFFFFFL;
    }

    /** out32 % k with unsigned semantics (mirrors Go uint32 %). k > 0. */
    public int nextMod(int k) {
        return (int) Long.remainderUnsigned(next32(), k);
    }
}
