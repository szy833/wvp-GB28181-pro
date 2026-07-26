package com.genersoft.iot.vmp.media.event.hook;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

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
}
