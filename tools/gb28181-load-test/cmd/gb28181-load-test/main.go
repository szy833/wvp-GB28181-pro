package main

import (
	"context"
	"flag"
	"fmt"
	"github.com/shizy/gb28181-load-test/internal/config"
	"github.com/shizy/gb28181-load-test/internal/device"
	"github.com/shizy/gb28181-load-test/internal/metrics"
	"github.com/shizy/gb28181-load-test/internal/rtp"
	"github.com/shizy/gb28181-load-test/internal/runner"
	"github.com/shizy/gb28181-load-test/internal/sip"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
)

type deviceFactory struct {
	identities *device.IdentityFactory
	cfg        config.Config
	media      *rtp.SharedMedia
}

func (f deviceFactory) New(index int) (*device.Device, error) {
	id, err := f.identities.New(index)
	if err != nil {
		return nil, err
	}
	local := f.cfg.SIP.Local
	if local == "" {
		local = "0.0.0.0:0"
	}
	tr, err := sip.NewUDPTransport(local)
	if err != nil {
		return nil, err
	}
	peer, err := netResolve(f.cfg.SIP.Address)
	if err != nil {
		_ = tr.Close()
		return nil, err
	}
	username := strings.ReplaceAll(f.cfg.Device.Username, "{deviceId}", id.DeviceID)
	localAddr := tr.LocalAddr().(*net.UDPAddr)
	advertiseHost := f.cfg.SIP.Advertise
	if advertiseHost == "" {
		advertiseHost = localAddr.IP.String()
		if advertiseHost == "0.0.0.0" {
			advertiseHost = "127.0.0.1"
		}
	}
	byeAfter := time.Duration(0)
	if strings.EqualFold(f.cfg.Scenario.Name, "device-bye") {
		byeAfter = f.cfg.Scenario.PlayDuration
	}
	hooks := device.DeviceHooks{Transport: tr, Peer: peer, Local: net.JoinHostPort(advertiseHost, strconv.Itoa(localAddr.Port)), Target: "sip:server@" + f.cfg.SIP.Address, Username: username, Password: f.cfg.Device.Password, Realm: f.cfg.Device.Realm, ServerID: f.cfg.Device.ServerID, Name: f.cfg.Device.Name, Manufacturer: f.cfg.Device.Manufacturer, Model: f.cfg.Device.Model, NoMedia: strings.EqualFold(f.cfg.Scenario.Name, "no-media-timeout"), DeviceByeAfter: byeAfter, RegisterInterval: f.cfg.RegisterTTL, KeepaliveInterval: f.cfg.Keepalive}
	hooks.SenderFactory = func(offer sip.SDPResponse) (device.MediaSender, string, error) {
		if f.media == nil {
			return nil, "", fmt.Errorf("shared media is not loaded")
		}
		if hooks.NoMedia {
			mode := "UDP"
			if strings.EqualFold(offer.Setup, "passive") {
				mode = "TCP-ACTIVE"
			}
			return nil, sip.BuildPlaySDP(sip.SDPRequest{Address: "0.0.0.0", Port: offer.Port, SSRC: id.SSRC, Mode: mode}), nil
		}
		source := f.media.NewReader(1200)
		value, _ := strconv.ParseUint(id.SSRC, 10, 64)
		if strings.EqualFold(offer.Setup, "passive") || strings.Contains(strings.ToUpper(offer.Protocol), "TCP") {
			target, err := net.ResolveTCPAddr("tcp", net.JoinHostPort(offer.Address, strconv.Itoa(offer.Port)))
			if err != nil {
				_ = source.Close()
				return nil, "", err
			}
			sender := rtp.NewTCPPSender(target, source, uint32(value))
			answer := sip.BuildPlaySDP(sip.SDPRequest{Address: "0.0.0.0", Port: offer.Port, SSRC: id.SSRC, Mode: "TCP-ACTIVE"})
			return &senderAdapter{start: sender.Start, stop: func() error { err := sender.Stop(); _ = source.Close(); return err }}, answer, nil
		}
		target, err := net.ResolveUDPAddr("udp", net.JoinHostPort(offer.Address, strconv.Itoa(offer.Port)))
		if err != nil {
			_ = source.Close()
			return nil, "", err
		}
		conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
		if err != nil {
			_ = source.Close()
			return nil, "", err
		}
		sender := rtp.NewSender(conn, target, source, uint32(value))
		localAddr := conn.LocalAddr().(*net.UDPAddr)
		answer := sip.BuildPlaySDP(sip.SDPRequest{Address: advertiseHost, Port: localAddr.Port, SSRC: id.SSRC, Mode: "UDP"})
		return &senderAdapter{start: sender.Start, stop: func() error { err := sender.Stop(); _ = source.Close(); _ = conn.Close(); return err }}, answer, nil
	}
	return device.New(id, hooks), nil
}

