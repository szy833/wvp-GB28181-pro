package sip

import (
	"context"
	"fmt"
	"net"
)

type Transport interface {
	Send(context.Context, net.Addr, []byte) error
	Receive(context.Context) (Message, error)
	Close() error
}

type UDPTransport struct { conn *net.UDPConn }

func NewUDPTransport(local string) (*UDPTransport, error) {
	a, err := net.ResolveUDPAddr("udp", local); if err != nil { return nil, err }
	c, err := net.ListenUDP("udp", a); if err != nil { return nil, err }
	return &UDPTransport{conn: c}, nil
}

func (t *UDPTransport) LocalAddr() net.Addr { return t.conn.LocalAddr() }

func (t *UDPTransport) Send(ctx context.Context, address net.Addr, payload []byte) error {
	if address == nil { return fmt.Errorf("destination address is nil") }
	select { case <-ctx.Done(): return ctx.Err(); default: }
	_, err := t.conn.WriteTo(payload, address); return err
}

func (t *UDPTransport) Receive(ctx context.Context) (Message, error) {
	if deadline, ok := ctx.Deadline(); ok { _ = t.conn.SetReadDeadline(deadline) }
	buf := make([]byte, 65535); n, _, err := t.conn.ReadFromUDP(buf)
	if err != nil { if ctx.Err() != nil { return Message{}, ctx.Err() }; return Message{}, err }
	return ParseMessage(buf[:n])
}

func (t *UDPTransport) Close() error { return t.conn.Close() }
