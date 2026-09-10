// Package lt implements the AirQR LT fountain code per spec/PROTOCOL.md §4.
// The only random source is splitmix64; block content is fully derived from
// its slot seed, so the receiver needs no per-block header beyond the seed.
package lt

import (
	"errors"
	"fmt"
)

const (
	// MaxK is the maximum number of source blocks (26-bit file addressing with
	// typical blen; hard protocol cap).
	MaxK = 8192
	// MinDegree / MaxDegree bound the LT degree distribution (4..64).
	MinDegree = 4
	MaxDegree = 64
	// SeedBits is the width of a slot seed.
	SeedBits = 26

	degreeMix = 0x9E3779B97F4A7C15 // selection-stream state mixer
	seedLimit = 1 << SeedBits
)

// PRNG is splitmix64 as specified (PROTOCOL §4): the ONLY random source.
type PRNG struct{ st uint64 }

// NewPRNG starts a stream at the given 64-bit state.
func NewPRNG(seed uint64) *PRNG { return &PRNG{st: seed} }

// Next32 advances the state and returns the mixed low 32 bits.
func (p *PRNG) Next32() uint32 {
	p.st += 0x9E3779B97F4A7C15
	z := p.st
	z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9
	z = (z ^ (z >> 27)) * 0x94D049BB133111EB
	z = z ^ (z >> 31)
	return uint32(z)
}

// NumSourceBlocks returns K for a file of the given size (size must be > 0).
func NumSourceBlocks(size, blen int) int {
	return (size + blen - 1) / blen
}

// CycleLen returns the default slot-space size (PROTOCOL §5: 1.25×K).
func CycleLen(k int) int { return CycleLenMul(k, 125) }

// CycleLenMul returns max(32, ceil(mul% × K)); mul is a percentage (125=1.25×).
func CycleLenMul(k, mul int) int {
	c := (k*mul + 99) / 100
	if c < 32 {
		c = 32
	}
	return c
}

// Validate enforces PROTOCOL constraints for a session.
func Validate(size, blen, k int) error {
	if size < 1 {
		return errors.New("size must be >= 1")
	}
	if blen < 1 {
		return errors.New("blen must be >= 1")
	}
	if k < 1 || k > MaxK {
		return fmt.Errorf("K=%d out of range [1,%d]", k, MaxK)
	}
	if got := NumSourceBlocks(size, blen); got != k {
		return fmt.Errorf("K=%d does not match size/blen (expected %d)", k, got)
	}
	return nil
}

// selectionsFn derives (degree, selected-set) from a slot seed. Package
// default = spec distribution v2; tests may swap it (e.g. the distribution
// study) — production code always uses the spec version.
var selectionsFn = selectionsSpec

// selectionsSpec is the v2 distribution (fallback, kept for study baseline).
func selectionsSpec(seed uint32, k int) (int, []bool) {
	p := NewPRNG(uint64(seed)*degreeMix ^ uint64(uint32(k)))
	var deg int
	switch u := p.Next32() % 10000; {
	case u < 200:
		deg = 1
	case u < 1500:
		deg = 2
	case u < 2900:
		deg = 3
	case u < 4000:
		deg = 4
	case u < 6500:
		deg = 5 + int(p.Next32()%6)
	default:
		deg = 11 + int(p.Next32()%54)
	}
	sel := make([]bool, k)
	for j := 0; j < deg; j++ {
		idx := p.Next32() % uint32(k)
		sel[idx] = !sel[idx]
	}
	return deg, sel
}

// Robust Soliton Distribution (Luby 2002) with fixed parameters. Sampling is
// fully deterministic: the degree CDF is computed in IEEE754 doubles from
// (K, c, delta) and walked with one uniform draw from the splitmix64 stream.
const (
	rsdDelta = 0.05
	rsdC     = 0.05
)

