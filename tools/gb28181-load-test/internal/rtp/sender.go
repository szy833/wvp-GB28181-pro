package rtp

import (
	"context"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

type SenderStats struct {
	Packets            uint64
	Bytes              uint64
	Errors             uint64
	LocalCancellations uint64
	RemoteClosures     uint64
	DiagnosticsDropped uint64
	Diagnostics        []SendDiagnostic
}
type Sender struct {
	Conn        *net.UDPConn
	Target      *net.UDPAddr
	Source      Source
	Packetizer  Packetizer
	SSRC        uint32
	Interval    time.Duration
	stats       SenderStats
	cancel      context.CancelFunc
	wg          sync.WaitGroup
	mu          sync.Mutex
	stopped     bool
	diagnostics sendDiagnostics
}

func NewSender(conn *net.UDPConn, target *net.UDPAddr, source Source, ssrc uint32) *Sender {
	return &Sender{Conn: conn, Target: target, Source: source, SSRC: ssrc, Packetizer: Packetizer{MTU: 1400, PayloadType: 96}}
}

func (s *Sender) Start(ctx context.Context) error {
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
	go s.loop(runCtx)
	return nil
}

func (s *Sender) loop(ctx context.Context) {
	defer s.wg.Done()
	seq := uint16(0)
	deadline := time.Now()
	for {
		unit, err := s.Source.Next(ctx)
		if err != nil {
			if s.diagnostics.record("source", err, ctx.Err() != nil) {
				atomic.AddUint64(&s.stats.Errors, 1)
			}
			return
		}
		for _, packet := range s.Packetizer.Packetize(unit, s.SSRC, seq) {
			if ctx.Err() != nil {
				return
			}
			seq++
			if _, err := s.Conn.WriteToUDP(packet, s.Target); err != nil {
				if s.diagnostics.record("write", err, ctx.Err() != nil) {
					atomic.AddUint64(&s.stats.Errors, 1)
				}
				return
			}
			atomic.AddUint64(&s.stats.Packets, 1)
			atomic.AddUint64(&s.stats.Bytes, uint64(len(packet)))
		}
		if !pace(ctx, &deadline, unit.Duration, s.Interval) {
			return
		}
	}
}

func pace(ctx context.Context, deadline *time.Time, duration, override time.Duration) bool {
	if override > 0 {
		duration = override
	}
	if duration <= 0 {
		duration = 40 * time.Millisecond
	}
	*deadline = deadline.Add(duration)
	if time.Since(*deadline) > duration {
		*deadline = time.Now()
	}
	timer := time.NewTimer(time.Until(*deadline))
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func (s *Sender) Stop() error {
	s.mu.Lock()
	// A new INVITE gets a new sender; final cleanup must reject late ACKs.
	s.stopped = true
	if s.cancel != nil {
		s.cancel()
		s.cancel = nil
	}
	s.mu.Unlock()
	s.wg.Wait()
	return nil
}
func (s *Sender) Stats() SenderStats {
	stats := s.diagnostics.snapshot()
	stats.Packets, stats.Bytes, stats.Errors = atomic.LoadUint64(&s.stats.Packets), atomic.LoadUint64(&s.stats.Bytes), atomic.LoadUint64(&s.stats.Errors)
	return stats
}
