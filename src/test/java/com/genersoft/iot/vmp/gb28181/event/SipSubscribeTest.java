package com.genersoft.iot.vmp.gb28181.event;

import com.genersoft.iot.vmp.gb28181.event.sip.SipEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class SipSubscribeTest {

    @Test
    void executeReturnsWhenHeadEventHasNotExpired() {
        SipSubscribe subscribe = new SipSubscribe();
        subscribe.addSubscribe("pending", SipEvent.getInstance("pending", null, null,
                TimeUnit.SECONDS.toMillis(2)));

        assertTimeout(Duration.ofMillis(200), subscribe::execute);
        assertEquals(1, subscribe.size());
    }
}
