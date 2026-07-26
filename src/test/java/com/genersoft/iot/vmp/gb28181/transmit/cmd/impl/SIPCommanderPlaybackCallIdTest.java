package com.genersoft.iot.vmp.gb28181.transmit.cmd.impl;

import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.SipLayer;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.gb28181.event.SipSubscribe;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import com.genersoft.iot.vmp.gb28181.transmit.SIPSender;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.SIPRequestHeaderProvider;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import gov.nist.javax.sip.message.SIPResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sip.ResponseEvent;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class SIPCommanderPlaybackCallIdTest {

    @Test
    void playbackSessionUsesCallIdFromTheActualInvite() throws Exception {
        SIPCommander commander = new SIPCommander();
        SipLayer sipLayer = mock(SipLayer.class);
        SIPSender sipSender = mock(SIPSender.class);
        SIPRequestHeaderProvider headerProvider = mock(SIPRequestHeaderProvider.class);
        SipInviteSessionManager sessionManager = mock(SipInviteSessionManager.class);
        UserSetting userSetting = mock(UserSetting.class);
        Request request = mock(Request.class);
        ResponseEvent responseEvent = mock(ResponseEvent.class);
        SIPResponse response = mock(SIPResponse.class);
        CallIdHeader actualCallId = mock(CallIdHeader.class);
        CallIdHeader unrelatedCallId = mock(CallIdHeader.class);
        ViaHeader viaHeader = mock(ViaHeader.class);
        SsrcTransaction[] stored = new SsrcTransaction[1];

        Device device = new Device();
        device.setDeviceId("device-1");
        device.setLocalIp("127.0.0.1");
        device.setHostAddress("127.0.0.1:5060");
        device.setTransport("UDP");
        device.setStreamMode("UDP");
        DeviceChannel channel = new DeviceChannel();
        channel.setId(2);
        channel.setDeviceId("channel-1");
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("media-1");
        mediaServer.setSdpIp("127.0.0.1");
        SSRCInfo ssrcInfo = new SSRCInfo(40000, "1200000001", "rtp", "device-1_channel-1");

        when(actualCallId.getCallId()).thenReturn("invite-call-id");
        when(unrelatedCallId.getCallId()).thenReturn("unrelated-call-id");
        when(response.getCallIdHeader()).thenReturn(actualCallId);
        when(response.getTopmostViaHeader()).thenReturn(viaHeader);
        when(viaHeader.getBranch()).thenReturn("branch-1");
        when(responseEvent.getResponse()).thenReturn(response);
        when(userSetting.getSeniorSdp()).thenReturn(false);
        when(sipLayer.getLocalIp("127.0.0.1")).thenReturn("127.0.0.1");
        when(sipSender.getNewCallIdHeader("127.0.0.1", "UDP"))
                .thenReturn(actualCallId, unrelatedCallId);
        when(headerProvider.createPlaybackInviteRequest(
                same(device), eq(channel.getDeviceId()), anyString(), anyString(), anyString(),
                isNull(), same(actualCallId), eq(ssrcInfo.getSsrc())))
                .thenReturn(request);
        doAnswer(invocation -> {
            SipSubscribe.Event callback = invocation.getArgument(3);
            SipSubscribe.EventResult<ResponseEvent> result = new SipSubscribe.EventResult<>();
            result.event = responseEvent;
            callback.response(result);
            return null;
        }).when(sipSender).transmitRequest(anyString(), same(request), any(), any(), anyLong());
        doAnswer(invocation -> {
            stored[0] = invocation.getArgument(0);
            return null;
        }).when(sessionManager).put(any(SsrcTransaction.class));

        ReflectionTestUtils.setField(commander, "sipLayer", sipLayer);
        ReflectionTestUtils.setField(commander, "sipSender", sipSender);
        ReflectionTestUtils.setField(commander, "headerProvider", headerProvider);
        ReflectionTestUtils.setField(commander, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(commander, "userSetting", userSetting);

        commander.playbackStreamCmd(mediaServer, ssrcInfo, device, channel,
                "2026-07-26 09:02:04", "2026-07-26 23:59:59",
                event -> { }, event -> { }, 5000L);

        verify(sessionManager).put(any(SsrcTransaction.class));
        verify(sipSender, times(1)).getNewCallIdHeader("127.0.0.1", "UDP");
        assertEquals("invite-call-id", stored[0].getCallId());
        assertEquals("invite-call-id", stored[0].getSipTransactionInfo().getCallId());
        assertEquals("invite-call-id", response.getCallIdHeader().getCallId());
    }
}
