package runner

import (
	"context"
	"testing"
	"github.com/shizy/gb28181-load-test/internal/config"
	"github.com/shizy/gb28181-load-test/internal/device"
)

type factory struct { idf *device.IdentityFactory }
func (f factory) New(i int) (*device.Device, error) { id, err := f.idf.New(i); if err != nil { return nil, err }; return device.New(id, device.DeviceHooks{}), nil }
func TestRunnerCreatesDevices(t *testing.T) { idf, _ := device.NewIdentityFactory("run", "loadtest-", "3402"); cfg := config.Config{RunID: "run", DeviceCount: 2, StartRate: 0}; r := Runner{}; report, err := r.Run(context.Background(), cfg, factory{idf}); if err != nil { t.Fatal(err) }; if report.DeviceCount != 2 || len(report.Stages) != 1 { t.Fatalf("%+v", report) } }
