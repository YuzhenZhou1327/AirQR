// Package wire implements the AirQR line format per spec/PROTOCOL.md §2–§3.
// Go is the reference implementation; the Java side must pass the same
// golden vectors (spec/vectors/cases.json).
package wire

import (
	"encoding/binary"
	"errors"
	"fmt"
	"strconv"
)

const (
	// Magic is the first byte of every BLOCK QR payload.
	Magic = 0x51
	// Version is the current protocol version.
	Version = 1
	// HeaderLen is the BLOCK header size (magic,version,tid,blen,id).
	HeaderLen = 14
	// ManifestFormatTag appears in every MANIFEST JSON.
	ManifestFormatTag = "airqr1"
)

// ErrReject is the base error for every malformed frame (receiver drops it).
var ErrReject = errors.New("airqr: frame rejected")

func rejectf(format string, a ...any) error {
	return fmt.Errorf("%w: "+format, append([]any{ErrReject}, a...)...)
}

// PackBlock builds one BLOCK QR payload:
// [0]=magic [1]=version [2:6]=tid LE [6:10]=blen LE [10:14]=id LE [14:]=data.
func PackBlock(tid, blen, id uint32, data []byte) []byte {
	out := make([]byte, HeaderLen+len(data))
	out[0] = Magic
	out[1] = Version
	binary.LittleEndian.PutUint32(out[2:], tid)
	binary.LittleEndian.PutUint32(out[6:], blen)
	binary.LittleEndian.PutUint32(out[10:], id)
	copy(out[HeaderLen:], data)
	return out
}

// Block is a parsed BLOCK frame.
type Block struct {
	TID    uint32
	Blen   uint32
	ID     uint32
	Data   []byte // alias into the input slice; do not retain without copying
}

// ParseBlock validates and splits a BLOCK QR payload. Everything malformed
// returns an error wrapping ErrReject — the receiver must drop these.
func ParseBlock(payload []byte) (Block, error) {
	if len(payload) < HeaderLen {
		return Block{}, rejectf("payload %d bytes < header %d", len(payload), HeaderLen)
	}
	if payload[0] != Magic {
		return Block{}, rejectf("magic %#02x", payload[0])
	}
	if payload[1] != Version {
		return Block{}, rejectf("version %d", payload[1])
	}
	b := Block{
		TID:  binary.LittleEndian.Uint32(payload[2:]),
		Blen: binary.LittleEndian.Uint32(payload[6:]),
		ID:   binary.LittleEndian.Uint32(payload[10:]),
		Data: payload[HeaderLen:],
	}
	if b.Blen == 0 {
		return Block{}, rejectf("blen=0")
	}
	if len(b.Data) == 0 {
		return Block{}, rejectf("empty block_data")
	}
	// block_data = 4B seed + payload(≤blen): source blocks carry
	// 4+min(blen,rest), LT blocks exactly 4+blen (PROTOCOL §2).
	if len(b.Data) > int(b.Blen)+4 {
		return Block{}, rejectf("data %d > 4+blen %d", len(b.Data), b.Blen)
	}
	return b, nil
}

// Manifest is the parsed MANIFEST frame (PROTOCOL §3).
type Manifest struct {
	TID  uint32
	Name string
	Size int
	Blen int
	K    int
	Zstd int
}

// ValidName enforces the manifest name charset (ASCII printable 0x20–0x7E
// except '"', '\' and '/' — no path traversal, PROTOCOL §3).
func ValidName(name string) bool {
	if name == "" || len(name) > 128 {
		return false
	}
	for i := 0; i < len(name); i++ {
		c := name[i]
		if c < 0x20 || c > 0x7E || c == '"' || c == '\\' || c == '/' {
			return false
		}
	}
	return true
}

