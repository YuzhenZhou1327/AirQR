// Package render composes full-screen QR grids as PNG images (PROTOCOL §2/§5).
//
// Design: each QR is printed black-on-white as a "card" (natural quiet zone),
// cards are laid out on a black canvas. White-on-black QRs would force
// TRY_HARDER/inverted decoding on the receiver — rejected by design.
//
// Performance (v1.16): bulk draw.Draw blits, parallel frame workers, and
// BestSpeed PNG. The old per-pixel Set() path was the dominant cost at 4K
// (~300ms/frame); this is 10-20x faster on multi-core hosts.
package render

import (
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"

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

var (
	whiteRGBA = color.RGBA{255, 255, 255, 255}
	blackRGBA = color.RGBA{0, 0, 0, 255}
	whiteImg  = image.NewUniform(whiteRGBA)
	blackImg  = image.NewUniform(blackRGBA)
)

// qrImage renders one payload to a white-card image (black modules),
// forced to the layout's QR version for uniform card geometry.
func qrImage(payload []byte, l Layout) (*image.RGBA, error) {
	q, err := qrcode.NewWithForcedVersion(string(payload), l.Version, l.ECC)
	if err != nil {
		return nil, fmt.Errorf("qr encode (v%d): %w", l.Version, err)
	}
	bm := q.Bitmap() // includes quiet-zone border when not disabled
	dims := len(bm)
	s := l.Scale
	w := dims * s
	card := image.NewRGBA(image.Rect(0, 0, w, w))
	// bulk-fill white, then paint only black modules
	draw.Draw(card, card.Bounds(), whiteImg, image.Point{}, draw.Src)
	pix, stride := card.Pix, card.Stride
	for my := 0; my < dims; my++ {
		if !bm[my][0] && !anyTrue(bm[my]) {
			continue // whole row white (common in quiet zone)
		}
		rowBase := my * s * stride
		for mx := 0; mx < dims; mx++ {
			if !bm[my][mx] {
				continue
			}
			x0 := mx * s
			for dy := 0; dy < s; dy++ {
				base := rowBase + dy*stride + x0*4
				for dx := 0; dx < s; dx++ {
					p := base + dx*4
					pix[p] = 0
					pix[p+1] = 0
					pix[p+2] = 0
					pix[p+3] = 255
				}
			}
		}
	}
	return card, nil
}

func anyTrue(row []bool) bool {
	for _, v := range row {
		if v {
			return true
		}
	}
	return false
}

// ComposeFrame lays out cell payloads (len = Rows*Cols, nil = empty cell)
// onto the black canvas; exactly one cell may be the manifest (text QR).
func ComposeFrame(cells [][]byte, l Layout) (image.Image, error) {
	if len(cells) != l.Rows*l.Cols {
		return nil, fmt.Errorf("got %d cells, want %d", len(cells), l.Rows*l.Cols)
	}
	imgs := make([]*image.RGBA, len(cells))
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
	draw.Draw(canvas, canvas.Bounds(), blackImg, image.Point{}, draw.Src)
	cellW := l.Cols*maxW + (l.Cols+1)*l.Gap
	cellH := l.Rows*maxH + (l.Rows+1)*l.Gap
	ox := (l.Width - cellW) / 2
	oy := (l.Height - cellH) / 2
	for i, im := range imgs {
		if im == nil {
			continue
		}
		r, c := i/l.Cols, i%l.Cols
		cx := ox + l.Gap + c*(maxW+l.Gap) + (maxW-im.Bounds().Dx())/2
		cy := oy + l.Gap + r*(maxH+l.Gap) + (maxH-im.Bounds().Dy())/2
		drawAt(canvas, im, cx, cy)
	}
	return canvas, nil
}

// drawAt copies src onto dst (integers only, no rescaling).
func drawAt(dst *image.RGBA, src image.Image, ox, oy int) {
	b := src.Bounds()
	r := image.Rect(ox, oy, ox+b.Dx(), oy+b.Dy())
	draw.Draw(dst, r, src, b.Min, draw.Src)
}

// SessionRenderer ties encoder + scheduler to PNG output.
type SessionRenderer struct {
	Sess     *schedule.Session
	Enc      *lt.Encoder
	TID      uint32
	Manifest []byte // packed manifest JSON
	Layout   Layout
	// OnProgress is invoked (from workers) as frames complete. done/total
	// are frames in the current RenderPass call.
	OnProgress func(done, total int)
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

// pngEncoder: BestSpeed is the right tradeoff for a slideshow of 4K frames
// (encode is a top-3 cost; visual content is 1-bit QR, compression level
// does not affect decode).
var pngEncoder = png.Encoder{CompressionLevel: png.BestSpeed}

// renderOneFrame composes and writes a single frame PNG.
func (r *SessionRenderer) renderOneFrame(pass, i int, fr schedule.Frame, outDir string) error {
	img, err := ComposeFrame(r.CellPayloads(fr), r.Layout)
	if err != nil {
		return err
	}
	name := filepath.Join(outDir, fmt.Sprintf("p%03d-f%05d.png", pass, i))
	f, err := os.Create(name)
	if err != nil {
		return err
	}
	err = pngEncoder.Encode(f, img)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	return err
}

// RenderPass writes all frames of one pass as PNGs into outDir.
// Frames are rendered on a worker pool (order of completion is arbitrary).
func (r *SessionRenderer) RenderPass(pass, maxFrames int, outDir string) (int, error) {
	frames := r.Sess.PassFrames(pass)
	n := len(frames)
	if maxFrames > 0 && maxFrames < n {
		n = maxFrames
	}
	if n == 0 {
		return 0, nil
	}
	workers := runtime.NumCPU()
	if workers > n {
		workers = n
	}
	if workers < 1 {
		workers = 1
	}

	jobs := make(chan int)
	var wg sync.WaitGroup
	var mu sync.Mutex
	var firstErr error
	done := 0

	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range jobs {
				mu.Lock()
				skip := firstErr != nil
				mu.Unlock()
				if skip {
					continue
				}
				err := r.renderOneFrame(pass, i, frames[i], outDir)
				mu.Lock()
				if err != nil && firstErr == nil {
					firstErr = err
				}
				done++
				cb := r.OnProgress
				d := done
				mu.Unlock()
				if cb != nil {
					cb(d, n)
				}
			}
		}()
	}
	for i := 0; i < n; i++ {
		jobs <- i
	}
	close(jobs)
	wg.Wait()
	if firstErr != nil {
		return done, firstErr
	}
	return n, nil
}

