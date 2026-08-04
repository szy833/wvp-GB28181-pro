package rtp

import (
	"context"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"testing"
)

func TestTCPPSenderFramesRTP(t *testing.T) { listener, err := net.ListenTCP("tcp", &net.TCPAddr{IP: net.ParseIP("127.0.0.1")}); if err != nil { t.Fatal(err) }; defer listener.Close(); path := filepath.Join(t.TempDir(), "x.ps"); _ = os.WriteFile(path, []byte("payload"), 0600); source, _ := NewFileSource(path, 100, 7); sender := NewTCPPSender(listener.Addr().(*net.TCPAddr), source, 1); received := make(chan error, 1); go func() { conn, err := listener.AcceptTCP(); if err != nil { received <- err; return }; defer conn.Close(); header := make([]byte, 4); _, err = conn.Read(header); if err == nil && header[0] != '$' { err = fmt.Errorf("invalid interleaved frame") }; received <- err }(); if err := sender.Start(context.Background()); err != nil { t.Fatal(err) }; if err := <-received; err != nil { t.Fatal(err) }; _ = sender.Stop() }
