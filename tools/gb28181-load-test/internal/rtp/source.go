package rtp

import (
	"context"
	"fmt"
	"os"
	"sync"
)

type MediaUnit struct { Data []byte; Timestamp int64 }
type Source interface { Next(context.Context) (MediaUnit, error); Reset() error; Close() error }

type FileSource struct { path string; data []byte; chunk int; pos int; timestamp int64; mu sync.Mutex; closed bool }

func NewFileSource(path string, maxSize, chunk int) (*FileSource, error) {
	if path == "" { return nil, fmt.Errorf("media file is empty") }; if chunk <= 0 { chunk = 1200 }
	st, err := os.Stat(path); if err != nil { return nil, err }; if st.Size() == 0 { return nil, fmt.Errorf("media file is empty") }; if maxSize > 0 && st.Size() > int64(maxSize) { return nil, fmt.Errorf("media file exceeds limit") }
	b, err := os.ReadFile(path); if err != nil { return nil, err }; return &FileSource{path: path, data: b, chunk: chunk}, nil
}

func (s *FileSource) Next(ctx context.Context) (MediaUnit, error) {
	select { case <-ctx.Done(): return MediaUnit{}, ctx.Err(); default: }
	s.mu.Lock(); defer s.mu.Unlock(); if s.closed { return MediaUnit{}, fmt.Errorf("source closed") }; if s.pos >= len(s.data) { s.pos = 0; s.timestamp = 0 }; end := s.pos + s.chunk; if end > len(s.data) { end = len(s.data) }; unit := MediaUnit{Data: append([]byte(nil), s.data[s.pos:end]...), Timestamp: s.timestamp}; s.pos = end; s.timestamp += 3600; return unit, nil
}

func (s *FileSource) Reset() error { s.mu.Lock(); defer s.mu.Unlock(); if s.closed { return fmt.Errorf("source closed") }; s.pos, s.timestamp = 0, 0; return nil }
func (s *FileSource) Close() error { s.mu.Lock(); defer s.mu.Unlock(); s.closed = true; s.data = nil; return nil }
