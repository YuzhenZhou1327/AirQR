package lt

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func testData(t *testing.T, n int) []byte {
	t.Helper()
	p := NewPRNG(42)
	out := make([]byte, n)
	for i := range out {
		if i%4 == 0 {
			binary.LittleEndian.PutUint32(out[i:], p.Next32())
		}
	}
	return out
}

// TestPRNGVector pins splitmix64 against an independent reference
// implementation (bench/py computed; same formula, different language).
func TestPRNGVector(t *testing.T) {
	p := NewPRNG(0)
	want := []uint32{
		0x7b1dcdaf, 0xa1b965f4, 0x8009454f, 0x724c81ec, 0x51a8749b,
	}
	for i, w := range want {
		if got := p.Next32(); got != w {
			t.Fatalf("Next32[%d] = %#x, want %#x", i, got, w)
		}
	}
}

func TestPRNGDeterministic(t *testing.T) {
	a := NewPRNG(777)
	b := NewPRNG(777)
	for i := 0; i < 1000; i++ {
		if a.Next32() != b.Next32() {
			t.Fatalf("stream diverged at %d", i)
		}
	}
}

func TestNumSourceBlocks(t *testing.T) {
	cases := []struct{ size, blen, want int }{
		{1, 16, 1}, {16, 16, 1}, {17, 16, 2}, {32, 16, 2}, {33, 16, 3},
		{10 << 20, 2900, 3616},
	}
	for _, c := range cases {
		if got := NumSourceBlocks(c.size, c.blen); got != c.want {
			t.Errorf("NumSourceBlocks(%d,%d)=%d want %d", c.size, c.blen, got, c.want)
		}
	}
}

func TestCycleLen(t *testing.T) {
	if CycleLen(1) != 32 || CycleLen(24) != 32 || CycleLen(25) != 32 {
		t.Errorf("min cycle rule broken: %d %d %d", CycleLen(1), CycleLen(24), CycleLen(25))
	}
	if got := CycleLen(3641); got != 4552 {
		t.Errorf("CycleLen(3641)=%d want 4552", got)
	}
}

func TestRoundTripNoLoss(t *testing.T) {
	sizes := []int{32, 1000, 4096, 100 << 10, 1 << 20}
	for _, size := range sizes {
		for _, blen := range []int{16, 2900} {
			k := NumSourceBlocks(size, blen)
			if k > MaxK {
				continue // skip impossible (test-matrix) combos
			}
			data := testData(t, size)
			enc, err := NewEncoder(data, blen, 55512345)
			if err != nil {
				t.Fatalf("NewEncoder(size=%d,blen=%d): %v", size, blen, err)
			}
			dec, err := NewDecoder(enc.K(), blen, size)
			if err != nil {
				t.Fatal(err)
			}
			for id := 0; id < enc.Cycle(); id++ {
				bd := enc.BlockData(id)
				feedBlock(t, dec, id, bd, blen)
				if dec.Solved() {
					break
				}
			}
			if !dec.Solved() {
				t.Fatalf("size=%d blen=%d: not solved after full cycle", size, blen)
			}
			if got := dec.File(); !bytes.Equal(got, data) {
				t.Fatalf("size=%d blen=%d: file mismatch (%d bytes out)", size, blen, len(got))
			}
		}
	}
}

// TestRoundTripRandomLoss simulates the real UX: the receiver watches
// passes until solved (max 3). Information-theoretic floor: one pass at 20%
// loss yields only ~1.0K distinct slots — undecodable regardless of code;
// pass 2 (different shuffle) closes the gaps. Asserts decode within the
// pass budget and logs overhead actually consumed.
func TestRoundTripRandomLoss(t *testing.T) {
	rng := NewPRNG(20260910)
	cases := []struct {
		size, blen  int
		lossPercent int
	}{
		{4096, 16, 20}, {64 << 10, 2900, 10}, {64 << 10, 2900, 20},
		{64 << 10, 2900, 30}, {1 << 20, 2900, 15}, {1 << 20, 2900, 25},
	}
	for _, c := range cases {
		data := testData(t, c.size)
		enc, err := NewEncoder(data, c.blen, 314159)
		if err != nil {
			t.Fatal(err)
		}
		dec, err := NewDecoder(enc.K(), c.blen, c.size)
		if err != nil {
			t.Fatal(err)
		}
		fed := 0
		solvedAt := 0
	loop:
		for pass := 0; pass < 3; pass++ {
			for id := 0; id < enc.Cycle(); id++ {
				if int(rng.Next32()%100) < c.lossPercent {
					continue
				}
				fed++
				feedBlock(t, dec, id, enc.BlockData(id), c.blen)
				if dec.Solved() {
					solvedAt = pass + 1
					break loop
				}
			}
		}
		if !dec.Solved() {
			t.Fatalf("loss=%d%% size=%d blen=%d: NOT solved in 3 passes (%s)",
				c.lossPercent, c.size, c.blen, dec.Stats())
		}
		if got := dec.File(); !bytes.Equal(got, data) {
			t.Fatalf("loss=%d%%: file mismatch", c.lossPercent)
		}
		t.Logf("loss=%d%% size=%d: solved in pass %d, fed %d slots (K=%d, %.2fxK)",
			c.lossPercent, c.size, solvedAt, fed, enc.K(), float64(fed)/float64(enc.K()))
	}
}