// rsdTable returns the cumulative degree distribution for K blocks.
// degrees are 1..K; table[i-1] = P(deg <= i).
func rsdTable(k int) []float64 {
	rho := make([]float64, k+1)
	for i := 1; i <= k; i++ {
		if i == 1 {
			rho[i] = 1.0 / float64(k)
		} else {
			rho[i] = 1.0 / (float64(i) * float64(i-1))
		}
	}
	s := rsdC * ln(float64(k)/rsdDelta) * sqrt(float64(k))
	tau := make([]float64, k+1)
	half := k / 2
	for i := 1; i < half; i++ {
		tau[i] = s / (float64(i) * float64(k))
	}
	if half >= 1 {
		tau[half] = s * ln(s/rsdDelta) / float64(k)
	}
	beta := 0.0
	cdf := make([]float64, k)
	for i := 1; i <= k; i++ {
		beta += rho[i] + tau[i]
	}
	acc := 0.0
	for i := 1; i <= k; i++ {
		acc += (rho[i] + tau[i]) / beta
		cdf[i-1] = acc
	}
	cdf[k-1] = 1.0
	return cdf
}

// sqrt/ln: tiny helpers to avoid importing math for two functions.
func sqrt(x float64) float64 {
	if x <= 0 {
		return 0
	}
	z := x
	for i := 0; i < 60; i++ {
		z = (z + x/z) / 2
	}
	return z
}

func ln(x float64) float64 {
	// natural log via atanh series on argument reduction
	const ln2 = 0.6931471805599453
	y := 0.0
	m := x
	for m > 2 {
		m /= 2
		y += ln2
	}
	for m < 1 {
		m *= 2
		y -= ln2
	}
	z := (m - 1) / (m + 1)
	z2 := z * z
	term := z
	sum := 0.0
	for i := 1; i <= 49; i += 2 {
		sum += term / float64(i)
		term *= z2
	}
	return y + 2*sum
}

// selectionsRSD is the Robust-Soliton selection (candidate v3).
func selectionsRSD(seed uint32, k int) (int, []bool) {
	p := NewPRNG(uint64(seed)*degreeMix ^ uint64(uint32(k)))
	cdf := rsdTable(k)
	u := float64(p.Next32()) / 4294967296.0
	deg := 1
	for deg <= k && cdf[deg-1] < u {
		deg++
	}
	sel := make([]bool, k)
	for j := 0; j < deg; j++ {
		idx := p.Next32() % uint32(k)
		sel[idx] = !sel[idx]
	}
	return deg, sel
}

// ---------------------------------------------------------------------------
// Encoder
// ---------------------------------------------------------------------------

// Encoder produces deterministic LT blocks for the slot space [0, cycle).
// Source slots [0,K) carry raw file chunks; redundancy slots [K,cycle) carry
// LT blocks whose content is fully derived from their precomputed seed.
type Encoder struct {
	data    []byte
	blen, k int
	cycle   int
	seeds   []uint32 // for slots [K, cycle)
	srcs    [][]byte // padded sources
}

// NewEncoder builds an encoder for data with the given block length and
// session seed (must be < 2^26).
func NewEncoder(data []byte, blen int, sessionSeed uint32) (*Encoder, error) {
	if sessionSeed >= seedLimit {
		return nil, fmt.Errorf("sessionSeed %d exceeds 26 bits", sessionSeed)
	}
	k := NumSourceBlocks(len(data), blen)
	if err := Validate(len(data), blen, k); err != nil {
		return nil, err
	}
	e := &Encoder{data: data, blen: blen, k: k, cycle: CycleLen(k)}
	e.srcs = make([][]byte, k)
	for i := 0; i < k; i++ {
		e.srcs[i] = e.srcPadded(i)
	}
	// Precompute redundancy-slot seeds (PROTOCOL §4.1): one splitmix64
	// stream, redraw only when the selection set is empty (degenerate).
	p := NewPRNG(uint64(sessionSeed))
	acc := make([]byte, blen)
	for id := k; id < e.cycle; id++ {
		for {
			s := p.Next32() % seedLimit
			empty, ok := e.xorForSeed(s, acc)
			if ok && !empty {
				e.seeds = append(e.seeds, s)
				break
			}
			// empty selection: redraw (st monotonically advances)
		}
	}
	return e, nil
}

