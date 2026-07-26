package com.genersoft.iot.vmp.service.bean;

import lombok.Getter;

@Getter
public class RtpServerOpenResult {

    private final int port;
    private final String resourceId;
    private final String businessStreamId;
    private final String zlmStreamId;
    private final RtpResourceContext context;

    public RtpServerOpenResult(int port, String resourceId, String businessStreamId, String zlmStreamId) {
        this(port, resourceId, businessStreamId, zlmStreamId, null);
    }

    public RtpServerOpenResult(int port, String resourceId, String businessStreamId, String zlmStreamId,
                               RtpResourceContext context) {
        this.port = port;
        this.resourceId = resourceId;
        this.businessStreamId = businessStreamId;
        this.zlmStreamId = zlmStreamId;
        this.context = context;
    }

    public boolean isSuccess() {
        return port > 0;
    }

    public boolean isFailure() {
        return !isSuccess();
    }

    public RtpResourceContext getContext() {
        return context;
    }
}
