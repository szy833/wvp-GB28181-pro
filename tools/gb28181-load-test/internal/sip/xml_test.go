package sip

import (
	"strings"
	"testing"
)

func TestCatalogAndDeviceInfoXML(t *testing.T) { q, err := ParseQuery([]byte("<Query><CmdType>Catalog</CmdType><SN>7</SN><DeviceID>server</DeviceID></Query>")); if err != nil { t.Fatal(err) }; catalog := string(BuildCatalogXML("server", q, CatalogItem{DeviceID: "channel", Name: "cam&1", Status: "ON"})); if !strings.Contains(catalog, "<CmdType>Catalog</CmdType>") || !strings.Contains(catalog, "cam&amp;1") { t.Fatal(catalog) }; info := string(BuildDeviceInfoXML("server", q, "cam", "vendor", "model")); if !strings.Contains(info, "<CmdType>DeviceInfo</CmdType>") { t.Fatal(info) } }
func TestKeepaliveXMLEscapesDevice(t *testing.T) { b := BuildKeepaliveXML("a&b", "vendor"); s := string(b); if !strings.Contains(s, "a&amp;b") || !strings.Contains(s, "<CmdType>Keepalive</CmdType>") { t.Fatal(s) } }

func TestParseQueryAcceptsGB2312Declaration(t *testing.T) {
	body := []byte(`<?xml version="1.0" encoding="GB2312"?>
<Query><CmdType>Catalog</CmdType><SN>754866</SN><DeviceID>37020000001110000001</DeviceID></Query>`)
	q, err := ParseQuery(body)
	if err != nil {
		t.Fatal(err)
	}
	if q.CmdType != "Catalog" || q.SN != 754866 || q.DeviceID != "37020000001110000001" {
		t.Fatalf("unexpected query: %+v", q)
	}
}
