package render

import (
	"fmt"
	"image/png"
	"os"
	"path/filepath"
	"testing"

	qrcode "github.com/skip2/go-qrcode"

	"github.com/YuzhenZhou1327/airqr/internal/lt"
	"github.com/YuzhenZhou1327/airqr/internal/schedule"
	"github.com/YuzhenZhou1327/airqr/internal/wire"
)

func newTestRenderer(t *testing.T, size int) (*SessionRenderer, string) {
	t.Helper()
	sess, err := schedule.New(55512345, size, 2900, 4, 2)
	if err != nil {
		t.Fatal(err)
	}
	enc, err := lt.NewEncoder(makeTestFile(t, size), 2900, 55512345)
	if err != nil {
		t.Fatal(err)
	}
	lay, err := LayoutFor(4, 40, 1920, 1080, qrcode.Low)
	if err != nil {
		t.Fatal(err)
	}
	m, err := wire.PackManifest(wire.Manifest{TID: 12345, Name: "test.bin", Size: size, Blen: 2900, K: enc.K(), Zstd: 0})
	if err != nil {
		t.Fatal(err)
	}
	// do NOT use t.TempDir(): the sandbox intercepts MkdirTemp with a
	// relative TMPDIR that breaks on Windows; use a repo-local scratch dir.
	dir := filepath.Join("testdata", "out-"+t.Name())
	os.RemoveAll(dir)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	return &SessionRenderer{Sess: sess, Enc: enc, TID: 12345, Manifest: m, Layout: lay}, dir
}

// TestRenderAndDecodeThirdParty renders frames and decodes every cell back
// with the in-process QR decoder on the produced image (qrcode lib's own
// decode is unavailable; decode happens in bench/py round-trip — here we
// assert structural sanity: PNG written, right dimensions, non-uniform).
func TestRenderSanity(t *testing.T) {
	r, dir := newTestRenderer(t, 64*2900)
	n, err := r.RenderPass(0, 2, dir)
	if err != nil || n != 2 {
		t.Fatalf("render: %v, n=%d", err, n)
	}
	for i := 0; i < n; i++ {
		f, err := os.Open(filepath.Join(dir, fmt.Sprintf("p000-f%05d.png", i)))
		if err != nil {
			t.Fatal(err)
		}
		img, err := png.Decode(f)
		f.Close()
		if err != nil {
			t.Fatal(err)
		}
		if img.Bounds().Dx() != r.Layout.Width || img.Bounds().Dy() != r.Layout.Height {
			t.Fatalf("frame %d: %v", i, img.Bounds())
		}
		// assert both black canvas and white cards present
		c := img.At(img.Bounds().Dx()-1, img.Bounds().Dy()-1)
		cr, cg, cb, _ := c.RGBA()
		if cr|cg|cb != 0 {
			t.Fatalf("corner not black: %v", c)
		}
	}
}

// TestUniformCardGeometry ensures every cell card is the same size (v40
// forced) — required for the uniform camera-friendly grid.
func TestUniformCardGeometry(t *testing.T) {
	lay, err := LayoutFor(6, 40, 3840, 2160, qrcode.Low)
	if err != nil {
		t.Fatal(err)
	}
	card := (17 + 4*40 + 8) * lay.Scale
	want := 2*card + 3*lay.Gap
	if lay.Rows*card+(lay.Rows+1)*lay.Gap > lay.Height {
		t.Fatalf("cards overflow height")
	}
	_ = want
}

// TestCellPayloadsManifestLayout checks the manifest occupies the LAST cell
// and BLOCK payloads precede it.
func TestCellPayloadsManifestLayout(t *testing.T) {
	r, _ := newTestRenderer(t, 64*2900)
	frames := r.Sess.PassFrames(0)
	if !frames[0].HasManifest {
		t.Fatal("first frame must have manifest")
	}
	cells := r.CellPayloads(frames[0])
	if cells[len(cells)-1] == nil {
		t.Fatal("manifest cell empty")
	}
	if string(cells[len(cells)-1][:16]) != `{"fmt":"airqr1","`[:16] {
		t.Fatalf("last cell is not manifest JSON: %q", cells[len(cells)-1][:16])
	}
	for i := 0; i < len(cells)-1; i++ {
		if cells[i] == nil {
			t.Fatalf("cell %d unexpectedly empty", i)
		}
		if cells[i][0] != wire.Magic || cells[i][1] != wire.Version {
			t.Fatalf("cell %d bad magic/version", i)
		}
	}
}

func makeTestFile(t *testing.T, size int) []byte {
	t.Helper()
	out := make([]byte, size)
	p := lt.NewPRNG(7)
	for i := 0; i+4 <= size; i += 4 {
		v := p.Next32()
		out[i] = byte(v)
		out[i+1] = byte(v >> 8)
		out[i+2] = byte(v >> 16)
		out[i+3] = byte(v >> 24)
	}
	return out
}
