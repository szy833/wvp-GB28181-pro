package com.genersoft.iot.vmp.gb28181.task;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionStatus;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.service.IPlayService;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InviteInfoCleanupTaskTest {

    @Test
    void presentActualZlmStreamIsRetained() {
        InviteInfo invite = invite("media-1", "business-stream", "zlm-stream");
        Fixture fixture = fixture(invite, List.of("zlm-stream"));

        fixture.task.execute();

        verifyNoInteractions(fixture.playService);
    }

    @Test
    void absentActualZlmStreamStopsOwner() {
        InviteInfo invite = invite("media-1", "business-stream", "zlm-stream");
        Fixture fixture = fixture(invite, List.of());

        fixture.task.execute();

        verify(fixture.playService).stopIfOwner(invite);
    }

    @Test
    void failedQueryDoesNotStopAnything() {
        InviteInfo invite = invite("media-1", "business-stream", "zlm-stream");
        Fixture fixture = fixture(invite, null);

        fixture.task.execute();

        verifyNoInteractions(fixture.playService);
    }

    @Test
    void completedDownloadBeforeDeadlineIsRetained() {
        InviteInfo invite = invite("media-1", "business-stream", "zlm-stream");
        invite.setType(InviteSessionType.DOWNLOAD);
        invite.getStreamInfo().setProgress(1.0);
        invite.setCleanupAt(System.currentTimeMillis() + 60_000L);
        Fixture fixture = fixture(invite, List.of());

        fixture.task.execute();

        verifyNoInteractions(fixture.playService);
    }

    @Test
    void mediaServersAreNeverCrossCleaned() {
        InviteInfo first = invite("media-1", "business-1", "zlm-1");
        InviteInfo second = invite("media-2", "business-2", "zlm-2");
        Fixture fixture = fixture(first, List.of());
        fixture.invites.getAllInviteInfo();
        when(fixture.invites.getAllInviteInfo()).thenReturn(List.of(first, second));
        MediaServer secondServer = server("media-2");
        when(fixture.mediaServers.getOne("media-2")).thenReturn(secondServer);
        when(fixture.mediaServers.listRtpServer(secondServer)).thenReturn(List.of("zlm-2"));

        fixture.task.execute();

        verify(fixture.playService).stopIfOwner(first);
        verify(fixture.playService, never()).stopIfOwner(second);
    }

    private static Fixture fixture(InviteInfo invite, List<String> rtpStreams) {
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IMediaServerService mediaServers = mock(IMediaServerService.class);
        IPlayService playService = mock(IPlayService.class);
        InviteInfoCleanupTask task = new InviteInfoCleanupTask();
        ReflectionTestUtils.setField(task, "inviteStreamService", invites);
        ReflectionTestUtils.setField(task, "mediaServerService", mediaServers);
        ReflectionTestUtils.setField(task, "playService", playService);
        when(invites.getAllInviteInfo()).thenReturn(List.of(invite));
        MediaServer mediaServer = server(invite.getMediaServerId());
        when(mediaServers.getOne(invite.getMediaServerId())).thenReturn(mediaServer);
        when(mediaServers.listRtpServer(mediaServer)).thenReturn(rtpStreams);
        return new Fixture(task, invites, mediaServers, playService);
    }

    private static InviteInfo invite(String mediaServerId, String businessStream, String zlmStream) {
        SSRCInfo ssrcInfo = new SSRCInfo(1234, "0001", "rtp", businessStream);
        ssrcInfo.setZlmStream(zlmStream);
        InviteInfo invite = InviteInfo.getInviteInfo("device-1", 1, businessStream, ssrcInfo,
                mediaServerId, "127.0.0.1", 1234, "UDP", InviteSessionType.PLAY, InviteSessionStatus.ok);
        invite.setStreamInfo(new StreamInfo());
        return invite;
    }

    private static MediaServer server(String id) {
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId(id);
        return mediaServer;
    }

    private record Fixture(InviteInfoCleanupTask task, IInviteStreamService invites,
                           IMediaServerService mediaServers, IPlayService playService) {
    }
}
