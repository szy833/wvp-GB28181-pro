package com.genersoft.iot.vmp.gb28181.event;

import com.genersoft.iot.vmp.gb28181.event.sip.MessageEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class MessageSubscribeTest {

    @Test
    void executeReturnsWhenHeadEventHasNotExpired() {
        MessageSubscribe subscribe = new MessageSubscribe();
        subscribe.addSubscribe(MessageEvent.getInstance("cmd", "pending", "device", 2000L, null));

        assertTimeout(Duration.ofMillis(200), subscribe::execute);
        assertEquals(1, subscribe.size());
    }
}
