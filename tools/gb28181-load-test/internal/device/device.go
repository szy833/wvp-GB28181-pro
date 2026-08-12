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

const (
	StateCreated  State = "CREATED"
	StateOnline   State = "ONLINE"
	StatePlaying  State = "PLAYING"
	StateStopping State = "STOPPING"
	StateFailed   State = "FAILED"
	StateClosed   State = "CLOSED"
)

type MediaSender interface {
	Start(context.Context) error
	Stop() error
}
type DeviceEvent struct {
	Name      string
	DeviceID  string
	ChannelID string
	Error     string
	At        time.Time
}
type EventSink interface{ OnDeviceEvent(DeviceEvent) }
type DeviceHooks struct {
	Transport         sip.Transport
	Peer              net.Addr
	Local             string
	Target            string
	Username          string
	Password          string
	Realm             string
	ServerID          string
	Name              string
	Manufacturer      string
	Model             string
	NoMedia           bool
	DeviceByeAfter    time.Duration
	Sender            MediaSender
	SenderFactory     func(sip.SDPResponse) (MediaSender, string, error)
	RegisterInterval  time.Duration
	KeepaliveInterval time.Duration
	MediaTimeout      time.Duration
}

type Device struct {
	Identity     Identity
	Hooks        DeviceHooks
	mu           sync.Mutex
	eventMu      sync.RWMutex
	eventSink    EventSink
	state        State
	active       bool
	dialog       sip.Dialog
	runCancel    context.CancelFunc
	wg           sync.WaitGroup
	byeSent      bool
	digest       sip.DigestChallenge
	registerCSeq uint32
	messageCSeq  uint32
	nonceCount   uint32
}

func New(id Identity, hooks DeviceHooks) *Device {
	if hooks.RegisterInterval <= 0 {
		hooks.RegisterInterval = time.Hour
	}
	if hooks.KeepaliveInterval <= 0 {
		hooks.KeepaliveInterval = 30 * time.Second
	}
	if hooks.MediaTimeout <= 0 {
		hooks.MediaTimeout = 10 * time.Second
	}
	if hooks.Name == "" {
		hooks.Name = id.DeviceID
	}
	if hooks.Manufacturer == "" {
		hooks.Manufacturer = "gb28181-load-test"
	}
	if hooks.Model == "" {
		hooks.Model = "virtual-camera"
	}
	return &Device{Identity: id, Hooks: hooks, state: StateCreated, registerCSeq: 1, messageCSeq: 1}
}
func (d *Device) SetEventSink(sink EventSink) {
	d.eventMu.Lock()
	d.eventSink = sink
	d.eventMu.Unlock()
}
func (d *Device) emit(name, channelID, errText string) {
	d.eventMu.RLock()
	sink := d.eventSink
	d.eventMu.RUnlock()
	if sink != nil {
		sink.OnDeviceEvent(DeviceEvent{Name: name, DeviceID: d.Identity.DeviceID, ChannelID: channelID, Error: errText, At: time.Now()})
	}
}
func (d *Device) State() State       { d.mu.Lock(); defer d.mu.Unlock(); return d.state }
func (d *Device) transition(s State) { d.mu.Lock(); d.state = s; d.mu.Unlock() }

func (d *Device) Run(ctx context.Context) error {
	if d.Hooks.Transport == nil {
		return fmt.Errorf("SIP transport is nil")
	}
	runCtx, cancel := context.WithCancel(ctx)
	d.runCancel = cancel
	d.wg.Add(1)
	go d.loop(runCtx)
	return nil
}

func (d *Device) loop(ctx context.Context) {
	defer d.wg.Done()
	ticker := time.NewTicker(d.Hooks.RegisterInterval)
	defer ticker.Stop()
	keepaliveTicker := time.NewTicker(d.Hooks.KeepaliveInterval)
	defer keepaliveTicker.Stop()
	d.sendRegister(ctx)
	d.transition(StateOnline)
	messages := make(chan sip.Message, 1)
	go func() {
		for {
			msg, err := d.Hooks.Transport.Receive(ctx)
			if err != nil {
				return
			}
			select {
			case messages <- msg:
			case <-ctx.Done():
				return
			}
		}
	}()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			d.sendRegister(ctx)
		case <-keepaliveTicker.C:
			d.sendKeepalive(ctx)
		case msg := <-messages:
			_ = d.HandleMessage(ctx, msg)
		}
	}
}

func (d *Device) sendRegister(ctx context.Context) {
	target := d.Hooks.Target
	if target == "" {
		target = "sip:server@127.0.0.1"
	}
	d.mu.Lock()
	cseq := d.registerCSeq
	if cseq == 0 {
		cseq = 1
	}
	d.registerCSeq = cseq + 1
	auth := ""
	if d.digest.Nonce != "" && d.Hooks.Username != "" {
		d.nonceCount++
		auth, _ = sip.BuildDigestAuthorization("REGISTER", target, d.Hooks.Username, d.Hooks.Password, d.digest, d.nonceCount)
	}
	local := d.Hooks.Local
	d.mu.Unlock()
	b, err := sip.BuildRegisterAuthCSeq(d.Identity, target, local, int(d.Hooks.RegisterInterval.Seconds()), auth, cseq)
	if err != nil {
		d.emit("register_error", "", err.Error())
		return
	}
	if err := d.Hooks.Transport.Send(ctx, d.Hooks.Peer, b); err != nil {
		d.emit("register_error", "", err.Error())
		return
	}
	d.emit("register_request", "", "")
}

