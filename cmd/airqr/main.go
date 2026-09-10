// Command airqr — sender-side CLI (render today; SDL send in M2).
package main

import (
	"crypto/rand"
	"encoding/binary"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"

	qrcode "github.com/skip2/go-qrcode"

	"github.com/YuzhenZhou1327/airqr/internal/lt"
	"github.com/YuzhenZhou1327/airqr/internal/render"
	"github.com/YuzhenZhou1327/airqr/internal/schedule"
	"github.com/YuzhenZhou1327/airqr/internal/wire"
)

const usage = `airqr — air-gapped QR file transfer (sender side)

Usage:
  airqr render <file> [flags]     render PNG frames for bench/offline playback
  airqr send   <file> [flags]     fullscreen player (M2)

render flags:
  --out DIR       output directory (default frames)
  --grid N        QR cards per screen: 1,2,4,6,8 (default 4)
  --version N     QR version (default 40)
  --ecc L|M       QR error correction (default L)
  --width PX      canvas width (default 3840)
  --height PX     canvas height (default 2160)
  --passes N      passes to render, 0=infinite unsupported for render (default 1)
  --seed N        26-bit session seed (default: random)
  --tid N         transfer id (default: random)
  --max-frames N  stop after N frames per pass (0 = all)`

func die(format string, a ...any) {
	fmt.Fprintf(os.Stderr, "airqr: "+format+"\n", a...)
	os.Exit(2)
}

func randUint32() uint32 {
	var b [4]byte
	if _, err := rand.Read(b[:]); err != nil {
		die("crypto rand: %v", err)
	}
	return binary.LittleEndian.Uint32(b[:])
}

func main() {
	if len(os.Args) < 2 {
		die("%s", usage)
	}
	switch os.Args[1] {
	case "render":
		renderCmd(os.Args[2:])
	case "send":
		sendCmd(os.Args[2:])
	default:
		die("unknown command %q\n%s", os.Args[1], usage)
	}
}

// sendCmd renders to a temp dir and loops a fullscreen player.
// Linux: feh slideshow (fast, no cgo needed). Other OS: instruct to use render+player manually.
func sendCmd(args []string) {
	var pos []string
	var flags []string
	for i := 0; i < len(args); i++ {
		if strings.HasPrefix(args[i], "-") && args[i] != "-" {
			flags = append(flags, args[i])
			if i+1 < len(args) && !strings.HasPrefix(args[i+1], "-") &&
				!strings.Contains(args[i], "=") {
				flags = append(flags, args[i+1])
				i++
			}
		} else {
			pos = append(pos, args[i])
		}
	}
	fs := flag.NewFlagSet("send", flag.ExitOnError)
	grid := fs.Int("grid", 4, "QR cards per screen")
	version := fs.Int("version", 40, "QR version")
	ecc := fs.String("ecc", "L", "QR ECC level")
	width := fs.Int("width", 0, "canvas width (0 = auto by OS)")
	height := fs.Int("height", 0, "canvas height (0 = auto by OS)")
	fps := fs.Float64("fps", 3, "frames per second")
	passes := fs.Int("passes", 0, "passes (0 = infinite loop of last pass set)")
	seed := fs.Uint64("seed", 0, "26-bit session seed (0=random)")
	tid := fs.Uint64("tid", 0, "transfer id (0=random)")
	if err := fs.Parse(flags); err != nil {
		die("%v", err)
	}
	if len(pos) != 1 {
		die("send needs exactly one input file\n%s", usage)
	}
	if *seed == 0 {
		*seed = uint64(randUint32() % (1 << 26))
	}
	if *tid == 0 {
		*tid = uint64(randUint32())
	}
	if *tid == 0 {
		*tid = 1
	}
	// default canvas by GOOS
	if *width == 0 || *height == 0 {
		switch runtime.GOOS {
		case "linux":
			*width, *height = 3840, 2160
		default:
			*width, *height = 1920, 1080
		}
	}
	home, err := os.UserCacheDir()
	if err != nil {
		die("%v", err)
	}
	out := filepath.Join(home, "airqr", fmt.Sprintf("s%d-t%d", *seed, *tid))
	os.RemoveAll(out)
	_ = passes // infinite loop handled by feh --reload; we render once with generous passes
	renderFrames(pos[0], out, *grid, *version, *ecc, *width, *height, 3, *seed, *tid, 0)
	switch runtime.GOOS {
	case "linux":
		delay := fmt.Sprintf("%.3f", 1.0 / *fps)
		fmt.Println("playing with feh (q quits; restart to resend):")
		cmd := exec.Command("feh", "--fullscreen", "--slideshow-delay", delay, "--recursive", out)
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		if err := cmd.Run(); err != nil {
			die("feh: %v (install feh, or use `render` + your own slideshow)", err)
		}
	default:
		fmt.Printf("frames written to %s\n", out)
		fmt.Println("no builtin player for this OS; use `render` + any image slideshow tool.")
	}
}

