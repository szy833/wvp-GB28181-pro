package runner

import (
	"context"
	"github.com/shizy/gb28181-load-test/internal/config"
	"github.com/shizy/gb28181-load-test/internal/device"
	"github.com/shizy/gb28181-load-test/internal/metrics"
	"testing"
	"time"
)

type factory struct{ idf *device.IdentityFactory }

func (f factory) New(i int) (*device.Device, error) {
	id, err := f.idf.New(i)
	if err != nil {
		return nil, err
	}
	return device.New(id, device.DeviceHooks{}), nil
}

func TestResultCollectorJoinsOutOfOrderPlayEvents(t *testing.T) {
	collector := newResultCollector(metrics.NewRegistry())
	started := time.Now()
	collector.OnPlayEvent("device-1", "media_confirmed", started.Add(2*time.Second))
	collector.RecordPlay(metrics.PlayResult{ID: "p1", DeviceID: "device-1", StartedAt: started, StartSuccess: true})
	collector.OnPlayEvent("device-1", "invite_success", started.Add(time.Second))
	plays := collector.PlayResults()
	if len(plays) != 1 || plays[0].InviteAt.IsZero() || plays[0].MediaAt.IsZero() {
		t.Fatalf("events were not joined: %+v", plays)
	}
	if plays[0].InviteLatencySeconds < .9 || plays[0].MediaConfirmationLatencySeconds < 1.9 {
		t.Fatalf("unexpected phase latency: %+v", plays[0])
	}
}
func TestRunnerCreatesDevices(t *testing.T) {
	idf, _ := device.NewIdentityFactory("run", "loadtest-", "3402")
	cfg := config.Config{RunID: "run", DeviceCount: 2, StartRate: 0}
	r := Runner{}
	report, err := r.Run(context.Background(), cfg, factory{idf})
	if err != nil {
		t.Fatal(err)
	}
	if report.DeviceCount != 2 || len(report.Stages) != 1 {
		t.Fatalf("%+v", report)
	}
}
