package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.media.event.media.MediaDepartureEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNull;

class MediaDepartureListenerTest {

    @Test
    void inviteServiceDoesNotIndependentlyConsumeMediaDeparture() throws Exception {
        for (Method method : InviteStreamServiceImpl.class.getDeclaredMethods()) {
            if (java.util.Arrays.asList(method.getParameterTypes()).contains(MediaDepartureEvent.class))
                assertNull(method.getAnnotation(EventListener.class), "media departure has one lifecycle coordinator");
        }
    }
}
