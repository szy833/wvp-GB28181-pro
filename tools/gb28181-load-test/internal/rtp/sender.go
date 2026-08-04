package rtp

import (
	"context"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

type SenderStats struct { Packets uint64; Bytes uint64; Errors uint64 }
type Sender struct { Conn *net.UDPConn; Target *net.UDPAddr; Source Source; Packetizer Packetizer; SSRC uint32; Interval time.Duration; stats SenderStats; cancel context.CancelFunc; wg sync.WaitGroup; mu sync.Mutex }

func NewSender(conn *net.UDPConn, target *net.UDPAddr, source Source, ssrc uint32) *Sender { return &Sender{Conn: conn, Target: target, Source: source, SSRC: ssrc, Packetizer: Packetizer{MTU: 1400, PayloadType: 96}} }

func (s *Sender) Start(ctx context.Context) error {
	s.mu.Lock(); if s.cancel != nil { s.mu.Unlock(); return fmt.Errorf("sender already running") }; runCtx, cancel := context.WithCancel(ctx); s.cancel = cancel; s.mu.Unlock(); s.wg.Add(1); go s.loop(runCtx); return nil
}

func (s *Sender) loop(ctx context.Context) { defer s.wg.Done(); seq := uint16(0); for { unit, err := s.Source.Next(ctx); if err != nil { return }; for _, packet := range s.Packetizer.Packetize(unit, s.SSRC, seq) { if _, err := s.Conn.WriteToUDP(packet, s.Target); err != nil { atomic.AddUint64(&s.stats.Errors, 1); continue }; atomic.AddUint64(&s.stats.Packets, 1); atomic.AddUint64(&s.stats.Bytes, uint64(len(packet))); seq++ }; d := s.Interval; if d <= 0 { d = 40 * time.Millisecond }; timer := time.NewTimer(d); select { case <-ctx.Done(): timer.Stop(); return; case <-timer.C: } } }

func (s *Sender) Stop() error { s.mu.Lock(); if s.cancel != nil { s.cancel(); s.cancel = nil }; s.mu.Unlock(); s.wg.Wait(); return nil }
func (s *Sender) Stats() SenderStats { return SenderStats{Packets: atomic.LoadUint64(&s.stats.Packets), Bytes: atomic.LoadUint64(&s.stats.Bytes), Errors: atomic.LoadUint64(&s.stats.Errors)} }
