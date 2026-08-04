package rtp

import (
	"context"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestSenderSendsUDP(t *testing.T) { recv, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")}); defer recv.Close(); p := filepath.Join(t.TempDir(), "x.ps"); _ = os.WriteFile(p, []byte("payload"), 0600); src, _ := NewFileSource(p, 100, 7); conn, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")}); defer conn.Close(); s := NewSender(conn, recv.LocalAddr().(*net.UDPAddr), src, 1); ctx, cancel := context.WithCancel(context.Background()); if err := s.Start(ctx); err != nil { t.Fatal(err) }; buf := make([]byte, 100); _ = recv.SetReadDeadline(time.Now().Add(time.Second)); if _, _, err := recv.ReadFromUDP(buf); err != nil { t.Fatal(err) }; cancel(); _ = s.Stop(); if s.Stats().Packets == 0 { t.Fatal("no packets") } }
