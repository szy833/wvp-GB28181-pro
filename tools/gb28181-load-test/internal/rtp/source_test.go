package rtp

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestFileSourceLoops(t *testing.T) {
	p := filepath.Join(t.TempDir(), "x.ps")
	if err := os.WriteFile(p, []byte("12345"), 0600); err != nil {
		t.Fatal(err)
	}
	s, err := NewFileSource(p, 100, 3)
	if err != nil {
		t.Fatal(err)
	}
	a, _ := s.Next(context.Background())
	b, _ := s.Next(context.Background())
	c, _ := s.Next(context.Background())
	if string(a.Data)+string(b.Data)+string(c.Data) != "12345123" {
		t.Fatalf("%q%q%q", a.Data, b.Data, c.Data)
	}
}

func TestFileSourceRejectsEmpty(t *testing.T) {
	p := filepath.Join(t.TempDir(), "empty.ps")
	_ = os.WriteFile(p, nil, 0600)
	if _, err := NewFileSource(p, 100, 10); err == nil {
		t.Fatal("expected error")
	}
}

func TestSharedMediaUsesOneBuffer(t *testing.T) {
	p := filepath.Join(t.TempDir(), "shared.ps")
	if err := os.WriteFile(p, append(timedPS(90000), timedPS(93600)...), 0600); err != nil {
		t.Fatal(err)
	}
	media, err := NewSharedMedia(p, 100)
	if err != nil {
		t.Fatal(err)
	}
	a, b := media.NewReader(3), media.NewReader(3)
	ua, _ := a.Next(context.Background())
	ub, _ := b.Next(context.Background())
	if string(ua.Data) != string(ub.Data) {
		t.Fatalf("%q != %q", ua.Data, ub.Data)
	}
}

func TestSharedReaderKeepsTimedPESGroupInOneUnit(t *testing.T) {
	p := filepath.Join(t.TempDir(), "shared.ps")
	data := timedPS(90000)
	if err := os.WriteFile(p, append(append([]byte(nil), data...), timedPS(93600)...), 0600); err != nil {
		t.Fatal(err)
	}
	media, err := NewSharedMedia(p, 100)
	if err != nil {
		t.Fatal(err)
	}
	unit, err := media.NewReader(3).Next(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if string(unit.Data) != string(data) {
		t.Fatalf("PS unit was split: %x", unit.Data)
	}
}
