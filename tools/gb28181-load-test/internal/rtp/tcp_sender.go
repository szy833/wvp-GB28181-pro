package rtp

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

type TCPPSender struct { Target *net.TCPAddr; Source Source; SSRC uint32; Packetizer Packetizer; Interval time.Duration; mu sync.Mutex; conn *net.TCPConn; cancel context.CancelFunc; wg sync.WaitGroup; packets uint64; bytes uint64; errors uint64 }
func NewTCPPSender(target *net.TCPAddr, source Source, ssrc uint32) *TCPPSender { return &TCPPSender{Target: target, Source: source, SSRC: ssrc, Interval: 40 * time.Millisecond, Packetizer: Packetizer{MTU: 1400, PayloadType: 96}} }
func (s *TCPPSender) Start(ctx context.Context) error { s.mu.Lock(); if s.cancel != nil { s.mu.Unlock(); return fmt.Errorf("sender already running") }; conn, err := net.DialTCP("tcp", nil, s.Target); if err != nil { s.mu.Unlock(); return err }; runCtx, cancel := context.WithCancel(ctx); s.conn, s.cancel = conn, cancel; s.wg.Add(1); s.mu.Unlock(); go s.loop(runCtx); return nil }
func (s *TCPPSender) loop(ctx context.Context) { defer s.wg.Done(); seq := uint16(0); for { unit, err := s.Source.Next(ctx); if err != nil { return }; for _, packet := range s.Packetizer.Packetize(unit, s.SSRC, seq) { frame := make([]byte, 4+len(packet)); frame[0], frame[1] = '$', 0; binary.BigEndian.PutUint16(frame[2:4], uint16(len(packet))); copy(frame[4:], packet); s.mu.Lock(); conn := s.conn; s.mu.Unlock(); if conn == nil { return }; if _, err := io.Copy(conn, bytesReader(frame)); err != nil { atomic.AddUint64(&s.errors, 1); return }; atomic.AddUint64(&s.packets, 1); atomic.AddUint64(&s.bytes, uint64(len(frame))); seq++ }; delay := s.Interval; if delay <= 0 { delay = 40 * time.Millisecond }; timer := time.NewTimer(delay); select { case <-ctx.Done(): timer.Stop(); return; case <-timer.C: } } }

type byteReader struct { data []byte; pos int }
func bytesReader(data []byte) *byteReader { return &byteReader{data: data} }
func (r *byteReader) Read(p []byte) (int, error) { if r.pos >= len(r.data) { return 0, io.EOF }; n := copy(p, r.data[r.pos:]); r.pos += n; return n, nil }
func (s *TCPPSender) Stop() error { s.mu.Lock(); if s.cancel != nil { s.cancel(); s.cancel = nil }; conn := s.conn; s.conn = nil; s.mu.Unlock(); if conn != nil { _ = conn.Close() }; s.wg.Wait(); return nil }
func (s *TCPPSender) Stats() SenderStats { return SenderStats{Packets: atomic.LoadUint64(&s.packets), Bytes: atomic.LoadUint64(&s.bytes), Errors: atomic.LoadUint64(&s.errors)} }
