package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionStatus;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InviteInfoRedisIndexTest {

    @Test
    void derivesPrimaryAndSecondaryKeys() {
        InviteInfo invite = activeInvite("media-1", "stream-1", "00000001");

        assertEquals("PLAY:1:stream-1", InviteInfoRedisIndex.primaryField(invite));
        assertEquals("VMP_GB_INVITE_INDEX_CHANNEL:PLAY:1", InviteInfoRedisIndex.channelIndexKey(invite));
        assertEquals("VMP_GB_INVITE_INDEX_STREAM:stream-1", InviteInfoRedisIndex.streamIndexKey(invite));
        assertEquals("VMP_GB_INVITE_INDEX_SSRC:00000001", InviteInfoRedisIndex.ssrcIndexKey(invite));
        assertEquals("VMP_GB_INVITE_INDEX_DEVICE:device-1", InviteInfoRedisIndex.deviceIndexKey(invite));
        assertEquals("VMP_GB_INVITE_ACTIVE_MEDIA:media-1", InviteInfoRedisIndex.activeMediaKey(invite));
    }

    @Test
    void completedDownloadHasRetentionDeadlineButIsNotActive() {
        InviteInfo invite = activeInvite("media-1", "download-1", "00000002");
        invite.setType(InviteSessionType.DOWNLOAD);
        invite.getStreamInfo().setProgress(1.0);
        invite.setCleanupAt(1_725_000_000_000L);

        assertEquals(1_725_000_000_000L, InviteInfoRedisIndex.deadline(invite).orElseThrow());
        assertFalse(InviteInfoRedisIndex.countsAsActive(invite));
    }

    @Test
    void downloadProgressAboveOneIsAlsoCompletedAndNotActive() {
        InviteInfo invite = activeInvite("media-1", "download-1", "00000002");
        invite.setType(InviteSessionType.DOWNLOAD);
        invite.getStreamInfo().setProgress(1.1);

        assertFalse(InviteInfoRedisIndex.countsAsActive(invite));
    }

    @Test
    void pendingInviteUsesItsExistingExpiryDeadline() {
        InviteInfo invite = InviteInfo.getInviteInfo("device-1", 1, "pending-1",
                new SSRCInfo(1234, "00000003", "rtp", "pending-1"), "media-1", "127.0.0.1", 1234,
                "UDP", InviteSessionType.PLAY, InviteSessionStatus.ready);
        invite.setCreateTime(1_000L);
        invite.setExpirationTime(2_000L);

        assertEquals(3_000L, InviteInfoRedisIndex.deadline(invite).orElseThrow());
        assertFalse(InviteInfoRedisIndex.countsAsActive(invite));
    }

    @Test
    void activeInviteUsesItsRecordedMediaServerWhenStreamInfoHasNoServerObject() {
        InviteInfo invite = InviteInfo.getInviteInfo("device-1", 1, "stream-1",
                new SSRCInfo(1234, "00000004", "rtp", "stream-1"), "media-1", "127.0.0.1", 1234,
                "UDP", InviteSessionType.PLAY, InviteSessionStatus.ok);
        invite.setStreamInfo(new StreamInfo());

        assertEquals("media-1", InviteInfoRedisIndex.mediaServerId(invite));
        assertEquals("VMP_GB_INVITE_ACTIVE_MEDIA:media-1", InviteInfoRedisIndex.activeMediaKey(invite));
        assertTrue(InviteInfoRedisIndex.countsAsActive(invite));
    }

    @Test
    void emptyStreamInfoMediaServerIdFallsBackToRecordedMediaServer() {
        InviteInfo invite = activeInvite("media-1", "stream-1", "00000005");
        invite.getStreamInfo().getMediaServer().setId("");

        assertEquals("media-1", InviteInfoRedisIndex.mediaServerId(invite));
        assertEquals("VMP_GB_INVITE_ACTIVE_MEDIA:media-1", InviteInfoRedisIndex.activeMediaKey(invite));
    }

    @Test
    void incompletePrimaryFieldIsRejected() {
        assertFalse(InviteInfoRedisIndex.primaryField(InviteSessionType.PLAY, null, "stream-1").isPresent());
        assertTrue(InviteInfoRedisIndex.primaryField(InviteSessionType.PLAY, 1, "stream-1").isPresent());
    }

    private static InviteInfo activeInvite(String mediaServerId, String stream, String ssrc) {
        InviteInfo invite = InviteInfo.getInviteInfo("device-1", 1, stream,
                new SSRCInfo(1234, ssrc, "rtp", stream), mediaServerId, "127.0.0.1", 1234,
                "UDP", InviteSessionType.PLAY, InviteSessionStatus.ok);
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId(mediaServerId);
        StreamInfo streamInfo = new StreamInfo();
        streamInfo.setMediaServer(mediaServer);
        streamInfo.setProgress(0.5);
        invite.setStreamInfo(streamInfo);
        return invite;
    }
}
