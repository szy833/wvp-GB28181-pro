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
    void singlePortNodeDoesNotUseRtpListenerListForLiveness() {
        InviteInfo invite = invite("media-1", "business-stream", "business-stream");
        Fixture fixture = fixture(invite, List.of());
        fixture.mediaServer.setRtpEnable(false);

        fixture.task.execute();

        verifyNoInteractions(fixture.playService);
    }

    @Test
    void broadcastInviteIsNotReconciledByDevicePlaybackCleaner() {
        InviteInfo invite = invite("media-1", "business-stream", "zlm-stream");
        invite.setType(InviteSessionType.BROADCAST);
        Fixture fixture = fixture(invite, List.of());

        fixture.task.execute();

        verifyNoInteractions(fixture.playService);
    }

    @Test
    void legacyInviteWithoutOwnerAwareZlmStreamIsRetained() {
        InviteInfo invite = invite("media-1", "business-stream", null);
        invite.getSsrcInfo().setResourceId(null);
        Fixture fixture = fixture(invite, List.of());

        fixture.task.execute();

        verifyNoInteractions(fixture.playService);
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
        MediaServer secondServer = server("media-2");
        secondServer.setRtpEnable(true);
        when(fixture.mediaServers.getAllOnlineList()).thenReturn(List.of(fixture.mediaServer, secondServer));
        when(fixture.invites.getActiveInviteInfoByMediaServer("media-1")).thenReturn(List.of(first));
        when(fixture.invites.getActiveInviteInfoByMediaServer("media-2")).thenReturn(List.of(second));
        when(fixture.mediaServers.listRtpServer(secondServer)).thenReturn(List.of("zlm-2"));

        fixture.task.execute();

        verify(fixture.playService).stopIfOwner(first);
        verify(fixture.playService, never()).stopIfOwner(second);
    }

    @Test
    void streamInfoMediaServerIdMatchesTheActiveIndexOwner() {
        InviteInfo invite = invite("media-root", "business-stream", "zlm-stream");
        MediaServer streamMediaServer = server("media-stream");
        invite.getStreamInfo().setMediaServer(streamMediaServer);

        IInviteStreamService invites = mock(IInviteStreamService.class);
        IMediaServerService mediaServers = mock(IMediaServerService.class);
        IPlayService playService = mock(IPlayService.class);
        InviteInfoCleanupTask task = new InviteInfoCleanupTask();
        ReflectionTestUtils.setField(task, "inviteStreamService", invites);
        ReflectionTestUtils.setField(task, "mediaServerService", mediaServers);
        ReflectionTestUtils.setField(task, "playService", playService);
        streamMediaServer.setRtpEnable(true);
        when(mediaServers.getAllOnlineList()).thenReturn(List.of(streamMediaServer));
        when(invites.getActiveInviteInfoByMediaServer("media-stream")).thenReturn(List.of(invite));
        when(mediaServers.listRtpServer(streamMediaServer)).thenReturn(List.of());

        task.execute();

        verify(playService).stopIfOwner(invite);
    }

    @Test
    void emptyStreamInfoMediaServerIdFallsBackToInviteMediaServerId() {
        InviteInfo invite = invite("media-1", "business-stream", "zlm-stream");
        invite.getStreamInfo().setMediaServer(server(""));
        Fixture fixture = fixture(invite, List.of());

        fixture.task.execute();

        verify(fixture.playService).stopIfOwner(invite);
    }

    @Test
    void activeInviteLookupFailureDoesNotAbortOtherNodes() {
        InviteInfo first = invite("media-1", "business-1", "zlm-1");
        InviteInfo second = invite("media-2", "business-2", "zlm-2");
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IMediaServerService mediaServers = mock(IMediaServerService.class);
        IPlayService playService = mock(IPlayService.class);
        InviteInfoCleanupTask task = new InviteInfoCleanupTask();
        ReflectionTestUtils.setField(task, "inviteStreamService", invites);
        ReflectionTestUtils.setField(task, "mediaServerService", mediaServers);
        ReflectionTestUtils.setField(task, "playService", playService);
        MediaServer firstServer = server("media-1");
        firstServer.setRtpEnable(true);
        MediaServer secondServer = server("media-2");
        secondServer.setRtpEnable(true);
        when(mediaServers.getAllOnlineList()).thenReturn(List.of(firstServer, secondServer));
        when(invites.getActiveInviteInfoByMediaServer("media-1"))
                .thenThrow(new IllegalStateException("redis unavailable"));
        when(invites.getActiveInviteInfoByMediaServer("media-2")).thenReturn(List.of(second));
        when(mediaServers.listRtpServer(secondServer)).thenReturn(List.of());

        task.execute();

        verify(playService).stopIfOwner(second);
    }

    @Test
    void onlineNodeWithoutActiveInvitesDoesNotQueryRtpListeners() {
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IMediaServerService mediaServers = mock(IMediaServerService.class);
        IPlayService playService = mock(IPlayService.class);
        InviteInfoCleanupTask task = new InviteInfoCleanupTask();
        ReflectionTestUtils.setField(task, "inviteStreamService", invites);
        ReflectionTestUtils.setField(task, "mediaServerService", mediaServers);
        ReflectionTestUtils.setField(task, "playService", playService);
        MediaServer mediaServer = server("media-1");
        mediaServer.setRtpEnable(true);
        when(mediaServers.getAllOnlineList()).thenReturn(List.of(mediaServer));
        when(invites.getActiveInviteInfoByMediaServer("media-1")).thenReturn(List.of());

        task.execute();

        verify(mediaServers, never()).listRtpServer(mediaServer);
        verify(invites, never()).getAllInviteInfo();
    }

    private static Fixture fixture(InviteInfo invite, List<String> rtpStreams) {
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IMediaServerService mediaServers = mock(IMediaServerService.class);
        IPlayService playService = mock(IPlayService.class);
        InviteInfoCleanupTask task = new InviteInfoCleanupTask();
        ReflectionTestUtils.setField(task, "inviteStreamService", invites);
        ReflectionTestUtils.setField(task, "mediaServerService", mediaServers);
        ReflectionTestUtils.setField(task, "playService", playService);
        MediaServer mediaServer = server(invite.getMediaServerId());
        mediaServer.setRtpEnable(true);
        when(mediaServers.getAllOnlineList()).thenReturn(List.of(mediaServer));
        when(invites.getActiveInviteInfoByMediaServer(invite.getMediaServerId())).thenReturn(List.of(invite));
        when(mediaServers.listRtpServer(mediaServer)).thenReturn(rtpStreams);
        return new Fixture(task, invites, mediaServers, playService, mediaServer);
    }

    private static InviteInfo invite(String mediaServerId, String businessStream, String zlmStream) {
        SSRCInfo ssrcInfo = new SSRCInfo(1234, "0001", "rtp", businessStream);
        ssrcInfo.setResourceId("resource-1");
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
                           IMediaServerService mediaServers, IPlayService playService,
                           MediaServer mediaServer) {
    }
}
