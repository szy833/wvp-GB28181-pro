package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayServiceImplInviteCallbackTest {
    @Test void acceptedMediaCannotActivateBeforeSipSessionConfirmation() {
        Fixture f = new Fixture();
        InviteInfo invite = InviteInfo.prepare("device", 1, InviteSessionType.PLAY, "PLAY:1", "media", "UDP", 60000);
        invite.setLifecycle(InviteLifecycle.WAITING_MEDIA);
        invite.setSipAccepted(true);
        invite.setMediaArrived(true);
        invite.setStreamInfo(new StreamInfo());
        f.owner.set(invite);

        ReflectionTestUtils.invokeMethod(f.play, "tryActivateInvite", invite);

        verifyNoInteractions(f.projection);
        verify(f.repository, never()).patch(anyString(), anyString(), any());
        verify(f.invites, never()).complete(any(), anyInt(), anyString(), any());
    }

    @Test void acceptedMediaCannotActivateBeforeSipAnswerProcessingFinishes() {
        Fixture f = new Fixture();
        InviteInfo invite = InviteInfo.prepare("device", 1, InviteSessionType.PLAY, "PLAY:1", "media", "UDP", 60000);
        invite.setLifecycle(InviteLifecycle.WAITING_MEDIA);
        invite.setSipAccepted(true);
        invite.setSipSessionConfirmed(true);
        invite.setMediaArrived(true);
        invite.setStreamInfo(new StreamInfo());
        f.owner.set(invite);

        ReflectionTestUtils.invokeMethod(f.play, "tryActivateInvite", invite);

        verifyNoInteractions(f.projection);
        verify(f.repository, never()).patch(anyString(), anyString(), any());
        verify(f.invites, never()).complete(any(), anyInt(), anyString(), any());
    }

    @Test void unconfirmedCreateNeverOpensRtpOrSendsInvite() {
        Fixture f=new Fixture();
        when(f.invites.createInvite(any())).thenThrow(new IllegalStateException("Redis unavailable"));
        when(f.invites.getById(anyString())).thenThrow(new IllegalStateException("Redis still unavailable"));
        AtomicInteger failures=new AtomicInteger();
        assertDoesNotThrow(()->f.play.play(f.device,f.channel,(code,msg,data)->failures.incrementAndGet()));
        assertEquals(1,failures.get());
        verifyNoInteractions(f.rtp,f.commander);
        verify(f.invites, never()).getById(anyString());
    }
    @Test void hundredDuplicateStartsHaveOneResourceAndSipOwner() throws Exception {
        Fixture f=new Fixture();
        ExecutorService pool=Executors.newFixedThreadPool(16);
        try {
            List<Callable<Void>> tasks=new ArrayList<>();
            for(int i=0;i<100;i++)tasks.add(()->{f.play.play(f.device,f.channel,(code,msg,data)->{});return null;});
            for(var future:pool.invokeAll(tasks))future.get();
        } finally {pool.shutdownNow();}
        verify(f.rtp,times(1)).openGbRTPServerForInvite(any(),any(),any(),any(),any(),anyBoolean(),any());
        verify(f.commander,times(1)).playStreamCmd(any(),any(),any(),any(),any(),any(),anyLong());
        verify(f.invites,times(100)).await(eq(f.owner.get()),any());
    }
    static final class Fixture {
        final PlayServiceImpl play=new PlayServiceImpl();
        final IInviteStreamService invites=mock(IInviteStreamService.class);
        final InviteRepository repository=mock(InviteRepository.class);
        final IReceiveRtpServerService rtp=mock(IReceiveRtpServerService.class);
        final ISIPCommander commander=mock(ISIPCommander.class);
        final InvitePlayProjectionStore projection=mock(InvitePlayProjectionStore.class);
        final AtomicReference<InviteInfo> owner=new AtomicReference<>();
        final Device device=new Device();final DeviceChannel channel=new DeviceChannel();
        Fixture() {
            device.setDeviceId("device");device.setServerId("server");device.setMediaServerId("media");device.setStreamMode("UDP");
            channel.setId(1);channel.setDeviceId("channel");
            var settings=new UserSetting();settings.setServerId("server");settings.setPlayTimeout(10000);settings.setRecordSip(false);
            var media=mock(IMediaServerService.class);var node=new MediaServer();node.setId("media");when(media.getOne("media")).thenReturn(node);
            when(invites.createInvite(any())).thenAnswer(i->{
                InviteInfo invite=i.getArgument(0);
                return new InviteWriteResult(owner.compareAndSet(null,invite)?InviteWriteResult.Code.APPLIED:InviteWriteResult.Code.OWNER_CHANGED,invite);
            });
            when(repository.owner(anyString())).thenAnswer(i->owner.get());
            when(invites.getById(anyString())).thenAnswer(i->owner.get());
            when(repository.patch(anyString(),anyString(),any())).thenAnswer(i->new InviteWriteResult(InviteWriteResult.Code.APPLIED,owner.get()));
            when(projection.allocateEpoch(1)).thenReturn(1L);
            when(rtp.openGbRTPServerForInvite(any(),any(),any(),any(),any(),anyBoolean(),any())).thenAnswer(i->{
                InviteInfo invite=i.getArgument(0);
                var resource=new SSRCInfo(41000,"0200000001","rtp",invite.getStream());resource.setInviteId(invite.getInviteId());return resource;
            });
            ReflectionTestUtils.setField(play,"inviteStreamService",invites);ReflectionTestUtils.setField(play,"inviteRepository",repository);
            ReflectionTestUtils.setField(play,"playProjection",projection);ReflectionTestUtils.setField(play,"receiveRtpServerService",rtp);
            ReflectionTestUtils.setField(play,"cmder",commander);ReflectionTestUtils.setField(play,"userSetting",settings);ReflectionTestUtils.setField(play,"mediaServerService",media);
        }
    }
}
