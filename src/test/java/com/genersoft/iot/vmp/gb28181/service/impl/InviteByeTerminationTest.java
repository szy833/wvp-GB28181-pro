package com.genersoft.iot.vmp.gb28181.service.impl;

import org.junit.jupiter.api.Test;
import com.genersoft.iot.vmp.common.InviteLifecycle;
import javax.sip.SipException;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.common.InviteWriteResult;
import com.genersoft.iot.vmp.gb28181.bean.Platform;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommanderForPlatform;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InviteByeTerminationTest {
    @Test void immediateCleanupRetryDoesNotResendBye() throws Exception {
        var fixture = new InviteJournalRegressionVerifier.Fixture();
        assertFalse(fixture.service.stopIfOwner(fixture.expected));
        assertEquals(0, fixture.service.retryPendingInviteCleanups(10));
        verify(fixture.commander, times(1)).streamByeCmd(any(), anyString(), any(), any());
    }

    @Test void platformByeAlsoRequiresRepositoryPermissionAndMatchingDialog() throws Exception {
        var fixture = new InviteJournalRegressionVerifier.Fixture();
        var invite = fixture.expected;
        invite.setType(InviteSessionType.BROADCAST); invite.setLifecycle(InviteLifecycle.STOPPING);
        invite.setSipPlatform(new Platform()); invite.setRemoteChannelId("channel");
        var commander = mock(ISIPCommanderForPlatform.class);
        ReflectionTestUtils.setField(fixture.service, "sipCommanderFroPlatform", commander);
        when(fixture.repository.beginByeAttempt(invite.getInviteId(), "lease"))
                .thenReturn(new InviteWriteResult(InviteWriteResult.Code.STATE_CONFLICT, invite));
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(fixture.service, "safeSendPlatformBye", invite, "lease"));
        verifyNoInteractions(commander);
        when(fixture.repository.beginByeAttempt(invite.getInviteId(), "lease"))
                .thenReturn(new InviteWriteResult(InviteWriteResult.Code.APPLIED, invite));
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(fixture.service, "safeSendPlatformBye", invite, "lease"));
        verify(commander).streamByeCmd(eq(invite.getSipPlatform()), any(), eq(invite.getSipDialog()), any());
        reset(commander, fixture.repository);
        var wrongDialog = new com.genersoft.iot.vmp.gb28181.bean.SipTransactionInfo();
        wrongDialog.setCallId("different-call"); invite.setSipDialog(wrongDialog);
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(fixture.service, "safeSendPlatformBye", invite, "lease"));
        verifyNoInteractions(commander, fixture.repository);
    }

    @Test
    void unreachablePeerDoesNotHoldLocalSessionForever() throws Exception {
        var fixture = new InviteJournalRegressionVerifier.Fixture();
        assertFalse(fixture.service.stopIfOwner(fixture.expected));
        fixture.expected.setByeSentAt(System.currentTimeMillis() - 60_000);
        fixture.expected.setByeFirstSentAt(System.currentTimeMillis() - 60_000);
        assertEquals(1, fixture.service.retryPendingInviteCleanups(10),
                "An expired BYE wait must not permanently block an already closed local stream");
        assertFalse(fixture.expected.isByeAcknowledged(), "Local termination must not invent a peer ACK");
        assertEquals("BYE_WAIT_EXPIRED", fixture.expected.getByeLocalTerminationReason());
        assertNotNull(fixture.expected.getByeTerminatedLocallyAt());
    }

    @Test
    void retriesDoNotTerminateBeforeWaitWindow() throws Exception {
        var fixture = new InviteJournalRegressionVerifier.Fixture();
        assertFalse(fixture.service.stopIfOwner(fixture.expected));
        Long first = fixture.expected.getByeFirstSentAt();
        fixture.expected.setByeSentAt(System.currentTimeMillis() - 11_000);
        assertEquals(0, fixture.service.retryPendingInviteCleanups(10));
        assertEquals(first, fixture.expected.getByeFirstSentAt());
        assertNull(fixture.expected.getByeTerminatedLocallyAt());
        assertEquals(InviteLifecycle.STOPPING, fixture.expected.getLifecycle());
    }

    @Test
    void failedTransmissionCanStillEndLocallyAfterResourcesAreClosed() throws Exception {
        var fixture = new InviteJournalRegressionVerifier.Fixture();
        doThrow(new SipException("offline peer")).when(fixture.commander)
                .streamByeCmd(any(), anyString(), any(), any());
        assertFalse(fixture.service.stopIfOwner(fixture.expected));
        assertNotNull(fixture.expected.getByeFirstSentAt());
        fixture.expected.setByeFirstSentAt(System.currentTimeMillis() - 60_000);
        assertEquals(1, fixture.service.retryPendingInviteCleanups(10));
        assertFalse(fixture.expected.isByeAcknowledged());
    }

    @Test
    void unknownMediaBlocksLocalTerminationUntilItCanBeVerified() throws Exception {
        var fixture = new InviteJournalRegressionVerifier.Fixture();
        when(fixture.media.probeStream(any(), anyString(), anyString())).thenReturn(null);
        assertFalse(fixture.service.stopIfOwner(fixture.expected));
        fixture.expected.setByeFirstSentAt(System.currentTimeMillis() - 60_000);
        assertEquals(0, fixture.service.retryPendingInviteCleanups(10));
        assertNull(fixture.expected.getByeTerminatedLocallyAt());
        when(fixture.media.probeStream(any(), anyString(), anyString())).thenReturn(false);
        fixture.service.retryPendingInviteCleanups(10);
        assertEquals(1, fixture.service.retryPendingInviteCleanups(10));
    }

    @Test
    void sqlFailureBlocksLocalTermination() throws Exception {
        var fixture = new InviteJournalRegressionVerifier.Fixture();
        when(fixture.projection.close(any())).thenReturn(false);
        fixture.service.stopIfOwner(fixture.expected);
        fixture.expected.setByeFirstSentAt(System.currentTimeMillis() - 60_000);
        assertEquals(0, fixture.service.retryPendingInviteCleanups(10));
        assertNull(fixture.expected.getByeTerminatedLocallyAt());
        when(fixture.projection.close(any())).thenReturn(true);
        fixture.service.retryPendingInviteCleanups(10);
        assertEquals(1, fixture.service.retryPendingInviteCleanups(10));
    }

    @Test
    void noMatchingDialogDoesNotInventAStartedByeWait() throws Exception {
        var fixture = new InviteJournalRegressionVerifier.Fixture();
        fixture.expected.setSipDialog(null);
        assertFalse(fixture.service.stopIfOwner(fixture.expected));
        assertEquals(0, fixture.service.retryPendingInviteCleanups(10));
        assertNull(fixture.expected.getByeFirstSentAt());
        assertNull(fixture.expected.getByeTerminatedLocallyAt());
        verifyNoInteractions(fixture.commander);
    }
}
