package com.genersoft.iot.vmp.gb28181.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayServiceImplStopTest {
    @Test
    void cleanupJournalAndResourceFaultRegressions() throws Exception {
        InviteJournalRegressionVerifier.main(new String[0]);
    }

    @Test
    void staleOwnerCannotClaimReplacement() throws Exception {
        var fixture = new InviteJournalRegressionVerifier.Fixture();
        var replacement = InviteJournalRegressionVerifier.invite("replacement");
        fixture.journal.record = replacement;
        assertFalse(fixture.service.stopIfOwner(fixture.expected));
        assertSame(replacement, fixture.journal.record);
        assertEquals(0, fixture.closes);
        assertEquals(0, fixture.stateResets);
    }

    @Test
    void alreadyLeasedTaskDoesNotStartAnotherCleanup() throws Exception {
        var fixture = new InviteJournalRegressionVerifier.Fixture();
        fixture.journal.prepare(fixture.expected, true, false);
        fixture.journal.claim("job-1");
        assertFalse(fixture.service.stopIfOwner(fixture.expected));
        assertEquals(0, fixture.closes);
    }

    @Test
    void inflightCreateCannotReleaseRtpEvenAfterInviteDeadline() throws Exception {
        var fixture = new InviteJournalRegressionVerifier.Fixture();
        fixture.expected.setRtpOpening(true);
        fixture.expected.setCreateTime(1L);
        fixture.expected.setExpirationTime(1L);
        org.mockito.Mockito.when(fixture.rtp.isRtpResourceOpening(fixture.expected.getSsrcInfo())).thenReturn(true);
        assertFalse(fixture.service.stopAfterDeviceBye(fixture.expected));
        assertEquals(0, fixture.closes);
        org.mockito.Mockito.when(fixture.rtp.isRtpResourceOpening(fixture.expected.getSsrcInfo())).thenReturn(false);
        assertEquals(1, fixture.service.retryPendingInviteCleanups(10));
    }
}
