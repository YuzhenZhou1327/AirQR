package lt

import (
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"hash/crc32"
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

func packBlockForVectors(tid, blen, id uint32, data []byte) []byte {
	out := make([]byte, 18+len(data))
	out[0] = 0x51
	out[1] = 1
	binary.LittleEndian.PutUint32(out[2:], tid)
	binary.LittleEndian.PutUint32(out[6:], blen)
	binary.LittleEndian.PutUint32(out[10:], id)
	binary.LittleEndian.PutUint32(out[14:], crc32.ChecksumIEEE(data))
	copy(out[18:], data)
	return out
}

func hexStr(b []byte) string { return hex.EncodeToString(b) }

func itoa(n int) string { return strconv.Itoa(n) }

func u32s(v uint32) string { return strconv.FormatUint(uint64(v), 10) }

func mustPRNGBytes(t *testing.T, n int, stream uint64) []byte {
	t.Helper()
	out := make([]byte, n)
	p := NewPRNG(stream * 0x51CE)
	for i := 0; i+4 <= n; i += 4 {
		binary.LittleEndian.PutUint32(out[i:], p.Next32())
	}
	return out
}

// TestGenerateGoldenVectors writes spec/vectors/cases.json from the reference
// implementation. Re-run whenever the protocol changes (then port to Java).
// Run: go test ./internal/lt/ -run TestGenerateGoldenVectors -v
func TestGenerateGoldenVectors(t *testing.T) {
	if os.Getenv("AIRQR_GEN_VECTORS") == "" {
		t.Skip("set AIRQR_GEN_VECTORS=1 to regenerate spec/vectors/cases.json")
	}
	type vecCase struct {
		Case       string `json:"case"`
		FileHex    string `json:"file_hex"`
		Blen       int    `json:"blen"`
		K          int    `json:"k"`
		Seed       uint32 `json:"seed"`
		TID        uint32 `json:"tid"`
		Block0Hex  string `json:"block_0_hex"`
		BlockC0Hex string `json:"block_c0_hex"`
		Manifest   string `json:"manifest_json"`
		AllBlocks  string `json:"all_blocks_hex"` // concatenation of PackBlock(id) for id<cycle
	}
	gen := func(name string, size, blen int, seed, tid uint32, data []byte) vecCase {
		enc, err := NewEncoder(data, blen, seed)
		if err != nil {
			t.Fatal(err)
		}
		c0 := enc.BlockData(enc.K())
		var allPayload []byte
		for id := 0; id < enc.Cycle(); id++ {
			// wrap with wire header exactly as the sender does
			allPayload = append(allPayload, packBlockForVectors(tid, uint32(blen), uint32(id), enc.BlockData(id))...)
		}
		man := `{"fmt":"airqr1","tid":` + u32s(tid) + `,"name":"vectors.bin","size":` + itoa(len(data)) +
			`,"blen":` + itoa(blen) + `,"k":` + itoa(enc.K()) + `,"zstd":0}`
		return vecCase{
			Case: name, FileHex: hexStr(data), Blen: blen, K: enc.K(), Seed: seed, TID: tid,
			Block0Hex:  hexStr(enc.BlockData(0)),
			BlockC0Hex: hexStr(c0),
			Manifest:   man,
			AllBlocks:  hexStr(allPayload),
		}
	}
	cases := []vecCase{
		gen("anchor-k2-blen16", 32, 16, 9, 7,
			[]byte{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
				0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F}),
		gen("small-256b", 256, 16, 314159, 42, mustPRNGBytes(t, 256, 1)),
		gen("one-blen-2900", 2900, 2900, 55512345, 12345, mustPRNGBytes(t, 2900, 2)),
		gen("typical-100kb", 100<<10, 2900, 271828, 777, mustPRNGBytes(t, 100<<10, 3)),
		gen("odd-tail", 2900*10+137, 2900, 161803, 888, mustPRNGBytes(t, 2900*10+137, 4)),
	}
	out := map[string][]vecCase{"cases": cases}
	blob, err := json.MarshalIndent(out, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	p := filepath.Join("..", "..", "spec", "vectors", "cases.json")
	if err := os.WriteFile(p, blob, 0o644); err != nil {
		t.Fatal(err)
	}
	t.Logf("wrote %s (%d bytes, %d cases)", p, len(blob), len(cases))
}
