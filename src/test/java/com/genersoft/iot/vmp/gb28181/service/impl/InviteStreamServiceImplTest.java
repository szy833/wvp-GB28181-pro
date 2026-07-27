package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionStatus;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import com.genersoft.iot.vmp.utils.redis.FastJsonRedisSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InviteStreamServiceImplTest {

    @Test
    void cleanupAtRoundTripsThroughRedisSerializer() {
        InviteInfo expected = new InviteInfo();
        expected.setCleanupAt(1_725_000_123_456L);
        FastJsonRedisSerializer<InviteInfo> serializer = new FastJsonRedisSerializer<>(InviteInfo.class);

        InviteInfo actual = serializer.deserialize(serializer.serialize(expected));

        assertEquals(expected.getCleanupAt(), actual.getCleanupAt());
    }

    @Test
    void oldRedisJsonDefaultsCleanupAtToNull() {
        FastJsonRedisSerializer<InviteInfo> serializer = new FastJsonRedisSerializer<>(InviteInfo.class);

        InviteInfo actual = serializer.deserialize(("{\"deviceId\":\"device-1\",\"channelId\":1,"
                + "\"stream\":\"stream-1\",\"type\":\"DOWNLOAD\",\"status\":\"ok\"}")
                .getBytes(StandardCharsets.UTF_8));

        assertNotNull(actual);
        assertNull(actual.getCleanupAt());
    }

    @Test
    void pendingReadyRecordsUseTheirExistingTimeout() {
        InviteInfo ready = invite(InviteSessionType.DOWNLOAD, InviteSessionStatus.ready);
        ready.setCreateTime(System.currentTimeMillis() - 10_000L);
        ready.setExpirationTime(1_000L);
        InviteStreamServiceImpl service = serviceWithValues(ready);
        doReturn(true).when(service).removeInviteInfoIfSame(ready);

        service.execute();

        verify(service).removeInviteInfoIfSame(ready);
    }

    @Test
    void activePlaybackIsLeftForActiveSessionReconciliation() {
        InviteInfo active = invite(InviteSessionType.PLAYBACK, InviteSessionStatus.ok);
        active.setStreamInfo(new StreamInfo());
        active.setCreateTime(System.currentTimeMillis() - 10_000L);
        active.setExpirationTime(1L);
        InviteStreamServiceImpl service = serviceWithValues(active);

        service.execute();

        verify(service, never()).removeInviteInfoIfSame(any());
    }

    @Test
    void completedDownloadBeforeCleanupAtIsRetained() {
        InviteInfo download = invite(InviteSessionType.DOWNLOAD, InviteSessionStatus.ok);
        StreamInfo streamInfo = new StreamInfo();
        streamInfo.setProgress(1.0);
        download.setStreamInfo(streamInfo);
        download.setCleanupAt(System.currentTimeMillis() + 60_000L);
        InviteStreamServiceImpl service = serviceWithValues(download);

        service.execute();

        verify(service, never()).removeInviteInfoIfSame(any());
    }

    @Test
    void completedDownloadAtCleanupAtIsRemovedConditionally() {
        InviteInfo download = invite(InviteSessionType.DOWNLOAD, InviteSessionStatus.ok);
        StreamInfo streamInfo = new StreamInfo();
        streamInfo.setProgress(1.0);
        download.setStreamInfo(streamInfo);
        download.setCleanupAt(System.currentTimeMillis() - 1L);
        InviteStreamServiceImpl service = serviceWithValues(download);
        doReturn(true).when(service).removeInviteInfoIfSame(download);

        service.execute();

        verify(service).removeInviteInfoIfSame(download);
    }

    @Test
    void redisReadFailureDoesNotAttemptDeletion() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(hash.size(anyString())).thenThrow(new IllegalStateException("redis unavailable"));
        InviteStreamServiceImpl service = spy(new InviteStreamServiceImpl());
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        assertDoesNotThrow(service::execute);
        verify(hash, never()).delete(any(), any());
    }

    @Test
    void nonReadyUpdatePreservesCleanupAtWhenIncomingFieldIsNull() {
        InviteInfo existing = invite(InviteSessionType.DOWNLOAD, InviteSessionStatus.ok);
        existing.setCleanupAt(1_725_000_123_456L);
        InviteInfo incoming = invite(InviteSessionType.DOWNLOAD, InviteSessionStatus.ok);
        incoming.setStreamInfo(new StreamInfo());
        InviteStreamServiceImpl service = spy(new InviteStreamServiceImpl());
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        doReturn(existing).when(service).getInviteInfo(incoming.getType(), incoming.getChannelId(), incoming.getStream());

        service.updateInviteInfo(incoming);

        org.mockito.ArgumentCaptor<Object> stored = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(hash).put(eq(VideoManagerConstants.INVITE_PREFIX), anyString(), stored.capture());
        assertEquals(1_725_000_123_456L, ((InviteInfo) stored.getValue()).getCleanupAt());
    }

    @Test
    void newReadyDownloadStartsWithoutCleanupAt() {
        InviteInfo ready = invite(InviteSessionType.DOWNLOAD, InviteSessionStatus.ready);
        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        ReflectionTestUtils.setField(service, "userSetting", userSetting(10));

        service.updateInviteInfo(ready);

        assertNull(ready.getCleanupAt());
    }

    private static InviteStreamServiceImpl serviceWithValues(InviteInfo value) {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(hash.size(anyString())).thenReturn(1L);
        when(hash.values(anyString())).thenReturn(Collections.singletonList(value));
        InviteStreamServiceImpl service = spy(new InviteStreamServiceImpl());
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        return service;
    }

    private static InviteInfo invite(InviteSessionType type, InviteSessionStatus status) {
        SSRCInfo ssrcInfo = new SSRCInfo(1234, "00000001", "rtp", "stream-1");
        return InviteInfo.getInviteInfo("device-1", 1, "stream-1", ssrcInfo,
                "media-1", "127.0.0.1", 1234, "UDP", type, status);
    }

    private static com.genersoft.iot.vmp.conf.UserSetting userSetting(int timeout) {
        com.genersoft.iot.vmp.conf.UserSetting settings = mock(com.genersoft.iot.vmp.conf.UserSetting.class);
        when(settings.getPlayTimeout()).thenReturn(timeout);
        return settings;
    }
}
