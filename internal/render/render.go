// Package render composes full-screen QR grids as PNG images (PROTOCOL §2/§5).
//
// Design: each QR is printed black-on-white as a "card" (natural quiet zone),
// cards are laid out on a black canvas. White-on-black QRs would force
// TRY_HARDER/inverted decoding on the receiver — rejected by design.
package render

import (
	"fmt"
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"

	qrcode "github.com/skip2/go-qrcode"

	"github.com/YuzhenZhou1327/airqr/internal/lt"
	"github.com/YuzhenZhou1327/airqr/internal/schedule"
	"github.com/YuzhenZhou1327/airqr/internal/wire"
)

// Layout is the full-screen geometry config.
type Layout struct {
	Version int                  // forced QR version for every cell (uniformity)
	Scale   int                  // px per QR module
	ECC     qrcode.RecoveryLevel // QR ECC level
	Gap     int                  // inter-card gap in px (black)
	Rows    int
	Cols    int
	Width   int // canvas px
	Height  int
}

// QR modules for a version (includes the 6 function-pattern rows/cols).
func qrModules(version int) int { return 17 + 4*version }

// LayoutFor computes a Layout fitting g cards into width×height.
// Card = modules(version) + 2*4 quiet-zone modules, drawn inside a white card.
func LayoutFor(g, version, width, height int, ecc qrcode.RecoveryLevel) (Layout, error) {
	rows, cols := GridGeom(g)
	card := qrModules(version) + 8
	maxScaleW := (width - 3*16) / (cols * card)
	maxScaleH := (height - 3*16) / (rows * card)
	s := maxScaleW
	if maxScaleH < s {
		s = maxScaleH
	}
	if s < 2 {
		return Layout{}, fmt.Errorf("screen %dx%d too small for %d v%d cards", width, height, g, version)
	}
	return Layout{Version: version, Scale: s, ECC: ecc, Gap: 16, Rows: rows, Cols: cols,
		Width: width, Height: height}, nil
}

// GridGeom maps grid cell count to (rows, cols) for 16:9 screens.
func GridGeom(g int) (int, int) {
	switch g {
	case 1:
		return 1, 1
	case 2:
		return 1, 2
	case 4:
		return 2, 2
	case 6:
		return 2, 3
	case 8:
		return 2, 4
	default:
		panic(fmt.Sprintf("unsupported grid %d", g))
	}
}

// qrImage renders one payload to a white-card image (black modules),
// forced to the layout's QR version for uniform card geometry.
func qrImage(payload []byte, l Layout) (image.Image, error) {
	q, err := qrcode.NewWithForcedVersion(string(payload), l.Version, l.ECC)
	if err != nil {
		return nil, fmt.Errorf("qr encode (v%d): %w", l.Version, err)
	}
	bm := q.Bitmap() // includes quiet-zone border when not disabled
	dims := len(bm)
	card := image.NewRGBA(image.Rect(0, 0, dims*l.Scale, dims*l.Scale))
	white := color.RGBA{255, 255, 255, 255}
	black := color.RGBA{0, 0, 0, 255}
	for my := 0; my < dims; my++ {
		for mx := 0; mx < dims; mx++ {
			c := white
			if bm[my][mx] {
				c = black
			}
			for dy := 0; dy < l.Scale; dy++ {
				for dx := 0; dx < l.Scale; dx++ {
					card.Set(mx*l.Scale+dx, my*l.Scale+dy, c)
				}
			}
		}
	}
	return card, nil
}

// ComposeFrame lays out cell payloads (len = Rows*Cols, nil = empty cell)
// onto the black canvas; exactly one cell may be the manifest (text QR).
func ComposeFrame(cells [][]byte, l Layout) (image.Image, error) {
	if len(cells) != l.Rows*l.Cols {
		return nil, fmt.Errorf("got %d cells, want %d", len(cells), l.Rows*l.Cols)
	}
	// render all cells first; find uniform card box
	imgs := make([]image.Image, len(cells))
	maxW, maxH := 0, 0
	for i, p := range cells {
		if p == nil {
			continue
		}
		im, err := qrImage(p, l)
		if err != nil {
			return nil, err
		}
		imgs[i] = im
		if im.Bounds().Dx() > maxW {
			maxW = im.Bounds().Dx()
		}
		if im.Bounds().Dy() > maxH {
			maxH = im.Bounds().Dy()
		}
	}
	canvas := image.NewRGBA(image.Rect(0, 0, l.Width, l.Height))
	black := color.RGBA{0, 0, 0, 255}
	for y := 0; y < l.Height; y++ {
		for x := 0; x < l.Width; x++ {
			canvas.Set(x, y, black)
		}
	}
	cellW := l.Cols*maxW + (l.Cols+1)*l.Gap
	cellH := l.Rows*maxH + (l.Rows+1)*l.Gap
	ox := (l.Width - cellW) / 2
	oy := (l.Height - cellH) / 2
	for i, im := range imgs {
		if im == nil {
			continue
		}
		r, c := i/l.Cols, i%l.Cols
		// center the QR within its uniform card box
		cx := ox + l.Gap + c*(maxW+l.Gap) + (maxW-im.Bounds().Dx())/2
		cy := oy + l.Gap + r*(maxH+l.Gap) + (maxH-im.Bounds().Dy())/2
		drawAt(canvas, im, cx, cy)
	}
	return canvas, nil
}

// drawAt copies src onto dst (no rescaling — integers only).
func drawAt(dst *image.RGBA, src image.Image, ox, oy int) {
	b := src.Bounds()
	for y := 0; y < b.Dy(); y++ {
		for x := 0; x < b.Dx(); x++ {
			dst.Set(ox+x, oy+y, src.At(b.Min.X+x, b.Min.Y+y))
		}
	}
}

// SessionRenderer ties encoder + scheduler to PNG output.
type SessionRenderer struct {
	Sess     *schedule.Session
	Enc      *lt.Encoder
	TID      uint32
	Manifest []byte // packed manifest JSON
	Layout   Layout
}

// CellPayloads returns the payloads for one frame (nil for empty cells).
func (r *SessionRenderer) CellPayloads(fr schedule.Frame) [][]byte {
	cells := make([][]byte, r.Layout.Rows*r.Layout.Cols)
	// manifest takes the LAST cell of the frame grid (deterministic corner)
	idx := 0
	n := len(fr.Slots)
	if fr.HasManifest {
		cells[len(cells)-1] = r.Manifest
		n = len(fr.Slots) // blocks still fill from the first cell
	}
	for i := 0; i < n; i++ {
		id := fr.Slots[i]
		cells[idx] = wire.PackBlock(r.TID, uint32(r.Sess.Blen), uint32(id), r.Enc.BlockData(id))
		idx++
	}
	return cells
}

// RenderPass writes all frames of one pass as PNGs into outDir.
func (r *SessionRenderer) RenderPass(pass, maxFrames int, outDir string) (int, error) {
	frames := r.Sess.PassFrames(pass)
	n := len(frames)
	if maxFrames > 0 && maxFrames < n {
		n = maxFrames
	}
	for i := 0; i < n; i++ {
		img, err := ComposeFrame(r.CellPayloads(frames[i]), r.Layout)
		if err != nil {
			return i, err
		}
		name := filepath.Join(outDir, fmt.Sprintf("p%03d-f%05d.png", pass, i))
		f, err := os.Create(name)
		if err != nil {
			return i, err
		}
		if err := png.Encode(f, img); err != nil {
			f.Close()
			return i, err
		}
		f.Close()
	}
	return n, nil
}
