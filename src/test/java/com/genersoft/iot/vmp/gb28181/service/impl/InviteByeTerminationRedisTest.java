package com.genersoft.iot.vmp.gb28181.service.impl;

import com.alibaba.fastjson2.JSON;
import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.redis.RedisTemplateConfig;
import com.genersoft.iot.vmp.gb28181.bean.SipTransactionInfo;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class InviteByeTerminationRedisTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.0"))
            .withExposedPorts(6379);
    LettuceConnectionFactory connection;
    RedisTemplate<String, Object> redis;
    UserSetting settings;
    InviteRepository repository;
    InviteInfoCleanupStore cleanup;

    @BeforeEach void connect() {
        connection = new LettuceConnectionFactory("127.0.0.1", REDIS.getMappedPort(6379));
        connection.afterPropertiesSet();
        redis = new RedisTemplateConfig().redisTemplate(connection);
        redis.afterPropertiesSet();
        settings = new UserSetting(); settings.setServerId("bye-test-" + UUID.randomUUID());
        repository = new InviteRepository(redis, settings);
        cleanup = new InviteInfoCleanupStore(redis, settings);
    }
    @AfterEach void disconnect() { connection.destroy(); }

    InviteInfo accepted(boolean expired) {
        var invite = InviteInfo.prepare("device", 1, InviteSessionType.PLAY, "PLAY:1", "media", "UDP", 60_000);
        invite.setCallId("call-" + invite.getInviteId());
        invite.setSipAccepted(true); invite.setSipFinal(true); invite.setSipSent(true);
        var dialog = new SipTransactionInfo(); dialog.setCallId(invite.getCallId()); invite.setSipDialog(dialog);
        if (expired) expireBye(invite);
        repository.createAndClaim(invite).requireApplied();
        return invite;
    }
    InviteInfoCleanupStore.Job stopped(InviteInfo invite) {
        repository.beginStop(invite, false).requireApplied();
        return Objects.requireNonNull(cleanup.claim(invite.getInviteId()));
    }
    void closeLocalResources(InviteInfoCleanupStore.Job job) {
        assertTrue(cleanup.completeStep(job, "RTP"));
        assertTrue(cleanup.completeStep(job, "STATE"));
    }
    long redisNow() {
        return repository.eval("local t=redis.call('TIME'); return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)", List.of());
    }
    void expireBye(InviteInfo invite) {
        invite.setByeClockSource("REDIS"); invite.setByeOperationId(UUID.randomUUID().toString());
        invite.setByeFirstSentAt(redisNow() - 60_000); invite.setByeSentAt(invite.getByeFirstSentAt());
    }
    void allowRetry(String id) {
        // Backdate only the retry fixture in the isolated Redis; the first attempt remains immutable.
        var value = repository.get(id); value.setByeSentAt(redisNow() - 11_000);
        redis.execute((org.springframework.data.redis.core.RedisCallback<Boolean>) c ->
                c.stringCommands().set(InviteRepository.bytes(repository.recordKey(id)), JSON.toJSONBytes(value)));
    }
    InviteRepository skewedRepository(long offset) {
        return new InviteRepository(redis, settings) {
            @Override protected long applicationTimeMillis() { return System.currentTimeMillis() + offset; }
        };
    }
    void saveFixture(InviteInfo value) {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Boolean>) c ->
                c.stringCommands().set(InviteRepository.bytes(repository.recordKey(value.getInviteId())), JSON.toJSONBytes(value)));
    }
    long pendingScore(String id) {
        return repository.eval("return tonumber(redis.call('ZSCORE',KEYS[1],ARGV[1]))", List.of(repository.pendingKey()), InviteRepository.bytes(id));
    }
    void assertServerInterval(long value, long before, long after) {
        assertTrue(value >= before && value <= after, value + " outside Redis interval " + before + ".." + after);
    }

    @Test void bothApplicationClockDirectionsLeaveByeAndPendingInRedisTime() {
        for (long offset : List.of(-120_000L, 120_000L)) {
            var invite = accepted(false); var skewed = skewedRepository(offset);
            long before = redisNow();
            skewed.beginStop(invite, false).requireApplied();
            assertServerInterval(pendingScore(invite.getInviteId()), before, redisNow());
            assertTrue(skewed.pending(10).contains(invite.getInviteId()));
            var job = Objects.requireNonNull(cleanup.claim(invite.getInviteId()));
            before = redisNow();
            var attempted = skewed.beginByeAttempt(invite.getInviteId(), job.token()).requireApplied();
            assertServerInterval(attempted.getByeFirstSentAt(), before, redisNow());
            assertEquals(attempted.getByeFirstSentAt(), attempted.getByeSentAt());
            closeLocalResources(job);
            assertEquals(InviteWriteResult.Code.STATE_CONFLICT, skewed.terminateByeLocally(invite.getInviteId(), job.token()).code());
            expireBye(attempted); saveFixture(attempted);
            before = redisNow();
            var ended = skewed.terminateByeLocally(invite.getInviteId(), job.token()).requireApplied();
            assertServerInterval(ended.getByeTerminatedLocallyAt(), before, redisNow());
            assertTrue(cleanup.completeStep(job, "SIP")); assertTrue(cleanup.finish(job));
        }
    }

    @Test void wrongLeaseCannotCreateAByeClockOrSendPermission() {
        var invite = accepted(false); var job = stopped(invite);
        assertEquals(InviteWriteResult.Code.STATE_CONFLICT, repository.beginByeAttempt(invite.getInviteId(), "wrong").code());
        assertNull(repository.get(invite.getInviteId()).getByeFirstSentAt());
        assertTrue(repository.beginByeAttempt(invite.getInviteId(), job.token()).applied());
    }

    @Test void concurrentAttemptsGrantExactlyOnePermission() throws Exception {
        var invite = accepted(false); var job = stopped(invite);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
            List<Future<InviteWriteResult>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(workers.submit(() -> {
                start.await(); return repository.beginByeAttempt(invite.getInviteId(), job.token());
            }));
            start.countDown();
            int permitted = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS).applied()) permitted++;
            assertEquals(1, permitted);
        }
    }

    @Test void committedAttemptResponseLossConfirmsStampedFactsNotPlaceholderJson() {
        var invite = accepted(false); var job = stopped(invite);
        var responseLoss = new InviteRepository(redis, settings) {
            @Override public long eval(String script, List<String> keys, byte[]... args) {
                super.eval(script, keys, args);
                throw new IllegalStateException("response lost after apply");
            }
        };
        var result = responseLoss.beginByeAttempt(invite.getInviteId(), job.token());
        assertEquals(InviteWriteResult.Code.ALREADY_APPLIED, result.code());
        assertTrue(result.invite().getByeFirstSentAt() > 0);
        assertEquals(result.invite().getByeFirstSentAt(), result.invite().getByeSentAt());
        assertEquals(InviteWriteResult.Code.STATE_CONFLICT, repository.beginByeAttempt(invite.getInviteId(), job.token()).code());
    }

    @Test void failedRedisWriteCannotFallBackToApplicationTimeOrAuthorizeSend() {
        var invite = accepted(false); var job = stopped(invite);
        var unavailable = new InviteRepository(redis, settings) {
            @Override public long eval(String script, List<String> keys, byte[]... args) {
                throw new IllegalStateException("request never reached Redis");
            }
        };
        assertThrows(IllegalStateException.class, () -> unavailable.beginByeAttempt(invite.getInviteId(), job.token()));
        assertNull(repository.get(invite.getInviteId()).getByeFirstSentAt());
        assertNull(repository.get(invite.getInviteId()).getByeOperationId());
    }

    @Test void oldActiveTimestampWithoutSourceIsRejectedRatherThanReinterpreted() {
        var invite = accepted(true); invite.setByeClockSource(null); saveFixture(invite);
        var job = stopped(invite); closeLocalResources(job);
        long original = invite.getByeFirstSentAt();
        assertThrows(IllegalStateException.class, () -> repository.beginByeAttempt(invite.getInviteId(), job.token()));
        assertThrows(IllegalStateException.class, () -> repository.terminateByeLocally(invite.getInviteId(), job.token()));
        assertEquals(original, repository.get(invite.getInviteId()).getByeFirstSentAt());
        assertNull(repository.get(invite.getInviteId()).getByeTerminatedLocallyAt());
    }

    @Test void claimRenewDeferAndDueScanShareRedisTime() {
        var invite = accepted(false); repository.beginStop(invite, false).requireApplied();
        assertTrue(cleanup.pending(10).contains(invite.getInviteId()));
        long before = redisNow(); var job = Objects.requireNonNull(cleanup.claim(invite.getInviteId()));
        assertServerInterval(pendingScore(job.id()) - 30_000, before, redisNow());
        assertTrue(cleanup.pending(10).isEmpty());
        before = redisNow(); assertTrue(cleanup.completeStep(job, "RTP"));
        assertServerInterval(pendingScore(job.id()) - 30_000, before, redisNow());
        before = redisNow(); cleanup.defer(job);
        long score = pendingScore(job.id());
        assertTrue(score >= before + 2_000 && score <= redisNow() + 2_500);
        assertTrue(cleanup.pending(10).isEmpty());
        assertNotNull(cleanup.claim(job.id()), "Deferral must release the old lease");
        assertTrue(cleanup.pending(0).isEmpty());
    }

    @Test void deferIsCappedAtRemainingByeWindowOnlyAfterResourceConfirmation() {
        var invite = accepted(true);
        invite.setByeFirstSentAt(redisNow() - InviteRepository.BYE_WAIT_MILLIS + 5_000);
        saveFixture(invite); var job = stopped(invite); closeLocalResources(job);
        repository.eval("redis.call('HSET',KEYS[1],'attempts',10);return 1", List.of(repository.jobKey(job.id())));
        cleanup.defer(job);
        assertEquals(invite.getByeFirstSentAt() + InviteRepository.BYE_WAIT_MILLIS, pendingScore(job.id()));
        job = Objects.requireNonNull(cleanup.claim(job.id()));
        var expired = repository.get(invite.getInviteId()); expireBye(expired); saveFixture(expired);
        long before = redisNow(); cleanup.defer(job);
        assertServerInterval(pendingScore(job.id()) - 1_000, before, redisNow());
    }

    @Test void unverifiedResourcesKeepNormalBackoffEvenAfterByeDeadline() {
        var invite = accepted(true); var job = stopped(invite);
        repository.eval("redis.call('HSET',KEYS[1],'attempts',10);return 1", List.of(repository.jobKey(job.id())));
        long before = redisNow(); cleanup.defer(job);
        assertTrue(pendingScore(job.id()) >= before + 60_000);
        assertTrue(pendingScore(job.id()) <= redisNow() + 60_500);
    }

    @Test void scalarStampPreservesNestedMediaLongPrecisionAndEscapedText() {
        var invite = accepted(false);
        var media = new com.genersoft.iot.vmp.media.bean.MediaInfo();
        media.setDuration(9_007_199_254_740_993L);
        media.setOriginUrl("text \"byeSentAt\":-1, [] {}");
        var stream = new StreamInfo(); stream.setMediaInfo(media); invite.setStreamInfo(stream); saveFixture(invite);
        var job = stopped(invite);
        var result = repository.beginByeAttempt(invite.getInviteId(), job.token()).requireApplied();
        assertEquals(media.getDuration(), result.getStreamInfo().getMediaInfo().getDuration());
        assertEquals(media.getOriginUrl(), result.getStreamInfo().getMediaInfo().getOriginUrl());
    }

    @Test void wrongLeaseCannotRenewOrDeferAnotherWorkersSchedule() {
        var invite = accepted(false); var job = stopped(invite);
        var wrong = new InviteInfoCleanupStore.Job(job.id(), job.invite(), "wrong", job.phase(), true, false, job.callId(), job.completed());
        long score = pendingScore(job.id());
        assertFalse(cleanup.completeStep(wrong, "RTP")); cleanup.defer(wrong);
        assertEquals(score, pendingScore(job.id()));
        assertFalse(cleanup.refresh(job).completed().contains("RTP"));
        assertTrue(cleanup.ownsResources(job));
    }

    @Test void peerByeSkipsClockCreationAndLocalTermination() {
        var invite = accepted(false); repository.beginStop(invite, true).requireApplied();
        var job = Objects.requireNonNull(cleanup.claim(invite.getInviteId())); closeLocalResources(job);
        assertEquals(InviteWriteResult.Code.STATE_CONFLICT, repository.beginByeAttempt(invite.getInviteId(), job.token()).code());
        assertFalse(cleanup.terminateByeLocally(cleanup.refresh(job)));
        assertNull(repository.get(invite.getInviteId()).getByeFirstSentAt());
    }

    @Test void firstAttemptDeclaresRedisClockAndPersistsAnOperationIdentity() {
        var invite = accepted(false); var job = stopped(invite);
        repository.beginByeAttempt(invite.getInviteId(), job.token()).requireApplied();
        var persisted = JSON.parseObject((byte[]) redis.execute(
                (org.springframework.data.redis.core.RedisCallback<byte[]>) c ->
                        c.stringCommands().get(InviteRepository.bytes(repository.recordKey(invite.getInviteId())))));
        assertEquals("REDIS", persisted.getString("byeClockSource"));
        assertNotNull(persisted.getString("byeOperationId"));
    }

    @Test void immediateSecondAttemptDoesNotAcquireSendPermission() {
        var invite = accepted(false); var job = stopped(invite);
        repository.beginByeAttempt(invite.getInviteId(), job.token()).requireApplied();
        assertEquals(InviteWriteResult.Code.STATE_CONFLICT, repository.beginByeAttempt(invite.getInviteId(), job.token()).code());
    }

    @Test void firstAttemptSurvivesRepositoryRecreationAndRetries() {
        var invite = accepted(false);
        var job = stopped(invite);
        long first = repository.beginByeAttempt(invite.getInviteId(), job.token()).requireApplied().getByeFirstSentAt();
        allowRetry(invite.getInviteId());
        var restarted = new InviteRepository(redis, settings);
        restarted.beginByeAttempt(invite.getInviteId(), job.token()).requireApplied();
        assertEquals(first, restarted.get(invite.getInviteId()).getByeFirstSentAt());
        assertNull(restarted.get(invite.getInviteId()).getByeTerminatedLocallyAt());
    }

    @Test void cannotTerminateBeforeDeadlineEvenWithVerifiedResources() {
        var invite = accepted(false); var job = stopped(invite);
        repository.beginByeAttempt(invite.getInviteId(), job.token()).requireApplied(); closeLocalResources(job);
        assertFalse(cleanup.terminateByeLocally(cleanup.refresh(job)));
        assertFalse(cleanup.refresh(job).completed().contains("BYE"));
        assertNotNull(repository.owner(invite.getBusinessSlot()));
    }

    @Test void redisGuardsDeadlineEvenWhenApplicationClockWouldAllowIt() {
        var invite = accepted(false); var job = stopped(invite); closeLocalResources(job);
        repository.beginByeAttempt(invite.getInviteId(), job.token()).requireApplied();
        var next = repository.get(invite.getInviteId()); next.setByeTerminatedLocallyAt(-1L);
        next.setByeOperationId(UUID.randomUUID().toString());
        // Exercise the production commit script with a premature candidate, not an application-only guard.
        var result = ReflectionTestUtils.invokeMethod(repository, "commit", "LOCAL_BYE",
                JSON.toJSONBytes(repository.get(invite.getInviteId())), next, job.token());
        assertEquals(InviteWriteResult.Code.STATE_CONFLICT, ((InviteWriteResult) result).code());
        assertNull(repository.get(invite.getInviteId()).getByeTerminatedLocallyAt());
    }

    @Test void atomicTerminationRequiresLeaseAndBothResourceConfirmations() {
        var invite = accepted(true); var job = stopped(invite);
        assertEquals(InviteWriteResult.Code.STATE_CONFLICT, repository.terminateByeLocally(invite.getInviteId(), job.token()).code());
        assertTrue(cleanup.completeStep(job, "RTP"));
        assertEquals(InviteWriteResult.Code.STATE_CONFLICT, repository.terminateByeLocally(invite.getInviteId(), job.token()).code());
        assertTrue(cleanup.completeStep(job, "STATE"));
        assertEquals(InviteWriteResult.Code.STATE_CONFLICT, repository.terminateByeLocally(invite.getInviteId(), "wrong-lease").code());
        assertFalse(cleanup.refresh(job).completed().contains("BYE"));
        assertTrue(cleanup.terminateByeLocally(cleanup.refresh(job)));
        var local = repository.get(invite.getInviteId());
        assertFalse(local.isByeAcknowledged()); assertNotNull(local.getByeTerminatedLocallyAt());
        assertEquals("BYE_WAIT_EXPIRED", local.getByeLocalTerminationReason());
        assertTrue(cleanup.refresh(job).completed().contains("BYE"));
        assertFalse(cleanup.finish(job), "SIP deletion still has to succeed");
    }

    @Test void committedResponseLossIsReadBackWithoutLosingJournalStep() {
        var invite = accepted(true); var job = stopped(invite); closeLocalResources(job);
        AtomicBoolean lost = new AtomicBoolean();
        var responseLoss = new InviteRepository(redis, settings) {
            @Override public long eval(String script, List<String> keys, byte[]... args) {
                long value = super.eval(script, keys, args);
                if (!lost.getAndSet(true)) throw new IllegalStateException("response lost after commit");
                return value;
            }
        };
        assertTrue(responseLoss.terminateByeLocally(invite.getInviteId(), job.token()).applied());
        assertTrue(cleanup.refresh(job).completed().contains("BYE"));
        assertNotNull(repository.get(invite.getInviteId()).getByeTerminatedLocallyAt());
    }

    @Test void realPeerAckIsNotMislabeledAsLocalTimeout() {
        var invite = accepted(true); var job = stopped(invite); closeLocalResources(job);
        repository.byeAcknowledged(invite.getInviteId()).requireApplied();
        assertFalse(cleanup.terminateByeLocally(cleanup.refresh(job)));
        assertTrue(repository.get(invite.getInviteId()).isByeAcknowledged());
        assertNull(repository.get(invite.getInviteId()).getByeLocalTerminationReason());
    }

    @Test void lateFirstAcceptedAnswerResetsTerminationWindowButDuplicateDoesNot() {
        // A late first answer belongs to an INVITE which was initially unaccepted.
        var fresh = InviteInfo.prepare("device", 2, InviteSessionType.PLAY, "PLAY:2", "media", "UDP", 60_000);
        fresh.setCallId("late-call"); fresh.setSipSent(true); fresh.setByeFirstSentAt(1L);
        fresh.setByeTerminatedLocallyAt(2L); fresh.setByeLocalTerminationReason("previous");
        repository.createAndClaim(fresh).requireApplied();
        var job = stopped(fresh);
        var dialog = new SipTransactionInfo(); dialog.setCallId(fresh.getCallId());
        var late = repository.sipFinal(fresh.getInviteId(), fresh.getCallId(), true, dialog).requireApplied();
        assertNull(late.getByeFirstSentAt()); assertNull(late.getByeTerminatedLocallyAt());
        assertNull(late.getByeLocalTerminationReason());
        job = Objects.requireNonNull(cleanup.claim(fresh.getInviteId()));
        long first = repository.beginByeAttempt(fresh.getInviteId(), job.token()).requireApplied().getByeFirstSentAt();
        repository.sipFinal(fresh.getInviteId(), fresh.getCallId(), true, dialog).requireApplied();
        assertEquals(first, repository.get(fresh.getInviteId()).getByeFirstSentAt());
    }

    @Test void realClockDeadlineClosesAnUnacknowledgedByeWithoutChangingTimestamps() throws Exception {
        var invite = accepted(false); var job = stopped(invite);
        long first = repository.beginByeAttempt(invite.getInviteId(), job.token()).requireApplied().getByeFirstSentAt();
        closeLocalResources(job);
        assertFalse(cleanup.terminateByeLocally(cleanup.refresh(job)));
        // Use the actual window once, rather than proving expiry only with backdated fixtures.
        Thread.sleep(InviteRepository.BYE_WAIT_MILLIS + 100);
        var restarted = new InviteInfoCleanupStore(redis, settings);
        // The original 30-second task lease also expires naturally; recover it as a new worker.
        var recovered = Objects.requireNonNull(restarted.claim(invite.getInviteId()));
        repository.beginByeAttempt(invite.getInviteId(), recovered.token()).requireApplied();
        assertEquals(first, repository.get(invite.getInviteId()).getByeFirstSentAt());
        assertTrue(restarted.terminateByeLocally(recovered));
        assertTrue(restarted.completeStep(recovered, "SIP"));
        assertTrue(restarted.finish(recovered));
        assertEquals(InviteLifecycle.CLOSED, repository.get(invite.getInviteId()).getLifecycle());
        assertFalse(repository.get(invite.getInviteId()).isByeAcknowledged());
        assertNull(repository.owner(invite.getBusinessSlot()));
    }

    @Test void realJournalAndSipIndexesCloseAndAllowSameChannelReplayWithoutAck() throws Exception {
        for (int round = 0; round < 10; round++) {
            var f = new InviteJournalRegressionVerifier.Fixture();
            var invite = f.expected;
            var dialog = new SipTransactionInfo(); dialog.setCallId(invite.getCallId()); invite.setSipDialog(dialog);
            invite.getSsrcInfo().setAllocatedSsrc("0200000001");
            expireBye(invite);
            repository.createAndClaim(invite).requireApplied();
            var sessions = new SipInviteSessionManager();
            ReflectionTestUtils.setField(sessions, "redisTemplate", redis);
            ReflectionTestUtils.setField(sessions, "userSetting", settings);
            var sip = new SsrcTransaction(); sip.setInviteId(invite.getInviteId()); sip.setCallId(invite.getCallId());
            sip.setApp("rtp"); sip.setStream(invite.getStream()); sip.setDeviceId("device"); sip.setChannelId(1);
            sip.setType(InviteSessionType.PLAY); sip.setMediaServerId("media"); sip.setSsrc("0200000001"); sip.setSipTransactionInfo(dialog);
            assertTrue(sessions.put(sip));
            ReflectionTestUtils.setField(f.service, "inviteCleanupStore", cleanup);
            ReflectionTestUtils.setField(f.service, "inviteRepository", repository);
            ReflectionTestUtils.setField(f.service, "sessionManager", sessions);
            // SQL/RTP adapters are mocks; repository, journal and SIP index operations are real Redis.
            when(f.projection.close(any())).thenReturn(true);
            when(f.media.probeStream(any(), anyString(), anyString())).thenReturn(false);
            assertFalse(f.service.stopIfOwner(invite));
            var retry = Objects.requireNonNull(cleanup.claim(invite.getInviteId()));
            assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(f.service, "processInviteCleanup", retry));
            InviteInfo closed = repository.get(invite.getInviteId());
            assertEquals(InviteLifecycle.CLOSED, closed.getLifecycle()); assertFalse(closed.isByeAcknowledged());
            assertNotNull(closed.getByeTerminatedLocallyAt()); assertNull(repository.owner("PLAY:1"));
            assertNull(sessions.getSsrcTransactionByCallId(invite.getCallId()));
            assertTrue(repository.reservedSsrcs("media").isEmpty()); assertTrue(repository.activeIds(100).isEmpty());
            assertTrue(repository.pending(100).isEmpty());
            var replacement = accepted(false);
            repository.byeAcknowledged(invite.getInviteId()).requireApplied();
            assertTrue(f.service.stopIfOwner(invite));
            assertEquals(replacement.getInviteId(), repository.owner("PLAY:1").getInviteId());
            var job = stopped(replacement);
            for (String step : List.of("BYE", "SIP", "RTP", "STATE")) assertTrue(cleanup.completeStep(job, step));
            assertTrue(cleanup.finish(job));
        }
    }
}