// TestIntermediateJoin simulates a receiver joining mid-pass: it receives a
// random subset equivalent to watching ~1.1 cycles.
func TestIntermediateJoin(t *testing.T) {
	const size, blen = 200 << 10, 2900
	data := testData(t, size)
	enc, _ := NewEncoder(data, blen, 271828)
	dec, _ := NewDecoder(enc.K(), blen, size)
	rng := NewPRNG(99)
	// watch two passes' worth of slots with 15% random loss
	for pass := 0; pass < 2 && !dec.Solved(); pass++ {
		for id := 0; id < enc.Cycle(); id++ {
			if int(rng.Next32()%100) < 15 {
				continue
			}
			feedBlock(t, dec, id, enc.BlockData(id), blen)
		}
	}
	if !dec.Solved() {
		t.Fatalf("join simulation failed: %s", dec.Stats())
	}
	if got := dec.File(); !bytes.Equal(got, data) {
		t.Fatal("file mismatch in join simulation")
	}
}

// TestZeroFile transfers an all-zero file (maximum degeneracy pressure):
// all-zero LT payloads are legal equations; only empty selection sets are
// redrawn. Also asserts every emitted slot has a non-empty selection set.
func TestZeroFile(t *testing.T) {
	data := make([]byte, 8192)
	enc, err := NewEncoder(data, 16, 7)
	if err != nil {
		t.Fatal(err)
	}
	for id := enc.K(); id < enc.Cycle(); id++ {
		seed := enc.SlotSeed(id)
		_, sel := selectionsSpec(seed, enc.K())
		terms := 0
		for _, on := range sel {
			if on {
				terms++
			}
		}
		if terms == 0 {
			t.Fatalf("slot %d: empty selection set (seed=%d)", id, seed)
		}
	}
	dec, err := NewDecoder(enc.K(), 16, len(data))
	if err != nil {
		t.Fatal(err)
	}
	for id := 0; id < enc.Cycle() && !dec.Solved(); id++ {
		feedBlock(t, dec, id, enc.BlockData(id), 16)
	}
	if !dec.Solved() {
		t.Fatalf("zero file not solved: %s", dec.Stats())
	}
	if got := dec.File(); len(got) != len(data) {
		t.Fatalf("zero file length %d != %d", len(got), len(data))
	}
}

// TestSeedBound ensures seeds are < 2^26.
func TestSeedBound(t *testing.T) {
	data := testData(t, 8192)
	enc, _ := NewEncoder(data, 16, 0x3FFFFFF)
	if enc.Cycle() <= enc.K() {
		t.Fatal("degenerate cycle")
	}
	for id := enc.K(); id < enc.Cycle(); id++ {
		if s := enc.SlotSeed(id); s >= 1<<26 {
			t.Fatalf("slot %d seed %#x exceeds 26 bits", id, s)
		}
	}
}

func feedBlock(t *testing.T, dec *Decoder, id int, bd []byte, blen int) {
	t.Helper()
	if id < dec.k {
		if err := dec.AddSource(id, bd[4:]); err != nil {
			t.Fatalf("AddSource(%d): %v", id, err)
		}
		return
	}
	seed := binary.LittleEndian.Uint32(bd[:4])
	if err := dec.AddLT(seed, bd[4:]); err != nil {
		t.Fatalf("AddLT(seed=%d): %v", seed, err)
	}
}
