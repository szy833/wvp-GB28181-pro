package sip

import (
	"context"
	"testing"
	"time"
)

func TestUDPTransportRoundTrip(t *testing.T) {
	a, err := NewUDPTransport("127.0.0.1:0"); if err != nil { t.Fatal(err) }; defer a.Close()
	b, err := NewUDPTransport("127.0.0.1:0"); if err != nil { t.Fatal(err) }; defer b.Close()
	want := []byte("OPTIONS sip:test SIP/2.0\r\nContent-Length: 0\r\n\r\n")
	if err := a.Send(context.Background(), b.LocalAddr(), want); err != nil { t.Fatal(err) }
	ctx, cancel := context.WithTimeout(context.Background(), time.Second); defer cancel(); got, err := b.Receive(ctx); if err != nil { t.Fatal(err) }; if got.StartLine != "OPTIONS sip:test SIP/2.0" { t.Fatalf("%q", got.StartLine) }
}
