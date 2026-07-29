package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionStatus;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.redis.RedisTemplateConfig;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class InviteStreamServiceRedisIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.0"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static RedisTemplate<String, Object> redisTemplate;

    private InviteStreamServiceImpl service;

    @BeforeAll
    static void setUpRedisTemplate() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new RedisTemplateConfig().redisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void closeRedisTemplate() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUpService() {
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
        service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
    }

    @Test
    void replacementOwnerSurvivesOldOwnerConditionalDelete() {
        InviteInfo oldOwner = activeInvite("owner-a");
        InviteInfo replacement = activeInvite("owner-b");
        service.updateInviteInfo(oldOwner, 1_000L);
        service.updateInviteInfo(replacement, 1_000L);

        assertFalse(service.removeInviteInfoIfSame(oldOwner));
        InviteInfo current = service.getInviteInfo(InviteSessionType.PLAY, 1, "stream-1");
        assertNotNull(current);
        assertEquals("owner-b", current.getSsrcInfo().getResourceId());
    }

    @Test
    void legacyRecordStaysDiscoverableUntilExplicitReadyActivation() {
        InviteInfo legacy = activeInvite("owner-a");
        redisTemplate.opsForHash().put(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1", legacy);

        assertNotNull(service.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, 1));
        assertTrue(service.rebuildInviteIndexes());
        assertTrue(service.inviteIndexesBackfilled());
        assertFalse(service.inviteIndexesReady());
        assertNotNull(service.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, 1));

        assertTrue(service.activateInviteIndexes());
        assertTrue(service.inviteIndexesReady());
        assertNotNull(service.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, 1));
    }

    @Test
    void removeAllInviteInfoClearsPrimaryAndEveryDerivedIndex() {
        InviteInfo active = activeInvite("owner-a");
        InviteInfo pending = activeInvite("owner-b");
        pending.setType(InviteSessionType.PLAYBACK);
        pending.setStream("pending-stream");
        pending.getSsrcInfo().setStream("pending-stream");
        pending.setStreamInfo(null);
        service.updateInviteInfo(active, 1_000L);
        service.updateInviteInfo(pending, 1_000L);
        service.activateInviteIndexes();

        service.removeInviteInfo(null, null, null);

        assertEquals(0, redisTemplate.opsForHash().size(VideoManagerConstants.INVITE_PREFIX));
        assertEquals(0, redisTemplate.opsForSet().size("VMP_GB_INVITE_INDEX_CHANNEL:PLAY:1"));
        assertEquals(0, redisTemplate.opsForSet().size("VMP_GB_INVITE_INDEX_CHANNEL:PLAYBACK:1"));
        assertEquals(0, redisTemplate.opsForSet().size("VMP_GB_INVITE_ACTIVE_MEDIA:media-1"));
        assertEquals(0, redisTemplate.opsForZSet().size(VideoManagerConstants.INVITE_EXPIRE_AT));
    }

    private static InviteInfo activeInvite(String owner) {
        SSRCInfo ssrcInfo = new SSRCInfo(1234, "00000001", "rtp", "stream-1");
        ssrcInfo.setResourceId(owner);
        ssrcInfo.setZlmStream("stream-1");
        InviteInfo invite = InviteInfo.getInviteInfo("device-1", 1, "stream-1", ssrcInfo,
                "media-1", "127.0.0.1", 1234, "UDP", InviteSessionType.PLAY, InviteSessionStatus.ready);
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("media-1");
        StreamInfo streamInfo = new StreamInfo();
        streamInfo.setMediaServer(mediaServer);
        streamInfo.setProgress(0.5D);
        invite.setStreamInfo(streamInfo);
        return invite;
    }
}
