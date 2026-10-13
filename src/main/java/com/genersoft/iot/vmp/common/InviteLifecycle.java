package com.genersoft.iot.vmp.common;

public enum InviteLifecycle {
    PREPARING, WAITING_MEDIA, ACTIVATING, ACTIVE, STOPPING, CLOSED;

    public boolean closing() { return this == STOPPING || this == CLOSED; }
}
