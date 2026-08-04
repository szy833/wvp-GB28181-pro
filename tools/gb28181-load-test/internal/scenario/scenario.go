package scenario

import (
	"context"
	"github.com/shizy/gb28181-load-test/internal/device"
)

type PlayPolicy string
const ( PolicyContinuous PlayPolicy = "continuous"; PolicyNoMedia PlayPolicy = "no-media"; PolicyDeviceBye PlayPolicy = "device-bye" )
type Scenario interface { Name() string; Run(context.Context, *device.Device) error; PlayPolicy() PlayPolicy }
