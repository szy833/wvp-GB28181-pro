package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.bean.SendRtpInfo;
import com.genersoft.iot.vmp.gb28181.controller.bean.AudioBroadcastEvent;
import com.genersoft.iot.vmp.gb28181.event.SipSubscribe;
import com.genersoft.iot.vmp.gb28181.service.IDeviceService;
import com.genersoft.iot.vmp.gb28181.session.SSRCFactory;
import com.genersoft.iot.vmp.gb28181.session.SendSsrcFactory;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import com.genersoft.iot.vmp.gb28181.session.SsrcLease;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.service.ISendRtpServerService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayServiceImplTalkTimeoutTest {

    @Test
    void talkTimeoutRunsFailureCallbackOnce() throws Exception {
        PlayServiceImpl service = new PlayServiceImpl();
        DynamicTask dynamicTask = mock(DynamicTask.class);
        SSRCFactory ssrcFactory = mock(SSRCFactory.class);
        SendSsrcFactory sendSsrcFactory = mock(SendSsrcFactory.class);
        ISendRtpServerService sendRtp = mock(ISendRtpServerService.class);
        IMediaServerService media = mock(IMediaServerService.class);
        IReceiveRtpServerService receiveRtp = mock(IReceiveRtpServerService.class);
        ISIPCommander commander = mock(ISIPCommander.class);
        SipInviteSessionManager sessions = mock(SipInviteSessionManager.class);
        UserSetting settings = mock(UserSetting.class);
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("media-1");
        Device device = new Device();
        device.setDeviceId("device-1");
        device.setStreamMode("UDP");
        DeviceChannel channel = new DeviceChannel();
        channel.setId(1);
        channel.setDeviceId("channel-1");
        SendRtpInfo sendRtpInfo = new SendRtpInfo();
        sendRtpInfo.setApp("talk");
        sendRtpInfo.setStream("device-1_channel-1");
        sendRtpInfo.setSsrc("00000001");
        AtomicReference<Runnable> timeoutTask = new AtomicReference<>();
        AtomicInteger failureCount = new AtomicInteger();

        SsrcLease lease = new SsrcLease("media-1", "00000001", true, "lease-1");
        when(ssrcFactory.allocatePlayLease(mediaServer)).thenReturn(lease);
        when(sendSsrcFactory.getSendSsrc("0")).thenReturn("00000002");
        when(sendRtp.createSendRtpInfo(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(sendRtpInfo);
        when(media.startSendRtpPassive(eq(mediaServer), eq(sendRtpInfo), anyInt())).thenReturn(1234);
        when(settings.getPlayTimeout()).thenReturn(10);
        doAnswer(invocation -> {
            timeoutTask.set(invocation.getArgument(1));
            return null;
        }).when(dynamicTask).startDelay(anyString(), any(Runnable.class), anyInt());

        ReflectionTestUtils.setField(service, "dynamicTask", dynamicTask);
        ReflectionTestUtils.setField(service, "ssrcFactory", ssrcFactory);
        ReflectionTestUtils.setField(service, "sendSsrcFactory", sendSsrcFactory);
        ReflectionTestUtils.setField(service, "sendRtpServerService", sendRtp);
        ReflectionTestUtils.setField(service, "mediaServerService", media);
        ReflectionTestUtils.setField(service, "receiveRtpServerService", receiveRtp);
        ReflectionTestUtils.setField(service, "cmder", commander);
        ReflectionTestUtils.setField(service, "sessionManager", sessions);
        ReflectionTestUtils.setField(service, "userSetting", settings);

        ReflectionTestUtils.invokeMethod(service, "talk", mediaServer, device, channel,
                "device-1_channel-1", (SipSubscribe.Event) event -> { },
                (Runnable) failureCount::incrementAndGet, (AudioBroadcastEvent) message -> { });

        assertNotNull(timeoutTask.get());
        timeoutTask.get().run();

        org.junit.jupiter.api.Assertions.assertEquals(1, failureCount.get());
        verify(commander).streamByeCmd(eq(device), eq(channel.getDeviceId()), isNull(), isNull(), anyString(), isNull());
        verify(ssrcFactory).release(lease);
    }

    @Test
    void talkSetupFailure_releasesAllocatedLease() throws Exception {
        PlayServiceImpl service = new PlayServiceImpl();
        SSRCFactory ssrcFactory = mock(SSRCFactory.class);
        SendSsrcFactory sendSsrcFactory = mock(SendSsrcFactory.class);
        ISendRtpServerService sendRtp = mock(ISendRtpServerService.class);
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("media-1");
        Device device = new Device();
        device.setDeviceId("device-1");
        DeviceChannel channel = new DeviceChannel();
        channel.setId(1);
        channel.setDeviceId("channel-1");
        SsrcLease lease = new SsrcLease("media-1", "00000001", true, "lease-1");

        when(ssrcFactory.allocatePlayLease(mediaServer)).thenReturn(lease);
        when(sendSsrcFactory.getSendSsrc("0")).thenReturn("00000002");
        when(sendRtp.createSendRtpInfo(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(null);

        ReflectionTestUtils.setField(service, "ssrcFactory", ssrcFactory);
        ReflectionTestUtils.setField(service, "sendSsrcFactory", sendSsrcFactory);
        ReflectionTestUtils.setField(service, "sendRtpServerService", sendRtp);

        ReflectionTestUtils.invokeMethod(service, "talk", mediaServer, device, channel,
                "device-1_channel-1", (SipSubscribe.Event) event -> { },
                (Runnable) () -> { }, (AudioBroadcastEvent) message -> { });

        verify(ssrcFactory).release(lease);
    }

    @Test
    void talkSetupRuntimeFailure_releasesAllocatedLease() throws Exception {
        PlayServiceImpl service = new PlayServiceImpl();
        SSRCFactory ssrcFactory = mock(SSRCFactory.class);
        SendSsrcFactory sendSsrcFactory = mock(SendSsrcFactory.class);
        ISendRtpServerService sendRtp = mock(ISendRtpServerService.class);
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("media-1");
        Device device = new Device();
        device.setDeviceId("device-1");
        DeviceChannel channel = new DeviceChannel();
        channel.setId(1);
        channel.setDeviceId("channel-1");
        SsrcLease lease = new SsrcLease("media-1", "00000001", true, "lease-1");

        when(ssrcFactory.allocatePlayLease(mediaServer)).thenReturn(lease);
        when(sendSsrcFactory.getSendSsrc("0")).thenReturn("00000002");
        when(sendRtp.createSendRtpInfo(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenThrow(new IllegalStateException("send port registry unavailable"));

        ReflectionTestUtils.setField(service, "ssrcFactory", ssrcFactory);
        ReflectionTestUtils.setField(service, "sendSsrcFactory", sendSsrcFactory);
        ReflectionTestUtils.setField(service, "sendRtpServerService", sendRtp);

        ReflectionTestUtils.invokeMethod(service, "talk", mediaServer, device, channel,
                "device-1_channel-1", (SipSubscribe.Event) event -> { },
                (Runnable) () -> { }, (AudioBroadcastEvent) message -> { });

        verify(ssrcFactory).release(lease);
    }

    @Test
    void talkDuplicateDoesNotRetainNewSendRtpInfo() throws Exception {
        PlayServiceImpl service = new PlayServiceImpl();
        SSRCFactory ssrcFactory = mock(SSRCFactory.class);
        SendSsrcFactory sendSsrcFactory = mock(SendSsrcFactory.class);
        ISendRtpServerService sendRtp = mock(ISendRtpServerService.class);
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("media-1");
        Device device = new Device();
        device.setDeviceId("device-1");
        DeviceChannel channel = new DeviceChannel();
        channel.setId(1);
        channel.setDeviceId("channel-1");
        SsrcLease lease = new SsrcLease("media-1", "00000001", true, "lease-new");
        SendRtpInfo sendRtpInfo = new SendRtpInfo();
        sendRtpInfo.setStream("device-1_channel-1");
        sendRtpInfo.setSsrc("00000002");

        when(ssrcFactory.allocatePlayLease(mediaServer)).thenReturn(lease);
        when(sendSsrcFactory.getSendSsrc("0")).thenReturn("00000002");
        when(sendRtp.createSendRtpInfo(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(sendRtpInfo);

        ReflectionTestUtils.setField(service, "ssrcFactory", ssrcFactory);
        ReflectionTestUtils.setField(service, "sendSsrcFactory", sendSsrcFactory);
        ReflectionTestUtils.setField(service, "sendRtpServerService", sendRtp);

        @SuppressWarnings("rawtypes")
        java.util.concurrent.ConcurrentMap owners =
                (java.util.concurrent.ConcurrentMap) ReflectionTestUtils.getField(service, "talkSsrcLeases");
        Class<?> ownerType = Class.forName("com.genersoft.iot.vmp.gb28181.service.impl.PlayServiceImpl$TalkSsrcOwner");
        java.lang.reflect.Constructor<?> constructor = ownerType.getDeclaredConstructor(
                String.class, SsrcLease.class, String.class, String.class);
        constructor.setAccessible(true);
        Object existingOwner = constructor.newInstance(
                "device-1", new SsrcLease("media-1", "00000003", true, "lease-existing"),
                "existing-stream", "call-existing");
        owners.put(channel.getId(), existingOwner);

        ReflectionTestUtils.invokeMethod(service, "talk", mediaServer, device, channel,
                "device-1_channel-1", (SipSubscribe.Event) event -> { },
                (Runnable) () -> { }, (AudioBroadcastEvent) message -> { });

        verify(sendRtp).delete(sendRtpInfo);
        verify(ssrcFactory).release(lease);
    }

    @Test
    void staleExpectedOwnerDoesNotStopReplacementSendRtp() throws Exception {
        PlayServiceImpl service = new PlayServiceImpl();
        SSRCFactory ssrcFactory = mock(SSRCFactory.class);
        ISendRtpServerService sendRtp = mock(ISendRtpServerService.class);
        IMediaServerService media = mock(IMediaServerService.class);
        SipInviteSessionManager sessions = mock(SipInviteSessionManager.class);
        Device device = new Device();
        device.setDeviceId("device-1");
        DeviceChannel channel = new DeviceChannel();
        channel.setId(1);
        channel.setDeviceId("channel-1");
        SendRtpInfo replacement = new SendRtpInfo();
        replacement.setMediaServerId("media-1");
        replacement.setApp("talk");
        replacement.setStream("replacement-stream");
        replacement.setSsrc("00000004");

        SsrcLease oldLease = new SsrcLease("media-1", "00000001", true, "lease-old");
        SsrcLease newLease = new SsrcLease("media-1", "00000002", true, "lease-new");
        Object oldOwner = talkOwner(oldLease, "old-stream", "call-old");
        Object newOwner = talkOwner(newLease, "replacement-stream", "call-new");
        java.util.concurrent.ConcurrentMap owners = talkOwners(service);
        owners.put(channel.getId(), newOwner);

        when(sendRtp.queryByChannelId(channel.getId(), device.getDeviceId())).thenReturn(replacement);

        ReflectionTestUtils.setField(service, "ssrcFactory", ssrcFactory);
        ReflectionTestUtils.setField(service, "sendRtpServerService", sendRtp);
        ReflectionTestUtils.setField(service, "mediaServerService", media);
        ReflectionTestUtils.setField(service, "sessionManager", sessions);

        ReflectionTestUtils.invokeMethod(service, "stopTalk", device, channel, null, oldOwner);

        verifyNoInteractions(media, sessions, ssrcFactory);
        verify(sendRtp, never()).deleteByChannel(anyInt(), anyString());
    }

    @Test
    void stopTalkWithoutSendInfoDoesNotReleaseUnmatchedCurrentOwner() throws Exception {
        PlayServiceImpl service = new PlayServiceImpl();
        SSRCFactory ssrcFactory = mock(SSRCFactory.class);
        ISendRtpServerService sendRtp = mock(ISendRtpServerService.class);
        Device device = new Device();
        device.setDeviceId("device-1");
        DeviceChannel channel = new DeviceChannel();
        channel.setId(1);
        channel.setDeviceId("channel-1");
        SsrcLease currentLease = new SsrcLease("media-1", "00000002", true, "lease-current");
        talkOwners(service).put(channel.getId(), talkOwner(currentLease, "current-stream", "call-current"));

        when(sendRtp.queryByChannelId(channel.getId(), device.getDeviceId())).thenReturn(null);
        ReflectionTestUtils.setField(service, "ssrcFactory", ssrcFactory);
        ReflectionTestUtils.setField(service, "sendRtpServerService", sendRtp);

        service.stopTalk(device, channel, null);

        verifyNoInteractions(ssrcFactory);
    }

    @Test
    void stopTalkSkipsSendInfoFromReplacementOwner() throws Exception {
        PlayServiceImpl service = new PlayServiceImpl();
        SSRCFactory ssrcFactory = mock(SSRCFactory.class);
        ISendRtpServerService sendRtp = mock(ISendRtpServerService.class);
        IMediaServerService media = mock(IMediaServerService.class);
        Device device = new Device();
        device.setDeviceId("device-1");
        DeviceChannel channel = new DeviceChannel();
        channel.setId(1);
        channel.setDeviceId("channel-1");
        SsrcLease lease = new SsrcLease("media-1", "00000001", true, "lease-current");
        talkOwners(service).put(channel.getId(), talkOwner(lease, "talk-stream", "call-current", "send-current"));

        SendRtpInfo replacement = new SendRtpInfo();
        replacement.setStream("talk-stream");
        replacement.setSsrc("send-replacement");
        replacement.setMediaServerId("media-1");
        when(sendRtp.queryByChannelId(channel.getId(), device.getDeviceId())).thenReturn(replacement);

        ReflectionTestUtils.setField(service, "ssrcFactory", ssrcFactory);
        ReflectionTestUtils.setField(service, "sendRtpServerService", sendRtp);
        ReflectionTestUtils.setField(service, "mediaServerService", media);

        service.stopTalk(device, channel, null);

        verifyNoInteractions(media, ssrcFactory);
        verify(sendRtp, never()).deleteByChannel(anyInt(), anyString());
    }

    @Test
    void stopTalkForOfflineDeviceReleasesLeaseWhenSendInfoLookupFails() throws Exception {
        PlayServiceImpl service = new PlayServiceImpl();
        SSRCFactory ssrcFactory = mock(SSRCFactory.class);
        ISendRtpServerService sendRtp = mock(ISendRtpServerService.class);
        Device device = new Device();
        device.setDeviceId("device-1");
        SsrcLease lease = new SsrcLease("media-1", "00000005", true, "lease-offline");
        talkOwners(service).put(1, talkOwner(lease, "talk-stream", "call-offline"));
        when(sendRtp.queryByChannelId(1, device.getDeviceId()))
                .thenThrow(new IllegalStateException("redis unavailable"));

        ReflectionTestUtils.setField(service, "ssrcFactory", ssrcFactory);
        ReflectionTestUtils.setField(service, "sendRtpServerService", sendRtp);

        service.stopTalkForDevice(device);

        verify(ssrcFactory).release(lease);
        org.junit.jupiter.api.Assertions.assertTrue(talkOwners(service).isEmpty());
    }

    @SuppressWarnings("rawtypes")
    private static java.util.concurrent.ConcurrentMap talkOwners(PlayServiceImpl service) {
        return (java.util.concurrent.ConcurrentMap) ReflectionTestUtils.getField(service, "talkSsrcLeases");
    }

    private static Object talkOwner(SsrcLease lease, String stream, String callId) throws Exception {
        return talkOwner(lease, stream, callId, null);
    }

    private static Object talkOwner(SsrcLease lease, String stream, String callId, String sendSsrc) throws Exception {
        Class<?> ownerType = Class.forName("com.genersoft.iot.vmp.gb28181.service.impl.PlayServiceImpl$TalkSsrcOwner");
        java.lang.reflect.Constructor<?> constructor = ownerType.getDeclaredConstructor(
                String.class, SsrcLease.class, String.class, String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance("device-1", lease, stream, callId, sendSsrc);
    }
}
