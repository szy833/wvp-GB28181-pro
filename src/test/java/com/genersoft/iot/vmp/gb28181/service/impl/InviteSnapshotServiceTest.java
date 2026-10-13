package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InviteSnapshotServiceTest {
    @Test
    void activeThumbnailRunsOutsideTheCallerAndDoesNotPropagateFailure() {
        var repository = mock(InviteRepository.class);
        var media = mock(IMediaServerService.class);
        var service = new InviteSnapshotService(repository, media);
        var invite = InviteInfo.prepare("device", 1, InviteSessionType.PLAY, "PLAY:1", "node", "UDP", 60000);
        invite.setRemoteChannelId("channel"); invite.setLifecycle(InviteLifecycle.ACTIVE);
        var node = new MediaServer(); node.setId("node");
        when(repository.get(invite.getInviteId())).thenReturn(invite);
        when(media.getOne("node")).thenReturn(node);
        when(media.getSnap(node, "rtp", invite.getStream(), 15, 1, "snap", "device_channel.jpg"))
                .thenThrow(new IllegalStateException("injected thumbnail failure"));
        try {
            assertDoesNotThrow(() -> service.submit(invite));
            verify(media, timeout(1000)).getSnap(node, "rtp", invite.getStream(), 15, 1, "snap", "device_channel.jpg");
        } finally { service.close(); }
    }

    @Test
    void closedGenerationAndClosedWorkerNeverRequestThumbnails() {
        var repository = mock(InviteRepository.class);
        var media = mock(IMediaServerService.class);
        var service = new InviteSnapshotService(repository, media);
        var invite = InviteInfo.prepare("device", 1, InviteSessionType.PLAY, "PLAY:1", "node", "UDP", 60000);
        invite.setLifecycle(InviteLifecycle.STOPPING);
        when(repository.get(invite.getInviteId())).thenReturn(invite);
        try {
            service.submit(invite);
            verify(repository, timeout(1000)).get(invite.getInviteId());
            verifyNoInteractions(media);
        } finally { service.close(); }
        assertDoesNotThrow(() -> service.submit(invite));
    }
}
