package com.genersoft.iot.vmp.gb28181.service.impl;

import org.junit.jupiter.api.Test;

class InviteCleanupRegressionTest {
    @Test
    void inviteDeletionAndIdempotentStopRegressions() throws Exception {
        InviteCleanupRegressionVerifier.main(new String[0]);
    }
}
