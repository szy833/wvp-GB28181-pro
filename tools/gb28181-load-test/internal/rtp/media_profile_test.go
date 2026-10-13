package rtp

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestSharedMediaRejectsMPEG1AndAggregatedFrames(t *testing.T) {
	first, second := timedPS(90000), timedPS(93600)
	pack := []byte{0, 0, 1, 0xba, 0x21, 0, 1, 0, 1, 0, 0, 3}
	legacy := append(append(append(append([]byte(nil), pack...), first[14:]...), pack...), second[14:]...)
	aggregated := append(append(append([]byte(nil), first...), first[14:]...), second...)
	for name, data := range map[string][]byte{"mpeg1": legacy, "two-frames-one-clock": aggregated} {
		t.Run(name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "unsafe.ps")
			if err := os.WriteFile(path, data, 0600); err != nil {
				t.Fatal(err)
			}
			if _, err := NewSharedMedia(path, 4096); err == nil {
				t.Fatal("unsafe media accepted; rebuild it with prepare-media")
			}
		})
	}
}

func TestMuxH264PSUsesOneFrameClockAndPreservesPayload(t *testing.T) {
	// AUD, SPS/PPS and one VCL; second frame exceeds the bounded PES size.
	aud := []byte{0, 0, 0, 1, 9, 0xf0}
	first := append(append([]byte(nil), aud...), []byte{0, 0, 1, 0x67, 0x12, 0, 0, 1, 0x68, 0x34, 0, 0, 1, 0x65, 0x88}...)
	second := append(append(append([]byte(nil), aud...), []byte{0, 0, 1, 0x41, 0x88}...), bytes.Repeat([]byte{0x55}, 80000)...)
	var ps bytes.Buffer
	if err := MuxH264PS(append(append([]byte(nil), first...), second...), &ps, 25); err != nil {
		t.Fatal(err)
	}
	packets, units, err := parseTimeline(ps.Bytes())
	if err != nil || len(units) != 2 || units[1].clock != 3600 {
		t.Fatalf("wrong timeline: units=%v error=%v", units, err)
	}
	var payload []byte
	for _, p := range packets {
		if p.video {
			h := p.start + 6
			if ps.Bytes()[h]&0xc0 != 0x80 || len(p.clockOffsets) != 1 {
				t.Fatal("every PES must use an MPEG-2 header and PTS")
			}
			payload = append(payload, ps.Bytes()[h+3+int(ps.Bytes()[h+2]):p.end]...)
		}
	}
	if !bytes.Equal(payload, append(append([]byte(nil), first...), second...)) {
		t.Fatal("H.264 payload was changed or dropped")
	}
	path := filepath.Join(t.TempDir(), "prepared.ps")
	if err := os.WriteFile(path, ps.Bytes(), 0600); err != nil {
		t.Fatal(err)
	}
	media, err := NewSharedMedia(path, 1<<20)
	if err != nil {
		t.Fatal(err)
	}
	r := media.NewReader(1200)
	for i := 0; i < 6; i++ {
		u, err := r.Next(context.Background())
		if err != nil || u.Timestamp != int64(i)*3600 {
			t.Fatalf("loop clock: %+v %v", u, err)
		}
	}
}

func TestMuxH264PSRejectsUnknownFrameBoundaries(t *testing.T) {
	for _, data := range [][]byte{nil, {0, 0, 1, 0x65, 0x88}, {0, 0, 1, 9, 0xf0}} {
		var ps bytes.Buffer
		if err := MuxH264PS(data, &ps, 25); err == nil {
			t.Fatal("invalid or unaudited raw H.264 was accepted")
		}
	}
	var ps bytes.Buffer
	if err := MuxH264PS(nil, &ps, 0); err == nil {
		t.Fatal("invalid frame rate accepted")
	}
}
