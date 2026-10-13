package com.genersoft.iot.vmp.gb28181.session;

import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SipInviteSessionTransactionsTest {
    private static final String SERVER = "cleanup-test";
    private static final String EXPIRE = VideoManagerConstants.SIP_INVITE_SESSION_V2_EXPIRE + SERVER;
    private static final String PENDING = VideoManagerConstants.SIP_INVITE_SESSION_V2_PENDING_DELETE + SERVER;

    @Test
    void concurrentTransactionsAndCompensationPassStandaloneRegression() throws Exception {
        SipInviteSessionConcurrencyVerifier.main(new String[]{"extended"});
    }

    @Test
    void unrelatedExpiryWritesDoNotAbortCallIdDeletion() {
        Fixture f = new Fixture();
        f.session("call-a", "stream-a");
        when(f.operations.exec()).thenAnswer(invocation ->
                f.watched.contains(EXPIRE) ? null : List.of(1L));

        f.manager.removeByCallId("call-a");

        assertFalse(f.watched.contains(EXPIRE), "Unrelated ZSET members must not invalidate deletion");
        verify(f.operations, times(1)).exec();
        verify(f.zsets).remove(EXPIRE, "call-a");
    }

    @Test
    void watchConflictsAreRetriedAndExhaustionIsVisible() {
        Fixture f = new Fixture();
        f.session("call-a", "stream-a");
        when(f.operations.exec()).thenReturn(null);

        assertFalse(f.manager.removeByCallId("call-a"));
        verify(f.operations, times(3)).exec();
        verify(f.zsets).add(eq(PENDING), eq("call-a"), anyDouble());
    }

    @Test
    void emptyExecResultIsNotTreatedAsSuccessfulDeletion() {
        Fixture f = new Fixture();
        f.session("call-a", "stream-a");
        when(f.operations.exec()).thenReturn(List.of(), List.of(1L));

        f.manager.removeByCallId("call-a");

        verify(f.operations, times(2)).exec();
    }

    @Test
    void sessionCreationReportsExhaustedWatchConflicts() {
        Fixture f = new Fixture();
        SsrcTransaction session = f.session("call-a", "stream-a");
        when(f.operations.exec()).thenReturn(List.of());

        assertFalse(f.manager.put(session));
        verify(f.operations, times(3)).exec();
    }

    @Test
    void sessionCreationReportsConfirmedCommit() {
        Fixture f = new Fixture();
        SsrcTransaction session = f.session("call-a", "stream-a");
        assertTrue(f.manager.put(session));
    }

    @Test
    void earlyOwnerMismatchReleasesWatchWithoutDeletingReplacement() {
        Fixture f = new Fixture();
        f.session("call-a", "stream-a");
        when(f.operations.opsForValue().get(streamKey("stream-a"))).thenReturn("call-a", "replacement");

        f.manager.removeByStream("rtp", "stream-a");

        verify(f.operations, never()).multi();
        verify(f.connection, atLeastOnce()).unwatch();
        verify(f.operations, never()).delete(streamKey("stream-a"));
    }

    @Test
    void exceptionAfterMultiDiscardsConnectionBeforeRetry() {
        Fixture f = new Fixture();
        f.session("call-a", "stream-a");
        when(f.connection.isQueueing()).thenReturn(true);
        doThrow(new IllegalStateException("command failed")).when(f.operations).delete(anyString());

        assertFalse(f.manager.removeByCallId("call-a"));

        verify(f.connection, times(3)).discard();
        verify(f.connection, times(3)).unwatch();
    }

    @Test
    void renewalAndExpiryCleanupDoNotWatchSharedZset() {
        Fixture f = new Fixture();
        SsrcTransaction session = f.session("call-a", "stream-a");
        session.setExpireAt(1L);
        when(f.zsets.rangeByScore(eq(EXPIRE), eq(0D), anyDouble(), eq(0L), anyLong()))
                .thenReturn(Set.of("call-a"));
        when(f.zsets.score(EXPIRE, "call-a")).thenReturn(1D);

        assertEquals(1, f.manager.cleanupExpiredSessions(10));
        f.manager.getSsrcTransactionByCallId("call-a");

        assertFalse(f.watched.contains(EXPIRE));
    }

    @Test
    void renewedDataProtectsAgainstStaleExpiryScore() {
        Fixture f = new Fixture();
        f.session("call-a", "stream-a");
        when(f.zsets.rangeByScore(eq(EXPIRE), eq(0D), anyDouble(), eq(0L), anyLong()))
                .thenReturn(Set.of("call-a"));
        when(f.zsets.score(EXPIRE, "call-a")).thenReturn(1D);

        assertEquals(0, f.manager.cleanupExpiredSessions(10));

        verify(f.operations, never()).multi();
    }

    @Test
    void compensationDeletesOnlyOldCallIdAndClearsIntentAtomically() {
        Fixture f = new Fixture();
        f.session("call-a", "stream-a");
        f.store.put(streamKey("stream-a"), "replacement");
        when(f.zsets.rangeByScore(eq(PENDING), eq(0D), anyDouble(), eq(0L), anyLong()))
                .thenReturn(Set.of("call-a"));

        assertEquals(1, f.manager.retryPendingSessionDeletes(10));

        verify(f.operations).delete(dataKey("call-a"));
        verify(f.operations, never()).delete(streamKey("stream-a"));
        verify(f.zsets).remove(PENDING, "call-a");
    }

    @Test
    void compensationRemovesAlreadyMissingSessionIntent() {
        Fixture f = new Fixture();
        doReturn(mock(HashOperations.class)).when(f.redis).opsForHash();
        when(f.zsets.rangeByScore(eq(PENDING), eq(0D), anyDouble(), eq(0L), anyLong()))
                .thenReturn(Set.of("gone-call"));

        assertEquals(1, f.manager.retryPendingSessionDeletes(10));

        verify(f.zsets).remove(PENDING, "gone-call");
    }

    @Test
    void compensationBatchIsBounded() {
        Fixture f = new Fixture();

        assertEquals(0, f.manager.retryPendingSessionDeletes(Integer.MAX_VALUE));

        verify(f.zsets).rangeByScore(eq(PENDING), eq(0D), anyDouble(), eq(0L), eq(100L));
    }

    @Test
    void execCommandErrorsAreNotAcceptedAsSuccess() {
        Fixture f = new Fixture();
        f.session("call-a", "stream-a");
        when(f.operations.exec()).thenReturn(List.of(new IllegalStateException("WRONGTYPE")));

        assertFalse(f.manager.removeByCallId("call-a"));

        verify(f.operations, times(3)).exec();
    }

    @Test
    void terminalDeletionDoesNotRenewSessionBeforeRemovingIt() {
        Fixture f = new Fixture();
        f.session("call-a", "stream-a").setExpireAt(1L);

        f.manager.removeByCallId("call-a");

        verify(f.operations, times(1)).exec();
        verify(f.values, never()).set(anyString(), any(), anyLong(), any());
    }

    @Test
    void lookupFailureRegistersKnownCallIdForCompensation() {
        Fixture f = new Fixture();
        when(f.values.get(dataKey("call-a"))).thenThrow(new IllegalStateException("read unavailable"));

        assertFalse(f.manager.removeByCallId("call-a"));

        verify(f.zsets).add(eq(PENDING), eq("call-a"), anyDouble());
    }

    @Test
    void corruptCallIdIsNotReportedAsSuccessfulCompensation() {
        Fixture f = new Fixture();
        f.session("call-a", "stream-a").setCallId("different-call");
        when(f.zsets.rangeByScore(eq(PENDING), eq(0D), anyDouble(), eq(0L), anyLong()))
                .thenReturn(Set.of("call-a"));

        assertEquals(0, f.manager.retryPendingSessionDeletes(10));

        verify(f.operations, never()).delete(anyString());
        verify(f.zsets).add(eq(PENDING), eq("call-a"), anyDouble());
    }

    private static String dataKey(String call) {
        return VideoManagerConstants.SIP_INVITE_SESSION_V2_DATA + SERVER + ":" + call;
    }

    private static String streamKey(String stream) {
        return VideoManagerConstants.SIP_INVITE_SESSION_V2_STREAM + SERVER + ":rtp" + stream;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static class Fixture {
        final SipInviteSessionManager manager = new SipInviteSessionManager();
        final RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        final RedisOperations<String, Object> operations = mock(RedisOperations.class);
        final ValueOperations<String, Object> values = mock(ValueOperations.class);
        final SetOperations<String, Object> sets = mock(SetOperations.class);
        final ZSetOperations<String, Object> zsets = mock(ZSetOperations.class);
        final RedisConnection connection = mock(RedisConnection.class);
        final Map<String, Object> store = new HashMap<>();
        final List<String> watched = new ArrayList<>();

        Fixture() {
            UserSetting settings = mock(UserSetting.class);
            when(settings.getServerId()).thenReturn(SERVER);
            when(settings.getSipInviteSessionTtlSeconds()).thenReturn(3600L);
            ReflectionTestUtils.setField(manager, "userSetting", settings);
            ReflectionTestUtils.setField(manager, "redisTemplate", redis);
            when(redis.opsForValue()).thenReturn(values);
            when(redis.opsForZSet()).thenReturn(zsets);
            when(operations.opsForValue()).thenReturn(values);
            when(operations.opsForSet()).thenReturn(sets);
            when(operations.opsForZSet()).thenReturn(zsets);
            when(values.get(anyString())).thenAnswer(i -> store.get(i.getArgument(0)));
            doAnswer(i -> { watched.addAll((Collection<String>) i.getArgument(0)); return null; })
                    .when(operations).watch(any(Collection.class));
            when(operations.exec()).thenReturn(List.of(1L));
            when(redis.execute(any(SessionCallback.class))).thenAnswer(i ->
                    ((SessionCallback) i.getArgument(0)).execute(operations));
            when(redis.execute(any(RedisCallback.class))).thenAnswer(i ->
                    ((RedisCallback) i.getArgument(0)).doInRedis(connection));
        }

        SsrcTransaction session(String call, String stream) {
            SsrcTransaction session = new SsrcTransaction();
            session.setCallId(call);
            session.setDeviceId("device-" + call);
            session.setApp("rtp");
            session.setStream(stream);
            session.setSsrc("0200000001");
            session.setCreatedAt(System.currentTimeMillis());
            session.setExpireAt(System.currentTimeMillis() + 3600_000L);
            store.put(dataKey(call), session);
            store.put(streamKey(stream), call);
            return session;
        }
    }
}
