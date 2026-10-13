package com.genersoft.iot.vmp.common;

/** Only DELETED grants the caller ownership of a new resource-cleanup operation. */
public enum InviteInfoDeleteResult {
    DELETED, ABSENT, OWNER_CHANGED, RETRYABLE_FAILURE
}
