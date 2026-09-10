package lt

import (
	"encoding/binary"
	"fmt"
	"testing"
)

// Distribution study: compares candidate degree distributions using the REAL
// Encoder/Decoder (selectionsFn is swapped per candidate), measuring the
// received-slots-until-solved overhead under the pass-based UX.
// Run: go test ./internal/lt/ -run TestDistributionStudy -v

type cand struct {
	name string
	table []struct{ hi, lo, span int } // u < hi -> degree = lo + draw%span
}

func (c cand) selFn(seed uint32, k int) (int, []bool) {
	p := NewPRNG(uint64(seed)*degreeMix ^ uint64(uint32(k)))
	deg := 0
	u := p.Next32() % 10000
	for _, b := range c.table {
		if u < uint32(b.hi) {
			deg = b.lo + int(p.Next32()%uint32(b.span))
			break
		}
	}
	sel := make([]bool, k)
	for j := 0; j < deg; j++ {
		idx := p.Next32() % uint32(k)
		sel[idx] = !sel[idx]
	}
	return deg, sel
}

var candidates = []cand{
	{"spec-v2", []struct{ hi, lo, span int }{{200, 1, 1}, {1500, 2, 1}, {2900, 3, 1}, {4000, 4, 1}, {6500, 5, 6}, {10000, 11, 54}}},
	{"cap40", []struct{ hi, lo, span int }{{200, 1, 1}, {1500, 2, 1}, {2900, 3, 1}, {4000, 4, 1}, {6500, 5, 6}, {10000, 11, 30}}},
	{"lowheavy", []struct{ hi, lo, span int }{{500, 1, 1}, {2500, 2, 1}, {4000, 3, 1}, {5000, 4, 1}, {7500, 5, 6}, {10000, 11, 22}}},
	{"solitonish", []struct{ hi, lo, span int }{{500, 1, 1}, {3000, 2, 1}, {4500, 3, 1}, {5500, 4, 1}, {8000, 5, 6}, {10000, 11, 22}}},
}

// rsdCand wraps selectionsRSD as a study candidate.
type rsdCand struct{ name string }

func (r rsdCand) selFn(seed uint32, k int) (int, []bool) { return selectionsRSD(seed, k) }

// runSession watches passes (max 4) with a fixed per-slot loss, returns fed/K.
func runSession(t *testing.T, k, blen int, sessionSeed uint32, loss int, fn func(uint32, int) (int, []bool)) (float64, bool) {
	old := selectionsFn
	selectionsFn = fn
	defer func() { selectionsFn = old }()

	size := k * blen
	data := make([]byte, size)
	r := NewPRNG(uint64(sessionSeed))
	for i := 0; i < size; i += 4 {
		binary.LittleEndian.PutUint32(data[i:], r.Next32())
	}
	enc, err := NewEncoder(data, blen, sessionSeed)
	if err != nil {
		t.Fatal(err)
	}
	dec, err := NewDecoder(k, blen, size)
	if err != nil {
		t.Fatal(err)
	}
	rng := NewPRNG(uint64(sessionSeed) * 7)
	fed := 0
loop:
	for pass := 0; pass < 4; pass++ {
		for id := 0; id < enc.Cycle(); id++ {
			if int(rng.Next32()%100) < loss {
				continue
			}
			fed++
			if id < k {
				off := id * blen
				end := off + blen
				if end > size {
					end = size
				}
				if err := dec.AddSource(id, data[off:end]); err != nil {
					t.Fatal(err)
				}
			} else {
				if err := dec.AddLT(enc.SlotSeed(id), enc.BlockData(id)[4:]); err != nil {
					t.Fatal(err)
				}
			}
			if dec.Solved() {
				break loop
			}
		}
	}
	if !dec.Solved() {
		return 0, false
	}
	if got := dec.File(); len(got) != size {
		t.Fatalf("length mismatch")
	}
	return float64(fed) / float64(k), true
}

func TestDistributionStudy(t *testing.T) {
	fmt.Printf("%-11s %-6s %-5s %s\n", "distrib", "K", "loss%", "solve% | avg fed/K (5 seeds)")
	type runner interface {
		name() string
	}
	type item struct {
		name string
		fn   func(uint32, int) (int, []bool)
	}
	items := []item{{name: "rsd-c0.05", fn: selectionsRSD}}
	for _, c := range candidates {
		cc := c
		items = append(items, item{name: cc.name, fn: cc.selFn})
	}
	for _, it := range items {
		for _, k := range []int{64, 256, 1024, 3616} {
			for _, loss := range []int{10, 20, 30} {
				var sum float64
				const seeds = 5
				okCount := 0
				for s := 0; s < seeds; s++ {
					ov, ok := runSession(t, k, 2900, uint32(1000+s*77), loss, it.fn)
					if ok {
						sum += ov
						okCount++
					}
				}
				fmt.Printf("%-11s %-6d %-5d %d%% | %.2f\n", it.name, k, loss,
					100*okCount/seeds, sum/float64(seeds))
			}
		}
	}
}