// xorForSeed computes XOR of the selected sources for a candidate seed into
// acc. Returns (selectionEmpty, true) on success; ok=false never happens
// today but keeps the call site explicit.
func (e *Encoder) xorForSeed(seed uint32, acc []byte) (emptySel bool, ok bool) {
	for i := range acc {
		acc[i] = 0
	}
	_, sel := selectionsFn(seed, e.k)
	terms := 0
	for i, on := range sel {
		if !on {
			continue
		}
		terms++
		src := e.srcs[i]
		for j := range acc {
			acc[j] ^= src[j]
		}
	}
	return terms == 0, true
}

func (e *Encoder) srcPadded(i int) []byte {
	out := make([]byte, e.blen)
	lo := i * e.blen
	hi := lo + e.blen
	if hi > len(e.data) {
		hi = len(e.data)
	}
	copy(out, e.data[lo:hi])
	return out
}

// K returns the source block count.
func (e *Encoder) K() int { return e.k }

// Cycle returns the slot-space size.
func (e *Encoder) Cycle() int { return e.cycle }

// SlotSeed returns the seed carried by slot id (0 for source slots).
func (e *Encoder) SlotSeed(id int) uint32 {
	if id < e.k {
		return 0
	}
	return e.seeds[id-e.k]
}

// BlockData returns the block_data field (PROTOCOL §2/§4): 4B seed LE +
// payload. Source slots carry min(blen, size-id*blen) raw bytes; LT slots
// carry exactly blen XORed bytes.
func (e *Encoder) BlockData(id int) []byte {
	if id < e.k {
		lo := id * e.blen
		hi := lo + e.blen
		if hi > len(e.data) {
			hi = len(e.data)
		}
		out := make([]byte, 4, 4+hi-lo)
		out[0] = byte(0) // seed = 0
		out[1] = 0
		out[2] = 0
		out[3] = 0
		return append(out, e.data[lo:hi]...)
	}
	seed := e.seeds[id-e.k]
	payload := make([]byte, e.blen)
	e.xorForSeed(seed, payload)
	out := make([]byte, 4, 4+e.blen)
	putU32LE(out, seed)
	return append(out, payload...)
}

func putU32LE(b []byte, v uint32) {
	b[0] = byte(v)
	b[1] = byte(v >> 8)
	b[2] = byte(v >> 16)
	b[3] = byte(v >> 24)
}

// ---------------------------------------------------------------------------
// Decoder
// ---------------------------------------------------------------------------

type equation struct {
	sel      []bool
	data     []byte // blen bytes, already peeled of known sources
	unknowns int
	active   bool
}

// Decoder accumulates source and LT blocks and peels via belief propagation.
type Decoder struct {
	k, blen, size int
	known         []bool
	src           [][]byte // blen-padded
	eqs           []*equation
	queue         []int // newly solved sources to cascade from
	solved        int
	stats         struct {
		blocks int // distinct blocks fed (sources + LT)
		eqs    int // stored equations
	}
}

// NewDecoder creates a decoder for a session.
func NewDecoder(k, blen, size int) (*Decoder, error) {
	if err := Validate(size, blen, k); err != nil {
		return nil, err
	}
	return &Decoder{k: k, blen: blen, size: size,
		known: make([]bool, k), src: make([][]byte, k)}, nil
}

// Solved reports whether all K source blocks are recovered.
func (d *Decoder) Solved() bool { return d.solved == d.k }

// Stats returns a one-line summary of decoder progress.
func (d *Decoder) Stats() string {
	return fmt.Sprintf("blocks=%d eqs=%d solved=%d/%d",
		d.stats.blocks, d.stats.eqs, d.solved, d.k)
}

