package com.genersoft.iot.vmp.media.event.hook;

/** Exact owner token for one hook callback registration. */
public final class HookSubscriptionHandle {
    private final String key;
    private final HookSubscribe.Event event;
    private final Hook hook;

    public HookSubscriptionHandle(String key, HookSubscribe.Event event) {
        this(key, event, null);
    }

    public HookSubscriptionHandle(String key, HookSubscribe.Event event, Hook hook) {
        this.key = key;
        this.event = event;
        this.hook = hook;
    }

    public String getKey() {
        return key;
    }

    public HookSubscribe.Event getEvent() {
        return event;
    }

    public Hook getHook() {
        return hook;
    }
}
