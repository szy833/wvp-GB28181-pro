package com.genersoft.iot.vmp.media.event.hook;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.genersoft.iot.vmp.media.event.media.MediaArrivalEvent;
import com.genersoft.iot.vmp.media.bean.MediaServer;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HookSubscribeTest {

    @Test
    void mediaServerIsPartOfHookIdentity() {
        Hook first = Hook.getInstance(HookType.on_media_arrival, "rtp", "stream", "media-1");
        Hook second = Hook.getInstance(HookType.on_media_arrival, "rtp", "stream", "media-2");
        assertNotEquals(first, second);
        assertNotEquals(first.toString(), second.toString());
        assertTrue(first.toString().contains("media-1"));
    }

    @Test
    void oldCallbackCannotRemoveNewCallback() {
        HookSubscribe subscribe = new HookSubscribe();
        Hook hook = Hook.getInstance(HookType.on_media_arrival, "rtp", "stream", "media-1");
        HookSubscribe.Event oldEvent = data -> {};
        HookSubscribe.Event newEvent = data -> {};
        subscribe.addSubscribe(hook, oldEvent);
        @SuppressWarnings("unchecked")
        Map<String, HookSubscribe.Event> callbacks = (Map<String, HookSubscribe.Event>)
                ReflectionTestUtils.getField(subscribe, "allSubscribes");
        callbacks.put(hook.toString(), newEvent);

        assertFalse(subscribe.removeSubscribe(hook, oldEvent));
        assertTrue(subscribe.removeSubscribe(hook, newEvent));
    }

    @Test
    void mediaServerIdIsNotReplacedByWvpServerId() {
        HookSubscribe subscribe = new HookSubscribe();
        AtomicInteger calls = new AtomicInteger();
        Hook hook = Hook.getInstance(HookType.on_media_arrival, "rtp", "stream", "wvp-1");
        subscribe.addSubscribe(hook, data -> calls.incrementAndGet());

        MediaArrivalEvent event = new MediaArrivalEvent(this);
        event.setApp("rtp");
        event.setStream("stream");
        event.setSchema("rtsp");
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("media-1");
        mediaServer.setServerId("wvp-1");
        event.setMediaServer(mediaServer);

        subscribe.onApplicationEvent(event);

        assertEquals(0, calls.get());
    }

    @Test
    void globalSubscriptionMustBeExplicit() {
        Hook global = Hook.getGlobalInstance(HookType.on_media_arrival, "rtp", "stream");
        Hook node = Hook.getInstance(HookType.on_media_arrival, "rtp", "stream", "media-1");

        assertNotEquals(global.toString(), node.toString());
        assertTrue(global.toString().contains("GLOBAL"));
    }
}
