package rtp

import (
	"bytes"
	"context"
	"encoding/binary"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func timestampBytes(v int64) []byte {
	return []byte{0x21 | byte(v>>29)&0x0e, byte(v >> 22), byte(v>>14)&0xfe | 1, byte(v >> 7), byte(v<<1) | 1}
}

func TestSenderUsesMediaClockInsteadOfFixed40ms(t *testing.T) {
	path := filepath.Join(t.TempDir(), "paced.ps")
	if err := os.WriteFile(path, append(timedPS(90000), timedPS(108000)...), 0600); err != nil {
		t.Fatal(err)
	}
	m, err := NewSharedMedia(path, 4096)
	if err != nil {
		t.Fatal(err)
	}
	recv, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	defer recv.Close()
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	s := NewSender(conn, recv.LocalAddr().(*net.UDPAddr), m.NewReader(1200), 9)
	if err := s.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	buf := make([]byte, 4096)
	_ = recv.SetReadDeadline(time.Now().Add(time.Second))
	if _, _, err := recv.ReadFromUDP(buf); err != nil {
		t.Fatal(err)
	}
	firstAt := time.Now()
	if _, _, err := recv.ReadFromUDP(buf); err != nil {
		t.Fatal(err)
	}
	if time.Since(firstAt) < 150*time.Millisecond {
		t.Fatal("media sent faster than its 200ms DTS interval")
	}
	if binary.BigEndian.Uint32(buf[4:8]) != 18000 {
		t.Fatal("wrong RTP clock")
	}
}

func timedPS(pts int64) []byte {
	pack := []byte{0, 0, 1, 0xba, 0x44, 0, 4, 0, 4, 1, 0, 0, 3, 0}
	pes := []byte{0, 0, 1, 0xe0, 0, 18, 0x80, 0x80, 5}
	pes = append(pes, timestampBytes(pts)...)
	return append(append(pack, pes...), 0, 0, 0, 1, 9, 0xf0, 0, 0, 1, 0x65)
}

func TestSharedReaderPacesTimedPESAndKeepsLoopClock(t *testing.T) {
	first, second := timedPS(90000), timedPS(99000)
	p := filepath.Join(t.TempDir(), "timed.ps")
	if err := os.WriteFile(p, append(first, second...), 0600); err != nil {
		t.Fatal(err)
	}
	m, err := NewSharedMedia(p, 4096)
	if err != nil {
		t.Fatal(err)
	}
	r := m.NewReader(1200)
	for i := 0; i < 4; i++ {
		u, err := r.Next(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		if len(u.Data) != len(first) {
			t.Fatalf("sent entire file instead of timed unit: %d bytes", len(u.Data))
		}
		if u.Timestamp != int64(i)*9000 {
			t.Fatalf("loop timestamp %d = %d, want %d", i, u.Timestamp, i*9000)
		}
		if i >= 2 && bytes.Equal(u.Data, first) {
			t.Fatal("looped PES timestamps must be rebased")
		}
	}
}

func TestSharedMediaRejectsUntimedInput(t *testing.T) {
	p := filepath.Join(t.TempDir(), "untimed.ps")
	if err := os.WriteFile(p, []byte("not a timed PS video"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := NewSharedMedia(p, 4096); err == nil {
		t.Fatal("cannot safely pace an unrecognized file")
	}
}

func TestPreparedMediaTimeline(t *testing.T) {
	p := os.Getenv("GB28181_TEST_MEDIA")
	if p == "" {
		t.Skip("optional local PS validation; no service traffic")
	}
	m, err := NewSharedMedia(p, 512<<20)
	if err != nil {
		t.Fatal(err)
	}
	r := m.NewReader(1200)
	var bytes, ticks int64
	for i := 0; i < len(m.units); i++ {
		u, err := r.Next(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		bytes += int64(len(u.Data))
		ticks += m.units[i].duration
	}
	if bytes != int64(len(m.data)) {
		t.Fatalf("timeline dropped bytes: %d != %d", bytes, len(m.data))
	}
	u, err := r.Next(context.Background())
	if err != nil || u.Timestamp != ticks {
		t.Fatalf("loop clock discontinuity: %d != %d: %v", u.Timestamp, ticks, err)
	}
	t.Logf("PS bytes=%d groups=%d duration=%.3fs payload bitrate=%.3fMbps", bytes, len(m.units), float64(ticks)/90000, float64(bytes)*8*90000/float64(ticks)/1e6)
}

func TestLoopRebasesMPEG1AndMPEG2SCRWithoutChangingSharedBuffer(t *testing.T) {
	for _, version := range []int{1, 2} {
		t.Run(string(rune('0'+version)), func(t *testing.T) {
			first, second := timedPS(90000), timedPS(99000)
			want := []byte{0x44, 0, 6, 0x32, 0x84, 1}
			ptsOffset := 23
			if version == 1 {
				pack := []byte{0, 0, 1, 0xba, 0x21, 0, 1, 0, 1, 0, 0, 3}
				first = append(append([]byte(nil), pack...), first[14:]...)
				second = append(append([]byte(nil), pack...), second[14:]...)
				want = []byte{0x21, 0, 1, 0x8c, 0xa1}
				ptsOffset = 21
			}
			original := append(append([]byte(nil), first...), second...)
			p := filepath.Join(t.TempDir(), "clock.ps")
			if err := os.WriteFile(p, original, 0600); err != nil {
				t.Fatal(err)
			}
			packets, units, err := parseTimeline(original)
			if err != nil {
				t.Fatal(err)
			}
			m := &SharedMedia{data: original, packets: packets, units: units}
			r := m.NewReader(1200)
			_, _ = r.Next(context.Background())
			_, _ = r.Next(context.Background())
			u, err := r.Next(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(u.Data[4:4+len(want)], want) {
				t.Fatalf("wrong loop SCR: %x", u.Data[4:4+len(want)])
			}
			if !bytes.Equal(u.Data[ptsOffset:ptsOffset+5], []byte{0x21, 0, 7, 0x4b, 0xc1}) {
				t.Fatalf("wrong loop PTS: %x", u.Data[ptsOffset:ptsOffset+5])
			}
			if !bytes.Equal(m.data, original) {
				t.Fatal("one stream mutated shared media")
			}
		})
	}
}

func TestTimelineUsesDTSWithBFramePTSAndHandles33BitWrap(t *testing.T) {
	for _, times := range [][2]int64{{90000, 93600}, {(1 << 33) - 3600, 0}} {
		var data []byte
		for i, dts := range times {
			frame := timedPS(dts + 7200 - int64(i)*3600)
			frame[19], frame[21], frame[22] = 23, 0xc0, 10
			frame[23] = frame[23]&15 | 0x30
			clock := timestampBytes(dts)
			clock[0] = clock[0]&15 | 0x10
			frame = append(append(append([]byte(nil), frame[:28]...), clock...), frame[28:]...)
			data = append(data, frame...)
		}
		p := filepath.Join(t.TempDir(), "dts.ps")
		if err := os.WriteFile(p, data, 0600); err != nil {
			t.Fatal(err)
		}
		m, err := NewSharedMedia(p, 4096)
		if err != nil {
			t.Fatal(err)
		}
		r := m.NewReader(1200)
		_, _ = r.Next(context.Background())
		second, err := r.Next(context.Background())
		if err != nil || second.Timestamp != 3600 {
			t.Fatalf("DTS/wrap not followed: %+v %v", second, err)
		}
	}
}
