package com.genersoft.iot.vmp.gb28181.session;

import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.conf.SipConfig;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.media.zlm.ZLMRESTfulUtils;
import com.genersoft.iot.vmp.media.zlm.dto.ZLMResult;
import com.genersoft.iot.vmp.media.event.mediaServer.MediaServerOnlineEvent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.BitSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.UUID;

@Slf4j
@Component
public class SSRCFactory {

    private enum ReconciliationState {
        UNKNOWN,
        READY,
        FAILED
    }

    private final ConcurrentHashMap<String, BitSet> usedMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> lockMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, SsrcLease>> activeLeases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReconciliationState> reconciliationStates = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ssrc-rebuild");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    private ZLMRESTfulUtils zlmresTfulUtils;

    @Autowired
    private IMediaServerService mediaServerService;

    @Autowired
    private SipConfig sipConfig;

    @Autowired
    private UserSetting userSetting;

    private String domainPart;

    @PostConstruct
    public void init() {
        String sipDomain = sipConfig.getDomain();
        domainPart = sipDomain.length() >= 8 ? sipDomain.substring(3, 8) : sipDomain;
        scheduler.scheduleAtFixedRate(this::rebuild, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * The first periodic reconciliation can race media-node startup. Retry
     * shortly after the online event so the status manager has time to persist
     * the node and ZLM has time to finish initializing its API.
     */
    @EventListener
    public void onMediaServerOnline(MediaServerOnlineEvent event) {
        MediaServer mediaServer = event == null ? null : event.getMediaServer();
        if (mediaServer == null || !mediaServer.isRtpEnable()) {
            return;
        }
        try {
            scheduler.schedule(this::rebuild, 1, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            // The application is shutting down; no retry is needed.
            log.debug("[SSRC对账] 媒体节点上线重试已取消，调度器已关闭：{}", mediaServer.getId());
        }
    }

    public SsrcLease allocatePlayLease(String mediaServerId) {
        if (!allocationReady(mediaServerId)) {
            return null;
        }
        return allocateLease(mediaServerId, "0");
    }

    public SsrcLease allocatePlaybackLease(String mediaServerId) {
        if (!allocationReady(mediaServerId)) {
            return null;
        }
        return allocateLease(mediaServerId, "1");
    }

    public SsrcLease allocatePlayLease(MediaServer mediaServer) {
        if (mediaServer == null || (mediaServer.isRtpEnable() && !allocationReady(mediaServer.getId()))) {
            if (mediaServer == null) {
                log.warn("[SSRC] 媒体节点为空，暂停自动分配");
            }
            return null;
        }
        if (mediaServer.isRtpEnable() && userSetting.getSsrcRandom()) {
            return randomLease(mediaServer.getId(), "0");
        }
        return allocateLease(mediaServer.getId(), "0");
    }

    public SsrcLease allocatePlaybackLease(MediaServer mediaServer) {
        if (mediaServer == null || (mediaServer.isRtpEnable() && !allocationReady(mediaServer.getId()))) {
            if (mediaServer == null) {
                log.warn("[SSRC] 媒体节点为空，暂停自动分配");
            }
            return null;
        }
        if (mediaServer.isRtpEnable() && userSetting.getSsrcRandom()) {
            return randomLease(mediaServer.getId(), "1");
        }
        return allocateLease(mediaServer.getId(), "1");
    }

    public void release(SsrcLease lease) {
        if (lease == null || !lease.isOwned() || lease.getLeaseId() == null) {
            return;
        }
        ConcurrentHashMap<String, SsrcLease> leases = activeLeases.get(lease.getMediaServerId());
        if (leases == null || leases.remove(lease.getLeaseId()) == null) {
            return;
        }
        synchronized (lockMap.computeIfAbsent(lease.getMediaServerId(), k -> new Object())) {
            BitSet bits = usedMap.get(lease.getMediaServerId());
            int suffix = suffixIndex(lease.getSsrc());
            if (bits != null && suffix >= 0) {
                bits.clear(suffix);
            }
        }
    }

    private String allocateLocked(String mediaServerId) {
        BitSet bits = usedMap.computeIfAbsent(mediaServerId, k -> new BitSet(10000));
        int start = ThreadLocalRandom.current().nextInt(10000);
        int index = start;
        do {
            if (!bits.get(index)) {
                bits.set(index);
                return domainPart + String.format("%04d", index);
            }
            index = (index + 1) % 10000;
        } while (index != start);
        log.warn("[SSRC] 媒体节点 {} 的SSRC已用尽", mediaServerId);
        return null;
    }

    private SsrcLease allocateLease(String mediaServerId, String prefix) {
        // Keep the BitSet reservation and lease index update atomic with rebuild().
        synchronized (lockMap.computeIfAbsent(mediaServerId, k -> new Object())) {
            String suffix = allocateLocked(mediaServerId);
            if (suffix == null) {
                return null;
            }
            String ssrc = prefix + suffix;
            SsrcLease lease = new SsrcLease(mediaServerId, ssrc, true, UUID.randomUUID().toString());
            activeLeases.computeIfAbsent(mediaServerId, key -> new ConcurrentHashMap<>())
                    .put(lease.getLeaseId(), lease);
            return lease;
        }
    }

    private SsrcLease randomLease(String mediaServerId, String prefix) {
        synchronized (lockMap.computeIfAbsent(mediaServerId, k -> new Object())) {
            BitSet bits = usedMap.computeIfAbsent(mediaServerId, k -> new BitSet(10000));
            int start = ThreadLocalRandom.current().nextInt(10000);
            for (int offset = 0; offset < 10000; offset++) {
                int index = (start + offset) % 10000;
                if (!bits.get(index)) {
                    bits.set(index);
                    String ssrc = prefix + domainPart + String.format("%04d", index);
                    SsrcLease lease = new SsrcLease(mediaServerId, ssrc, true, UUID.randomUUID().toString());
                    activeLeases.computeIfAbsent(mediaServerId, key -> new ConcurrentHashMap<>())
                            .put(lease.getLeaseId(), lease);
                    return lease;
                }
            }
            log.warn("[SSRC] 媒体节点 {} 的随机SSRC已用尽", mediaServerId);
            return null;
        }
    }

    private int suffixIndex(String ssrc) {
        if (ssrc == null || ssrc.length() < 4) {
            return -1;
        }
        try {
            return Integer.parseInt(ssrc.substring(ssrc.length() - 4));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    void rebuild() {
        try {
            List<MediaServer> servers = mediaServerService.getAll();
            if (servers == null || servers.isEmpty()) {
                log.warn("[SSRC对账] 无法获取有效媒体节点列表，暂停自动分配");
                markAllReconciliationFailed("媒体节点列表为空");
                return;
            }
            boolean validServer = false;
            java.util.Set<String> observedServerIds = ConcurrentHashMap.newKeySet();
            for (MediaServer server : servers) {
                try {
                    if (server == null || server.getId() == null) {
                        continue;
                    }
                    validServer = true;
                    observedServerIds.add(server.getId());
                    if (!server.isRtpEnable()) {
                        reconciliationStates.put(server.getId(), ReconciliationState.READY);
                        continue;
                    }
                    ZLMResult<?> result = zlmresTfulUtils.getMediaList(server, null, null, "rtsp", null);
                    if (result == null || result.getCode() != 0
                            || (result.getData() != null && !(result.getData() instanceof List<?>))) {
                        markReconciliationFailed(server, describeResult(result));
                        continue;
                    }
                    // ZLM may omit data when the successful media list is empty.
                    List<?> list = result.getData() == null ? List.of() : (List<?>) result.getData();
                    List<JSONObject> snapshot = new ArrayList<>(list.size());
                    boolean valid = true;
                    for (Object item : list) {
                        if (!(item instanceof JSONObject)) {
                            valid = false;
                            break;
                        }
                        snapshot.add((JSONObject) item);
                    }
                    if (!valid) {
                        markReconciliationFailed(server, "ZLM媒体列表包含非法条目");
                        continue;
                    }
                    markReconciliationReady(server, snapshot);
                }catch (Exception e) {
                    markReconciliationFailed(server, describeException(e));
                }
            }
            for (String knownServerId : reconciliationStates.keySet()) {
                if (!observedServerIds.contains(knownServerId)) {
                    markReconciliationFailed(knownServerId, "媒体节点未出现在当前列表");
                }
            }
            if (!validServer) {
                log.warn("[SSRC对账] 媒体节点列表不包含有效节点，暂停自动分配");
                markAllReconciliationFailed("媒体节点列表不包含有效节点");
            }
        }catch (Exception e) {
            log.error("[SSRC] 重建SSRC失败", e);
            markAllReconciliationFailed(e.getMessage());
        }
    }

    void markReconciliationReady(MediaServer server, List<JSONObject> list) {
        if (server == null || server.getId() == null || list == null) {
            if (server != null) {
                markReconciliationFailed(server, "ZLM媒体列表为空");
            }
            return;
        }
        synchronized (lockMap.computeIfAbsent(server.getId(), k -> new Object())) {
            BitSet bits = new BitSet(10000);
            int count = 0;
            for (JSONObject obj : list) {
                if (obj == null) {
                    markReconciliationFailed(server, "ZLM媒体列表包含空条目");
                    return;
                }
                if (!obj.containsKey("originType")) {
                    markReconciliationFailed(server, "ZLM媒体列表条目缺少originType");
                    return;
                }
                if (obj.getIntValue("originType") != 3) {
                    continue;
                }
                String originUrl = obj.getString("originUrl");
                int idx = originUrl == null ? -1 : originUrl.lastIndexOf("/rtp/");
                if (idx == -1) {
                    markReconciliationFailed(server, "ZLM RTP条目缺少originUrl");
                    return;
                }
                try {
                    bits.set((int) (Long.parseLong(originUrl.substring(idx + 5), 16) % 10000));
                    count++;
                } catch (NumberFormatException e) {
                    markReconciliationFailed(server, "ZLM RTP条目的originUrl非法");
                    return;
                }
            }
            for (SsrcLease lease : activeLeases
                    .getOrDefault(server.getId(), new ConcurrentHashMap<>()).values()) {
                int suffix = suffixIndex(lease.getSsrc());
                if (suffix >= 0) {
                    bits.set(suffix);
                }
            }
            usedMap.put(server.getId(), bits);
            ReconciliationState previous = reconciliationStates.put(server.getId(), ReconciliationState.READY);
            if (previous != ReconciliationState.READY) {
                log.info("[SSRC对账] 媒体节点 {} 对账成功，恢复自动分配，已观察{}个RTP SSRC", server.getId(), count);
            }
            if (count > 8000) {
                log.info("[SSRC重建] 媒体节点 {} 的SSRC使用率已超过80%，请注意扩展服务提升性能", server.getId());
            }
        }
    }

    void markReconciliationFailed(MediaServer server, String reason) {
        if (server == null || server.getId() == null) {
            return;
        }
        String endpoint = server.getIp() == null
                ? "unknown"
                : server.getIp() + ":" + server.getHttpPort();
        markReconciliationFailed(server.getId(), reason + "，地址=" + endpoint);
    }

    private void markReconciliationFailed(String mediaServerId, String reason) {
        if (mediaServerId == null || mediaServerId.isEmpty()) {
            return;
        }
        ReconciliationState previous = reconciliationStates.put(mediaServerId, ReconciliationState.FAILED);
        if (previous != ReconciliationState.FAILED) {
            log.warn("[SSRC对账] 媒体节点 {} 对账失败，暂停自动分配：{}", mediaServerId, reason);
        }
    }

    private boolean allocationReady(String mediaServerId) {
        if (mediaServerId == null || mediaServerId.isEmpty()) {
            log.warn("[SSRC] 媒体节点ID为空，暂停自动分配");
            return false;
        }
        ReconciliationState state = reconciliationStates.getOrDefault(mediaServerId, ReconciliationState.UNKNOWN);
        if (state != ReconciliationState.READY) {
            log.warn("[SSRC] 媒体节点 {} 的SSRC对账状态为{}，暂停自动分配", mediaServerId, state);
            return false;
        }
        return true;
    }

    private void markAllReconciliationFailed(String reason) {
        for (String mediaServerId : reconciliationStates.keySet()) {
            markReconciliationFailed(mediaServerId, reason);
        }
    }

    private String describeResult(ZLMResult<?> result) {
        if (result == null) {
            return "ZLM返回为空";
        }
        Object data = result.getData();
        return String.format("ZLM响应异常(code=%d,msg=%s,dataType=%s)", result.getCode(),
                result.getMsg(), data == null ? "null" : data.getClass().getName());
    }

    private String describeException(Exception exception) {
        if (exception == null) {
            return "未知异常";
        }
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
