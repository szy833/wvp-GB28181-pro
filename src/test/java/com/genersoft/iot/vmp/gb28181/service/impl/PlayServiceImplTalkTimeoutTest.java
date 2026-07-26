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

        when(ssrcFactory.getPlaySsrc(mediaServer)).thenReturn("00000001");
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
    }
}
