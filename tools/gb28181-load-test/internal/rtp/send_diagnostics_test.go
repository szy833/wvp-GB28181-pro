package rtp

import (
	"context"
	"errors"
	"fmt"
	"net"
	"syscall"
	"testing"
	"time"
)

func TestSendFailureClassification(t *testing.T) {
	for _, tc := range []struct {
		name, kind string
		cancelled  bool
		err        error
		fatal      bool
	}{
		{"local_close", "local_cancel", true, fmt.Errorf("write: %w", net.ErrClosed), false},
		{"cancelled_source", "local_cancel", true, context.Canceled, false},
		{"source_deadline", "local_cancel", true, context.DeadlineExceeded, false},
		{"closed_without_cancel", "send_error", false, net.ErrClosed, true},
		{"reset_active", "remote_close", false, syscall.ECONNRESET, true},
		{"reset_during_cancel", "remote_close", true, syscall.ECONNRESET, true},
		{"broken_pipe", "remote_close", false, syscall.EPIPE, true},
		{"source_failure", "source_error", false, errors.New("bad media"), true},
		{"timeout_after_cancel", "send_error", true, context.DeadlineExceeded, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var d sendDiagnostics
			op := "write"
			if tc.name == "source_failure" || tc.name == "cancelled_source" || tc.name == "source_deadline" {
				op = "source"
			}
			if fatal := d.record(op, tc.err, tc.cancelled); fatal != tc.fatal {
				t.Fatalf("fatal=%t want %t", fatal, tc.fatal)
			}
			s := d.snapshot()
			if len(s.Diagnostics) != 1 || s.Diagnostics[0].Kind != tc.kind || s.Diagnostics[0].At.IsZero() {
				t.Fatalf("wrong diagnostic: %+v", s)
			}
		})
	}
}

func TestSendDiagnosticsBoundedAndCopied(t *testing.T) {
	var d sendDiagnostics
	for i := 0; i < 40; i++ {
		d.record("write", syscall.EPIPE, false)
	}
	s := d.snapshot()
	if len(s.Diagnostics) != 8 || s.RemoteClosures != 40 || s.DiagnosticsDropped != 32 || s.Diagnostics[0].Sequence != 33 {
		t.Fatalf("unbounded/inaccurate diagnostics: %+v", s)
	}
	s.Diagnostics[0].Kind = "changed"
	if d.snapshot().Diagnostics[0].Kind != "remote_close" {
		t.Fatal("snapshot aliases live diagnostics")
	}
}

type diagnosticSource struct{ data []byte }

func (s diagnosticSource) Next(context.Context) (MediaUnit, error) {
	return MediaUnit{Data: s.data, Duration: time.Millisecond}, nil
}
func (s diagnosticSource) Reset() error { return nil }
func (s diagnosticSource) Close() error { return nil }

func connectedDiagnosticSender(t *testing.T) (*TCPPSender, *net.TCPConn) {
	t.Helper()
	l, err := net.ListenTCP("tcp", &net.TCPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = l.Close() })
	accepted := make(chan *net.TCPConn, 1)
	go func() {
		conn, err := l.AcceptTCP()
		if err == nil {
			accepted <- conn
		}
	}()
	s := NewTCPPSender(l.Addr().(*net.TCPAddr), diagnosticSource{make([]byte, 8*1024*1024)}, 1)
	if err := s.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Stop() })
	var peer *net.TCPConn
	select {
	case peer = <-accepted:
	case <-time.After(2 * time.Second):
		t.Fatal("TCP accept timeout")
	}
	t.Cleanup(func() { _ = peer.Close() })
	deadline := time.Now().Add(2 * time.Second)
	for s.Stats().Packets < 100 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if s.Stats().Packets < 100 {
		t.Fatal("sender never wrote")
	}
	return s, peer
}

func TestLocalTCPStopDoesNotCountSendFailure(t *testing.T) {
	s, _ := connectedDiagnosticSender(t)
	if s.Stats().Errors != 0 {
		t.Fatal("peer failed before stop")
	}
	if err := s.Stop(); err != nil {
		t.Fatal(err)
	}
	if stats := s.Stats(); stats.Errors != 0 || stats.RemoteClosures != 0 {
		t.Fatalf("local stop counted as failure: %+v", stats)
	}
}

func TestTCPRemoteResetIsStillFailure(t *testing.T) {
	s, peer := connectedDiagnosticSender(t)
	_ = peer.SetLinger(0)
	_ = peer.Close()
	deadline := time.Now().Add(2 * time.Second)
	for s.Stats().Errors == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if stats := s.Stats(); stats.Errors != 1 || stats.RemoteClosures != 1 || len(stats.Diagnostics) != 1 || stats.Diagnostics[0].Kind != "remote_close" {
		t.Fatalf("remote reset was hidden: %+v", stats)
	}
}
