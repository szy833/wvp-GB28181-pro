package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionStatus;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import com.genersoft.iot.vmp.utils.redis.FastJsonRedisSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.Cursor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
        when(hash.scan(anyString(), any())).thenThrow(new IllegalStateException("redis unavailable"));
        InviteStreamServiceImpl service = spy(new InviteStreamServiceImpl());
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        assertDoesNotThrow(service::execute);
        verify(hash, never()).delete(any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void restoreInviteInfoDoesNotStartTransactionWhenFieldExists() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        RedisOperations<String, Object> operations = mock(RedisOperations.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        InviteInfo invite = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        when(redis.execute(any(SessionCallback.class))).thenAnswer(invocation ->
                ((SessionCallback) invocation.getArgument(0)).execute(operations));
        when(operations.opsForHash()).thenReturn((HashOperations) hash);
        when(hash.get(eq(VideoManagerConstants.INVITE_PREFIX), anyString())).thenReturn(invite);
        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        assertFalse(service.restoreInviteInfoIfAbsent(invite));

        verify(operations, never()).multi();
    }

    @Test
    void nonReadyUpdatePreservesCleanupAtWhenIncomingFieldIsNull() {
        InviteInfo existing = invite(InviteSessionType.DOWNLOAD, InviteSessionStatus.ok);
        existing.setCleanupAt(1_725_000_123_456L);
        InviteInfo incoming = invite(InviteSessionType.DOWNLOAD, InviteSessionStatus.ok);
        incoming.setStreamInfo(new StreamInfo());
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        RedisOperations<String, Object> operations = mock(RedisOperations.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        SetOperations<String, Object> set = mock(SetOperations.class);
        ZSetOperations<String, Object> zSet = mock(ZSetOperations.class);
        when(redis.execute(any(SessionCallback.class))).thenAnswer(invocation ->
                ((SessionCallback) invocation.getArgument(0)).execute(operations));
        when(operations.opsForHash()).thenReturn((HashOperations) hash);
        when(operations.opsForSet()).thenReturn((SetOperations) set);
        when(operations.opsForZSet()).thenReturn((ZSetOperations) zSet);
        when(hash.get(VideoManagerConstants.INVITE_PREFIX, "DOWNLOAD:1:stream-1")).thenReturn(existing);
        when(operations.exec()).thenReturn(Collections.singletonList(1L));
        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

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

    @Test
    void exactLookupUsesHashGetAndNeverScans() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        InviteInfo expected = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        when(redis.opsForHash()).thenReturn(hash);
        when(hash.get(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1")).thenReturn(expected);
        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        assertSame(expected, service.getInviteInfo(InviteSessionType.PLAY, 1, "stream-1"));

        verify(hash).get(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1");
        verify(hash, never()).scan(anyString(), any());
        verify(hash, never()).values(anyString());
    }

    @Test
    void exactLookupRejectsValueWhoseIdentityDoesNotMatchThePrimaryField() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        InviteInfo mismatched = invite(InviteSessionType.PLAYBACK, InviteSessionStatus.ok);
        when(redis.opsForHash()).thenReturn(hash);
        when(hash.get(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1")).thenReturn(mismatched);

        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        assertNull(service.getInviteInfo(InviteSessionType.PLAY, 1, "stream-1"));
        verify(hash, never()).scan(anyString(), any());
    }

    @Test
    void removeAllInviteInfoUsesOwnerSafeRemovalInsteadOfDeletingOnlyThePrimaryHash() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        InviteInfo first = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        InviteInfo second = invite(InviteSessionType.PLAYBACK, InviteSessionStatus.ok);
        InviteStreamServiceImpl service = spy(new InviteStreamServiceImpl());
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        doReturn(List.of(first, second)).when(service).getAllInviteInfo();
        doReturn(true).when(service).removeInviteInfoIfSame(any(InviteInfo.class));

        service.removeInviteInfo(null, null, null);

        verify(redis, never()).delete(VideoManagerConstants.INVITE_PREFIX);
        verify(service).removeInviteInfoIfSame(first);
        verify(service).removeInviteInfoIfSame(second);
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedPrimaryValueIsNotDeletedWithoutDerivedIndexCleanup() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        Cursor<Map.Entry<Object, Object>> cursor = mock(Cursor.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(hash.scan(anyString(), any())).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(new java.util.AbstractMap.SimpleEntry<>(
                "PLAY:1:stream-1", "malformed"));
        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        assertNull(service.getInviteInfo(null, 1, "stream-1"));

        verify(hash, never()).delete(any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void conditionalRemovalDeletesPrimaryAndAllDerivedMembershipsTogether() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        RedisOperations<String, Object> operations = mock(RedisOperations.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        SetOperations<String, Object> set = mock(SetOperations.class);
        ZSetOperations<String, Object> zSet = mock(ZSetOperations.class);
        InviteInfo expected = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        expected.setStreamInfo(activeStreamInfo("media-1"));
        when(redis.execute(any(SessionCallback.class))).thenAnswer(invocation ->
                ((SessionCallback) invocation.getArgument(0)).execute(operations));
        when(operations.opsForHash()).thenReturn((HashOperations) hash);
        when(operations.opsForSet()).thenReturn((SetOperations) set);
        when(operations.opsForZSet()).thenReturn((ZSetOperations) zSet);
        when(hash.get(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1")).thenReturn(expected);
        when(operations.exec()).thenReturn(Collections.singletonList(1L));
        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        assertTrue(service.removeInviteInfoIfSame(expected));

        verify(operations).multi();
        verify(hash).delete(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1");
        verify(set).remove("VMP_GB_INVITE_INDEX_CHANNEL:PLAY:1", "PLAY:1:stream-1");
        verify(set).remove("VMP_GB_INVITE_INDEX_STREAM:stream-1", "PLAY:1:stream-1");
        verify(set).remove("VMP_GB_INVITE_INDEX_SSRC:00000001", "PLAY:1:stream-1");
        verify(set).remove("VMP_GB_INVITE_INDEX_DEVICE:device-1", "PLAY:1:stream-1");
        verify(set).remove("VMP_GB_INVITE_ACTIVE_MEDIA:media-1", "PLAY:1:stream-1");
        verify(zSet).remove(VideoManagerConstants.INVITE_EXPIRE_AT, "PLAY:1:stream-1");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void updateWritesPrimaryAndDerivedMembershipsInOneTransaction() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        RedisOperations<String, Object> operations = mock(RedisOperations.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        SetOperations<String, Object> set = mock(SetOperations.class);
        ZSetOperations<String, Object> zSet = mock(ZSetOperations.class);
        InviteInfo invite = invite(InviteSessionType.PLAY, InviteSessionStatus.ready);
        when(redis.execute(any(SessionCallback.class))).thenAnswer(invocation ->
                ((SessionCallback) invocation.getArgument(0)).execute(operations));
        when(operations.opsForHash()).thenReturn((HashOperations) hash);
        when(operations.opsForSet()).thenReturn((SetOperations) set);
        when(operations.opsForZSet()).thenReturn((ZSetOperations) zSet);
        when(hash.get(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1")).thenReturn(null);
        when(operations.exec()).thenReturn(Collections.singletonList(1L));
        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        service.updateInviteInfo(invite, 1_000L);

        verify(operations).multi();
        verify(hash).put(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1", invite);
        verify(set).add("VMP_GB_INVITE_INDEX_CHANNEL:PLAY:1", "PLAY:1:stream-1");
        verify(set).add("VMP_GB_INVITE_INDEX_STREAM:stream-1", "PLAY:1:stream-1");
        verify(set).add("VMP_GB_INVITE_INDEX_SSRC:00000001", "PLAY:1:stream-1");
        verify(set).add("VMP_GB_INVITE_INDEX_DEVICE:device-1", "PLAY:1:stream-1");
        verify(zSet).add(VideoManagerConstants.INVITE_EXPIRE_AT, "PLAY:1:stream-1",
                invite.getCreateTime() + invite.getExpirationTime());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void nonReadyUpdateReplacesOldSsrcAndMediaMemberships() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        RedisOperations<String, Object> operations = mock(RedisOperations.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        SetOperations<String, Object> set = mock(SetOperations.class);
        ZSetOperations<String, Object> zSet = mock(ZSetOperations.class);
        InviteInfo existing = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        existing.setStreamInfo(activeStreamInfo("media-1"));
        InviteInfo incoming = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        incoming.setSsrcInfo(new SSRCInfo(1234, "00000002", "rtp", "stream-1"));
        incoming.setStreamInfo(activeStreamInfo("media-2"));
        when(redis.execute(any(SessionCallback.class))).thenAnswer(invocation ->
                ((SessionCallback) invocation.getArgument(0)).execute(operations));
        when(operations.opsForHash()).thenReturn((HashOperations) hash);
        when(operations.opsForSet()).thenReturn((SetOperations) set);
        when(operations.opsForZSet()).thenReturn((ZSetOperations) zSet);
        when(hash.get(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1")).thenReturn(existing);
        when(operations.exec()).thenReturn(Collections.singletonList(1L));
        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        service.updateInviteInfo(incoming, null);

        verify(set).remove("VMP_GB_INVITE_INDEX_SSRC:00000001", "PLAY:1:stream-1");
        verify(set).remove("VMP_GB_INVITE_ACTIVE_MEDIA:media-1", "PLAY:1:stream-1");
        verify(set).add("VMP_GB_INVITE_INDEX_SSRC:00000002", "PLAY:1:stream-1");
        verify(set).add("VMP_GB_INVITE_ACTIVE_MEDIA:media-2", "PLAY:1:stream-1");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void streamMigrationMovesPrimaryAndDerivedMembershipsInOneTransaction() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        RedisOperations<String, Object> operations = mock(RedisOperations.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        SetOperations<String, Object> set = mock(SetOperations.class);
        ZSetOperations<String, Object> zSet = mock(ZSetOperations.class);
        InviteInfo existing = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        existing.setStreamInfo(activeStreamInfo("media-1"));
        when(redis.execute(any(SessionCallback.class))).thenAnswer(invocation ->
                ((SessionCallback) invocation.getArgument(0)).execute(operations));
        when(operations.opsForHash()).thenReturn((HashOperations) hash);
        when(operations.opsForSet()).thenReturn((SetOperations) set);
        when(operations.opsForZSet()).thenReturn((ZSetOperations) zSet);
        when(hash.get(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1")).thenReturn(existing);
        when(operations.exec()).thenReturn(Collections.singletonList(1L));
        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        InviteInfo moved = service.updateInviteInfoForStream(existing, "stream-2");

        assertSame(existing, moved);
        verify(operations).multi();
        verify(hash).delete(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1");
        verify(set).remove("VMP_GB_INVITE_INDEX_STREAM:stream-1", "PLAY:1:stream-1");
        verify(set).add("VMP_GB_INVITE_INDEX_STREAM:stream-2", "PLAY:1:stream-2");
        verify(hash).put(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-2", existing);
        verify(zSet).remove(VideoManagerConstants.INVITE_EXPIRE_AT, "PLAY:1:stream-1");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ssrcUpdateReplacesOnlyTheDerivedSsrcMembershipInOneTransaction() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        RedisOperations<String, Object> operations = mock(RedisOperations.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        SetOperations<String, Object> set = mock(SetOperations.class);
        ZSetOperations<String, Object> zSet = mock(ZSetOperations.class);
        InviteInfo existing = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        when(redis.execute(any(SessionCallback.class))).thenAnswer(invocation ->
                ((SessionCallback) invocation.getArgument(0)).execute(operations));
        when(operations.opsForHash()).thenReturn((HashOperations) hash);
        when(operations.opsForSet()).thenReturn((SetOperations) set);
        when(operations.opsForZSet()).thenReturn((ZSetOperations) zSet);
        when(hash.get(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1")).thenReturn(existing);
        when(operations.exec()).thenReturn(Collections.singletonList(1L));
        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        InviteInfo updated = service.updateInviteInfoForSSRC(existing, "00000002");

        assertSame(existing, updated);
        verify(operations).multi();
        verify(set).remove("VMP_GB_INVITE_INDEX_SSRC:00000001", "PLAY:1:stream-1");
        verify(set).add("VMP_GB_INVITE_INDEX_SSRC:00000002", "PLAY:1:stream-1");
        verify(hash).put(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1", existing);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void restoreIfAbsentWritesPrimaryAndIndexesInOneTransaction() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        RedisOperations<String, Object> operations = mock(RedisOperations.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        SetOperations<String, Object> set = mock(SetOperations.class);
        ZSetOperations<String, Object> zSet = mock(ZSetOperations.class);
        InviteInfo invite = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        when(redis.execute(any(SessionCallback.class))).thenAnswer(invocation ->
                ((SessionCallback) invocation.getArgument(0)).execute(operations));
        when(operations.opsForHash()).thenReturn((HashOperations) hash);
        when(operations.opsForSet()).thenReturn((SetOperations) set);
        when(operations.opsForZSet()).thenReturn((ZSetOperations) zSet);
        when(hash.get(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1")).thenReturn(null);
        when(operations.exec()).thenReturn(Collections.singletonList(1L));
        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        assertTrue(service.restoreInviteInfoIfAbsent(invite));

        verify(operations).multi();
        verify(hash).put(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1", invite);
        verify(set).add("VMP_GB_INVITE_INDEX_CHANNEL:PLAY:1", "PLAY:1:stream-1");
        verify(set).add("VMP_GB_INVITE_INDEX_STREAM:stream-1", "PLAY:1:stream-1");
        verify(set).add("VMP_GB_INVITE_INDEX_SSRC:00000001", "PLAY:1:stream-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void channelLookupUsesOnlyItsIndexAfterActivation() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        SetOperations<String, Object> set = mock(SetOperations.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        Cursor<Object> cursor = mock(Cursor.class);
        InviteInfo expected = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        when(redis.opsForHash()).thenReturn(hash);
        when(redis.opsForSet()).thenReturn(set);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(VideoManagerConstants.INVITE_INDEX_READY)).thenReturn("ready");
        when(set.scan(eq("VMP_GB_INVITE_INDEX_CHANNEL:PLAY:1"), any())).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("PLAY:1:stream-1");
        when(hash.get(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1")).thenReturn(expected);
        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        assertSame(expected, service.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, 1));

        verify(hash, never()).scan(anyString(), any());
        verify(hash, never()).values(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void indexReadFailureFallsBackToTheAuthoritativeHash() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        SetOperations<String, Object> set = mock(SetOperations.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        Cursor<Map.Entry<Object, Object>> cursor = mock(Cursor.class);
        InviteInfo expected = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        when(redis.opsForHash()).thenReturn(hash);
        when(redis.opsForSet()).thenReturn(set);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(VideoManagerConstants.INVITE_INDEX_READY)).thenReturn("ready");
        when(set.scan(eq("VMP_GB_INVITE_INDEX_CHANNEL:PLAY:1"), any()))
                .thenThrow(new IllegalStateException("index unavailable"));
        when(hash.scan(eq(VideoManagerConstants.INVITE_PREFIX), any())).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(new java.util.AbstractMap.SimpleEntry<>(
                "PLAY:1:stream-1", expected));

        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        assertSame(expected, service.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, 1));

        verify(hash).scan(eq(VideoManagerConstants.INVITE_PREFIX), any());
    }

    @Test
    void streamInfoCountUsesActiveSetCardinality() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        SetOperations<String, Object> set = mock(SetOperations.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForSet()).thenReturn(set);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(VideoManagerConstants.INVITE_INDEX_READY)).thenReturn("ready");
        when(set.size("VMP_GB_INVITE_ACTIVE_MEDIA:media-1")).thenReturn(7L);
        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        assertEquals(7, service.getStreamInfoCount("media-1"));

        verify(set).size("VMP_GB_INVITE_ACTIVE_MEDIA:media-1");
        verify(redis, never()).opsForHash();
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeIndexReadFailureFallsBackToTheAuthoritativeHash() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        SetOperations<String, Object> set = mock(SetOperations.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        Cursor<Map.Entry<Object, Object>> cursor = mock(Cursor.class);
        InviteInfo expected = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        expected.setStreamInfo(activeStreamInfo("media-1"));
        when(redis.opsForSet()).thenReturn(set);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hash);
        when(values.get(VideoManagerConstants.INVITE_INDEX_READY)).thenReturn("ready");
        when(set.scan(eq("VMP_GB_INVITE_ACTIVE_MEDIA:media-1"), any()))
                .thenThrow(new IllegalStateException("index unavailable"));
        when(hash.scan(eq(VideoManagerConstants.INVITE_PREFIX), any())).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(new java.util.AbstractMap.SimpleEntry<>(
                "PLAY:1:stream-1", expected));

        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        assertEquals(List.of(expected), service.getActiveInviteInfoByMediaServer("media-1"));
        verify(hash).scan(eq(VideoManagerConstants.INVITE_PREFIX), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void indexRepairFailureDoesNotHideAuthoritativeLookupResult() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        SetOperations<String, Object> set = mock(SetOperations.class);
        Cursor<Object> indexCursor = mock(Cursor.class);
        Cursor<Map.Entry<Object, Object>> primaryCursor = mock(Cursor.class);
        InviteInfo expected = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        when(redis.opsForSet()).thenReturn(set);
        when(redis.opsForHash()).thenReturn(hash);
        when(set.scan(eq("VMP_GB_INVITE_INDEX_CHANNEL:PLAY:1"), any())).thenReturn(indexCursor);
        when(indexCursor.hasNext()).thenReturn(false);
        when(hash.scan(eq(VideoManagerConstants.INVITE_PREFIX), any())).thenReturn(primaryCursor);
        when(primaryCursor.hasNext()).thenReturn(true, false);
        when(primaryCursor.next()).thenReturn(new java.util.AbstractMap.SimpleEntry<>(
                "PLAY:1:stream-1", expected));
        when(redis.execute(any(SessionCallback.class))).thenThrow(new IllegalStateException("repair failed"));

        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        assertSame(expected, service.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, 1));
    }

    @Test
    void expiryUsesBoundedDueMembersAndNeverHvalsAfterActivation() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        ZSetOperations<String, Object> zSet = mock(ZSetOperations.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        InviteInfo expired = invite(InviteSessionType.PLAY, InviteSessionStatus.ready);
        expired.setCreateTime(System.currentTimeMillis() - 2_000L);
        expired.setExpirationTime(1_000L);
        when(redis.opsForHash()).thenReturn(hash);
        when(redis.opsForZSet()).thenReturn(zSet);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(VideoManagerConstants.INVITE_INDEX_READY)).thenReturn("ready");
        when(zSet.rangeByScore(eq(VideoManagerConstants.INVITE_EXPIRE_AT), eq(0D), anyDouble(), eq(0L), eq(200L)))
                .thenReturn(Collections.singleton("PLAY:1:stream-1"));
        when(hash.get(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1")).thenReturn(expired);
        InviteStreamServiceImpl service = spy(new InviteStreamServiceImpl());
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        doReturn(true).when(service).removeInviteInfoIfSame(expired);

        service.execute();

        verify(zSet).rangeByScore(eq(VideoManagerConstants.INVITE_EXPIRE_AT), eq(0D), anyDouble(), eq(0L), eq(200L));
        verify(hash, never()).values(VideoManagerConstants.INVITE_PREFIX);
        verify(service).removeInviteInfoIfSame(expired);
    }

    @Test
    void backfilledMarkerDoesNotEnableIndexOnlyReads() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(VideoManagerConstants.INVITE_INDEX_READY)).thenReturn("backfilled");
        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        assertFalse(service.inviteIndexesReady());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void readyModeWatchesThePerFieldVersionInsteadOfThePrimaryHash() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        RedisOperations<String, Object> operations = mock(RedisOperations.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        SetOperations<String, Object> set = mock(SetOperations.class);
        ZSetOperations<String, Object> zSet = mock(ZSetOperations.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        InviteInfo invite = invite(InviteSessionType.PLAY, InviteSessionStatus.ready);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(VideoManagerConstants.INVITE_INDEX_READY)).thenReturn("ready");
        when(redis.execute(any(SessionCallback.class))).thenAnswer(invocation ->
                ((SessionCallback) invocation.getArgument(0)).execute(operations));
        when(operations.opsForHash()).thenReturn((HashOperations) hash);
        when(operations.opsForSet()).thenReturn((SetOperations) set);
        when(operations.opsForZSet()).thenReturn((ZSetOperations) zSet);
        when(hash.get(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1")).thenReturn(invite);
        when(operations.exec()).thenReturn(Collections.singletonList(1L));

        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        UserSetting userSetting = mock(UserSetting.class);
        when(userSetting.getPlayTimeout()).thenReturn(30);
        ReflectionTestUtils.setField(service, "userSetting", userSetting);

        service.updateInviteInfo(invite);

        verify(operations).watch("VMP_GB_INVITE_VERSION:PLAY:1:stream-1");
        verify(operations, never()).watch(VideoManagerConstants.INVITE_PREFIX);
    }

    @Test
    @SuppressWarnings("unchecked")
    void incompletePrimaryScanDoesNotDeleteThePartialResult() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        Cursor<Map.Entry<Object, Object>> cursor = mock(Cursor.class);
        InviteInfo invite = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        when(redis.opsForHash()).thenReturn(hash);
        when(hash.scan(eq(VideoManagerConstants.INVITE_PREFIX), any())).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true).thenThrow(new IllegalStateException("scan interrupted"));
        when(cursor.next()).thenReturn(new java.util.AbstractMap.SimpleEntry<>("PLAY:1:stream-1", invite));
        InviteStreamServiceImpl service = spy(new InviteStreamServiceImpl());
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        service.clearInviteInfo("device-1");

        verify(service, never()).removeInviteInfoIfSame(any(InviteInfo.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void indexedMemberMustMatchThePrimaryFieldBeforeItIsReturned() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        SetOperations<String, Object> set = mock(SetOperations.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        Cursor<Object> cursor = mock(Cursor.class);
        InviteInfo invite = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        when(redis.opsForHash()).thenReturn(hash);
        when(redis.opsForSet()).thenReturn(set);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(VideoManagerConstants.INVITE_INDEX_READY)).thenReturn("ready");
        when(set.scan(eq("VMP_GB_INVITE_INDEX_CHANNEL:PLAY:1"), any())).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("PLAY:1:stale-field");
        when(hash.get(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stale-field")).thenReturn(invite);

        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        assertNull(service.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, 1));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void streamMigrationRemovesDestinationMembershipsBeforeOverwrite() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        RedisOperations<String, Object> operations = mock(RedisOperations.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        SetOperations<String, Object> set = mock(SetOperations.class);
        ZSetOperations<String, Object> zSet = mock(ZSetOperations.class);
        InviteInfo source = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        InviteInfo destination = invite(InviteSessionType.PLAY, InviteSessionStatus.ok);
        destination.setStream("stream-2");
        destination.getSsrcInfo().setStream("stream-2");
        when(redis.execute(any(SessionCallback.class))).thenAnswer(invocation ->
                ((SessionCallback) invocation.getArgument(0)).execute(operations));
        when(operations.opsForHash()).thenReturn((HashOperations) hash);
        when(operations.opsForSet()).thenReturn((SetOperations) set);
        when(operations.opsForZSet()).thenReturn((ZSetOperations) zSet);
        when(hash.get(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-1"))
                .thenReturn(source);
        when(hash.get(VideoManagerConstants.INVITE_PREFIX, "PLAY:1:stream-2"))
                .thenReturn(destination);
        when(operations.exec()).thenReturn(Collections.singletonList(1L));
        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        service.updateInviteInfoForStream(source, "stream-2");

        verify(set).remove("VMP_GB_INVITE_INDEX_STREAM:stream-2", "PLAY:1:stream-2");
        verify(set).remove("VMP_GB_INVITE_INDEX_SSRC:00000001", "PLAY:1:stream-2");
    }

    private static InviteStreamServiceImpl serviceWithValues(InviteInfo value) {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        Cursor<Map.Entry<Object, Object>> cursor = mock(Cursor.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(hash.scan(anyString(), any())).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(new java.util.AbstractMap.SimpleEntry<>("PLAY:1:stream-1", value));
        InviteStreamServiceImpl service = spy(new InviteStreamServiceImpl());
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        return service;
    }

    private static InviteInfo invite(InviteSessionType type, InviteSessionStatus status) {
        SSRCInfo ssrcInfo = new SSRCInfo(1234, "00000001", "rtp", "stream-1");
        return InviteInfo.getInviteInfo("device-1", 1, "stream-1", ssrcInfo,
                "media-1", "127.0.0.1", 1234, "UDP", type, status);
    }

    private static StreamInfo activeStreamInfo(String mediaServerId) {
        com.genersoft.iot.vmp.media.bean.MediaServer mediaServer = new com.genersoft.iot.vmp.media.bean.MediaServer();
        mediaServer.setId(mediaServerId);
        StreamInfo streamInfo = new StreamInfo();
        streamInfo.setMediaServer(mediaServer);
        streamInfo.setProgress(0.5);
        return streamInfo;
    }

    private static com.genersoft.iot.vmp.conf.UserSetting userSetting(int timeout) {
        com.genersoft.iot.vmp.conf.UserSetting settings = mock(com.genersoft.iot.vmp.conf.UserSetting.class);
        when(settings.getPlayTimeout()).thenReturn(timeout);
        return settings;
    }
}