// ProgressStyle is a single-line CLI progress readout with ETA.
type ProgressStyle struct {
	Label      string
	Total      int // total frames across all passes
	Start      time.Time
	lastPrint  time.Time
	printEvery time.Duration
	width      int
	isTTY      bool
}

// NewProgress builds a progress printer. total is the overall frame count.
func NewProgress(label string, total int, isTTY bool) *ProgressStyle {
	return &ProgressStyle{
		Label:      label,
		Total:      total,
		Start:      time.Now(),
		printEvery: 100 * time.Millisecond,
		width:      28,
		isTTY:      isTTY,
	}
}

// FrameDone records n completed frames (cumulative for the whole job).
func (p *ProgressStyle) FrameDone(done int) {
	now := time.Now()
	if now.Sub(p.lastPrint) < p.printEvery && done < p.Total {
		return
	}
	p.lastPrint = now
	p.Print(done)
}

// Print writes the current bar line (no-op if not a TTY and not finished).
func (p *ProgressStyle) Print(done int) {
	if p.Total <= 0 {
		return
	}
	if done > p.Total {
		done = p.Total
	}
	elapsed := time.Since(p.Start)
	var eta time.Duration
	if done > 0 && done < p.Total {
		eta = time.Duration(float64(elapsed) / float64(done) * float64(p.Total-done))
	}
	frac := float64(done) / float64(p.Total)
	filled := int(frac * float64(p.width))
	if filled > p.width {
		filled = p.width
	}
	bar := make([]byte, p.width)
	for i := 0; i < p.width; i++ {
		if i < filled {
			bar[i] = '#'
		} else {
			bar[i] = '.'
		}
	}
	rate := 0.0
	if elapsed > 0 {
		rate = float64(done) / elapsed.Seconds()
	}
	line := fmt.Sprintf("%s [%s] %3.0f%%  %d/%d  %.1f/s  ETA %s",
		p.Label, bar, frac*100, done, p.Total, rate, fmtDur(eta))
	if p.isTTY {
		fmt.Fprintf(os.Stderr, "\r\033[K%s", line)
		if done >= p.Total {
			fmt.Fprintln(os.Stderr)
		}
	} else if done >= p.Total || done%10 == 0 {
		fmt.Fprintln(os.Stderr, line)
	}
}

// Finish prints a final 100% line.
func (p *ProgressStyle) Finish() {
	p.lastPrint = time.Time{}
	p.Print(p.Total)
}

func fmtDur(d time.Duration) string {
	if d < 0 {
		d = 0
	}
	sec := int(d.Seconds() + 0.5)
	if sec < 60 {
		return fmt.Sprintf("0:%02d", sec)
	}
	m := sec / 60
	s := sec % 60
	if m < 60 {
		return fmt.Sprintf("%d:%02d", m, s)
	}
	return fmt.Sprintf("%d:%02d:%02d", m/60, m%60, s)
}
