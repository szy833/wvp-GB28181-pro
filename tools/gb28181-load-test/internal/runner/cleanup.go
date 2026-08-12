package runner

import "github.com/shizy/gb28181-load-test/internal/device"

func Cleanup(devices []*device.Device) {
	for _, d := range devices {
		if d != nil {
			_ = d.Close()
		}
	}
}
