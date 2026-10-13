package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.service.IPlayService;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Indexed reads and owner-specific commands over the single Invite lifecycle protocol. */
@Slf4j
@Service
public class InviteStreamServiceImpl implements IInviteStreamService {
    private final InviteRepository repository;
    private final ObjectProvider<IPlayService> play;
    private final Map<String, Waiters> waiters = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean expiring = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ExecutorService expiryWorker = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "invite-expiry"); thread.setDaemon(true); return thread;
    });
    private record Outcome(int code, String message, StreamInfo stream) { }
    private static final class Waiters {
        final List<ErrorCallback<StreamInfo>> callbacks = new ArrayList<>();
        Outcome outcome;
        long completedAt;
    }

    public InviteStreamServiceImpl(InviteRepository repository, ObjectProvider<IPlayService> play) {
        this.repository = repository;
        this.play = play;
    }

    @Override public InviteWriteResult createInvite(InviteInfo invite) { return repository.createAndClaim(invite); }
    @Override public InviteInfo getById(String id) { return repository.get(id); }
    @Override public InviteInfo getByCallId(String id) { return repository.find("CALL", id).stream().findFirst().orElse(null); }

    @Override public InviteInfo recordSipReady(InviteInfo invite, String callId) {
        return repository.patch(invite.getInviteId(), "sip-ready", current -> {
            if (!Objects.equals(current.getCallId(), callId) || !current.isSipAccepted() || !current.isSipSessionConfirmed())
                throw new IllegalStateException("SIP answer requires a committed matching session");
            current.setSipReady(true);
            current.setStatus(InviteSessionStatus.ok);
        }).requireApplied();
    }

    @Override public InviteInfo recordMediaArrival(InviteInfo invite, StreamInfo stream) {
        if (stream == null || !Objects.equals(invite.getStream(), stream.getStream())) throw new IllegalArgumentException("Wrong media identity");
        return repository.patch(invite.getInviteId(), "media-arrived", current -> {
            current.setStreamInfo(stream);
            current.setMediaArrived(true);
        }).requireApplied();
    }

    @Override public InviteInfo activate(InviteInfo invite) {
        return repository.patch(invite.getInviteId(), "active", current -> {
            if (!current.isSipAccepted() || !current.isSipSessionConfirmed() || !current.isSipReady()
                    || !current.isMediaArrived() || current.getStreamInfo() == null) {
                throw new IllegalStateException("SIP session, answer and media must all be confirmed");
            }
            current.setLifecycle(InviteLifecycle.ACTIVE);
        }).requireApplied();
    }

    @Override public InviteWriteResult updateInviteInfo(InviteInfo invite) { return updateInviteInfo(invite, null); }
    @Override public InviteWriteResult updateInviteInfo(InviteInfo invite, Long retention) {
        if (invite.getType() == InviteSessionType.DOWNLOAD && invite.getStreamInfo() != null
                && invite.getStreamInfo().getProgress() >= 1) {
            if (retention != null) invite.setCleanupAt(System.currentTimeMillis() + retention);
            InviteWriteResult saved = repository.downloadResult(invite);
            saved.requireApplied();
            if (!saved.invite().getLifecycle().closing()) play.getObject().stopIfOwner(saved.invite());
            return saved;
        }
        // Metadata commands never create, restore, change SIP/media facts, or regress lifecycle state.
        InviteWriteResult result = repository.patch(invite.getInviteId(), "metadata", current -> {
            if (invite.getStreamInfo() != null) {
                if (!current.isMediaArrived()) throw new IllegalStateException("Media was not confirmed");
                current.setStreamInfo(invite.getStreamInfo());
            }
            if (invite.getCleanupAt() != null) current.setCleanupAt(invite.getCleanupAt());
            if (retention != null) current.setCleanupAt(System.currentTimeMillis() + retention);
        });
        result.requireApplied();
        if (result.invite().getType() == InviteSessionType.DOWNLOAD && result.invite().getStreamInfo() != null
                && result.invite().getStreamInfo().getProgress() >= 1) play.getObject().stopIfOwner(result.invite());
        return result;
    }

    @Override public InviteInfo updateInviteInfoForSSRC(InviteInfo invite, String ssrc) {
        return repository.patch(invite.getInviteId(), "ssrc", current -> {
            if (current.getSsrcInfo() == null) throw new IllegalStateException("RTP not bound");
            current.getSsrcInfo().setSsrc(ssrc);
        }).requireApplied();
    }

    @Override public InviteInfo getInviteInfo(InviteSessionType type, Integer channelId, String stream) {
        List<InviteInfo> values = stream == null ? repository.find("CHANNEL", type + ":" + channelId) : repository.find("STREAM", stream);
        return values.stream().filter(value -> (type == null || type == value.getType())
                && (channelId == null || channelId.equals(value.getChannelId()))).findFirst().orElse(null);
    }
    @Override public InviteInfo getInviteInfoByDeviceAndChannel(InviteSessionType type, Integer channelId) { return getInviteInfo(type, channelId, null); }
    @Override public InviteInfo getInviteInfoByStream(InviteSessionType type, String stream) { return getInviteInfo(type, null, stream); }
    @Override public InviteInfo getInviteInfoByStreamAndMediaServer(String node, String stream) {
        return repository.find("STREAM", stream).stream().filter(value -> Objects.equals(node, value.getMediaServerId())).findFirst().orElse(null);
    }
    @Override public InviteInfo getInviteInfoBySSRC(String ssrc) { return repository.find("SSRC", ssrc).stream().findFirst().orElse(null); }
    @Override public List<InviteInfo> getActiveInviteInfoByMediaServer(String node) { return repository.find("MEDIA", node).stream().filter(value -> !value.getLifecycle().closing()).toList(); }
    @Override public int getStreamInfoCount(String node) { return repository.activeCount(node); }

    @Override public void removeInviteInfo(InviteInfo invite) { if (invite != null) play.getObject().stopIfOwner(invite); }
    @Override public void removeInviteInfo(InviteSessionType type, Integer channelId, String stream) { removeInviteInfo(getInviteInfo(type, channelId, stream)); }
    @Override public void removeInviteInfoByDeviceAndChannel(InviteSessionType type, Integer channelId) {
        repository.find("CHANNEL", type + ":" + channelId).forEach(this::removeInviteInfo);
    }
    @Override public boolean removeInviteInfoIfSame(InviteInfo expected) { return play.getObject().stopIfOwner(expected); }
    @Override public void clearInviteInfo(String deviceId) { clearActiveInviteInfoByDeviceId(deviceId); }
    @Override public int clearActiveInviteInfoByDeviceId(String deviceId) {
        int count = 0;
        for (InviteInfo value : repository.find("DEVICE", deviceId)) if (play.getObject().stopIfOwner(value)) count++;
        return count;
    }

    @Override public void await(InviteInfo invite, ErrorCallback<StreamInfo> callback) {
        if (callback == null) return;
        Waiters group = waiters.computeIfAbsent(invite.getInviteId(), key -> new Waiters());
        Outcome outcome;
        synchronized (group) {
            outcome = group.outcome;
            if (outcome == null) group.callbacks.add(callback);
        }
        if (outcome != null) deliver(callback, outcome);
        else {
            InviteInfo current = getById(invite.getInviteId());
            if (current != null && current.getLifecycle() == InviteLifecycle.ACTIVE) complete(current, 0, "success", current.getStreamInfo());
            else if (current == null || current.getLifecycle().closing()) complete(invite, -1, "Invite closed", null);
        }
    }
    @Override public void complete(InviteInfo invite, int code, String message, StreamInfo stream) {
        Waiters group = waiters.computeIfAbsent(invite.getInviteId(), key -> new Waiters());
        List<ErrorCallback<StreamInfo>> callbacks;
        Outcome outcome = new Outcome(code, message, stream);
        synchronized (group) {
            if (group.outcome != null) return;
            group.outcome = outcome;
            group.completedAt = System.currentTimeMillis();
            callbacks = new ArrayList<>(group.callbacks);
            group.callbacks.clear();
        }
        callbacks.forEach(callback -> deliver(callback, outcome));
    }
    private void deliver(ErrorCallback<StreamInfo> callback, Outcome outcome) {
        try { callback.run(outcome.code(), outcome.message(), outcome.stream()); }
        catch (RuntimeException error) { log.warn("[Invite回调] 单个请求回调失败", error); }
    }

    @Scheduled(fixedDelay = 1000)
    public void expireInvites() {
        if (!expiring.compareAndSet(false, true)) return;
        try {
            expiryWorker.execute(() -> {
                try { processExpiry(); }
                catch (RuntimeException failure) { log.warn("[Invite到期] 处理失败，保留到期意图等待下一轮", failure); }
                finally { expiring.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) { expiring.set(false); }
    }

    void processExpiry() {
        long now = System.currentTimeMillis();
        for (String id : repository.due(100, now)) {
            InviteInfo invite = getById(id);
            if (invite != null && invite.getLifecycle() == InviteLifecycle.CLOSED) repository.purgeTerminal(id, now);
            else if (invite != null && !invite.getLifecycle().closing()) {
                complete(invite, -1, "Invite establishment timed out", null);
                play.getObject().stopIfOwner(invite);
            }
        }
        waiters.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) { return entry.getValue().completedAt > 0 && now - entry.getValue().completedAt > 60_000; }
        });
    }

    @jakarta.annotation.PreDestroy public void close() { expiryWorker.shutdownNow(); }
}
