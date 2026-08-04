package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"syscall"
	"github.com/shizy/gb28181-load-test/internal/config"
	"github.com/shizy/gb28181-load-test/internal/device"
	"github.com/shizy/gb28181-load-test/internal/metrics"
	"github.com/shizy/gb28181-load-test/internal/runner"
	"github.com/shizy/gb28181-load-test/internal/sip"
)

type deviceFactory struct { identities *device.IdentityFactory; cfg config.Config }
func (f deviceFactory) New(index int) (*device.Device, error) { id, err := f.identities.New(index); if err != nil { return nil, err }; tr, err := sip.NewUDPTransport(f.cfg.SIP.Local); if err != nil { return nil, err }; peer, err := netResolve(f.cfg.SIP.Address); if err != nil { _ = tr.Close(); return nil, err }; return device.New(id, device.DeviceHooks{Transport: tr, Peer: peer, RegisterInterval: f.cfg.RegisterTTL}), nil }
func netResolve(address string) (*net.UDPAddr, error) { return net.ResolveUDPAddr("udp", address) }

func main() {
	path := flag.String("config", "configs/example.yaml", "配置文件"); flag.Parse(); cfg, err := config.Load(*path); if err != nil { log.Fatal(err) }; ids, err := device.NewIdentityFactory(cfg.RunID, cfg.IDPrefix, cfg.Domain); if err != nil { log.Fatal(err) }; ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM); defer stop(); reg := metrics.NewRegistry(); r := runner.Runner{Metrics: reg}; report, err := r.Run(ctx, cfg, deviceFactory{identities: ids, cfg: cfg}); if err != nil { log.Fatal(err) }; if cfg.Report.JSON != "" { if err := metrics.WriteJSON(cfg.Report.JSON, report); err != nil { log.Fatal(err) } }; if cfg.Report.CSV != "" { if err := metrics.WriteCSV(cfg.Report.CSV, report); err != nil { log.Fatal(err) } }; fmt.Printf("run=%s devices=%d started=%s finished=%s\n", report.RunID, report.DeviceCount, report.StartedAt.Format("2006-01-02T15:04:05Z07:00"), report.FinishedAt.Format("2006-01-02T15:04:05Z07:00"))
}
