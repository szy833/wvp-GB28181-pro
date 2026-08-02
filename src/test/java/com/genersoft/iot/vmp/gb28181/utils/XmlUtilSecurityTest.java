package com.genersoft.iot.vmp.gb28181.utils;

import org.dom4j.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlUtilSecurityTest {

    @Test
    void doesNotExpandInlineExternalEntityDeclarations() {
        String xml = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE root [<!ENTITY xxe \"expanded\">]>"
                + "<root>&xxe;</root>";

        Element root = XmlUtil.parseXml(xml);

        assertTrue(root == null || !"expanded".equals(root.getText()),
                "XML parser must not expand entity declarations");
    }

    @Test
    void rejectsOversizedXmlPayload() {
        String xml = "<root>" + "x".repeat(2 * 1024 * 1024) + "</root>";

        assertTrue(XmlUtil.parseXml(xml) == null,
                "oversized SIP XML must be rejected before DOM construction");
    }
}
