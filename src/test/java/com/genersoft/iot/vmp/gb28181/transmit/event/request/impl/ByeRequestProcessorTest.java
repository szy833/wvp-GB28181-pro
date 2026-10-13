package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl;

import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.service.*;
import com.genersoft.iot.vmp.gb28181.session.*;
import com.genersoft.iot.vmp.service.ISendRtpServerService;
import gov.nist.javax.sip.message.SIPRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sip.RequestEvent;
import javax.sip.header.CallIdHeader;
import static org.mockito.Mockito.*;

class ByeRequestProcessorTest {
    @Test void deviceMissingStillUsesDurableInviteCleanup() throws Exception {
        Fixture f=new Fixture();
        var invite=InviteInfo.prepare("device",1,InviteSessionType.PLAY,"PLAY:1","node","UDP",60000);invite.setCallId("call");
        when(f.invites.getByCallId("call")).thenReturn(invite);
        f.processor.process(f.event);
        verify(f.play).stopAfterDeviceBye(invite);
        verify(f.sessions,never()).removeByCallId(anyString());
        verifyNoInteractions(f.devices);
    }
    @Test void failedCleanupDoesNotDeleteTheRetryDialog() throws Exception {
        Fixture f=new Fixture();
        var invite=InviteInfo.prepare("device",1,InviteSessionType.PLAYBACK,"PLAYBACK:1:window","node","UDP",60000);invite.setCallId("call");
        when(f.invites.getByCallId("call")).thenReturn(invite);when(f.play.stopAfterDeviceBye(invite)).thenReturn(false);
        f.processor.process(f.event);
        verify(f.sessions,never()).removeByCallId(anyString());
        verify(f.play).stopAfterDeviceBye(invite);
    }
    @Test void unmanagedTalkStillUsesItsOwnOwnerCleanup() throws Exception {
        Fixture f=new Fixture();var transaction=new SsrcTransaction();transaction.setCallId("call");transaction.setDeviceId("device");transaction.setChannelId(1);transaction.setType(InviteSessionType.TALK);
        var device=new Device();device.setDeviceId("device");var channel=new DeviceChannel();channel.setId(1);
        when(f.sessions.getSsrcTransactionByCallId("call")).thenReturn(transaction);when(f.devices.getDeviceByDeviceId("device")).thenReturn(device);when(f.channels.getOneForSourceById(1)).thenReturn(channel);
        f.processor.process(f.event);
        verify(f.play).stopTalk(device,channel,null);verify(f.sessions).removeByCallId("call");
    }
    static final class Fixture {
        final ByeRequestProcessor processor=spy(new ByeRequestProcessor());final RequestEvent event=mock(RequestEvent.class);
        final SipInviteSessionManager sessions=mock(SipInviteSessionManager.class);final IInviteStreamService invites=mock(IInviteStreamService.class);
        final IPlayService play=mock(IPlayService.class);final IDeviceService devices=mock(IDeviceService.class);final IDeviceChannelService channels=mock(IDeviceChannelService.class);
        Fixture() throws Exception {
            SIPRequest request=mock(SIPRequest.class);CallIdHeader header=mock(CallIdHeader.class);
            when(event.getRequest()).thenReturn(request);when(request.getHeader(CallIdHeader.NAME)).thenReturn(header);when(header.getCallId()).thenReturn("call");
            doReturn(null).when(processor).responseAck(request,200);
            ReflectionTestUtils.setField(processor,"sendRtpServerService",mock(ISendRtpServerService.class));
            ReflectionTestUtils.setField(processor,"sessionManager",sessions);ReflectionTestUtils.setField(processor,"inviteStreamService",invites);ReflectionTestUtils.setField(processor,"playService",play);
            ReflectionTestUtils.setField(processor,"deviceService",devices);ReflectionTestUtils.setField(processor,"deviceChannelService",channels);ReflectionTestUtils.setField(processor,"audioBroadcastManager",mock(AudioBroadcastManager.class));
        }
    }
}
