package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl;

import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.gb28181.service.IDeviceService;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
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
}
