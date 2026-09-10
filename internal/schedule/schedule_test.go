package schedule

import (
	"testing"

	"github.com/YuzhenZhou1327/airqr/internal/lt"
)

func newTestSession(t *testing.T, k int) *Session {
	t.Helper()
	s, err := New(55512345, k*2900, 2900, 6, 3)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestPassFramesDeterministic(t *testing.T) {
	s := newTestSession(t, 50)
	a := s.PassFrames(0)
	b := s.PassFrames(0)
	if len(a) != len(b) {
		t.Fatalf("frame count differs")
	}
	for i := range a {
		if len(a[i].Slots) != len(b[i].Slots) {
			t.Fatalf("frame %d slot count differs", i)
		}
		for j := range a[i].Slots {
			if a[i].Slots[j] != b[i].Slots[j] {
				t.Fatalf("frame %d slot %d differs", i, j)
			}
		}
	}
}

func TestPassFramesCoverAllSlots(t *testing.T) {
	s := newTestSession(t, 50)
	seen := map[int]int{}
	for _, fr := range s.PassFrames(0) {
		for _, id := range fr.Slots {
			seen[id]++
		}
	}
	if len(seen) != s.Cycle() {
		t.Fatalf("covered %d of %d slots", len(seen), s.Cycle())
	}
	for id, n := range seen {
		if n != 1 {
			t.Fatalf("slot %d appears %d times in one pass", id, n)
		}
	}
}

func TestManifestInjection(t *testing.T) {
	s := newTestSession(t, 50)
	frames := s.PassFrames(0)
	inj := 0
	for _, fr := range frames {
		if fr.HasManifest {
			inj++
		}
	}
	if inj != 2 {
		t.Fatalf("expected 2 manifest frames, got %d", inj)
	}
	if frames[0].HasManifest == false {
		t.Fatal("first frame must carry manifest")
	}
	// every other frame must have exactly G slots
	for i, fr := range frames {
		if fr.HasManifest {
			continue
		}
		if len(fr.Slots) != s.G && i != len(frames)-1 {
			t.Fatalf("frame %d has %d slots, want %d", i, len(fr.Slots), s.G)
		}
	}
	// non-manifest frames must have G slots except the final remainder
	last := frames[len(frames)-1]
	if !last.HasManifest && len(last.Slots) > s.G {
		t.Fatalf("last frame oversized")
	}
}

func TestPassesDifferInOrder(t *testing.T) {
	s := newTestSession(t, 50)
	p0 := s.PassFrames(0)
	p1 := s.PassFrames(1)
	same := true
	for i := 0; i < len(p0) && i < len(p1); i++ {
		if len(p0[i].Slots) != len(p1[i].Slots) {
			same = false
			break
		}
		if len(p0[i].Slots) > 0 && p0[i].Slots[0] != p1[i].Slots[0] {
			same = false
			break
		}
	}
	if same {
		t.Fatal("pass 0 and 1 are identical — shuffle not advancing")
	}
}

func TestSmallKCycle(t *testing.T) {
	s, err := New(42, 300, 100, 4, 0) // K=3 → cycle=max(32, ceil(3.75))=32
	if err != nil {
		t.Fatal(err)
	}
	if s.Cycle() != 32 {
		t.Fatalf("cycle=%d want 32", s.Cycle())
	}
	if lt.CycleLen(3) != 32 {
		t.Fatal("min-cycle rule regressed")
	}
}