// PackManifest serializes the MANIFEST JSON (text-mode QR payload).
func PackManifest(m Manifest) ([]byte, error) {
	if !ValidName(m.Name) {
		return nil, rejectf("invalid name %q", m.Name)
	}
	if m.TID == 0 {
		return nil, rejectf("tid must be nonzero")
	}
	j := fmt.Sprintf(`{"fmt":"%s","tid":%d,"name":"%s","size":%d,"blen":%d,"k":%d,"zstd":%d}`,
		ManifestFormatTag, m.TID, m.Name, m.Size, m.Blen, m.K, m.Zstd)
	return []byte(j), nil
}

// ParseManifest parses a MANIFEST JSON payload with strict field checks.
func ParseManifest(payload []byte) (Manifest, error) {
	var m Manifest
	s := string(payload)
	// field-by-field scan; strict, no external JSON dep for order tolerance
	fields := map[string]string{}
	if err := scanJSONFields(s, fields); err != nil {
		return m, err
	}
	if fields["fmt"] != ManifestFormatTag {
		return m, rejectf("fmt %q", fields["fmt"])
	}
	tid, err := strconv.ParseUint(fields["tid"], 10, 32)
	if err != nil {
		return m, rejectf("tid %q", fields["tid"])
	}
	m.TID = uint32(tid)
	m.Name = fields["name"]
	if !ValidName(m.Name) {
		return m, rejectf("invalid name")
	}
	for k, bits := range map[string]int{"size": 63, "blen": 32, "k": 32, "zstd": 8} {
		v, err := strconv.ParseUint(fields[k], 10, bits)
		if err != nil {
			return m, rejectf("%s %q", k, fields[k])
		}
		switch k {
		case "size":
			m.Size = int(v)
		case "blen":
			m.Blen = int(v)
		case "k":
			m.K = int(v)
		case "zstd":
			m.Zstd = int(v)
		}
	}
	if m.Size < 1 || m.Blen < 1 || m.K < 1 || (m.Zstd != 0 && m.Zstd != 1) {
		return m, rejectf("field range")
	}
	if m.TID == 0 {
		return m, rejectf("tid must be nonzero")
	}
	return m, nil
}

// scanJSONFields parses the flat one-level manifest JSON object into a map.
// Strict: no nesting, no escapes needed (names are charset-limited), ints
// only as bare digit strings.
func scanJSONFields(s string, out map[string]string) error {
	i := 0
	skipWS := func() {
		for i < len(s) && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t') {
			i++
		}
	}
	skipWS()
	if i >= len(s) || s[i] != '{' {
		return rejectf("json: no opening brace")
	}
	i++
	skipWS()
	if i < len(s) && s[i] == '}' {
		return rejectf("json: empty object")
	}
	for {
		skipWS()
		if i >= len(s) || s[i] != '"' {
			return rejectf("json: expected key at %d", i)
		}
		i++
		kStart := i
		for i < len(s) && s[i] != '"' {
			i++
		}
		if i >= len(s) {
			return rejectf("json: unterminated key")
		}
		key := s[kStart:i]
		i++
		skipWS()
		if i >= len(s) || s[i] != ':' {
			return rejectf("json: expected : after %q", key)
		}
		i++
		skipWS()
		var val string
		if i < len(s) && s[i] == '"' {
			i++
			vStart := i
			for i < len(s) && s[i] != '"' {
				i++
			}
			if i >= len(s) {
				return rejectf("json: unterminated value")
			}
			val = s[vStart:i]
			i++
		} else {
			vStart := i
			for i < len(s) && s[i] >= '0' && s[i] <= '9' {
				i++
			}
			if i == vStart {
				return rejectf("json: expected number at %d", vStart)
			}
			val = s[vStart:i]
		}
		out[key] = val
		skipWS()
		if i < len(s) && s[i] == ',' {
			i++
			continue
		}
		if i < len(s) && s[i] == '}' {
			i++
			// trailing content after the closing brace is rejected
			skipWS()
			if i != len(s) {
				return rejectf("json: trailing content at %d", i)
			}
			return nil
		}
		return rejectf("json: expected , or } at %d", i)
	}
}
