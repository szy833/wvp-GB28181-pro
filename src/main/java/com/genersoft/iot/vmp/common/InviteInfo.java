package com.genersoft.iot.vmp.common;

import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import lombok.Data;

/**
 * 记录每次发送invite消息的状态
 */
@Data
public class InviteInfo {

    private String inviteId;
    private String platformId;
    private String remoteChannelId;
    private String businessSlot;
    private long revision;
    private long playEpoch;
    private InviteLifecycle lifecycle;
    private boolean sipAccepted;
    private boolean sipSessionConfirmed;
    private boolean sipReady;
    private boolean sipSent;
    private boolean sipFinal;
    private boolean skipBye;
    private boolean rtpOpening;
    private boolean mediaArrived;
    private String callId;
    private String lastOperation;
    private String failureReason;
    private String startTime;
    private String endTime;
    private Long terminalExpiresAt;
    private boolean byeAcknowledged;
    private Long byeSentAt;
    private Long byeFirstSentAt;
    private Long byeTerminatedLocallyAt;
    private String byeLocalTerminationReason;
    private String byeClockSource;
    private String byeOperationId;
    private com.genersoft.iot.vmp.gb28181.bean.SipTransactionInfo sipDialog;
    private com.genersoft.iot.vmp.gb28181.bean.Device sipDevice;
    private com.genersoft.iot.vmp.gb28181.bean.Platform sipPlatform;

    public void captureSipTarget(com.genersoft.iot.vmp.gb28181.bean.Device source) {
        sipDevice = new com.genersoft.iot.vmp.gb28181.bean.Device();
        sipDevice.setDeviceId(source.getDeviceId());
        sipDevice.setHostAddress(source.getHostAddress());
        sipDevice.setLocalIp(source.getLocalIp());
        sipDevice.setTransport(source.getTransport());
    }

    public void captureSipTarget(com.genersoft.iot.vmp.gb28181.bean.Platform source) {
        sipPlatform = new com.genersoft.iot.vmp.gb28181.bean.Platform();
        sipPlatform.setServerGBId(source.getServerGBId());
        sipPlatform.setDeviceIp(source.getDeviceIp());
        sipPlatform.setDevicePort(source.getDevicePort());
        sipPlatform.setTransport(source.getTransport());
    }

    public static InviteInfo prepare(String deviceId, Integer channelId, InviteSessionType type,
                                     String businessSlot, String mediaServerId, String streamMode, long timeout) {
        InviteInfo invite = new InviteInfo();
        invite.setInviteId(java.util.UUID.randomUUID().toString());
        invite.setBusinessSlot(businessSlot);
        invite.setDeviceId(deviceId);
        invite.setChannelId(channelId);
        invite.setType(type);
        invite.setMediaServerId(mediaServerId);
        invite.setStreamMode(streamMode);
        invite.setStream("gb_" + invite.getInviteId().replace("-", ""));
        invite.setLifecycle(InviteLifecycle.PREPARING);
        invite.setStatus(InviteSessionStatus.ready);
        invite.setCreateTime(System.currentTimeMillis());
        invite.setExpirationTime(timeout);
        return invite;
    }

    private String deviceId;

    private Integer channelId;

    private String stream;

    private SSRCInfo ssrcInfo;

    private String receiveIp;

    private Integer receivePort;

    private String streamMode;

    private InviteSessionType type;

    private InviteSessionStatus status;

    private StreamInfo streamInfo;

    private String mediaServerId;

    private Long expirationTime;

    private Long createTime;

    /**
     * Absolute deadline for retaining a completed download's metadata.
     */
    private Long cleanupAt;


    public static InviteInfo getInviteInfo(String deviceId, Integer channelId, String stream, SSRCInfo ssrcInfo, String mediaServerId,
                                           String receiveIp, Integer receivePort, String streamMode,
                                           InviteSessionType type, InviteSessionStatus status) {
        InviteInfo inviteInfo = new InviteInfo();
        inviteInfo.setDeviceId(deviceId);
        inviteInfo.setChannelId(channelId);
        inviteInfo.setStream(stream);
        inviteInfo.setSsrcInfo(ssrcInfo);
        inviteInfo.setReceiveIp(receiveIp);
        inviteInfo.setReceivePort(receivePort);
        inviteInfo.setStreamMode(streamMode);
        inviteInfo.setType(type);
        inviteInfo.setStatus(status);
        inviteInfo.setMediaServerId(mediaServerId);
        return inviteInfo;
    }

}
