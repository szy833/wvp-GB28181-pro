package sip

import "encoding/xml"

type keepalive struct { XMLName xml.Name `xml:"Notify"`; CmdType string `xml:"CmdType"`; SN int `xml:"SN"`; DeviceID string `xml:"DeviceID"`; Status string `xml:"Status"`; Info string `xml:"Info,omitempty"` }

func BuildKeepaliveXML(deviceID, manufacturer string) []byte { b, _ := xml.Marshal(keepalive{CmdType: "Keepalive", SN: 1, DeviceID: deviceID, Status: "OK", Info: manufacturer}); return append([]byte(xml.Header), b...) }