type senderAdapter struct {
	start func(context.Context) error
	stop  func() error
}

func (s *senderAdapter) Start(ctx context.Context) error { return s.start(ctx) }
func (s *senderAdapter) Stop() error                     { return s.stop() }
func netResolve(address string) (*net.UDPAddr, error)    { return net.ResolveUDPAddr("udp", address) }

func main() {
	path := flag.String("config", "configs/example.yaml", "配置文件")
	flag.Parse()
	cfg, err := config.Load(*path)
	if err != nil {
		log.Fatal(err)
	}
	media, err := rtp.NewSharedMedia(cfg.Media.File, 512*1024*1024)
	if err != nil {
		log.Fatal(err)
	}
	ids, err := device.NewIdentityFactoryWithConfig(device.IdentityFactoryConfig{Mode: cfg.Identity.Mode, DeviceBase: cfg.Identity.DeviceBase, ChannelBase: cfg.Identity.ChannelBase, LegacyPrefix: cfg.Identity.LegacyPrefix, RunID: cfg.RunID, Domain: cfg.Domain})
	if err != nil {
		log.Fatal(err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	reg := metrics.NewRegistry()
	metricsServer := startMetricsServer(cfg.MetricsAddr, reg)
	if metricsServer != nil {
		defer metricsServer.Shutdown(context.Background())
	}
	runCtx := ctx
	cancel := func() {}
	if cfg.Scenario.Duration > 0 {
		runCtx, cancel = context.WithTimeout(ctx, cfg.Scenario.Duration)
	}
	defer cancel()
	r := runner.Runner{Metrics: reg, PlayRate: cfg.Scenario.PlayRate, MaxConcurrent: cfg.Scenario.MaxConcurrent, PlayDuration: cfg.Scenario.PlayDuration, StartupGrace: cfg.Scenario.StartupGrace}
	if cfg.Control.BaseURL != "" {
		r.PlayController = &runner.HTTPPlayController{BaseURL: cfg.Control.BaseURL, Token: cfg.Control.Token, StartPath: cfg.Control.StartPath, StopPath: cfg.Control.StopPath, Metrics: reg}
	}
	report, err := r.Run(runCtx, cfg, deviceFactory{identities: ids, cfg: cfg, media: media})
	if err != nil {
		log.Fatal(err)
	}
	if cfg.Report.JSON != "" {
		if err := metrics.WriteJSON(cfg.Report.JSON, report); err != nil {
			log.Fatal(err)
		}
	}
	if cfg.Report.CSV != "" {
		if err := metrics.WriteCSV(cfg.Report.CSV, report); err != nil {
			log.Fatal(err)
		}
	}
	detailPath := cfg.Report.DetailCSV
	if detailPath == "" && cfg.Report.CSV != "" {
		detailPath = metrics.DetailCSVPath(cfg.Report.CSV)
	}
	if detailPath != "" {
		if err := metrics.WriteDetailCSV(detailPath, report); err != nil {
			log.Fatal(err)
		}
	}
	metrics.PrintSummary(os.Stdout, report, detailPath)
}

func startMetricsServer(address string, registry *metrics.Registry) *http.Server {
	if address == "" {
		return nil
	}
	mux := http.NewServeMux()
	mux.Handle("/metrics", registry)
	server := &http.Server{Addr: address, Handler: mux}
	go func() {
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Printf("metrics server: %v", err)
		}
	}()
	return server
}
