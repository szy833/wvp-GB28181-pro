package rtp

import (
	"context"
	"io"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestStoppedSessionSenderCannotStartAfterFinalCleanup(t *testing.T) {
	p := filepath.Join(t.TempDir(), "data.ps")
	if err := os.WriteFile(p, []byte("payload"), 0600); err != nil {
		t.Fatal(err)
	}
	u, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	c, err := net.ListenTCP("tcp", &net.TCPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	for _, kind := range []string{"udp", "tcp"} {
		t.Run(kind, func(t *testing.T) {
			source, err := NewFileSource(p, 100, 7)
			if err != nil {
				t.Fatal(err)
			}
			var sender interface {
				Start(context.Context) error
				Stop() error
			}
			if kind == "udp" {
				sender = NewSender(u, u.LocalAddr().(*net.UDPAddr), source, 1)
			} else {
				sender = NewTCPPSender(c.Addr().(*net.TCPAddr), source, 1)
			}
			_ = sender.Stop()
			_ = source.Close()
			if err := sender.Start(context.Background()); err == nil {
				_ = sender.Stop()
				t.Fatal("closed session sender restarted after final cleanup")
			}
		})
	}
}

func TestTCPClosesConnectionWhenSourceExits(t *testing.T) {
	l, err := net.ListenTCP("tcp", &net.TCPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	p := filepath.Join(t.TempDir(), "closed.ps")
	if err := os.WriteFile(p, []byte("payload"), 0600); err != nil {
		t.Fatal(err)
	}
	source, err := NewFileSource(p, 100, 7)
	if err != nil {
		t.Fatal(err)
	}
	_ = source.Close()
	sender := NewTCPPSender(l.Addr().(*net.TCPAddr), source, 1)
	defer sender.Stop()
	if err := sender.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	_ = l.SetDeadline(time.Now().Add(time.Second))
	conn, err := l.AcceptTCP()
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := conn.Read(make([]byte, 1)); err != io.EOF {
		t.Fatalf("source exited without closing TCP connection: %v", err)
	}
}
