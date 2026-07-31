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

import java.util.List;

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
        List<MediaServer> mediaServers;
        try {
            mediaServers = mediaServerService.getAllOnlineList();
        } catch (RuntimeException e) {
            log.warn("[Invite孤儿校验] 读取在线媒体节点失败，跳过本轮", e);
            return;
        }
        if (mediaServers == null || mediaServers.isEmpty()) {
            return;
        }

        for (MediaServer mediaServer : mediaServers) {
            if (mediaServer == null || mediaServer.getId() == null || mediaServer.getId().isEmpty()) {
                continue;
            }
            List<InviteInfo> activeInvites;
            try {
                activeInvites = inviteStreamService.getActiveInviteInfoByMediaServer(mediaServer.getId());
            } catch (RuntimeException e) {
                log.warn("[Invite孤儿校验] 读取媒体节点Invite失败，跳过：{}", mediaServer.getId(), e);
                continue;
            }
            if (activeInvites == null || activeInvites.isEmpty()) {
                continue;
            }
            if (!mediaServer.isRtpEnable()) {
                log.debug("[Invite孤儿校验] 媒体节点为单端口模式，跳过RTP监听对账：{}", mediaServer.getId());
                continue;
            }
            List<String> rtpStreams;
            try {
                rtpStreams = mediaServerService.listRtpServer(mediaServer);
            } catch (RuntimeException e) {
                log.warn("[Invite孤儿校验] 查询RTP失败，跳过媒体节点：{}", mediaServer.getId(), e);
                continue;
            }
            if (rtpStreams == null) {
                continue;
            }
            for (InviteInfo inviteInfo : activeInvites) {
                if (inviteInfo == null || inviteInfo.getStatus() != InviteSessionStatus.ok
                        || inviteInfo.getStreamInfo() == null || !isReconciliableType(inviteInfo)
                        || !hasRtpIdentity(inviteInfo)
                        || !mediaServer.getId().equals(resolveMediaServerId(inviteInfo))) {
                    continue;
                }
                String actualStream = resolveActualRtpStream(inviteInfo);
                if (actualStream == null) {
                    log.debug("[Invite孤儿校验] 缺少可靠ZLM流标识，跳过：{}", inviteInfo);
                    continue;
                }
                if (containsRtpStream(rtpStreams, actualStream) || isCompletedDownloadRetained(inviteInfo)) {
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
        if (inviteInfo.getStreamInfo().getMediaServer() != null
                && inviteInfo.getStreamInfo().getMediaServer().getId() != null
                && !inviteInfo.getStreamInfo().getMediaServer().getId().isEmpty()) {
            return inviteInfo.getStreamInfo().getMediaServer().getId();
        }
        return inviteInfo.getMediaServerId();
    }

    private String resolveActualRtpStream(InviteInfo inviteInfo) {
        if (inviteInfo.getSsrcInfo() != null && inviteInfo.getSsrcInfo().getZlmStream() != null
                && !inviteInfo.getSsrcInfo().getZlmStream().isEmpty()) {
            return inviteInfo.getSsrcInfo().getZlmStream();
        }
        // SSRC alone is not enough to identify a listener safely. Runtime
        // close paths may validate it against listRtpServer, but this periodic
        // task must not guess or remove a legacy InviteInfo.
        return null;
    }

    private boolean isReconciliableType(InviteInfo inviteInfo) {
        return inviteInfo.getType() == InviteSessionType.PLAY
                || inviteInfo.getType() == InviteSessionType.PLAYBACK
                || inviteInfo.getType() == InviteSessionType.DOWNLOAD;
    }

    private boolean hasRtpIdentity(InviteInfo inviteInfo) {
        return inviteInfo.getSsrcInfo() != null && resolveActualRtpStream(inviteInfo) != null;
    }

    private boolean containsRtpStream(List<String> streams, String expected) {
        if (expected == null || streams == null) {
            return false;
        }
        return streams.stream().anyMatch(item -> expected.equalsIgnoreCase(item));
    }

    private boolean isCompletedDownloadRetained(InviteInfo inviteInfo) {
        return inviteInfo.getType() == InviteSessionType.DOWNLOAD
                && inviteInfo.getStreamInfo() != null
                && inviteInfo.getStreamInfo().getProgress() >= 1
                && inviteInfo.getCleanupAt() != null
                && System.currentTimeMillis() < inviteInfo.getCleanupAt();
    }
}
