package com.genersoft.iot.vmp.gb28181.session;

import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.conf.SipConfig;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.media.zlm.ZLMRESTfulUtils;
import com.genersoft.iot.vmp.media.zlm.dto.ZLMResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.BitSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

@Slf4j
@Component
public class SSRCFactory {

    private final ConcurrentHashMap<String, BitSet> usedMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> lockMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, SsrcLease>> activeLeases = new ConcurrentHashMap<>();
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

    public String getPlaySsrc(String mediaServerId) {
        String suffix = allocate(mediaServerId);
        return suffix == null ? null : "0" + suffix;
    }

    public String getPlayBackSsrc(String mediaServerId) {
        String suffix = allocate(mediaServerId);
        return suffix == null ? null : "1" + suffix;
    }

    public SsrcLease allocatePlayLease(String mediaServerId) {
        return allocateLease(mediaServerId, "0");
    }

    public SsrcLease allocatePlaybackLease(String mediaServerId) {
        return allocateLease(mediaServerId, "1");
    }

    public String getPlaySsrc(MediaServer mediaServer) {
        if (mediaServer.isRtpEnable() && userSetting.getSsrcRandom()) {
            return randomLegacy(mediaServer.getId(), "0");
        }
        return getPlaySsrc(mediaServer.getId());
    }

    public String getPlayBackSsrc(MediaServer mediaServer) {
        if (mediaServer.isRtpEnable() && userSetting.getSsrcRandom()) {
            return randomLegacy(mediaServer.getId(), "1");
        }
        return getPlayBackSsrc(mediaServer.getId());
    }

    public SsrcLease allocatePlayLease(MediaServer mediaServer) {
        if (mediaServer.isRtpEnable() && userSetting.getSsrcRandom()) {
            return randomLease(mediaServer.getId(), "0");
        }
        return allocatePlayLease(mediaServer.getId());
    }

    public SsrcLease allocatePlaybackLease(MediaServer mediaServer) {
        if (mediaServer.isRtpEnable() && userSetting.getSsrcRandom()) {
            return randomLease(mediaServer.getId(), "1");
        }
        return allocatePlaybackLease(mediaServer.getId());
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

    private String allocate(String mediaServerId) {
        synchronized (lockMap.computeIfAbsent(mediaServerId, k -> new Object())) {
            return allocateLocked(mediaServerId);
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
        String ssrc = randomLegacy(mediaServerId, prefix);
        return new SsrcLease(mediaServerId, ssrc, false, UUID.randomUUID().toString());
    }

    private String randomLegacy(String mediaServerId, String prefix) {
        return prefix + domainPart + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
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
            for (MediaServer server : servers) {
                try {
                    if (server.isRtpEnable() && userSetting.getSsrcRandom()) {
                        continue;
                    }
                    synchronized (lockMap.computeIfAbsent(server.getId(), k -> new Object())) {
                        BitSet bits = new BitSet(10000);
                        int count = 0;
                        try {
                            ZLMResult<?> result = zlmresTfulUtils.getMediaList(server, null, null, "rtsp", null);
                            if (result != null && result.getCode() == 0 && result.getData() != null) {
                                List<JSONObject> list = (List<JSONObject>) result.getData();
                                BitSet activeBits = new BitSet(10000);
                                for (JSONObject obj : list) {
                                    if (obj.getIntValue("originType") != 3) continue;
                                    String originUrl = obj.getString("originUrl");
                                    if (originUrl == null) continue;
                                    int idx = originUrl.lastIndexOf("/rtp/");
                                    if (idx == -1) continue;
                                    try {
                                        int suffix = (int) (Long.parseLong(originUrl.substring(idx + 5), 16) % 10000);
                                        bits.set(suffix);
                                        count++;
                                    } catch (NumberFormatException ignored) {
                                    }
                                }
                                for (SsrcLease lease : activeLeases
                                        .getOrDefault(server.getId(), new ConcurrentHashMap<>()).values()) {
                                    int suffix = suffixIndex(lease.getSsrc());
                                    if (suffix >= 0) {
                                        activeBits.set(suffix);
                                    }
                                }
                                bits.or(activeBits);
                                usedMap.put(server.getId(), bits);
                                if (count > 8000) {
                                    log.info("[SSRC重建] 媒体节点 {} 的SSRC使用率已超过80%，请注意扩展服务提升性能", server.getId());
                                }
                                if (log.isDebugEnabled()) {
                                    log.debug("[SSRC重建] 节点 {} 已占用 {} 个SSRC", server.getId(), count);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("[SSRC重建] 查询媒体节点 {} 失败: {}", server.getId(), e.getMessage());
                        }

                    }
                }catch (Exception e) {
                    log.warn("[SSRC重建] 处理媒体节点 {} 失败: {}", server.getId(), e.getMessage());
                }
            }
        }catch (Exception e) {
            log.error("[SSRC] 重建SSRC失败", e);
        }
    }
}
