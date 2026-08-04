package rtp

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestFileSourceLoops(t *testing.T) { p := filepath.Join(t.TempDir(), "x.ps"); if err := os.WriteFile(p, []byte("12345"), 0600); err != nil { t.Fatal(err) }; s, err := NewFileSource(p, 100, 3); if err != nil { t.Fatal(err) }; a, _ := s.Next(context.Background()); b, _ := s.Next(context.Background()); c, _ := s.Next(context.Background()); if string(a.Data)+string(b.Data)+string(c.Data) != "12345123" { t.Fatalf("%q%q%q", a.Data, b.Data, c.Data) } }

func TestFileSourceRejectsEmpty(t *testing.T) { p := filepath.Join(t.TempDir(), "empty.ps"); _ = os.WriteFile(p, nil, 0600); if _, err := NewFileSource(p, 100, 10); err == nil { t.Fatal("expected error") } }