// renderFrames is the shared render pipeline (used by render and send).
func renderFrames(path, out string, grid, version int, ecc string, width, height, passes int, seed, tid uint64, maxFrames int) {
	data, err := os.ReadFile(path)
	if err != nil {
		die("read %s: %v", path, err)
	}
	if len(data) == 0 {
		die("empty file")
	}
	if len(data) > 10<<20 {
		die("file %d bytes exceeds 10MB design limit", len(data))
	}
	blen := 2900
	if version == 25 {
		blen = 1200
	} else if strings.EqualFold(ecc, "M") {
		blen = 2200
	}
	k := lt.NumSourceBlocks(len(data), blen)
	if k > lt.MaxK {
		die("K=%d exceeds %d (file too large for blen=%d)", k, lt.MaxK, blen)
	}
	if seed >= 1<<26 {
		die("seed must be < 2^26")
	}
	level := qrcode.Low
	if strings.EqualFold(ecc, "M") {
		level = qrcode.Medium
	}
	lay, err := render.LayoutFor(grid, version, width, height, level)
	if err != nil {
		die("%v", err)
	}
	sess, err := schedule.New(uint32(seed), len(data), blen, grid, passes)
	if err != nil {
		die("%v", err)
	}
	enc, err := lt.NewEncoder(data, blen, uint32(seed))
	if err != nil {
		die("%v", err)
	}
	name := filepath.Base(path)
	if !wire.ValidName(name) {
		die("file name %q violates manifest charset; rename the file", name)
	}
	man, err := wire.PackManifest(wire.Manifest{
		TID: uint32(tid), Name: name, Size: len(data), Blen: blen, K: k, Zstd: 0,
	})
	if err != nil {
		die("%v", err)
	}
	r := &render.SessionRenderer{Sess: sess, Enc: enc, TID: uint32(tid), Manifest: man, Layout: lay}
	if err := os.MkdirAll(out, 0o755); err != nil {
		die("%v", err)
	}
	total := 0
	for p := 0; p < passes; p++ {
		n, err := r.RenderPass(p, maxFrames, out)
		if err != nil {
			die("pass %d: %v", p, err)
		}
		total += n
	}
	info := filepath.Join(out, "session.txt")
	os.WriteFile(info, []byte(strings.Join([]string{
		"tid=" + strconv.FormatUint(tid, 10),
		"seed=" + strconv.FormatUint(seed, 10),
		"size=" + strconv.Itoa(len(data)),
		"blen=" + strconv.Itoa(blen),
		"k=" + strconv.Itoa(k),
		"sha256=see-bench",
		"frames=" + strconv.Itoa(total),
		"grid=" + strconv.Itoa(grid),
		"version=" + strconv.Itoa(version),
	}, "\n")), 0o644)
	fmt.Printf("rendered %d frames (%d passes, K=%d, cycle=%d, blen=%d, seed=%d, tid=%d) -> %s\n",
		total, passes, k, sess.Cycle(), blen, seed, tid, out)
}

