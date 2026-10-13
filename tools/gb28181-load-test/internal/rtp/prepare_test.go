package rtp

import (
	"bytes"
	"context"
	"io"
	"os"
	"path/filepath"
	"testing"
)

func TestPrepareMediaDoesNotOverwriteInputOrExistingOutput(t *testing.T) {
	dir := t.TempDir()
	input, output := filepath.Join(dir, "input.ps"), filepath.Join(dir, "output.ps")
	for _, path := range []string{input, output} {
		if err := os.WriteFile(path, []byte("preserve"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	for _, dest := range []string{input, output} {
		if err := PrepareMedia(context.Background(), input, dest, 25); err == nil {
			t.Fatal("existing input/output may not be overwritten")
		}
		data, err := os.ReadFile(dest)
		if err != nil || string(data) != "preserve" {
			t.Fatal("existing file changed")
		}
	}
}

func TestMPEGCRCAndWriteFailures(t *testing.T) {
	if got := mpegCRC([]byte("123456789")); got != 0x0376e6e7 {
		t.Fatalf("wrong CRC-32/MPEG-2: %08x", got)
	}
	frame := []byte{0, 0, 0, 1, 9, 0xf0, 0, 0, 1, 0x65, 0x88}
	if err := MuxH264PS(append(append([]byte(nil), frame...), frame...), failingMediaWriter{}, 25); err != io.ErrShortWrite {
		t.Fatalf("write failure lost: %v", err)
	}
	var ps bytes.Buffer
	if err := MuxH264PS(append(append([]byte(nil), frame...), frame...), &ps, 25); err != nil {
		t.Fatal(err)
	}
	packets, _, err := parseTimeline(ps.Bytes())
	if err != nil {
		t.Fatal(err)
	}
	for _, p := range packets {
		if ps.Bytes()[p.start+3] == 0xbc && mpegCRC(ps.Bytes()[p.start:p.end]) != 0 {
			t.Fatal("PSM CRC does not cover the complete map")
		}
	}
}

type failingMediaWriter struct{}

func (failingMediaWriter) Write([]byte) (int, error) { return 0, nil }
