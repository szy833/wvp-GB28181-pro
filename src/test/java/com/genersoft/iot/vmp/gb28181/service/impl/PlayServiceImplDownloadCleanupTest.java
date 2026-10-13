package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.event.hook.*;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.bean.DownloadFileInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayServiceImplDownloadCleanupTest {
    @Test void lateRecordHookRetainsResultForFifteenMinutesEvenAfterMediaDeparture() {
        var service=new PlayServiceImpl();var invites=mock(IInviteStreamService.class);var hooks=mock(HookSubscribe.class);var media=mock(IMediaServerService.class);
        var invite=InviteInfo.prepare("device",1,InviteSessionType.DOWNLOAD,"DOWNLOAD:1:window","node","UDP",60000);
        invite.setLifecycle(InviteLifecycle.CLOSED);invite.setStreamInfo(new StreamInfo());invite.getStreamInfo().setStream(invite.getStream());
        when(invites.getById(invite.getInviteId())).thenReturn(invite);
        when(invites.updateInviteInfo(invite)).thenAnswer(i->new InviteWriteResult(InviteWriteResult.Code.APPLIED,invite));
        var file=new DownloadFileInfo();when(media.getDownloadFilePath(any(),any())).thenReturn(file);
        AtomicReference<HookSubscribe.Event> callback=new AtomicReference<>();
        doAnswer(i->{callback.set(i.getArgument(1));return null;}).when(hooks).addSubscribe(any(Hook.class),any(HookSubscribe.Event.class));
        ReflectionTestUtils.setField(service,"inviteStreamService",invites);ReflectionTestUtils.setField(service,"subscribe",hooks);ReflectionTestUtils.setField(service,"mediaServerService",media);
        var node=new MediaServer();node.setId("node");
        ReflectionTestUtils.invokeMethod(service,"registerDownloadCompletion",invite,node);
        long before=System.currentTimeMillis();callback.get().response(new HookData());
        assertEquals(1,invite.getStreamInfo().getProgress());
        assertSame(file,invite.getStreamInfo().getDownLoadFilePath());
        assertTrue(invite.getCleanupAt()>=before+900000);
        assertTrue(invite.getCleanupAt()<=System.currentTimeMillis()+900000);
        verify(invites).updateInviteInfo(invite);
    }
}
