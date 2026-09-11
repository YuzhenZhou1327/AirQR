package wire

import (
	"bytes"
	"strings"
	"testing"
)

func TestBlockRoundTrip(t *testing.T) {
	data := bytes.Repeat([]byte{0xAB}, 2900)
	p := PackBlock(0xDEADBEEF, 2900, 12345, data)
	b, err := ParseBlock(p)
	if err != nil {
		t.Fatal(err)
	}
	if b.TID != 0xDEADBEEF || b.Blen != 2900 || b.ID != 12345 {
		t.Fatalf("header fields wrong: %+v", b)
	}
	if !bytes.Equal(b.Data, data) {
		t.Fatal("data mismatch")
	}
}

func TestBlockReject(t *testing.T) {
	good := PackBlock(1, 2900, 0, []byte("hello"))
	cases := map[string][]byte{
		"truncated":       good[:13],
		"empty":           {},
		"bad-magic":       append([]byte{0x52}, good[1:]...),
		"bad-version":     append([]byte{0x51, 2}, good[2:]...),
		"blen-zero":       PackBlock(1, 0, 0, []byte("x")),
		"header-only":     PackBlock(1, 2900, 0, nil),
	}
	for name, payload := range cases {
		if _, err := ParseBlock(payload); err == nil {
			t.Errorf("%s: expected rejection", name)
		}
	}
	// future version must be rejected (we are strict v1)
	if _, err := ParseBlock(good); err != nil {
		t.Errorf("good frame rejected: %v", err)
	}
}

func TestManifestRoundTrip(t *testing.T) {
	m := Manifest{TID: 305419896, Name: "report.pdf", Size: 1048576, Blen: 2900, K: 362, Zstd: 0, Grid: 4}
	j, err := PackManifest(m)
	if err != nil {
		t.Fatal(err)
	}
	got, err := ParseManifest(j)
	if err != nil {
		t.Fatalf("parse: %v (json=%s)", err, j)
	}
	if got != m {
		t.Fatalf("mismatch: %+v vs %+v", got, m)
	}
}

func TestManifestFieldOrderAgnostic(t *testing.T) {
	// receiver must accept any key order / whitespace
	j := []byte(`{ "zstd" : 0 , "k":362,"blen":2900, "size":1048576, "name":"a.bin","tid":42,"fmt":"airqr1" }`)
	m, err := ParseManifest(j)
	if err != nil {
		t.Fatal(err)
	}
	if m.TID != 42 || m.Name != "a.bin" || m.Size != 1048576 || m.Blen != 2900 || m.K != 362 || m.Zstd != 0 {
		t.Fatalf("fields wrong: %+v", m)
	}
	if m.Grid != 0 {
		t.Fatalf("absent grid must parse as 0 (unknown), got %+v", m)
	}
}

func TestManifestReject(t *testing.T) {
	cases := map[string]string{
		"bad-fmt":     `{"fmt":"other","tid":1,"name":"a","size":1,"blen":10,"k":1,"zstd":0}`,
		"empty-name":  `{"fmt":"airqr1","tid":1,"name":"","size":1,"blen":10,"k":1,"zstd":0}`,
		"path-name":   `{"fmt":"airqr1","tid":1,"name":"a/b","size":1,"blen":10,"k":1,"zstd":0}`,
		"quote-name":  `{"fmt":"airqr1","tid":1,"name":"a\"b","size":1,"blen":10,"k":1,"zstd":0}`,
		"backslash":   `{"fmt":"airqr1","tid":1,"name":"a\\b","size":1,"blen":10,"k":1,"zstd":0}`,
		"neg-size":    `{"fmt":"airqr1","tid":1,"name":"a","size":-1,"blen":10,"k":1,"zstd":0}`,
		"bad-blen":    `{"fmt":"airqr1","tid":1,"name":"a","size":1,"blen":0,"k":1,"zstd":0}`,
		"bad-zstd":    `{"fmt":"airqr1","tid":1,"name":"a","size":1,"blen":10,"k":1,"zstd":2}`,
		"bad-grid":    `{"fmt":"airqr1","tid":1,"name":"a","size":1,"blen":10,"k":1,"zstd":0,"grid":3}`,
		"grid-text":   `{"fmt":"airqr1","tid":1,"name":"a","size":1,"blen":10,"k":1,"zstd":0,"grid":"x"}`,
		"bad-tid":     `{"fmt":"airqr1","tid":0,"name":"a","size":1,"blen":10,"k":1,"zstd":0}`,
		"missing-key": `{"fmt":"airqr1","tid":1,"name":"a","size":1,"blen":10,"k":1}`,
		"not-object":  `"airqr1"`,
		"nested":      `{"fmt":"airqr1","tid":1,"name":{"x":1},"size":1,"blen":10,"k":1,"zstd":0}`,
		"trailing":    `{"fmt":"airqr1","tid":1,"name":"a","size":1,"blen":10,"k":1,"zstd":0} xx`,
	}
	for name, j := range cases {
		if _, err := ParseManifest([]byte(j)); err == nil {
			t.Errorf("%s: expected rejection", name)
		}
	}
}

func TestValidName(t *testing.T) {
	for _, ok := range []string{"report.pdf", "a-b_c.tar.gz", "IMG 0001.jpg"} {
		if !ValidName(ok) {
			t.Errorf("%q should be valid", ok)
		}
	}
	for _, bad := range []string{"", "a/b", "a\"b", "a\\b", "é.pdf"} {
		if ValidName(bad) {
			t.Errorf("%q should be invalid", bad)
		}
	}
	if !strings.HasPrefix("x", "x") {
		t.Fatal("sanity")
	}
}
