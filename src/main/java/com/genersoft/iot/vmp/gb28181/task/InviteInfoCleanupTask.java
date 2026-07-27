package com.genersoft.iot.vmp.gb28181.task;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionStatus;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.service.IPlayService;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconciles active RTP-backed InviteInfo records with the media node's RTP listeners.
 */
@Slf4j
@Component
public class InviteInfoCleanupTask {

    @Autowired
    private IInviteStreamService inviteStreamService;

    @Autowired
    private IMediaServerService mediaServerService;

    @Autowired
    private IPlayService playService;

    @Scheduled(fixedRate = 30000)
    public void execute() {
        List<InviteInfo> snapshot;
        try {
            snapshot = inviteStreamService.getAllInviteInfo();
        } catch (RuntimeException e) {
            log.warn("[Invite孤儿校验] 读取InviteInfo失败，跳过本轮", e);
            return;
        }
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }

        Map<String, List<InviteInfo>> byMediaServer = new HashMap<>();
        for (InviteInfo inviteInfo : snapshot) {
            if (inviteInfo == null || inviteInfo.getStatus() != InviteSessionStatus.ok
                    || inviteInfo.getStreamInfo() == null || !isReconciliableType(inviteInfo)
                    || !hasReliableOwner(inviteInfo)) {
                continue;
            }
            String mediaServerId = resolveMediaServerId(inviteInfo);
            if (mediaServerId == null) {
                log.debug("[Invite孤儿校验] 缺少媒体节点，跳过：{}", inviteInfo);
                continue;
            }
            byMediaServer.computeIfAbsent(mediaServerId, ignored -> new ArrayList<>()).add(inviteInfo);
        }

        for (Map.Entry<String, List<InviteInfo>> entry : byMediaServer.entrySet()) {
            MediaServer mediaServer;
            try {
                mediaServer = mediaServerService.getOne(entry.getKey());
            } catch (RuntimeException e) {
                log.warn("[Invite孤儿校验] 查询媒体节点失败，跳过：{}", entry.getKey(), e);
                continue;
            }
            if (mediaServer == null) {
                log.debug("[Invite孤儿校验] 未找到媒体节点：{}", entry.getKey());
                continue;
            }
            if (!mediaServer.isRtpEnable()) {
                log.debug("[Invite孤儿校验] 媒体节点为单端口模式，跳过RTP监听对账：{}", entry.getKey());
                continue;
            }
            List<String> rtpStreams;
            try {
                rtpStreams = mediaServerService.listRtpServer(mediaServer);
            } catch (RuntimeException e) {
                log.warn("[Invite孤儿校验] 查询RTP失败，跳过媒体节点：{}", entry.getKey(), e);
                continue;
            }
            if (rtpStreams == null) {
                continue;
            }
            for (InviteInfo inviteInfo : entry.getValue()) {
                String actualStream = resolveActualRtpStream(inviteInfo);
                if (actualStream == null) {
                    log.debug("[Invite孤儿校验] 缺少可靠ZLM流标识，跳过：{}", inviteInfo);
                    continue;
                }
                if (rtpStreams.contains(actualStream) || isCompletedDownloadRetained(inviteInfo)) {
                    continue;
                }
                try {
                    playService.stopIfOwner(inviteInfo);
                } catch (RuntimeException e) {
                    log.warn("[Invite孤儿校验] 条件停止失败：{}", inviteInfo, e);
                }
            }
        }
    }

    private String resolveMediaServerId(InviteInfo inviteInfo) {
        if (inviteInfo.getMediaServerId() != null) {
            return inviteInfo.getMediaServerId();
        }
        if (inviteInfo.getStreamInfo().getMediaServer() != null) {
            return inviteInfo.getStreamInfo().getMediaServer().getId();
        }
        return null;
    }

    private String resolveActualRtpStream(InviteInfo inviteInfo) {
        if (hasReliableOwner(inviteInfo)) {
            return inviteInfo.getSsrcInfo().getZlmStream();
        }
        return null;
    }

    private boolean isReconciliableType(InviteInfo inviteInfo) {
        return inviteInfo.getType() == InviteSessionType.PLAY
                || inviteInfo.getType() == InviteSessionType.PLAYBACK
                || inviteInfo.getType() == InviteSessionType.DOWNLOAD;
    }

    private boolean hasReliableOwner(InviteInfo inviteInfo) {
        return inviteInfo.getSsrcInfo() != null
                && inviteInfo.getSsrcInfo().getResourceId() != null
                && !inviteInfo.getSsrcInfo().getResourceId().isEmpty()
                && inviteInfo.getSsrcInfo().getZlmStream() != null
                && !inviteInfo.getSsrcInfo().getZlmStream().isEmpty();
    }

    private boolean isCompletedDownloadRetained(InviteInfo inviteInfo) {
        return inviteInfo.getType() == InviteSessionType.DOWNLOAD
                && inviteInfo.getStreamInfo().getProgress() >= 1
                && inviteInfo.getCleanupAt() != null
                && System.currentTimeMillis() < inviteInfo.getCleanupAt();
    }
}
