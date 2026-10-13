package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.conf.UserSetting;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/** Runs against an explicitly supplied disposable Redis, never the application configuration. */
public final class InviteLifecycleRedisVerifier {
    private static int assertions;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }

    public static void main(String[] args) throws Exception {
        assertions = 0;
        if (args.length != 1) throw new IllegalArgumentException("Disposable loopback Redis port required");
        LettuceConnectionFactory connection = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(args[0]));
        connection.afterPropertiesSet();
        RedisTemplate<String, Object> redis = new com.genersoft.iot.vmp.conf.redis.RedisTemplateConfig().redisTemplate(connection);
        redis.afterPropertiesSet();
        UserSetting settings = new UserSetting();
        settings.setServerId("verify-" + UUID.randomUUID());
        InviteRepository store = new InviteRepository(redis, settings);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<InviteInfo>> created = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int channel = i;
                created.add(pool.submit(() -> {
                    InviteInfo draft = InviteInfo.prepare("device", channel, InviteSessionType.PLAY,
                            "slot-" + channel, "node", "UDP", 60_000);
                    store.createAndClaim(draft).requireApplied();
                    store.patch(draft.getInviteId(), "bind", current -> current.setReceivePort(40000 + channel)).requireApplied();
                    return draft;
                }));
            }
            for (Future<InviteInfo> future : created) {
                InviteInfo draft = future.get(30, TimeUnit.SECONDS);
                check(store.get(draft.getInviteId()).getReceivePort() != null, "Concurrent write lost");
            }
            check(store.activeIds(200).size() == 100, "Primary/index mismatch");
            List<Future<InviteWriteResult>> competitors = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                competitors.add(pool.submit(() -> store.createAndClaim(InviteInfo.prepare("device", 1000,
                        InviteSessionType.PLAY, "same-slot", "node", "UDP", 60_000))));
            }
            int winners = 0;
            for (var future : competitors) if (future.get().code() == InviteWriteResult.Code.APPLIED) winners++;
            check(winners == 1, "Business owner claim not exclusive");
            InviteInfo owner = store.owner("same-slot");
            store.patch(owner.getInviteId(), "sip-intent", current -> { current.setCallId("call-" + owner.getInviteId()); current.setSipSent(true); }).requireApplied();
            store.beginStop(owner, false).requireApplied();
            check(store.get(owner.getInviteId()).getLifecycle() == InviteLifecycle.STOPPING, "Stop deleted primary early");
            check(store.patch(owner.getInviteId(), "late-media", value -> value.setMediaArrived(true)).code()
                    == InviteWriteResult.Code.STATE_CONFLICT, "Stopped owner accepted late update");
            check(store.pending(10).contains(owner.getInviteId()), "Stop intent not durable");
            check(store.close(owner.getInviteId(), null).code() == InviteWriteResult.Code.STATE_CONFLICT, "Unleased close bypassed journal");
            InviteInfoCleanupStore cleanup = new InviteInfoCleanupStore(redis, settings);
            close(cleanup, owner.getInviteId());
            check(store.owner("same-slot") == null, "Closed owner still claims slot");
            check(store.createAndClaim(owner).code() == InviteWriteResult.Code.STATE_CONFLICT, "Closed owner resurrected");
            check(store.patch(UUID.randomUUID().toString(), "missing", value -> value.setMediaArrived(true)).code()
                    == InviteWriteResult.Code.NOT_FOUND, "Update created missing record");
            InviteInfo next = InviteInfo.prepare("device", 1000, InviteSessionType.PLAY, "same-slot", "node", "UDP", 60_000);
            store.createAndClaim(next).requireApplied();
            store.close(owner.getInviteId(), null);
            check(store.owner("same-slot").getInviteId().equals(next.getInviteId()), "Old close removed new owner");
            check(store.find("CALL", "call-" + owner.getInviteId()).size() == 1, "Closed tombstone lost late SIP correlation");
            InviteInfo late = store.sipFinal(owner.getInviteId(), "call-" + owner.getInviteId(), true).requireApplied();
            check(late.getLifecycle() == InviteLifecycle.STOPPING && late.isSipAccepted(), "Late accepted dialog not requeued");
            check(store.owner("same-slot").getInviteId().equals(next.getInviteId()), "Late SIP reclaimed new owner");
            check(store.find("CHANNEL", "PLAY:1000").size() == 1, "Old cleanup shadowed current channel owner");
            check(store.patch(late.getInviteId(), "resurrect", value -> value.setLifecycle(InviteLifecycle.ACTIVE)).code()
                    == InviteWriteResult.Code.STATE_CONFLICT, "Late SIP reactivated playback");
            close(cleanup, late.getInviteId());

            InviteInfo download = InviteInfo.prepare("device", 2000, InviteSessionType.DOWNLOAD,
                    "DOWNLOAD:2000:window", "node", "UDP", 60_000);
            store.createAndClaim(download).requireApplied();
            store.beginStop(download, true).requireApplied();
            close(cleanup, download.getInviteId());
            InviteInfo retained = store.get(download.getInviteId());
            check(retained.getCleanupAt() >= System.currentTimeMillis() + 895_000, "Download retained milliseconds not fifteen minutes");
            StreamInfo file = new StreamInfo(); file.setStream(download.getStream()); file.setProgress(1);
            retained.setStreamInfo(file);
            store.downloadResult(retained).requireApplied();
            check(store.find("STREAM", download.getStream()).getFirst().getStreamInfo().getProgress() == 1,
                    "Late MP4 result was discarded after resource close");
            check(store.purgeTerminal(download.getInviteId(), retained.getTerminalExpiresAt() + 1), "Terminal purge failed");
            check(store.find("STREAM", download.getStream()).isEmpty(), "Retained index leaked permanently");
            check(store.get(download.getInviteId()) == null, "Terminal primary leaked");

            checkInvalidTransition(store, next, value -> value.setLifecycle(InviteLifecycle.ACTIVE));
            checkInvalidTransition(store, next, value -> value.setStream("replacement"));
            check(store.reconciliationBatch(10).size() <= 10, "Reconciliation budget exceeded");

            InviteInfo tracked = InviteInfo.prepare("tracked-device", 2500, InviteSessionType.PLAY, "tracked-slot", "node", "UDP", 60_000);
            store.createAndClaim(tracked).requireApplied();
            var resource = new com.genersoft.iot.vmp.service.bean.SSRCInfo(41000, "0200000001", "rtp", tracked.getStream());
            resource.setResourceId(tracked.getInviteId()); resource.setInviteId(tracked.getInviteId());
            resource.setAllocatedSsrc("0200000001"); resource.setZlmStream("0BEBC201_" + tracked.getInviteId().replace("-", ""));
            store.patch(tracked.getInviteId(), "rtp-plan", value -> { value.setSsrcInfo(resource); value.setRtpOpening(true); }).requireApplied();
            check(new InviteRepository(redis, settings).reservedSsrcs("node").contains("0200000001"), "Restart lost SSRC plan");
            store.resourceOpened(tracked.getInviteId(), resource).requireApplied();
            var sessionManager = new com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager();
            org.springframework.test.util.ReflectionTestUtils.setField(sessionManager, "redisTemplate", redis);
            org.springframework.test.util.ReflectionTestUtils.setField(sessionManager, "userSetting", settings);
            var sip = new com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction();
            sip.setInviteId(tracked.getInviteId()); sip.setCallId("tracked-call"); sip.setApp("rtp"); sip.setStream(tracked.getStream());
            sip.setDeviceId(tracked.getDeviceId()); sip.setChannelId(tracked.getChannelId()); sip.setType(InviteSessionType.PLAY);
            sip.setMediaServerId("node"); sip.setSsrc("0200000001");
            store.patch(tracked.getInviteId(), "sip-intent", value -> { value.setCallId(sip.getCallId()); value.setSipSent(true); }).requireApplied();
            checkInvalidTransition(store, tracked, value -> value.setSipSessionConfirmed(true));
            checkInvalidTransition(store, tracked, value -> value.setSipReady(true));
            store.sipFinal(tracked.getInviteId(), sip.getCallId(), true).requireApplied();
            StreamInfo media = new StreamInfo(); media.setStream(tracked.getStream());
            store.patch(tracked.getInviteId(), "media", value -> { value.setMediaArrived(true); value.setStreamInfo(media); }).requireApplied();
            checkInvalidTransition(store, tracked, value -> value.setLifecycle(InviteLifecycle.ACTIVATING));
            check(sessionManager.put(sip), "SIP creation did not report its commit");
            check(sessionManager.getSsrcTransactionByCallId(sip.getCallId()) != null, "Production serializer rejected active Invite admission");
            store.sipSessionConfirmed(tracked.getInviteId(), sip.getCallId()).requireApplied();
            checkInvalidTransition(store, tracked, value -> value.setLifecycle(InviteLifecycle.ACTIVATING));
            store.patch(tracked.getInviteId(), "sip-ready", value -> value.setSipReady(true)).requireApplied();
            store.patch(tracked.getInviteId(), "activating", value -> value.setLifecycle(InviteLifecycle.ACTIVATING)).requireApplied();
            store.patch(tracked.getInviteId(), "activate", value -> value.setLifecycle(InviteLifecycle.ACTIVE)).requireApplied();
            store.patch(tracked.getInviteId(), "activate-again", value -> value.setLifecycle(InviteLifecycle.ACTIVE)).requireApplied();
            check(store.activeCount("node") == 1, "Duplicate activation inflated media count");
            store.beginStop(tracked, true).requireApplied();
            check(store.activeCount("node") == 0, "Stop retained playing count");
            check(sessionManager.removeByCallId(sip.getCallId()), "SIP session cleanup failed");
            check(!sessionManager.put(sip), "STOPPING session creation falsely reported success");
            check(store.sipSessionConfirmed(tracked.getInviteId(), sip.getCallId()).code() == InviteWriteResult.Code.STATE_CONFLICT,
                    "STOPPING accepted SIP session confirmation");
            check(sessionManager.getSsrcTransactionByCallId(sip.getCallId()) == null, "STOPPING admitted late SIP session");
            close(new InviteInfoCleanupStore(redis, settings), tracked.getInviteId());
            check(!store.reservedSsrcs("node").contains("0200000001"), "Closed resource retained durable SSRC reservation");

            InviteInfo lateRtp = InviteInfo.prepare("late-device", 2600, InviteSessionType.PLAY, "late-rtp", "node", "UDP", 60_000);
            store.createAndClaim(lateRtp).requireApplied();
            resource.setInviteId(lateRtp.getInviteId()); resource.setResourceId(lateRtp.getInviteId()); resource.setStream(lateRtp.getStream());
            store.patch(lateRtp.getInviteId(), "rtp-plan", value -> { value.setSsrcInfo(resource); value.setRtpOpening(true); }).requireApplied();
            store.beginStop(lateRtp, true).requireApplied();
            close(cleanup, lateRtp.getInviteId());
            check(store.resourceOpened(lateRtp.getInviteId(), resource).requireApplied().getLifecycle() == InviteLifecycle.STOPPING,
                    "Late RTP binding did not reopen cleanup only");
            check(store.owner(lateRtp.getBusinessSlot()) == null, "Late RTP reclaimed owner");
            check(store.reservedSsrcs("node").contains("0200000001"), "Late RTP lost SSRC reservation");
            close(cleanup, lateRtp.getInviteId());

            InviteInfo badKey = InviteInfo.prepare("bad-device", 2700, InviteSessionType.PLAY, "bad-index", "node", "UDP", 60_000);
            String badIndex = "WVP:INVITE:" + settings.getServerId() + ":INDEX:DEVICE:" + badKey.getDeviceId();
            redis.execute((org.springframework.data.redis.core.RedisCallback<Boolean>) c -> c.stringCommands().set(InviteRepository.bytes(badIndex), InviteRepository.bytes("wrong-type")));
            boolean rejectedType = false;
            try { store.createAndClaim(badKey); } catch (IllegalStateException expected) { rejectedType = true; }
            check(rejectedType && store.get(badKey.getInviteId()) == null && store.owner(badKey.getBusinessSlot()) == null,
                    "Wrong-type Lua failure partially created primary or owner");

            java.util.concurrent.atomic.AtomicBoolean lost = new java.util.concurrent.atomic.AtomicBoolean(true);
            InviteRepository responseLoss = new InviteRepository(redis, settings) {
                @Override public long eval(String script, List<String> keys, byte[]... args) {
                    long result = super.eval(script, keys, args);
                    if (lost.getAndSet(false)) throw new IllegalStateException("injected response loss after commit");
                    return result;
                }
            };
            InviteInfo unknown = InviteInfo.prepare("device", 3000, InviteSessionType.PLAY, "unknown-slot", "node", "UDP", 60_000);
            check(responseLoss.createAndClaim(unknown).code() == InviteWriteResult.Code.ALREADY_APPLIED, "Committed response loss not confirmed by exact readback");
            check(store.owner("unknown-slot").getInviteId().equals(unknown.getInviteId()), "Response loss caused another owner");
            System.out.println("INVITE_LIFECYCLE_ASSERTIONS=" + assertions);
        } finally {
            pool.shutdownNow();
            connection.destroy();
        }
    }

    private static void close(InviteInfoCleanupStore cleanup, String id) {
        var job = cleanup.claim(id);
        check(job != null, "Cleanup lease missing");
        check(!cleanup.finish(job), "Unfinished cleanup released owner");
        for (String step : List.of("BYE", "SIP", "RTP", "STATE")) check(cleanup.completeStep(job, step), "Stage acknowledgement failed");
        check(cleanup.finish(job), "Complete cleanup did not close primary");
    }

    private static void checkInvalidTransition(InviteRepository store, InviteInfo invite, java.util.function.Consumer<InviteInfo> mutation) {
        boolean rejected = false;
        try { store.patch(invite.getInviteId(), "invalid", mutation); }
        catch (IllegalStateException | IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Invalid transition accepted");
    }
}