func renderCmd(args []string) {
	// Go's flag package stops at the first positional; split them up front
	// so `airqr render file --seed 1` and `airqr render --seed 1 file` both work.
	var pos []string
	var flags []string
	for i := 0; i < len(args); i++ {
		if strings.HasPrefix(args[i], "-") && args[i] != "-" {
			flags = append(flags, args[i])
			if i+1 < len(args) && !strings.HasPrefix(args[i+1], "-") &&
				!strings.Contains(args[i], "=") {
				flags = append(flags, args[i+1])
				i++
			}
		} else {
			pos = append(pos, args[i])
		}
	}
	fs := flag.NewFlagSet("render", flag.ExitOnError)
	out := fs.String("out", "frames", "output directory")
	grid := fs.Int("grid", 4, "QR cards per screen")
	version := fs.Int("version", 40, "QR version")
	ecc := fs.String("ecc", "L", "QR ECC level")
	width := fs.Int("width", 3840, "canvas width")
	height := fs.Int("height", 2160, "canvas height")
	passes := fs.Int("passes", 1, "passes to render")
	seed := fs.Uint64("seed", 0, "26-bit session seed (0=random)")
	tid := fs.Uint64("tid", 0, "transfer id (0=random)")
	maxFrames := fs.Int("max-frames", 0, "stop after N frames per pass")
	if err := fs.Parse(flags); err != nil {
		die("%v", err)
	}
	if len(pos) != 1 {
		die("render needs exactly one input file\n%s", usage)
	}
	path := pos[0]
	data, err := os.ReadFile(path)
	if err != nil {
		die("read %s: %v", path, err)
	}
	if len(data) == 0 {
		die("empty file")
	}
	if len(data) > 10<<20 {
		die("file %d bytes exceeds 10MB design limit", len(data))
	}
	blen := 2900
	if *version == 25 {
		blen = 1200
	} else if *ecc == "M" {
		blen = 2200
	}
	k := lt.NumSourceBlocks(len(data), blen)
	if k > lt.MaxK {
		die("K=%d exceeds %d (file too large for blen=%d)", k, lt.MaxK, blen)
	}
	if *seed == 0 {
		*seed = uint64(randUint32() % (1 << 26))
	}
	if *seed >= 1<<26 {
		die("seed must be < 2^26")
	}
	if *tid == 0 {
		*tid = uint64(randUint32())
	}
	if *tid == 0 {
		*tid = 1
	}
	level := qrcode.Low
	if strings.EqualFold(*ecc, "M") {
		level = qrcode.Medium
	}
	lay, err := render.LayoutFor(*grid, *version, *width, *height, level)
	if err != nil {
		die("%v", err)
	}
	sess, err := schedule.New(uint32(*seed), len(data), blen, *grid, *passes)
	if err != nil {
		die("%v", err)
	}
	enc, err := lt.NewEncoder(data, blen, uint32(*seed))
	if err != nil {
		die("%v", err)
	}
	name := filepath.Base(path)
	if !wire.ValidName(name) {
		die("file name %q violates manifest charset; rename the file", name)
	}
	man, err := wire.PackManifest(wire.Manifest{
		TID: uint32(*tid), Name: name, Size: len(data), Blen: blen, K: k, Zstd: 0,
	})
	if err != nil {
		die("%v", err)
	}
	r := &render.SessionRenderer{Sess: sess, Enc: enc, TID: uint32(*tid), Manifest: man, Layout: lay}
	if err := os.MkdirAll(*out, 0o755); err != nil {
		die("%v", err)
	}
	total := 0
	for p := 0; p < *passes; p++ {
		n, err := r.RenderPass(p, *maxFrames, *out)
		if err != nil {
			die("pass %d: %v", p, err)
		}
		total += n
	}
	info := filepath.Join(*out, "session.txt")
	os.WriteFile(info, []byte(strings.Join([]string{
		"tid=" + strconv.FormatUint(*tid, 10),
		"seed=" + strconv.FormatUint(*seed, 10),
		"size=" + strconv.Itoa(len(data)),
		"blen=" + strconv.Itoa(blen),
		"k=" + strconv.Itoa(k),
		"sha256=see-bench",
		"frames=" + strconv.Itoa(total),
		"grid=" + strconv.Itoa(*grid),
		"version=" + strconv.Itoa(*version),
	}, "\n")), 0o644)
	fmt.Printf("rendered %d frames (%d passes, K=%d, cycle=%d, blen=%d, seed=%d, tid=%d) -> %s\n",
		total, *passes, k, sess.Cycle(), blen, *seed, *tid, *out)
}
