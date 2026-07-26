package com.genersoft.iot.vmp.media.zlm;

import com.genersoft.iot.vmp.media.bean.MediaServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ZLMServerFactoryTest {

    @Test
    void nullRtpInfoUsesFailureSentinel() {
        ZLMRESTfulUtils utils = mock(ZLMRESTfulUtils.class);
        when(utils.getRtpInfo(any(MediaServer.class), anyString())).thenReturn(null);
        ZLMServerFactory factory = new ZLMServerFactory();
        ReflectionTestUtils.setField(factory, "zlmresTfulUtils", utils);

        assertEquals(-1, factory.createRTPServer(mock(MediaServer.class), "rtp", "stream",
                0L, null, false, false, false, 0));
    }
}
