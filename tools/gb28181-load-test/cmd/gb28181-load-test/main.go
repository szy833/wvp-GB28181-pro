package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"github.com/shizy/gb28181-load-test/internal/config"
	"github.com/shizy/gb28181-load-test/internal/device"
	"github.com/shizy/gb28181-load-test/internal/metrics"
	"github.com/shizy/gb28181-load-test/internal/runner"
	"github.com/shizy/gb28181-load-test/internal/rtp"
	"github.com/shizy/gb28181-load-test/internal/sip"
)

type deviceFactory struct { identities *device.IdentityFactory; cfg config.Config }
func (f deviceFactory) New(index int) (*device.Device, error) {
	id, err := f.identities.New(index); if err != nil { return nil, err }
	local := f.cfg.SIP.Local; if local == "" { local = "0.0.0.0:0" }
	tr, err := sip.NewUDPTransport(local); if err != nil { return nil, err }
	peer, err := netResolve(f.cfg.SIP.Address); if err != nil { _ = tr.Close(); return nil, err }
	hooks := device.DeviceHooks{Transport: tr, Peer: peer, Target: "sip:server@" + f.cfg.SIP.Address, RegisterInterval: f.cfg.RegisterTTL}
	hooks.SenderFactory = func(offer sip.SDPResponse) (device.MediaSender, string, error) {
		target, err := net.ResolveUDPAddr("udp", net.JoinHostPort(offer.Address, strconv.Itoa(offer.Port))); if err != nil { return nil, "", err }
		conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0}); if err != nil { return nil, "", err }
		source, err := rtp.NewFileSource(f.cfg.Media.File, 512*1024*1024, 1200); if err != nil { _ = conn.Close(); return nil, "", err }
		value, _ := strconv.ParseUint(id.SSRC, 10, 64); sender := rtp.NewSender(conn, target, source, uint32(value)); localAddr := conn.LocalAddr().(*net.UDPAddr)
		answer := sip.BuildPlaySDP(sip.SDPRequest{Address: localAddr.IP.String(), Port: localAddr.Port, SSRC: id.SSRC, Mode: "UDP"})
		return &senderAdapter{sender: sender, conn: conn}, answer, nil
	}
	return device.New(id, hooks), nil
}

type senderAdapter struct { sender *rtp.Sender; conn *net.UDPConn }
func (s *senderAdapter) Start(ctx context.Context) error { return s.sender.Start(ctx) }
func (s *senderAdapter) Stop() error { err := s.sender.Stop(); _ = s.sender.Source.Close(); _ = s.conn.Close(); return err }
func netResolve(address string) (*net.UDPAddr, error) { return net.ResolveUDPAddr("udp", address) }

func main() {
	path := flag.String("config", "configs/example.yaml", "配置文件"); flag.Parse(); cfg, err := config.Load(*path); if err != nil { log.Fatal(err) }; ids, err := device.NewIdentityFactory(cfg.RunID, cfg.IDPrefix, cfg.Domain); if err != nil { log.Fatal(err) }; ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM); defer stop(); reg := metrics.NewRegistry(); r := runner.Runner{Metrics: reg}; report, err := r.Run(ctx, cfg, deviceFactory{identities: ids, cfg: cfg}); if err != nil { log.Fatal(err) }; if cfg.Report.JSON != "" { if err := metrics.WriteJSON(cfg.Report.JSON, report); err != nil { log.Fatal(err) } }; if cfg.Report.CSV != "" { if err := metrics.WriteCSV(cfg.Report.CSV, report); err != nil { log.Fatal(err) } }; fmt.Printf("run=%s devices=%d started=%s finished=%s\n", report.RunID, report.DeviceCount, report.StartedAt.Format("2006-01-02T15:04:05Z07:00"), report.FinishedAt.Format("2006-01-02T15:04:05Z07:00"))
}
