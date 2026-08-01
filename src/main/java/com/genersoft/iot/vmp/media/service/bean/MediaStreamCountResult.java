package com.genersoft.iot.vmp.media.service.bean;

import java.util.Objects;

/**
 * Result of querying active media streams on a media node.
 *
 * <p>A successful zero count is intentionally distinct from a failed query so
 * callers do not reset a previously known load to zero when a node is down.</p>
 */
public final class MediaStreamCountResult {

    private final boolean success;
    private final int count;
    private final String reason;

    private MediaStreamCountResult(boolean success, int count, String reason) {
        this.success = success;
        this.count = count;
        this.reason = reason;
    }

    public static MediaStreamCountResult success(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        return new MediaStreamCountResult(true, count, null);
    }

    public static MediaStreamCountResult failure(String reason) {
        return new MediaStreamCountResult(false, 0,
                Objects.requireNonNullElse(reason, "unknown error"));
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isFailure() {
        return !success;
    }

    public int getCount() {
        return count;
    }

    public String getReason() {
        return reason;
    }
}
