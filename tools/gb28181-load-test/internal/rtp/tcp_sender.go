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

type TCPPSender struct {
	Target      *net.TCPAddr
	Source      Source
	SSRC        uint32
	Packetizer  Packetizer
	Interval    time.Duration
	mu          sync.Mutex
	conn        *net.TCPConn
	cancel      context.CancelFunc
	wg          sync.WaitGroup
	packets     uint64
	bytes       uint64
	errors      uint64
	stopped     bool
	diagnostics sendDiagnostics
}

func NewTCPPSender(target *net.TCPAddr, source Source, ssrc uint32) *TCPPSender {
	return &TCPPSender{Target: target, Source: source, SSRC: ssrc, Packetizer: Packetizer{MTU: 1400, PayloadType: 96}}
}
func (s *TCPPSender) Start(ctx context.Context) error {
	s.mu.Lock()
	if s.stopped {
		s.mu.Unlock()
		return fmt.Errorf("session sender stopped")
	}
	if s.cancel != nil {
		s.mu.Unlock()
		return fmt.Errorf("sender already running")
	}
	runCtx, cancel := context.WithCancel(ctx)
	s.cancel = cancel
	s.wg.Add(1)
	s.mu.Unlock()
	// Publish cancellation before dialing; Stop can abort a blocked connect.
	conn, err := (&net.Dialer{Timeout: 5 * time.Second}).DialContext(runCtx, "tcp", s.Target.String())
	if err != nil {
		cancel()
		s.wg.Done()
		return err
	}
	s.mu.Lock()
	if runCtx.Err() != nil {
		s.mu.Unlock()
		_ = conn.Close()
		s.wg.Done()
		return runCtx.Err()
	}
	s.conn = conn.(*net.TCPConn)
	s.mu.Unlock()
	go s.loop(runCtx, conn.(*net.TCPConn))
	return nil
}
func (s *TCPPSender) loop(ctx context.Context, conn *net.TCPConn) {
	defer s.wg.Done()
	defer conn.Close()
	seq := uint16(0)
	deadline := time.Now()
	for {
		unit, err := s.Source.Next(ctx)
		if err != nil {
			if s.diagnostics.record("source", err, ctx.Err() != nil) {
				atomic.AddUint64(&s.errors, 1)
			}
			return
		}
		for _, packet := range s.Packetizer.Packetize(unit, s.SSRC, seq) {
			if ctx.Err() != nil {
				return
			}
			frame := make([]byte, 4+len(packet))
			frame[0], frame[1] = '$', 0
			binary.BigEndian.PutUint16(frame[2:4], uint16(len(packet)))
			copy(frame[4:], packet)
			_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
			if _, err := io.Copy(conn, bytesReader(frame)); err != nil {
				if s.diagnostics.record("write", err, ctx.Err() != nil) {
					atomic.AddUint64(&s.errors, 1)
				}
				return
			}
			atomic.AddUint64(&s.packets, 1)
			atomic.AddUint64(&s.bytes, uint64(len(frame)))
			seq++
		}
		if !pace(ctx, &deadline, unit.Duration, s.Interval) {
			return
		}
	}
}

type byteReader struct {
	data []byte
	pos  int
}

func bytesReader(data []byte) *byteReader { return &byteReader{data: data} }
func (r *byteReader) Read(p []byte) (int, error) {
	if r.pos >= len(r.data) {
		return 0, io.EOF
	}
	n := copy(p, r.data[r.pos:])
	r.pos += n
	return n, nil
}
func (s *TCPPSender) Stop() error {
	s.mu.Lock()
	s.stopped = true
	if s.cancel != nil {
		s.cancel()
	}
	conn := s.conn
	s.conn = nil
	s.mu.Unlock()
	if conn != nil {
		_ = conn.Close()
	}
	s.wg.Wait()
	s.mu.Lock()
	s.cancel = nil
	s.mu.Unlock()
	return nil
}
func (s *TCPPSender) Stats() SenderStats {
	stats := s.diagnostics.snapshot()
	stats.Packets, stats.Bytes, stats.Errors = atomic.LoadUint64(&s.packets), atomic.LoadUint64(&s.bytes), atomic.LoadUint64(&s.errors)
	return stats
}
