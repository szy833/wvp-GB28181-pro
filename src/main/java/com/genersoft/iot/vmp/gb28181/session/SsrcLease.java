package com.genersoft.iot.vmp.gb28181.session;

import lombok.Getter;

@Getter
public final class SsrcLease {

    private final String mediaServerId;
    private final String ssrc;
    private final boolean owned;
    private final String leaseId;

    public SsrcLease(String mediaServerId, String ssrc, boolean owned, String leaseId) {
        this.mediaServerId = mediaServerId;
        this.ssrc = ssrc;
        this.owned = owned;
        this.leaseId = leaseId;
    }
}