func (d *Device) sendKeepalive(ctx context.Context) {
	target := d.Hooks.Target
	if target == "" {
		target = "sip:server@127.0.0.1"
	}
	d.mu.Lock()
	cseq := d.messageCSeq
	d.messageCSeq++
	local := d.Hooks.Local
	d.mu.Unlock()
	body := sip.BuildKeepaliveXML(d.Identity.DeviceID, d.Hooks.Manufacturer)
	b, err := sip.BuildMessage(d.Identity, target, local, body, cseq)
	if err == nil {
		_ = d.Hooks.Transport.Send(ctx, d.Hooks.Peer, b)
	}
}

func (d *Device) HandleMessage(ctx context.Context, msg sip.Message) error {
	if strings.HasPrefix(msg.StartLine, "SIP/2.0 401") && strings.Contains(strings.ToUpper(msg.Headers["cseq"]), "REGISTER") {
		return d.handleRegisterChallenge(ctx, msg)
	}
	if strings.HasPrefix(msg.StartLine, "SIP/2.0 200") && strings.Contains(strings.ToUpper(msg.Headers["cseq"]), "REGISTER") {
		d.emit("register_success", "", "")
		return nil
	}
	if strings.HasPrefix(msg.StartLine, "INVITE ") {
		return d.handleInvite(ctx, msg)
	}
	if strings.HasPrefix(msg.StartLine, "ACK ") {
		return d.startMedia(ctx)
	}
	if strings.HasPrefix(msg.StartLine, "BYE ") {
		return d.handleRemoteBye(ctx, msg)
	}
	if strings.HasPrefix(msg.StartLine, "MESSAGE ") {
		return d.handleQuery(ctx, msg)
	}
	if strings.HasPrefix(msg.StartLine, "OPTIONS ") {
		return d.sendResponse(ctx, msg, "200 OK", "")
	}
	return nil
}

func (d *Device) handleRegisterChallenge(ctx context.Context, msg sip.Message) error {
	challenge, err := sip.ParseDigestChallenge(msg.Headers["www-authenticate"])
	if err != nil {
		d.emit("register_error", "", err.Error())
		return err
	}
	d.emit("register_challenge", "", "")
	d.mu.Lock()
	d.digest = challenge
	d.mu.Unlock()
	d.sendRegister(ctx)
	return nil
}

func (d *Device) handleQuery(ctx context.Context, msg sip.Message) error {
	q, err := sip.ParseQuery(msg.Body)
	if err != nil {
		d.emit("query_error", "", err.Error())
		return err
	}
	if err := d.sendResponse(ctx, msg, "200 OK", ""); err != nil {
		d.emit("query_error", "", err.Error())
		return err
	}
	target := d.Hooks.Target
	if target == "" {
		target = strings.TrimSpace(strings.Split(msg.Headers["from"], ";")[0])
		target = strings.Trim(target, "<>")
	}
	d.mu.Lock()
	cseq := d.messageCSeq
	d.messageCSeq++
	d.mu.Unlock()
	var body []byte
	switch strings.ToLower(q.CmdType) {
	case "catalog":
		body = sip.BuildCatalogXML(d.Hooks.ServerID, q, sip.CatalogItem{DeviceID: d.Identity.ChannelID, Name: d.Hooks.Name, Manufacturer: d.Hooks.Manufacturer, Model: d.Hooks.Model, Status: "ON", ParentID: d.Hooks.ServerID})
	case "deviceinfo":
		body = sip.BuildDeviceInfoXML(d.Hooks.ServerID, q, d.Hooks.Name, d.Hooks.Manufacturer, d.Hooks.Model)
	case "devicestatus":
		body = sip.BuildDeviceStatusXML(d.Hooks.ServerID, q)
	default:
		return nil
	}
	b, err := sip.BuildMessage(d.Identity, target, d.Hooks.Local, body, cseq)
	if err != nil {
		d.emit("query_error", "", err.Error())
		return err
	}
	if err := d.Hooks.Transport.Send(ctx, d.Hooks.Peer, b); err != nil {
		d.emit("query_error", "", err.Error())
		return err
	}
	d.emit(strings.ToLower(q.CmdType)+"_response", d.Identity.ChannelID, "")
	return nil
}

func (d *Device) sendResponse(ctx context.Context, msg sip.Message, status, body string) error {
	response := []byte(fmt.Sprintf("SIP/2.0 %s\r\nVia: %s\r\nFrom: %s\r\nTo: %s;tag=loadtest\r\nCall-ID: %s\r\nCSeq: %s\r\nContent-Length: %d\r\n\r\n%s", status, msg.Headers["via"], msg.Headers["from"], msg.Headers["to"], msg.Headers["call-id"], msg.Headers["cseq"], len(body), body))
	return d.Hooks.Transport.Send(ctx, d.Hooks.Peer, response)
}

