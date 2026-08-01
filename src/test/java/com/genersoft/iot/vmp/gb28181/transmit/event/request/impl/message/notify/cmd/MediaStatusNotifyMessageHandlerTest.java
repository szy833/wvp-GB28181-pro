package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl.message.notify.cmd;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.service.IPlayService;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import com.genersoft.iot.vmp.media.event.hook.HookSubscribe;
import gov.nist.javax.sip.message.SIPRequest;
import org.dom4j.DocumentHelper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sip.RequestEvent;
import javax.sip.header.CallIdHeader;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaStatusNotifyMessageHandlerTest {

    @Test
    void mediaStatus121StopsPlaybackSession() throws Exception {
        MediaStatusNotifyMessageHandler handler = handler();
        SsrcTransaction transaction = transaction(InviteSessionType.PLAYBACK);
        InviteInfo inviteInfo = new InviteInfo();
        when(sessionManager(handler).getSsrcTransactionByCallId("call-1")).thenReturn(transaction);
        when(inviteService(handler).getInviteInfo(InviteSessionType.PLAYBACK, 2, "stream-1"))
                .thenReturn(inviteInfo);

        handler.handForDevice(requestEvent(), null,
                DocumentHelper.parseText("<Notify><NotifyType>121</NotifyType></Notify>").getRootElement());

        verify(playService(handler)).stop(inviteInfo);
    }

    @Test
    void mediaStatus121StopsDownloadSession() throws Exception {
        MediaStatusNotifyMessageHandler handler = handler();
        SsrcTransaction transaction = transaction(InviteSessionType.DOWNLOAD);
        InviteInfo inviteInfo = new InviteInfo();
        when(sessionManager(handler).getSsrcTransactionByCallId("call-1")).thenReturn(transaction);
        when(inviteService(handler).getInviteInfo(InviteSessionType.DOWNLOAD, 2, "stream-1"))
                .thenReturn(inviteInfo);

        handler.handForDevice(requestEvent(), null,
                DocumentHelper.parseText("<Notify><NotifyType>121</NotifyType></Notify>").getRootElement());

        verify(playService(handler)).stop(inviteInfo);
    }

    @Test
    void mediaStatus121DoesNotStopLivePlaySession() throws Exception {
        MediaStatusNotifyMessageHandler handler = handler();
        when(sessionManager(handler).getSsrcTransactionByCallId("call-1"))
                .thenReturn(transaction(InviteSessionType.PLAY));

        handler.handForDevice(requestEvent(), null,
                DocumentHelper.parseText("<Notify><NotifyType>121</NotifyType></Notify>").getRootElement());

        verify(playService(handler), never()).stop(any(InviteInfo.class));
        verify(inviteService(handler), never()).getInviteInfo(any(), any(), any());
    }

    private static MediaStatusNotifyMessageHandler handler() throws Exception {
        MediaStatusNotifyMessageHandler handler = spy(new MediaStatusNotifyMessageHandler());
        SipInviteSessionManager sessionManager = mock(SipInviteSessionManager.class);
        IInviteStreamService inviteService = mock(IInviteStreamService.class);
        IPlayService playService = mock(IPlayService.class);
        ReflectionTestUtils.setField(handler, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(handler, "inviteStreamService", inviteService);
        ReflectionTestUtils.setField(handler, "playService", playService);
        ReflectionTestUtils.setField(handler, "subscribe", mock(HookSubscribe.class));
        doReturn(null).when(handler).responseAck(any(SIPRequest.class), anyInt());
        return handler;
    }

    private static RequestEvent requestEvent() {
        RequestEvent event = mock(RequestEvent.class);
        SIPRequest request = mock(SIPRequest.class);
        CallIdHeader callIdHeader = mock(CallIdHeader.class);
        when(callIdHeader.getCallId()).thenReturn("call-1");
        when(request.getHeader(CallIdHeader.NAME)).thenReturn(callIdHeader);
        when(event.getRequest()).thenReturn(request);
        return event;
    }

    private static SsrcTransaction transaction(InviteSessionType type) {
        SsrcTransaction transaction = new SsrcTransaction();
        transaction.setCallId("call-1");
        transaction.setChannelId(2);
        transaction.setStream("stream-1");
        transaction.setType(type);
        return transaction;
    }

    private static SipInviteSessionManager sessionManager(MediaStatusNotifyMessageHandler handler) {
        return (SipInviteSessionManager) ReflectionTestUtils.getField(handler, "sessionManager");
    }

    private static IInviteStreamService inviteService(MediaStatusNotifyMessageHandler handler) {
        return (IInviteStreamService) ReflectionTestUtils.getField(handler, "inviteStreamService");
    }

    private static IPlayService playService(MediaStatusNotifyMessageHandler handler) {
        return (IPlayService) ReflectionTestUtils.getField(handler, "playService");
    }
}
