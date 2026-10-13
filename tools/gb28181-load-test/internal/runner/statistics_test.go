package runner

import (
	"context"
	"github.com/shizy/gb28181-load-test/internal/config"
	"github.com/shizy/gb28181-load-test/internal/device"
	"github.com/shizy/gb28181-load-test/internal/metrics"
	"testing"
	"time"
)

func TestBusinessRegistrationCountsDevicesNotDigestRequests(t *testing.T) {
	b := businessSummary(metrics.Report{Devices: []metrics.DeviceResult{{RegisterRequests: 2, RegisterChallenges: 1, RegisterSuccess: 1}}})
	if b.RegisterSuccessRate != 1 {
		t.Fatalf("successful Digest registration reported as %v", b.RegisterSuccessRate)
	}
}

func TestOnlineOnlyRunWaitsForConfiguredDuration(t *testing.T) {
	idf, _ := device.NewIdentityFactory("online", "loadtest-", "3402")
	started := time.Now()
	r := Runner{}
	_, err := r.Run(context.Background(), config.Config{DeviceCount: 1, Scenario: config.ScenarioConfig{Duration: 30 * time.Millisecond}}, factory{idf})
	if err != nil {
		t.Fatal(err)
	}
	if time.Since(started) < 20*time.Millisecond {
		t.Fatal("online-only run closed devices immediately")
	}
}

func TestCatalogDoesNotIncludeOtherQueryTypes(t *testing.T) {
	c := newResultCollector(nil)
	for _, name := range []string{"catalog_response", "deviceinfo_response", "devicestatus_response"} {
		c.OnDeviceEvent(device.DeviceEvent{Name: name, DeviceID: "d"})
	}
	if got := c.DeviceResults()[0].CatalogSuccess; got != 1 {
		t.Fatalf("catalog count=%d, want 1", got)
	}
}

func TestUnexecutedBusinessRateIsNotOne(t *testing.T) {
	b := businessSummary(metrics.Report{})
	if b.PlaySuccessRate == 1 || b.MediaSuccessRate == 1 || b.StopSuccessRate == 1 {
		t.Fatalf("unexecuted tests reported successful: %+v", b)
	}
}
