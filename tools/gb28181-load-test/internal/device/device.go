package device

import (
	"context"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/shizy/gb28181-load-test/internal/sip"
)

type State string
const ( StateCreated State = "CREATED"; StateOnline State = "ONLINE"; StatePlaying State = "PLAYING"; StateStopping State = "STOPPING"; StateFailed State = "FAILED"; StateClosed State = "CLOSED" )

type MediaSender interface { Start(context.Context) error; Stop() error }
type DeviceHooks struct { Transport sip.Transport; Peer net.Addr; Sender MediaSender; RegisterInterval time.Duration; MediaTimeout time.Duration }

type Device struct { Identity Identity; Hooks DeviceHooks; mu sync.Mutex; state State; active bool; dialog sip.Dialog; runCancel context.CancelFunc; wg sync.WaitGroup; byeSent bool }

func New(id Identity, hooks DeviceHooks) *Device { if hooks.RegisterInterval <= 0 { hooks.RegisterInterval = time.Hour }; if hooks.MediaTimeout <= 0 { hooks.MediaTimeout = 10 * time.Second }; return &Device{Identity: id, Hooks: hooks, state: StateCreated} }
func (d *Device) State() State { d.mu.Lock(); defer d.mu.Unlock(); return d.state }
func (d *Device) transition(s State) { d.mu.Lock(); d.state = s; d.mu.Unlock() }

func (d *Device) Run(ctx context.Context) error {
	if d.Hooks.Transport == nil { return fmt.Errorf("SIP transport is nil") }; runCtx, cancel := context.WithCancel(ctx); d.runCancel = cancel; d.wg.Add(1); go d.loop(runCtx); return nil
}

func (d *Device) loop(ctx context.Context) { defer d.wg.Done(); ticker := time.NewTicker(d.Hooks.RegisterInterval); defer ticker.Stop(); d.sendRegister(ctx); d.transition(StateOnline); for { select { case <-ctx.Done(): return; case <-ticker.C: d.sendRegister(ctx); default: msg, err := d.Hooks.Transport.Receive(ctx); if err != nil { if ctx.Err() != nil { return }; continue }; _ = d.HandleMessage(ctx, msg) } } }

func (d *Device) sendRegister(ctx context.Context) { b, err := sip.BuildRegister(d.Identity, "sip:server@127.0.0.1", "", int(d.Hooks.RegisterInterval.Seconds())); if err == nil { _ = d.Hooks.Transport.Send(ctx, d.Hooks.Peer, b) } }

func (d *Device) HandleMessage(ctx context.Context, msg sip.Message) error {
	if strings.HasPrefix(msg.StartLine, "INVITE ") { return d.handleInvite(ctx, msg) }; if strings.HasPrefix(msg.StartLine, "ACK ") { return d.startMedia(ctx) }; if strings.HasPrefix(msg.StartLine, "BYE ") { return d.StopPlay(ctx) }; return nil
}

func (d *Device) handleInvite(ctx context.Context, msg sip.Message) error {
	d.mu.Lock(); if d.active { d.mu.Unlock(); return fmt.Errorf("playback already active") }; callID := msg.Headers["call-id"]; if callID == "" { callID = d.Identity.CallID }; d.dialog = sip.Dialog{Target: msg.Headers["from"], Local: msg.Headers["to"], CallID: callID, CSeq: 1}; d.active = true; d.byeSent = false; d.mu.Unlock()
	response := []byte(fmt.Sprintf("SIP/2.0 200 OK\r\nVia: %s\r\nFrom: %s\r\nTo: %s;tag=loadtest\r\nCall-ID: %s\r\nCSeq: 1 INVITE\r\nContent-Length: 0\r\n\r\n", msg.Headers["via"], msg.Headers["from"], msg.Headers["to"], callID)); if err := d.Hooks.Transport.Send(ctx, d.Hooks.Peer, response); err != nil { d.transition(StateFailed); return err }; d.transition(StatePlaying); return nil
}

func (d *Device) StartPlay(ctx context.Context) error { return d.startMedia(ctx) }
func (d *Device) startMedia(ctx context.Context) error { d.mu.Lock(); active := d.active; d.mu.Unlock(); if !active { return fmt.Errorf("no active invite") }; if d.Hooks.Sender == nil { return nil }; if err := d.Hooks.Sender.Start(ctx); err != nil { d.transition(StateFailed); return err }; return nil }

func (d *Device) StopPlay(ctx context.Context) error {
	d.mu.Lock(); if !d.active { d.mu.Unlock(); return nil }; d.active = false; dialog := d.dialog; already := d.byeSent; d.byeSent = true; d.mu.Unlock(); if d.Hooks.Sender != nil { _ = d.Hooks.Sender.Stop() }; if !already && d.Hooks.Transport != nil { b, _ := sip.BuildBye(dialog); if len(b) > 0 { _ = d.Hooks.Transport.Send(ctx, d.Hooks.Peer, b) } }; d.transition(StateStopping); d.transition(StateOnline); return nil
}

func (d *Device) Close() error { if d.runCancel != nil { d.runCancel() }; d.wg.Wait(); _ = d.StopPlay(context.Background()); d.transition(StateClosed); if d.Hooks.Transport != nil { return d.Hooks.Transport.Close() }; return nil }
