// Package schedule implements PROTOCOL §5: per-pass deterministic shuffles
// of the slot space with MANIFEST frame injections. Both scheduler output
// and renderer layout are fully deterministic for a given sessionSeed.
package schedule

import (
	"fmt"

	"github.com/YuzhenZhou1327/airqr/internal/lt"
)

// Frame is one full-screen unit: either a grid of BLOCK slots (len(Slots)>0)
// and/or a MANIFEST display (HasManifest).
type Frame struct {
	Pass        int
	Idx         int    // frame index within the pass
	Slots       []int  // slot ids shown as BLOCK QRs (grid order)
	HasManifest bool   // one grid cell (or the whole screen) is MANIFEST
	LastPass    bool   // set on the final pass for UI
}

// Session describes a send session.
type Session struct {
	SessionSeed uint32
	K           int
	Blen        int
	G           int // grid cells per screen (2..8 even counts)
	Passes      int // 0 = infinite
	cycle       int
}

// New validates and builds a session.
func New(sessionSeed uint32, size, blen, g, passes int) (*Session, error) {
	k := lt.NumSourceBlocks(size, blen)
	if err := lt.Validate(size, blen, k); err != nil {
		return nil, err
	}
	if g < 1 || g > 8 {
		return nil, fmt.Errorf("grid G=%d out of [1,8]", g)
	}
	return &Session{SessionSeed: sessionSeed, K: k, Blen: blen, G: g,
		Passes: passes, cycle: lt.CycleLen(k)}, nil
}

// Cycle returns the slot-space size.
func (s *Session) Cycle() int { return s.cycle }

// framesPerPass returns the base frame count for one pass (before injections
// the count only grows by at most G-1 cells; see PassFrames).
func (s *Session) framesPerPass() int {
	return (s.cycle + s.G - 1) / s.G
}

// injectionFrames returns the set of frame indices carrying a MANIFEST.
// PROTOCOL §5: first frame, middle frame, and (if pass has >200 frames)
// every 120th frame.
func (s *Session) injectionFrames(f int) map[int]bool {
	pos := map[int]bool{0: true, f / 2: true}
	if f > 200 {
		for i := 120; i < f; i += 120 {
			pos[i] = true
		}
	}
	return pos
}

// PassFrames returns the deterministic frame sequence for one pass.
// Slots are shuffled (Fisher–Yates over splitmix64) and chunked G per frame;
// MANIFEST injections defer one slot to the end (frame cell shrinks by one).
func (s *Session) PassFrames(pass int) []Frame {
	order := s.passOrder(pass)
	inj := s.injectionFrames(s.framesPerPass())
	var frames []Frame
	q := order
	fIdx := 0
	for len(q) > 0 {
		fr := Frame{Pass: pass, Idx: fIdx}
		if inj[fIdx] {
			fr.HasManifest = true
			// one cell replaced by the manifest: only G-1 blocks this frame,
			// the deferred slot flows to later frames naturally
			if len(q) > 0 && s.G > 1 {
				n := s.G - 1
				if n > len(q) {
					n = len(q)
				}
				fr.Slots = q[:n]
				q = q[n:]
			}
		} else {
			n := s.G
			if n > len(q) {
				n = len(q)
			}
			fr.Slots = q[:n]
			q = q[n:]
		}
		frames = append(frames, fr)
		fIdx++
	}
	return frames
}

// passOrder produces the pass-shuffled slot ids (deterministic).
func (s *Session) passOrder(pass int) []int {
	p := lt.NewPRNG(uint64(s.SessionSeed) ^ 0x5EED5EED ^ (uint64(pass) << 32))
	order := make([]int, s.cycle)
	for i := range order {
		order[i] = i
	}
	for i := s.cycle - 1; i >= 1; i-- {
		j := int(p.Next32() % uint32(i+1))
		order[i], order[j] = order[j], order[i]
	}
	return order
}
