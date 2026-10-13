package rtp

import (
	"context"
	"fmt"
	"os"
	"sync"
	"time"
)

type MediaUnit struct {
	Data      []byte
	Timestamp int64
	Duration  time.Duration
}
type Source interface {
	Next(context.Context) (MediaUnit, error)
	Reset() error
	Close() error
}

type SharedMedia struct {
	data    []byte
	packets []psPacket
	units   []psUnit
}
type SharedReader struct {
	media     *SharedMedia
	chunk     int
	pos       int
	timestamp int64
	mu        sync.Mutex
	closed    bool
}

func NewSharedMedia(path string, maxSize int64) (*SharedMedia, error) {
	if path == "" {
		return nil, fmt.Errorf("media file is empty")
	}
	st, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if st.Size() == 0 {
		return nil, fmt.Errorf("media file is empty")
	}
	if maxSize > 0 && st.Size() > maxSize {
		return nil, fmt.Errorf("media file exceeds limit")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	packets, units, err := parseTimeline(data)
	if err != nil {
		return nil, err
	}
	if err := validateMediaProfile(data, packets, units); err != nil {
		return nil, err
	}
	return &SharedMedia{data: data, packets: packets, units: units}, nil
}
func (m *SharedMedia) NewReader(chunk int) *SharedReader {
	if chunk <= 0 {
		chunk = 1200
	}
	return &SharedReader{media: m, chunk: chunk}
}
func (r *SharedReader) Next(ctx context.Context) (MediaUnit, error) {
	select {
	case <-ctx.Done():
		return MediaUnit{}, ctx.Err()
	default:
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return MediaUnit{}, fmt.Errorf("source closed")
	}
	if len(r.media.data) == 0 {
		return MediaUnit{}, fmt.Errorf("shared media is empty")
	}
	if r.pos >= len(r.media.units) {
		r.pos = 0
	}
	u := r.media.units[r.pos]
	out := MediaUnit{Data: append([]byte(nil), r.media.data[u.start:u.end]...), Timestamp: r.timestamp, Duration: time.Duration(u.duration) * time.Second / 90000}
	// Rebase embedded PS clocks as well as RTP across loops.
	offset := r.timestamp - u.clock
	if offset != 0 {
		for _, p := range u.packets {
			shiftPacketClock(out.Data, p, u.start, offset)
		}
	}
	r.pos++
	r.timestamp += u.duration
	return out, nil
}
func (r *SharedReader) Reset() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return fmt.Errorf("source closed")
	}
	r.pos, r.timestamp = 0, 0
	return nil
}
func (r *SharedReader) Close() error { r.mu.Lock(); r.closed = true; r.mu.Unlock(); return nil }

type FileSource struct {
	path      string
	data      []byte
	chunk     int
	pos       int
	timestamp int64
	mu        sync.Mutex
	closed    bool
}

func NewFileSource(path string, maxSize, chunk int) (*FileSource, error) {
	if path == "" {
		return nil, fmt.Errorf("media file is empty")
	}
	if chunk <= 0 {
		chunk = 1200
	}
	st, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if st.Size() == 0 {
		return nil, fmt.Errorf("media file is empty")
	}
	if maxSize > 0 && st.Size() > int64(maxSize) {
		return nil, fmt.Errorf("media file exceeds limit")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return &FileSource{path: path, data: b, chunk: chunk}, nil
}

func (s *FileSource) Next(ctx context.Context) (MediaUnit, error) {
	select {
	case <-ctx.Done():
		return MediaUnit{}, ctx.Err()
	default:
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return MediaUnit{}, fmt.Errorf("source closed")
	}
	if s.pos >= len(s.data) {
		s.pos = 0
		s.timestamp = 0
	}
	end := s.pos + s.chunk
	if end > len(s.data) {
		end = len(s.data)
	}
	unit := MediaUnit{Data: append([]byte(nil), s.data[s.pos:end]...), Timestamp: s.timestamp}
	s.pos = end
	s.timestamp += 3600
	return unit, nil
}

func (s *FileSource) Reset() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return fmt.Errorf("source closed")
	}
	s.pos, s.timestamp = 0, 0
	return nil
}
func (s *FileSource) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	s.data = nil
	return nil
}
