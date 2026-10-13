package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.service.IPlayService;
import com.genersoft.iot.vmp.gb28181.bean.Platform;
import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.service.bean.InviteErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformInviteActivationTest {
    @Test void broadcastStopResolvesCoordinatorAtRuntimeAndKeepsByeSemantics() {
        PlatformServiceImpl service = new PlatformServiceImpl();
        IInviteStreamService invites = mock(IInviteStreamService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<IPlayService> provider = mock(ObjectProvider.class);
        IPlayService play = mock(IPlayService.class);
        when(provider.getObject()).thenReturn(play);
        ReflectionTestUtils.setField(service, "playServiceProvider", provider);
        ReflectionTestUtils.setField(service, "inviteStreamService", invites);
        InviteInfo invite = broadcast();
        invite.setPlatformId("platform");
        Platform platform = new Platform();
        platform.setServerGBId("platform");
        MediaServer node = new MediaServer();
        node.setId("node");
        when(invites.getInviteInfoByStreamAndMediaServer("node", invite.getStream())).thenReturn(invite);

        service.stopBroadcast(platform, new CommonGBChannel(), "rtp", invite.getStream(), true, node);
        verify(play).stopIfOwner(invite);
        verify(play, never()).stopAfterDeviceBye(any());
        service.stopBroadcast(platform, new CommonGBChannel(), "rtp", invite.getStream(), false, node);
        verify(play).stopAfterDeviceBye(invite);
        verify(provider, times(2)).getObject();
    }

    @Test void foreignBroadcastDoesNotResolveCoordinator() {
        PlatformServiceImpl service = new PlatformServiceImpl();
        IInviteStreamService invites = mock(IInviteStreamService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<IPlayService> provider = mock(ObjectProvider.class);
        ReflectionTestUtils.setField(service, "playServiceProvider", provider);
        ReflectionTestUtils.setField(service, "inviteStreamService", invites);
        InviteInfo invite = broadcast();
        invite.setPlatformId("another-platform");
        Platform platform = new Platform();
        platform.setServerGBId("platform");
        MediaServer node = new MediaServer();
        node.setId("node");
        when(invites.getInviteInfoByStreamAndMediaServer("node", invite.getStream())).thenReturn(invite);

        service.stopBroadcast(platform, new CommonGBChannel(), "rtp", invite.getStream(), true, node);
        verifyNoInteractions(provider);
    }

    @Test void broadcastMustPassActivatingAfterAllFactsAreConfirmed() {
        PlatformServiceImpl service = new PlatformServiceImpl();
        InviteRepository repository = mock(InviteRepository.class);
        IInviteStreamService invites = mock(IInviteStreamService.class);
        InviteInfo invite = broadcast();
        invite.setSipSessionConfirmed(true);
        invite.setSipReady(true);
        when(invites.getById(invite.getInviteId())).thenReturn(invite);
        when(repository.patch(eq(invite.getInviteId()), eq("activating"), any())).thenAnswer(call -> {
            Consumer<InviteInfo> mutation = call.getArgument(2);
            mutation.accept(invite);
            return new InviteWriteResult(InviteWriteResult.Code.APPLIED, invite);
        });
        when(invites.activate(invite)).thenAnswer(call -> {
            assertEquals(InviteLifecycle.ACTIVATING, invite.getLifecycle());
            invite.setLifecycle(InviteLifecycle.ACTIVE);
            return invite;
        });
        ReflectionTestUtils.setField(service, "inviteStreamService", invites);
        ReflectionTestUtils.setField(service, "inviteRepository", repository);

        ReflectionTestUtils.invokeMethod(service, "activateBroadcast", invite);

        verify(invites).activate(invite);
        verify(invites).complete(invite, 0, InviteErrorCode.SUCCESS.getMsg(), invite.getStreamInfo());
    }

    @Test void earlyMediaCannotActivateBroadcastBeforeSipIsReady() {
        PlatformServiceImpl service = new PlatformServiceImpl();
        IInviteStreamService invites = mock(IInviteStreamService.class);
        InviteRepository repository = mock(InviteRepository.class);
        InviteInfo invite = broadcast();
        when(invites.getById(invite.getInviteId())).thenReturn(invite);
        ReflectionTestUtils.setField(service, "inviteStreamService", invites);
        ReflectionTestUtils.setField(service, "inviteRepository", repository);

        ReflectionTestUtils.invokeMethod(service, "activateBroadcast", invite);
        invite.setSipSessionConfirmed(true);
        ReflectionTestUtils.invokeMethod(service, "activateBroadcast", invite);

        verifyNoInteractions(repository);
        verify(invites, never()).activate(any());
        verify(invites, never()).complete(any(), anyInt(), anyString(), any());
    }

    private static InviteInfo broadcast() {
        InviteInfo invite = InviteInfo.prepare(null, 1, InviteSessionType.BROADCAST, "BROADCAST:p:1", "node", "UDP", 60000);
        invite.setLifecycle(InviteLifecycle.WAITING_MEDIA);
        invite.setSipAccepted(true);
        invite.setMediaArrived(true);
        StreamInfo stream = new StreamInfo();
        stream.setStream(invite.getStream());
        invite.setStreamInfo(stream);
        return invite;
    }
}
