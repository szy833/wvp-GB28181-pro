package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionStatus;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.gb28181.service.IDeviceService;
import com.genersoft.iot.vmp.gb28181.service.IDeviceChannelService;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.service.bean.InviteErrorCode;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import com.genersoft.iot.vmp.service.ISendRtpServerService;
import gov.nist.javax.sip.message.SIPRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sip.RequestEvent;
import javax.sip.header.CallIdHeader;

import static org.mockito.Mockito.*;

class ByeRequestProcessorTest {

    @Test
    void process_shouldRemoveSessionWhenDeviceIsMissing() throws Exception {
        ByeRequestProcessor processor = spy(new ByeRequestProcessor());
        ISendRtpServerService sendRtpServerService = mock(ISendRtpServerService.class);
        SipInviteSessionManager sessionManager = mock(SipInviteSessionManager.class);
        IDeviceService deviceService = mock(IDeviceService.class);
        IReceiveRtpServerService receiveRtpServerService = mock(IReceiveRtpServerService.class);
        RequestEvent event = mock(RequestEvent.class);
        SIPRequest request = mock(SIPRequest.class);
        CallIdHeader callIdHeader = mock(CallIdHeader.class);
        SsrcTransaction transaction = new SsrcTransaction();
        transaction.setCallId("call-1");
        transaction.setDeviceId("device-1");
        transaction.setType(InviteSessionType.PLAY);
        transaction.setMediaServerId("media-1");
        transaction.setApp("rtp");
        transaction.setStream("device-1_channel-1");

        when(event.getRequest()).thenReturn(request);
        when(request.getHeader(CallIdHeader.NAME)).thenReturn(callIdHeader);
        when(callIdHeader.getCallId()).thenReturn("call-1");
        when(sendRtpServerService.queryByCallId("call-1")).thenReturn(null);
        when(sessionManager.getSsrcTransactionByCallId("call-1")).thenReturn(transaction);
        when(deviceService.getDeviceByDeviceId("device-1")).thenReturn(null);

        ReflectionTestUtils.setField(processor, "sendRtpServerService", sendRtpServerService);
        ReflectionTestUtils.setField(processor, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(processor, "deviceService", deviceService);
        ReflectionTestUtils.setField(processor, "receiveRtpServerService", receiveRtpServerService);
        doReturn(null).when(processor).responseAck(request, 200);

        processor.process(event);

        verify(sessionManager).removeByCallId("call-1");
        verify(receiveRtpServerService).closeRTPServerByMediaServerId(
                "media-1", "rtp", "device-1_channel-1");
    }

    @Test
    void process_shouldNotifyPendingInviteWhenDeviceSendsBye() throws Exception {
        ByeRequestProcessor processor = spy(new ByeRequestProcessor());
        ISendRtpServerService sendRtpServerService = mock(ISendRtpServerService.class);
        SipInviteSessionManager sessionManager = mock(SipInviteSessionManager.class);
        IDeviceService deviceService = mock(IDeviceService.class);
        IDeviceChannelService deviceChannelService = mock(IDeviceChannelService.class);
        IInviteStreamService inviteStreamService = mock(IInviteStreamService.class);
        IReceiveRtpServerService receiveRtpServerService = mock(IReceiveRtpServerService.class);
        RequestEvent event = mock(RequestEvent.class);
        SIPRequest request = mock(SIPRequest.class);
        CallIdHeader callIdHeader = mock(CallIdHeader.class);

        Device device = new Device();
        device.setDeviceId("device-1");
        DeviceChannel channel = new DeviceChannel();
        channel.setId(2);
        channel.setDeviceId("channel-1");
        SSRCInfo ssrcInfo = new SSRCInfo(44668, "0200007930", "rtp", "device-1_channel-1");
        InviteInfo inviteInfo = InviteInfo.getInviteInfo("device-1", 2, "device-1_channel-1", ssrcInfo,
                "media-1", "192.168.3.172", 44668, "UDP", InviteSessionType.PLAY,
                InviteSessionStatus.ready);
        SsrcTransaction transaction = new SsrcTransaction();
        transaction.setCallId("call-1");
        transaction.setDeviceId("device-1");
        transaction.setChannelId(2);
        transaction.setType(InviteSessionType.PLAY);
        transaction.setMediaServerId("media-1");
        transaction.setApp("rtp");
        transaction.setStream("device-1_channel-1");

        when(event.getRequest()).thenReturn(request);
        when(request.getHeader(CallIdHeader.NAME)).thenReturn(callIdHeader);
        when(callIdHeader.getCallId()).thenReturn("call-1");
        when(sendRtpServerService.queryByCallId("call-1")).thenReturn(null);
        when(sessionManager.getSsrcTransactionByCallId("call-1")).thenReturn(transaction);
        when(deviceService.getDeviceByDeviceId("device-1")).thenReturn(device);
        when(deviceChannelService.getOneForSourceById(2)).thenReturn(channel);
        when(inviteStreamService.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, 2))
                .thenReturn(inviteInfo);

        ReflectionTestUtils.setField(processor, "sendRtpServerService", sendRtpServerService);
        ReflectionTestUtils.setField(processor, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(processor, "deviceService", deviceService);
        ReflectionTestUtils.setField(processor, "deviceChannelService", deviceChannelService);
        ReflectionTestUtils.setField(processor, "inviteStreamService", inviteStreamService);
        ReflectionTestUtils.setField(processor, "receiveRtpServerService", receiveRtpServerService);
        doReturn(null).when(processor).responseAck(request, 200);

        processor.process(event);

        verify(inviteStreamService).call(InviteSessionType.PLAY, 2, null,
                InviteErrorCode.ERROR_FOR_FINISH.getCode(), InviteErrorCode.ERROR_FOR_FINISH.getMsg(), null);
        verify(inviteStreamService).removeInviteInfo(inviteInfo);
        verify(deviceChannelService).stopPlay(2);
        verify(receiveRtpServerService).closeRTPServer(ssrcInfo);
    }
}
