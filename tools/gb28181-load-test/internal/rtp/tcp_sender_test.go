package rtp

import (
	"context"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestTCPPSenderFramesRTP(t *testing.T) {
	listener, err := net.ListenTCP("tcp", &net.TCPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	path := filepath.Join(t.TempDir(), "x.ps")
	_ = os.WriteFile(path, []byte("payload"), 0600)
	source, _ := NewFileSource(path, 100, 7)
	sender := NewTCPPSender(listener.Addr().(*net.TCPAddr), source, 1)
	received := make(chan error, 1)
	go func() {
		conn, err := listener.AcceptTCP()
		if err != nil {
			received <- err
			return
		}
		defer conn.Close()
		header := make([]byte, 4)
		_, err = conn.Read(header)
		if err == nil && header[0] != '$' {
			err = fmt.Errorf("invalid interleaved frame")
		}
		received <- err
	}()
	if err := sender.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := <-received; err != nil {
		t.Fatal(err)
	}
	_ = sender.Stop()
}

func TestTCPStartHonorsCancelledContext(t *testing.T) {
	l, err := net.ListenTCP("tcp", &net.TCPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	p := filepath.Join(t.TempDir(), "x.ps")
	if err := os.WriteFile(p, []byte("payload"), 0600); err != nil {
		t.Fatal(err)
	}
	source, err := NewFileSource(p, 100, 7)
	if err != nil {
		t.Fatal(err)
	}
	s := NewTCPPSender(l.Addr().(*net.TCPAddr), source, 1)
	if err := s.Start(ctx); err == nil {
		_ = s.Stop()
		t.Fatal("cancelled start still established a TCP session")
	}
	started := time.Now()
	_ = s.Stop()
	if time.Since(started) > time.Second {
		t.Fatal("stop did not converge")
	}
}