// AddSource registers a raw source block (id < K). Payload must be exactly
// min(blen, size-id*blen) bytes.
func (d *Decoder) AddSource(id int, payload []byte) error {
	if id < 0 || id >= d.k {
		return fmt.Errorf("source id %d out of range", id)
	}
	if d.known[id] {
		return nil // idempotent
	}
	want := d.blen
	if lo, hi := id*d.blen, (id+1)*d.blen; hi > d.size {
		want = d.size - lo
	}
	if len(payload) != want {
		return fmt.Errorf("source %d: payload %d bytes, want %d", id, len(payload), want)
	}
	pad := make([]byte, d.blen)
	copy(pad, payload)
	d.src[id] = pad
	d.known[id] = true
	d.solved++
	d.stats.blocks++
	d.cascade(id)
	return nil
}

// AddLT registers one LT block (id >= K). payload must be exactly blen bytes.
func (d *Decoder) AddLT(seed uint32, payload []byte) error {
	if len(payload) != d.blen {
		return fmt.Errorf("LT payload %d bytes, want blen=%d", len(payload), d.blen)
	}
	d.stats.blocks++
	_, sel := selectionsFn(seed, d.k)
	eq := &equation{sel: sel, data: make([]byte, d.blen), active: true}
	copy(eq.data, payload)
	for i, on := range sel {
		if on && d.known[i] {
			xorInto(eq.data, d.src[i])
			eq.sel[i] = false
		}
	}
	// unknowns = remaining selected (parity-cancelled draws are not terms)
	unk := 0
	for i, on := range eq.sel {
		if on && !d.known[i] {
			unk++
		}
	}
	eq.unknowns = unk
	if unk == 0 {
		return nil // fully consumed
	}
	if unk == 1 {
		d.solveFrom(eq)
		return nil
	}
	d.eqs = append(d.eqs, eq)
	d.stats.eqs++
	return nil
}

// solveFrom derives the single remaining unknown of eq and cascades.
// Defensive: re-peels any knowns, re-scans true unknowns, and only solves
// when exactly one remains (bookkeeping-independent).
func (d *Decoder) solveFrom(eq *equation) {
	for i, on := range eq.sel {
		if on && d.known[i] {
			xorInto(eq.data, d.src[i])
			eq.sel[i] = false
		}
	}
	unknown := -1
	for i, on := range eq.sel {
		if !on || d.known[i] {
			continue
		}
		if unknown != -1 {
			return // >=2 true unknowns: not solvable now
		}
		unknown = i
	}
	if unknown == -1 {
		eq.active = false
		return
	}
	pad := make([]byte, d.blen)
	copy(pad, eq.data)
	d.src[unknown] = pad
	d.known[unknown] = true
	d.solved++
	eq.active = false
	d.cascade(unknown)
}

// cascade peels newly known source i from every active equation.
func (d *Decoder) cascade(i int) {
	d.queue = append(d.queue, i)
	for len(d.queue) > 0 {
		x := d.queue[0]
		d.queue = d.queue[1:]
		for _, eq := range d.eqs {
			if !eq.active || !eq.sel[x] {
				continue
			}
			xorInto(eq.data, d.src[x])
			eq.sel[x] = false
			eq.unknowns--
			switch eq.unknowns {
			case 0:
				eq.active = false
			case 1:
				d.solveFrom(eq)
			}
		}
	}
}

// File returns the assembled file once solved; nil otherwise.
func (d *Decoder) File() []byte {
	if !d.Solved() {
		return nil
	}
	out := make([]byte, 0, d.size)
	for i := 0; i < d.k; i++ {
		lo := i * d.blen
		hi := lo + d.blen
		if hi > d.size {
			hi = d.size
		}
		out = append(out, d.src[i][lo%d.blen:hi-lo+lo%d.blen]...)
	}
	return out
}

func xorInto(dst, src []byte) {
	for i := range dst {
		dst[i] ^= src[i]
	}
}
