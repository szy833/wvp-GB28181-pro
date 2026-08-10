package sip

import (
	"bytes"
	"encoding/xml"
	"fmt"
	"io"
	"strings"
)

type keepalive struct { XMLName xml.Name `xml:"Notify"`; CmdType string `xml:"CmdType"`; SN int `xml:"SN"`; DeviceID string `xml:"DeviceID"`; Status string `xml:"Status"`; Info string `xml:"Info,omitempty"` }

func BuildKeepaliveXML(deviceID, manufacturer string) []byte { b, _ := xml.Marshal(keepalive{CmdType: "Keepalive", SN: 1, DeviceID: deviceID, Status: "OK", Info: manufacturer}); return append([]byte(xml.Header), b...) }

type Query struct { CmdType string `xml:"CmdType"`; SN int `xml:"SN"`; DeviceID string `xml:"DeviceID"` }

// ParseQuery accepts the GB2312/GBK declarations commonly emitted by GB28181
// devices. The query fields are protocol identifiers and are ASCII in practice;
// preserving the bytes also keeps this package dependency-free.
func ParseQuery(body []byte) (Query, error) {
	var q Query
	decoder := xml.NewDecoder(bytes.NewReader(body))
	decoder.CharsetReader = func(charset string, input io.Reader) (io.Reader, error) {
		name := strings.ToLower(strings.TrimSpace(charset))
		switch name {
		case "utf-8", "utf8", "gb2312", "gbk", "gb18030", "iso-8859-1":
			return input, nil
		default:
			return nil, fmt.Errorf("unsupported XML charset %q", charset)
		}
	}
	if err := decoder.Decode(&q); err != nil {
		return q, err
	}
	return q, nil
}

type catalogResponse struct { XMLName xml.Name `xml:"Response"`; CmdType string `xml:"CmdType"`; SN int `xml:"SN"`; DeviceID string `xml:"DeviceID"`; SumNum int `xml:"SumNum"`; DeviceList catalogList `xml:"DeviceList"` }
type catalogList struct { Num int `xml:"Num,attr"`; Items []CatalogItem `xml:"Item"` }
type CatalogItem struct { DeviceID string `xml:"DeviceID"`; Name string `xml:"Name"`; Manufacturer string `xml:"Manufacturer"`; Model string `xml:"Model"`; Owner string `xml:"Owner"`; CivilCode string `xml:"CivilCode"`; Status string `xml:"Status"`; ParentID string `xml:"ParentID,omitempty"` }

func BuildCatalogXML(serverID string, q Query, id CatalogItem) []byte { responseID := q.DeviceID; if responseID == "" { responseID = serverID }; b, _ := xml.Marshal(catalogResponse{CmdType: "Catalog", SN: q.SN, DeviceID: responseID, SumNum: 1, DeviceList: catalogList{Num: 1, Items: []CatalogItem{id}}}); return append([]byte(xml.Header), b...) }

type deviceInfoResponse struct { XMLName xml.Name `xml:"Response"`; CmdType string `xml:"CmdType"`; SN int `xml:"SN"`; DeviceID string `xml:"DeviceID"`; DeviceName string `xml:"DeviceName"`; Manufacturer string `xml:"Manufacturer"`; Model string `xml:"Model"`; Firmware string `xml:"Firmware"`; Result string `xml:"Result"` }
func BuildDeviceInfoXML(serverID string, q Query, name, manufacturer, model string) []byte { responseID := q.DeviceID; if responseID == "" { responseID = serverID }; b, _ := xml.Marshal(deviceInfoResponse{CmdType: "DeviceInfo", SN: q.SN, DeviceID: responseID, DeviceName: name, Manufacturer: manufacturer, Model: model, Firmware: "loadtest/1.0", Result: "OK"}); return append([]byte(xml.Header), b...) }

type deviceStatusResponse struct { XMLName xml.Name `xml:"Response"`; CmdType string `xml:"CmdType"`; SN int `xml:"SN"`; DeviceID string `xml:"DeviceID"`; Result string `xml:"Result"`; Online string `xml:"Online"`; Status string `xml:"Status"` }
func BuildDeviceStatusXML(serverID string, q Query) []byte { responseID := q.DeviceID; if responseID == "" { responseID = serverID }; b, _ := xml.Marshal(deviceStatusResponse{CmdType: "DeviceStatus", SN: q.SN, DeviceID: responseID, Result: "OK", Online: "ONLINE", Status: "OK"}); return append([]byte(xml.Header), b...) }
