package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionStatus;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.event.SipSubscribe;
import com.genersoft.iot.vmp.gb28181.service.IDeviceChannelService;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import com.genersoft.iot.vmp.service.bean.InviteErrorCode;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import gov.nist.javax.sip.message.SIPResponse;
import javax.sip.ResponseEvent;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayServiceImplInviteCallbackTest {

    @Test
    void firstPlayRegistersCallbackForRemoteByeCompletion() {
        PlayServiceImpl service = new PlayServiceImpl();
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IMediaServerService mediaServers = mock(IMediaServerService.class);
        IReceiveRtpServerService receiveRtp = mock(IReceiveRtpServerService.class);
        ISIPCommander commander = mock(ISIPCommander.class);
        SipInviteSessionManager sessions = mock(SipInviteSessionManager.class);
        UserSetting settings = mock(UserSetting.class);
        DeviceChannel channel = new DeviceChannel();
        channel.setId(2);
        channel.setDeviceId("channel-1");
        Device device = new Device();
        device.setDeviceId("device-1");
        device.setServerId("server-1");
        device.setMediaServerId("media-1");
        device.setStreamMode("UDP");
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("media-1");
        mediaServer.setSdpIp("192.168.3.172");
        mediaServer.setRtpEnable(true);
        SSRCInfo ssrcInfo = new SSRCInfo(44668, "0200007930", "rtp", "device-1_channel-1");

        when(settings.getServerId()).thenReturn("server-1");
        when(settings.getRecordSip()).thenReturn(false);
        when(settings.getPlayTimeout()).thenReturn(30);
        when(mediaServers.getOne("media-1")).thenReturn(mediaServer);
        when(invites.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, 2)).thenReturn(null);
        when(receiveRtp.openGbRTPServerForPlay(eq(mediaServer), eq(device), eq(channel), any(), anyBoolean(), any()))
                .thenReturn(ssrcInfo);

        ReflectionTestUtils.setField(service, "userSetting", settings);
        ReflectionTestUtils.setField(service, "mediaServerService", mediaServers);
        ReflectionTestUtils.setField(service, "inviteStreamService", invites);
        ReflectionTestUtils.setField(service, "receiveRtpServerService", receiveRtp);
        ReflectionTestUtils.setField(service, "cmder", commander);
        ReflectionTestUtils.setField(service, "sessionManager", sessions);

        AtomicInteger callbackCount = new AtomicInteger();
        service.play(device, channel, (code, msg, data) -> callbackCount.incrementAndGet());

        ArgumentCaptor<ErrorCallback<StreamInfo>> callbackCaptor = ArgumentCaptor.forClass(ErrorCallback.class);
        verify(invites).once(eq(InviteSessionType.PLAY), eq(2), eq(null), callbackCaptor.capture());
        callbackCaptor.getValue().run(InviteErrorCode.SUCCESS.getCode(), InviteErrorCode.SUCCESS.getMsg(), null);
        callbackCaptor.getValue().run(InviteErrorCode.ERROR_FOR_FINISH.getCode(), InviteErrorCode.ERROR_FOR_FINISH.getMsg(), null);

        assertEquals(1, callbackCount.get());
    }

    @Test
    void inviteOkPersistsReadyInviteAsOkForMatchingUdpSsrc() {
        PlayServiceImpl service = new PlayServiceImpl();
        IInviteStreamService invites = mock(IInviteStreamService.class);
        Device device = new Device();
        device.setDeviceId("device-1");
        device.setStreamMode("UDP");
        DeviceChannel channel = new DeviceChannel();
        channel.setId(2);
        channel.setDeviceId("channel-1");
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("media-1");
        mediaServer.setRtpEnable(true);
        SSRCInfo ssrcInfo = new SSRCInfo(40070, "0200009865", "rtp", "device-1_channel-1");
        InviteInfo inviteInfo = InviteInfo.getInviteInfo(device.getDeviceId(), channel.getId(),
                ssrcInfo.getStream(), ssrcInfo, mediaServer.getId(), "192.168.3.172", 40070,
                "UDP", InviteSessionType.PLAY, InviteSessionStatus.ready);

        SIPResponse response = mock(SIPResponse.class);
        when(response.getRawContent()).thenReturn("v=0\r\ny=0200009865\r\n".getBytes());
        ResponseEvent responseEvent = mock(ResponseEvent.class);
        when(responseEvent.getResponse()).thenReturn(response);
        SipSubscribe.EventResult<ResponseEvent> eventResult = new SipSubscribe.EventResult<>();
        eventResult.event = responseEvent;

        ReflectionTestUtils.setField(service, "inviteStreamService", invites);
        ReflectionTestUtils.invokeMethod(service, "InviteOKHandler", eventResult, ssrcInfo, mediaServer,
                device, channel, (ErrorCallback<StreamInfo>) (code, msg, data) -> {}, inviteInfo,
                InviteSessionType.PLAY);

        assertEquals(InviteSessionStatus.ok, inviteInfo.getStatus());
        verify(invites).updateInviteInfo(inviteInfo);
    }
}