func (d *Device) handleRemoteBye(ctx context.Context, msg sip.Message) error {
	d.mu.Lock()
	active := d.active
	d.active = false
	d.mu.Unlock()
	if active && d.Hooks.Sender != nil {
		_ = d.Hooks.Sender.Stop()
	}
	d.transition(StateOnline)
	d.emit("device_bye", d.Identity.ChannelID, "")
	response := []byte(fmt.Sprintf("SIP/2.0 200 OK\r\nVia: %s\r\nFrom: %s\r\nTo: %s\r\nCall-ID: %s\r\nCSeq: %s\r\nContent-Length: 0\r\n\r\n", msg.Headers["via"], msg.Headers["from"], msg.Headers["to"], msg.Headers["call-id"], msg.Headers["cseq"]))
	if d.Hooks.Transport != nil {
		return d.Hooks.Transport.Send(ctx, d.Hooks.Peer, response)
	}
	return nil
}

func (d *Device) handleInvite(ctx context.Context, msg sip.Message) error {
	d.mu.Lock()
	if d.active {
		d.mu.Unlock()
		return fmt.Errorf("playback already active")
	}
	callID := msg.Headers["call-id"]
	if callID == "" {
		callID = d.Identity.CallID
	}
	d.dialog = sip.Dialog{Target: msg.Headers["from"], Local: msg.Headers["to"], CallID: callID, CSeq: 1}
	d.active = true
	d.byeSent = false
	d.mu.Unlock()
	var sender MediaSender
	answer := ""
	if d.Hooks.SenderFactory != nil {
		offer, err := sip.ParseSDP(msg.Body)
		if err != nil {
			d.transition(StateFailed)
			d.emit("invite_error", d.Identity.ChannelID, err.Error())
			return err
		}
		sender, answer, err = d.Hooks.SenderFactory(offer)
		if err != nil {
			d.transition(StateFailed)
			d.emit("invite_error", d.Identity.ChannelID, err.Error())
			return err
		}
		if d.Hooks.NoMedia {
			sender = nil
		}
		d.mu.Lock()
		d.Hooks.Sender = sender
		d.mu.Unlock()
	}
	response := []byte(fmt.Sprintf("SIP/2.0 200 OK\r\nVia: %s\r\nFrom: %s\r\nTo: %s;tag=loadtest\r\nCall-ID: %s\r\nCSeq: 1 INVITE\r\nContent-Type: application/sdp\r\nContent-Length: %d\r\n\r\n%s", msg.Headers["via"], msg.Headers["from"], msg.Headers["to"], callID, len(answer), answer))
	if err := d.Hooks.Transport.Send(ctx, d.Hooks.Peer, response); err != nil {
		d.transition(StateFailed)
		d.emit("invite_error", d.Identity.ChannelID, err.Error())
		return err
	}
	d.transition(StatePlaying)
	d.emit("invite_success", d.Identity.ChannelID, "")
	return nil
}

func (d *Device) StartPlay(ctx context.Context) error { return d.startMedia(ctx) }
func (d *Device) startMedia(ctx context.Context) error {
	d.mu.Lock()
	active := d.active
	d.mu.Unlock()
	if !active {
		err := fmt.Errorf("no active invite")
		d.emit("media_start_error", d.Identity.ChannelID, err.Error())
		return err
	}
	if d.Hooks.Sender == nil {
		return nil
	}
	if err := d.Hooks.Sender.Start(ctx); err != nil {
		d.transition(StateFailed)
		d.emit("media_start_error", d.Identity.ChannelID, err.Error())
		return err
	}
	d.emit("media_started", d.Identity.ChannelID, "")
	if d.Hooks.DeviceByeAfter > 0 {
		byeCtx := ctx
		go func() {
			timer := time.NewTimer(d.Hooks.DeviceByeAfter)
			defer timer.Stop()
			select {
			case <-timer.C:
				_ = d.StopPlay(byeCtx)
			case <-byeCtx.Done():
			}
		}()
	}
	return nil
}

func (d *Device) StopPlay(ctx context.Context) error {
	d.mu.Lock()
	if !d.active {
		d.mu.Unlock()
		return nil
	}
	d.active = false
	dialog := d.dialog
	already := d.byeSent
	d.byeSent = true
	d.mu.Unlock()
	if d.Hooks.Sender != nil {
		_ = d.Hooks.Sender.Stop()
	}
	if !already && d.Hooks.Transport != nil {
		b, _ := sip.BuildBye(dialog)
		if len(b) > 0 {
			_ = d.Hooks.Transport.Send(ctx, d.Hooks.Peer, b)
			d.emit("device_bye_sent", d.Identity.ChannelID, "")
		}
	}
	d.transition(StateStopping)
	d.transition(StateOnline)
	return nil
}

func (d *Device) Close() error {
	if d.runCancel != nil {
		d.runCancel()
	}
	d.wg.Wait()
	_ = d.StopPlay(context.Background())
	d.transition(StateClosed)
	if d.Hooks.Transport != nil {
		return d.Hooks.Transport.Close()
	}
	return nil
}
