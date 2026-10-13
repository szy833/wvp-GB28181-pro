package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.common.enums.MediaStreamUtil;
import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.conf.exception.ServiceException;
import com.genersoft.iot.vmp.conf.exception.SsrcTransactionNotFoundException;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.controller.bean.AudioBroadcastEvent;
import com.genersoft.iot.vmp.gb28181.event.SipSubscribe;
import com.genersoft.iot.vmp.gb28181.service.*;
import com.genersoft.iot.vmp.gb28181.session.AudioBroadcastManager;
import com.genersoft.iot.vmp.gb28181.session.SSRCFactory;
import com.genersoft.iot.vmp.gb28181.session.SendSsrcFactory;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import com.genersoft.iot.vmp.gb28181.session.SsrcLease;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommanderForPlatform;
import com.genersoft.iot.vmp.gb28181.utils.SipUtils;
import com.genersoft.iot.vmp.media.bean.MediaInfo;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.bean.RecordInfo;
import com.genersoft.iot.vmp.media.event.hook.Hook;
import com.genersoft.iot.vmp.media.event.hook.HookSubscribe;
import com.genersoft.iot.vmp.media.event.hook.HookType;
import com.genersoft.iot.vmp.media.event.media.MediaArrivalEvent;
import com.genersoft.iot.vmp.media.event.media.MediaDepartureEvent;
import com.genersoft.iot.vmp.media.event.media.MediaNotFoundEvent;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.media.zlm.dto.StreamAuthorityInfo;
import com.genersoft.iot.vmp.service.ICloudRecordService;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.service.ISendRtpServerService;
import com.genersoft.iot.vmp.service.bean.*;
import com.genersoft.iot.vmp.service.redisMsg.IRedisRpcPlayService;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.utils.DateUtil;
import com.genersoft.iot.vmp.vmanager.bean.AudioBroadcastResult;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import com.genersoft.iot.vmp.vmanager.bean.StreamContent;
import gov.nist.javax.sip.message.SIPResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;

import javax.sdp.*;
import javax.sip.InvalidArgumentException;
import javax.sip.ResponseEvent;
import javax.sip.SipException;
import javax.sip.header.CallIdHeader;
import javax.sip.message.Response;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings(value = {"rawtypes", "unchecked"})
@Slf4j
@Service("playService")
public class PlayServiceImpl implements IPlayService {

    private static final long DOWNLOAD_CLEANUP_RETENTION_MILLIS = 15 * 60 * 1000L;

    private static final class TalkSsrcOwner {
        private final String deviceId;
        private final SsrcLease lease;
        private final String stream;
        private final String callId;
        private final String sendSsrc;
        private volatile String timeoutTaskKey;

        private TalkSsrcOwner(String deviceId, SsrcLease lease, String stream, String callId) {
            this(deviceId, lease, stream, callId, null);
        }

        private TalkSsrcOwner(String deviceId, SsrcLease lease, String stream, String callId, String sendSsrc) {
            this.deviceId = deviceId;
            this.lease = lease;
            this.stream = stream;
            this.callId = callId;
            this.sendSsrc = sendSsrc;
        }
    }

    private final ConcurrentHashMap<Integer, TalkSsrcOwner> talkSsrcLeases = new ConcurrentHashMap<>();


    @Autowired
    private ISIPCommander cmder;

    @Autowired
    private AudioBroadcastManager audioBroadcastManager;

    @Autowired
    private IDeviceService deviceService;

    @Autowired
    private ISIPCommanderForPlatform sipCommanderFroPlatform;

    @Autowired
    private IRedisCatchStorage redisCatchStorage;

    @Autowired
    private IInviteStreamService inviteStreamService;

    @Autowired
    private InviteInfoCleanupStore inviteCleanupStore;

    @Autowired
    private InviteRepository inviteRepository;

    @Autowired
    private InvitePlayProjectionStore playProjection;

    @Autowired
    private HookSubscribe subscribe;

    @Autowired
    private IMediaServerService mediaServerService;

    @Autowired
    private SipInviteSessionManager sessionManager;

    @Autowired
    private UserSetting userSetting;

    @Autowired
    private IDeviceChannelService deviceChannelService;

    @Autowired
    private DynamicTask dynamicTask;

    @Autowired
    private ISIPCommanderForPlatform commanderForPlatform;

    @Autowired
    private SSRCFactory ssrcFactory;

    @Autowired
    private SendSsrcFactory sendSsrcFactory;

    @Autowired
    private IPlatformService platformService;

    @Autowired
    private IGbChannelService channelService;

    @Autowired
    private ISendRtpServerService sendRtpServerService;

    @Autowired
    private IReceiveRtpServerService receiveRtpServerService;

    @Autowired
    private InviteSnapshotService inviteSnapshots;

    @Autowired
    private ICloudRecordService cloudRecordService;

    @Autowired
    private IRedisRpcPlayService redisRpcPlayService;

    /**
     * 流到来的处理
     */
    @Async
    @EventListener
    public void onApplicationEvent(MediaArrivalEvent event) {
        if (MediaStreamUtil.GB28181_BROADCAST.equals(event.getApp()) || MediaStreamUtil.GB28181_TALK.equals(event.getApp())) {
            if (event.getStream().indexOf("_") > 0) {
                String[] streamArray = event.getStream().split("_");
                if (streamArray.length == 2) {
                    String deviceId = streamArray[0];
                    String channelId = streamArray[1];
                    Device device = deviceService.getDeviceByDeviceId(deviceId);
                    DeviceChannel channel = deviceChannelService.getOneForSource(deviceId, channelId);
                    if (device == null) {
                        log.info("[语音对讲/喊话] 未找到设备：{}", deviceId);
                        return;
                    }
                    if (channel == null) {
                        log.info("[语音对讲/喊话] 未找到通道：{}", channelId);
                        return;
                    }
                    if (MediaStreamUtil.GB28181_BROADCAST.equals(event.getApp())) {
                        if (audioBroadcastManager.exit(channel.getId())) {
                            stopAudioBroadcast(device, channel);
                        }
                        // 开启语音对讲通道
                        try {
                            audioBroadcastCmd(device, channel, event.getMediaServer(),
                                    event.getApp(), event.getStream(), 60, false, (msg) -> log.info("[语音喊话] 通道建立成功, device: {}, channel: {}", deviceId, channelId));
                        } catch (InvalidArgumentException | ParseException | SipException e) {
                            log.error("[命令发送失败] 语音对讲: {}", e.getMessage());
                        }
                    }else if (MediaStreamUtil.GB28181_TALK.equals(event.getApp())) {
                        // 开启语音对讲通道
                        talkCmd(device, channel, event.getMediaServer(), event.getStream(), (msg) -> log.info("[语音对讲] 通道建立成功, device: {}, channel: {}", deviceId, channelId));
                    }
                }
            }
        }


    }

    /**
     * 流离开的处理
     */
    @Async
    @EventListener
    public void onApplicationEvent(MediaDepartureEvent event) {
        List<SendRtpInfo> sendRtpInfos = sendRtpServerService.queryByStream(event.getStream());
        if (!sendRtpInfos.isEmpty()) {
            for (SendRtpInfo sendRtpInfo : sendRtpInfos) {
                if (sendRtpInfo != null && sendRtpInfo.isSendToPlatform() && sendRtpInfo.getApp().equals(event.getApp())) {
                    String platformId = sendRtpInfo.getTargetId();
                    Device device = deviceService.getDeviceByDeviceId(platformId);
                    DeviceChannel channel = deviceChannelService.getOneById(sendRtpInfo.getChannelId());
                    try {
                        if (device != null && channel != null) {
                            cmder.streamByeCmd(device, channel.getDeviceId(), event.getApp(), event.getStream(), sendRtpInfo.getCallId(), null);
                            if (sendRtpInfo.getPlayType().equals(InviteStreamType.BROADCAST)
                                    || sendRtpInfo.getPlayType().equals(InviteStreamType.TALK)) {
                                AudioBroadcastCatch audioBroadcastCatch = audioBroadcastManager.get(channel.getId());
                                if (audioBroadcastCatch != null) {
                                    // 来自上级平台的停止对讲
                                    log.info("[停止对讲] 来自上级，平台：{}, 通道：{}", sendRtpInfo.getTargetId(), sendRtpInfo.getChannelId());
                                    audioBroadcastManager.del(sendRtpInfo.getChannelId());
                                }
                            }
                        }
                    } catch (SipException | InvalidArgumentException | ParseException |
                             SsrcTransactionNotFoundException e) {
                        log.error("[命令发送失败] 发送BYE: {}", e.getMessage());
                    }
                }
            }
        }

        if (MediaStreamUtil.GB28181_BROADCAST.equals(event.getApp()) || MediaStreamUtil.GB28181_TALK.equals(event.getApp())) {
            if (event.getStream().indexOf("_") > 0) {
                String[] streamArray = event.getStream().split("_");
                if (streamArray.length == 2) {
                    String deviceId = streamArray[0];
                    String channelId = streamArray[1];
                    Device device = deviceService.getDeviceByDeviceId(deviceId);
                    if (device == null) {
                        log.info("[语音对讲/喊话] 未找到设备：{}", deviceId);
                        return;
                    }
                    DeviceChannel channel = deviceChannelService.getOneForSource(deviceId, channelId);
                    if (channel == null) {
                        log.info("[语音对讲/喊话] 未找到通道：{}", channelId);
                        return;
                    }
                    if (MediaStreamUtil.GB28181_BROADCAST.equals(event.getApp())) {
                        stopAudioBroadcast(device, channel);
                    }else if (MediaStreamUtil.GB28181_TALK.equals(event.getApp())) {
                        stopTalk(device, channel, false);
                    }
                }
            }
        }else if (MediaStreamUtil.isGB28181(event.getApp(), event.getStream())) {
            // 释放ssrc
            String mediaServerId = event.getMediaServer() == null ? null : event.getMediaServer().getId();
            InviteInfo inviteInfo = inviteStreamService.getInviteInfoByStreamAndMediaServer(
                    mediaServerId, event.getStream());
            if (inviteInfo != null && inviteInfo.getLifecycle() != InviteLifecycle.CLOSED) {
                if (event.getOriginUrl() == null && !Boolean.FALSE.equals(mediaServerService.probeStream(
                        event.getMediaServer(), event.getApp(), event.getStream()))) return;
                // 发送bye
                stop(inviteInfo);
            }

        }
    }

    /**
     * 流未找到的处理
     */
    @Async
    @EventListener
    public void onApplicationEvent(MediaNotFoundEvent event) {
        // A generation-specific playback URL must never silently start a different Invite generation.
        if (MediaStreamUtil.isGB28181(event.getApp(), event.getStream())) {
            log.debug("[媒体未找到] 请通过点播API获取当前代播放地址：stream={}", event.getStream());
        }
    }

    @Override
    public void play(Device device, DeviceChannel channel, ErrorCallback<StreamInfo> callback) {

        // 判断设备是否属于当前平台, 如果不属于则发起自动调用
        if (!userSetting.getServerId().equals(device.getServerId())) {
            redisRpcPlayService.play(device.getServerId(), channel.getId(), callback);
            return;
        }
        MediaServer mediaServerItem = getNewMediaServerItem(device);
        if (mediaServerItem == null) {
            log.warn("[点播] 未找到可用的zlm deviceId: {},channelId:{}", device.getDeviceId(), channel.getDeviceId());
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "未找到可用的zlm");
        }
        play(mediaServerItem, device, channel, null, userSetting.getRecordSip(), callback);
    }

    @Override
    public SSRCInfo play(MediaServer mediaServerItem, String deviceId, String channelId, String ssrc, ErrorCallback<StreamInfo> callback) {
        if (mediaServerItem == null) {
            log.warn("[点播] 未找到可用的zlm deviceId: {},channelId:{}", deviceId, channelId);
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "未找到可用的zlm");
        }
        Device device = redisCatchStorage.getDevice(deviceId);
        if (device.getStreamMode().equalsIgnoreCase("TCP-ACTIVE") && !mediaServerItem.isRtpEnable()) {
            log.warn("[点播] 单端口收流时不支持TCP主动方式收流 deviceId: {},channelId:{}", deviceId, channelId);
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "单端口收流时不支持TCP主动方式收流");
        }
        DeviceChannel channel = deviceChannelService.getOneForSource(deviceId, channelId);
        if (channel == null) {
            log.warn("[点播] 未找到通道 deviceId: {},channelId:{}", deviceId, channelId);
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "未找到通道");
        }

        return play(mediaServerItem, device, channel, ssrc, userSetting.getRecordSip(), callback);
    }

    private SSRCInfo play(MediaServer mediaServer, Device device, DeviceChannel channel, String ssrc, Boolean record,
                          ErrorCallback<StreamInfo> callback) {
        return startInvite(mediaServer, device, channel, InviteSessionType.PLAY, null, null, ssrc,
                record != null ? record : userSetting.getRecordSip(), 1, callback);
    }

    private SSRCInfo startInvite(MediaServer mediaServer, Device device, DeviceChannel channel, InviteSessionType type,
                                  String start, String end, String presetSsrc, boolean record, int speed,
                                  ErrorCallback<StreamInfo> originalCallback) {
        java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
        ErrorCallback<StreamInfo> callback = (code, message, data) -> {
            if (originalCallback != null && delivered.compareAndSet(false, true)) {
                try { originalCallback.run(code, message, data); }
                catch (RuntimeException failure) { log.warn("[Invite回调] 等待者处理失败", failure); }
            }
        };
        String slot = type + ":" + channel.getId() + (type == InviteSessionType.PLAY ? "" : ":" + start + ":" + end);
        InviteInfo draft = InviteInfo.prepare(device.getDeviceId(), channel.getId(), type, slot,
                mediaServer.getId(), device.getStreamMode(), userSetting.getPlayTimeout() * 2L);
        draft.setReceiveIp(mediaServer.getSdpIp());
        draft.setRemoteChannelId(channel.getDeviceId());
        draft.captureSipTarget(device);
        draft.setStartTime(start);
        draft.setEndTime(end);
        try {
            InviteWriteResult claimed = inviteStreamService.createInvite(draft);
            if (claimed.code() == InviteWriteResult.Code.OWNER_CHANGED) {
                InviteInfo current = inviteRepository.owner(slot);
                if (current == null) throw new IllegalStateException("Invite owner changed during claim");
                inviteStreamService.await(current, callback);
                return current.getSsrcInfo();
            }
            claimed.requireApplied();
            inviteStreamService.await(draft, callback);
            if (type == InviteSessionType.PLAY) {
                long epoch = playProjection.allocateEpoch(channel.getId());
                InviteInfo reserved = inviteRepository.patch(draft.getInviteId(), "projection-epoch",
                        current -> current.setPlayEpoch(epoch)).requireApplied();
                draft.setPlayEpoch(epoch);
                playProjection.reserve(reserved);
            }
            SSRCInfo resource = receiveRtpServerService.openGbRTPServerForInvite(draft, mediaServer, device, channel,
                    presetSsrc, record, (code, message, result) -> {
                try {
                    if (code != InviteErrorCode.SUCCESS.getCode() || result == null || result.getHookData() == null) {
                        failInvite(draft, code, message);
                        return;
                    }
                    StreamInfo stream = onPublishHandler(mediaServer, result.getHookData().getMediaInfo(), device, channel);
                    if (stream == null) throw new IllegalStateException("Media URL construction failed");
                    stream.setStartTime(start);
                    stream.setEndTime(end);
                    if (type == InviteSessionType.DOWNLOAD) stream.setProgress(0);
                    InviteInfo arrived = inviteStreamService.recordMediaArrival(draft, stream);
                    tryActivateInvite(arrived);
                } catch (RuntimeException failure) {
                    failInvite(draft, InviteErrorCode.FAIL.getCode(), failure.getMessage());
                }
            });
            if (resource == null || resource.getPort() <= 0) {
                failInvite(draft, InviteErrorCode.ERROR_FOR_SSRC_UNAVAILABLE.getCode(), "RTP allocation failed");
                return resource;
            }
            InviteInfo bound = inviteStreamService.getById(draft.getInviteId());
            if (bound == null || bound.getLifecycle().closing()) return resource;
            draft.setSsrcInfo(resource);
            if (type == InviteSessionType.DOWNLOAD) registerDownloadCompletion(draft, mediaServer);
            SipSubscribe.Event ok = event -> {
                try {
                    InviteOKHandler(event, resource, mediaServer, device, channel,
                            (code, message, data) -> failInvite(draft, code, message), draft, type);
                    InviteInfo accepted = inviteStreamService.getById(draft.getInviteId());
                    if (accepted != null && !accepted.getLifecycle().closing()) tryActivateInvite(accepted);
                } catch (RuntimeException failure) {
                    failInvite(draft, InviteErrorCode.FAIL.getCode(), failure.getMessage());
                }
            };
            SipSubscribe.Event error = event -> {
                try {
                    InviteInfo current = inviteStreamService.getById(draft.getInviteId());
                    if (current != null && current.getCallId() != null)
                        inviteRepository.sipFinal(current.getInviteId(), current.getCallId(), false).requireApplied();
                } finally { failInvite(draft, event.statusCode, event.msg); }
            };
            log.info("[点播开始] inviteId={}, type={}, deviceId={}, channelId={}, stream={}, port={}",
                    draft.getInviteId(), type, device.getDeviceId(), channel.getDeviceId(), resource.getStream(), resource.getPort());
            if (type == InviteSessionType.PLAY)
                cmder.playStreamCmd(mediaServer, resource, device, channel, ok, error, userSetting.getPlayTimeout().longValue());
            else if (type == InviteSessionType.PLAYBACK)
                cmder.playbackStreamCmd(mediaServer, resource, device, channel, start, end, ok, error, userSetting.getPlayTimeout().longValue());
            else
                cmder.downloadStreamCmd(mediaServer, resource, device, channel, start, end, speed, error, ok, userSetting.getPlayTimeout().longValue());
            return resource;
        } catch (InvalidArgumentException | SipException | ParseException | RuntimeException failure) {
            log.warn("[Invite创建] 创建未完成：inviteId={}", draft.getInviteId(), failure);
            // Deliver locally even if Redis remains unavailable. A committed draft retains its durable deadline.
            inviteStreamService.complete(draft, InviteErrorCode.FAIL.getCode(), "Invite creation failed", null);
            callback.run(InviteErrorCode.FAIL.getCode(), "Invite creation failed", null);
            beginInviteCleanup(draft, false);
            return null;
        }
    }

    private void tryActivateInvite(InviteInfo candidate) {
        InviteInfo current = inviteStreamService.getById(candidate.getInviteId());
        if (current == null || current.getLifecycle().closing() || !current.isSipAccepted()
                || !current.isSipSessionConfirmed() || !current.isSipReady() || !current.isMediaArrived()) return;
        if (current.getLifecycle() != InviteLifecycle.ACTIVE) {
            current = inviteRepository.patch(current.getInviteId(), "activating",
                    invite -> { if (invite.getLifecycle() != InviteLifecycle.ACTIVE) invite.setLifecycle(InviteLifecycle.ACTIVATING); }).requireApplied();
            if (current.getType() == InviteSessionType.PLAY) playProjection.activate(current);
            current = inviteStreamService.activate(current);
        }
        inviteStreamService.complete(current, InviteErrorCode.SUCCESS.getCode(), InviteErrorCode.SUCCESS.getMsg(), current.getStreamInfo());
        log.info("[点播成功] inviteId={}, type={}, channelId={}, stream={}",
                current.getInviteId(), current.getType(), current.getChannelId(), current.getStream());
        if (inviteSnapshots != null) inviteSnapshots.submit(current);
    }

    private void failInvite(InviteInfo invite, int code, String message) {
        inviteStreamService.complete(invite, code, message, null);
        beginInviteCleanup(invite, false);
    }

    private void registerDownloadCompletion(InviteInfo invite, MediaServer mediaServer) {
        Hook hook = Hook.getInstance(HookType.on_record_mp4, MediaStreamUtil.RTP_APP, invite.getStream(), mediaServer.getId());
        hook.setExpireTime(System.currentTimeMillis() + 24 * 60 * 60 * 1000L);
        subscribe.addSubscribe(hook, data -> {
            InviteInfo current = inviteStreamService.getById(invite.getInviteId());
            if (current == null || current.getStreamInfo() == null) return;
            DownloadFileInfo file = mediaServerService.getDownloadFilePath(mediaServer, data.getRecordInfo());
            if (file == null) return;
            current.getStreamInfo().setDownLoadFilePath(file);
            current.getStreamInfo().setProgress(1.0);
            current.setCleanupAt(System.currentTimeMillis() + DOWNLOAD_CLEANUP_RETENTION_MILLIS);
            inviteStreamService.updateInviteInfo(current).requireApplied();
        });
    }




    private void talk(MediaServer mediaServerItem, Device device, DeviceChannel channel, String stream,
                      SipSubscribe.Event errorEvent, Runnable timeoutCallback, AudioBroadcastEvent audioEvent) {

        SsrcLease lease = ssrcFactory.allocatePlayLease(mediaServerItem);
        String ySsrc = lease == null ? null : lease.getSsrc();

        if (ySsrc == null) {
            audioEvent.call("ssrc已经用尽");
            return;
        }
        String sendSsrc;
        SendRtpInfo sendRtpInfo;
        try {
            sendSsrc = sendSsrcFactory.getSendSsrc("0");
            sendRtpInfo = sendRtpServerService.createSendRtpInfo(mediaServerItem, null, null, sendSsrc, device.getDeviceId(), MediaStreamUtil.GB28181_TALK, stream,
                    channel.getId(), true, false);
            if (sendRtpInfo == null) {
                releaseTalkLeaseDirect(lease, channel.getId());
                audioEvent.call("获取发流端口失败");
                return;
            }
            sendRtpInfo.setPlayType(InviteStreamType.TALK);
        }catch (PlayException e) {
            releaseTalkLeaseDirect(lease, channel.getId());
            log.info("[语音对讲]开始 获取发流端口失败 deviceId: {}, channelId: {},", device.getDeviceId(), channel.getDeviceId());
            return;
        } catch (RuntimeException e) {
            releaseTalkLeaseDirect(lease, channel.getId());
            log.warn("[语音对讲]开始 获取发流端口异常 deviceId: {}, channelId: {}", device.getDeviceId(), channel.getDeviceId(), e);
            return;
        }

        sendRtpInfo.setOnlyAudio(true);
        sendRtpInfo.setPt(8);
        sendRtpInfo.setStatus(1);
        sendRtpInfo.setTcpActive(false);
        sendRtpInfo.setUsePs(false);
        sendRtpInfo.setReceiveStream(stream + "_talk");

        String callId = SipUtils.getNewCallId();
        TalkSsrcOwner leaseOwner = new TalkSsrcOwner(device.getDeviceId(), lease, sendRtpInfo.getStream(), callId,
                sendRtpInfo.getSsrc());
        if (talkSsrcLeases.putIfAbsent(channel.getId(), leaseOwner) != null) {
            // The duplicate request has not started ZLM yet, but discard the temporary
            // record so a future port-registry implementation cannot retain it.
            try {
                sendRtpServerService.delete(sendRtpInfo);
            } catch (RuntimeException e) {
                log.warn("[语音对讲] 清理重复请求的发流信息失败：deviceId={}, channelId={}",
                        device.getDeviceId(), channel.getDeviceId(), e);
            }
            releaseTalkLeaseDirect(lease, channel.getId());
            audioEvent.call("语音对讲进行中");
            return;
        }
        log.info("[语音对讲]开始 deviceId: {}, channelId: {},收流端口： {}, 收流模式：{}, SSRC: {}, SSRC校验：{}", device.getDeviceId(), channel.getDeviceId(), sendRtpInfo.getLocalPort(), device.getStreamMode(), sendRtpInfo.getSsrc(), false);
        // 超时处理
        String timeOutTaskKey = UUID.randomUUID().toString();
        leaseOwner.timeoutTaskKey = timeOutTaskKey;
        try {
            dynamicTask.startDelay(timeOutTaskKey, () -> {

            log.info("[语音对讲] 收流超时 deviceId: {}, channelId: {}，端口：{}, SSRC: {}", device.getDeviceId(), channel.getDeviceId(), sendRtpInfo.getPort(), sendRtpInfo.getSsrc());
            // 点播超时回复BYE 同时释放ssrc以及此次点播的资源
            try {
                cmder.streamByeCmd(device, channel.getDeviceId(), null,  null, callId, null);
            } catch (InvalidArgumentException | ParseException | SipException | SsrcTransactionNotFoundException e) {
                log.error("[语音对讲]超时， 发送BYE失败 {}", e.getMessage());
            } finally {
                stopTalk(device, channel, null, leaseOwner);
                timeoutCallback.run();
                sessionManager.removeByStream(sendRtpInfo.getApp(), sendRtpInfo.getStream());
            }
            }, userSetting.getPlayTimeout());
        } catch (RuntimeException e) {
            log.warn("[语音对讲]注册超时任务失败 deviceId: {}, channelId: {}", device.getDeviceId(), channel.getDeviceId(), e);
            stopTalk(device, channel, null, leaseOwner);
            audioEvent.call("失败，超时任务注册失败");
            return;
        }

        try {
            Integer localPort = mediaServerService.startSendRtpPassive(mediaServerItem, sendRtpInfo, userSetting.getPlayTimeout() * 1000);
            if (localPort == null || localPort <= 0) {
                stopTalk(device, channel, null, leaseOwner);
                timeoutCallback.run();
                sessionManager.removeByStream(sendRtpInfo.getApp(), sendRtpInfo.getStream());
                return;
            }
            sendRtpInfo.setPort(localPort);
            // 增加鉴权信息
            receiveRtpServerService.addAuthenticateInfoForGb28181Talk(mediaServerItem, sendRtpInfo.getStream());

        }catch (ControllerException e) {
            log.info("[语音对讲]失败 deviceId: {}, channelId: {}", device.getDeviceId(), channel.getDeviceId());
            audioEvent.call("失败, " + e.getMessage());
            // 查看是否已经建立了通道，存在则发送bye
            stopTalk(device, channel, null, leaseOwner);
            return;
        } catch (RuntimeException e) {
            log.warn("[语音对讲]失败 deviceId: {}, channelId: {}", device.getDeviceId(), channel.getDeviceId(), e);
            stopTalk(device, channel, null, leaseOwner);
            return;
        }


        // 查看设备是否已经在推流
        try {
            cmder.talkStreamCmd(mediaServerItem, sendRtpInfo, ySsrc, device, channel, callId, (hookData) -> {
                log.info("[语音对讲] 流已生成， 开始推流： " + hookData);
                stopTalkTimeoutTask(timeOutTaskKey);
                // TODO 暂不做处理
            }, (hookData) -> {
                log.info("[语音对讲] 设备开始推流： " + hookData);
                stopTalkTimeoutTask(timeOutTaskKey);

            }, (event) -> {
                stopTalkTimeoutTask(timeOutTaskKey);
                try {
                    if (!handleTalkInviteResponse(device, sendRtpInfo, event)) {
                        stopTalk(device, channel, null, leaseOwner);
                        sessionManager.removeByStream(sendRtpInfo.getApp(), sendRtpInfo.getStream());
                    }
                } catch (RuntimeException e) {
                    log.warn("[语音对讲]处理SIP响应失败 deviceId: {}, channelId: {}", device.getDeviceId(), channel.getDeviceId(), e);
                    stopTalk(device, channel, null, leaseOwner);
                    sessionManager.removeByStream(sendRtpInfo.getApp(), sendRtpInfo.getStream());
                }

            }, (event) -> {
                stopTalkTimeoutTask(timeOutTaskKey);
                stopTalk(device, channel, null, leaseOwner);
                sessionManager.removeByStream(sendRtpInfo.getApp(), sendRtpInfo.getStream());
                errorEvent.response(event);
            }, userSetting.getPlayTimeout().longValue());
        } catch (InvalidArgumentException | SipException | ParseException e) {

            log.error("[命令发送失败] 对讲消息: {}", e.getMessage());
            stopTalkTimeoutTask(timeOutTaskKey);
            stopTalk(device, channel, null, leaseOwner);
            sessionManager.removeByStream(sendRtpInfo.getApp(), sendRtpInfo.getStream());
            SipSubscribe.EventResult eventResult = new SipSubscribe.EventResult();
            eventResult.type = SipSubscribe.EventResultType.cmdSendFailEvent;
            eventResult.statusCode = -1;
            eventResult.msg = "命令发送失败";
            errorEvent.response(eventResult);
        } catch (RuntimeException e) {
            log.warn("[命令发送失败] 对讲消息异常", e);
            stopTalkTimeoutTask(timeOutTaskKey);
            stopTalk(device, channel, null, leaseOwner);
            sessionManager.removeByStream(sendRtpInfo.getApp(), sendRtpInfo.getStream());
        }
//        }

    }

    private boolean handleTalkInviteResponse(Device device, SendRtpInfo sendRtpInfo, SipSubscribe.EventResult event) {
        if (!(event.event instanceof ResponseEvent)) {
            log.error("[语音对讲]收到的消息错误，event不是ResponseEvent");
            return false;
        }
        ResponseEvent responseEvent = (ResponseEvent) event.event;
        if (!(responseEvent.getResponse() instanceof SIPResponse)) {
            log.error("[语音对讲]收到的消息错误，response不是SIPResponse");
            return false;
        }
        SIPResponse response = (SIPResponse) responseEvent.getResponse();
        sendRtpInfo.setFromTag(response.getFromTag());
        sendRtpInfo.setToTag(response.getToTag());
        sendRtpInfo.setCallId(response.getCallIdHeader().getCallId());
        sendRtpServerService.update(sendRtpInfo);

        SsrcTransaction ssrcTransaction = SsrcTransaction.buildForDevice(device.getDeviceId(), sendRtpInfo.getChannelId(), response.getCallIdHeader().getCallId(), sendRtpInfo.getApp(),
                sendRtpInfo.getStream(), sendRtpInfo.getSsrc(), sendRtpInfo.getMediaServerId(),
                response, InviteSessionType.TALK);
        sessionManager.put(ssrcTransaction);
        return true;
    }

    private void tcpActiveHandler(Device device, DeviceChannel channel, String contentString,
                                  MediaServer mediaServerItem, SSRCInfo ssrcInfo, ErrorCallback<StreamInfo> callback,
                                  InviteSessionType inviteSessionType, InviteInfo inviteInfo){
        if (!device.getStreamMode().equalsIgnoreCase("TCP-ACTIVE")) {
            return;
        }

        String substring;
        if (contentString.indexOf("y=") > 0) {
            substring = contentString.substring(0, contentString.indexOf("y="));
        }else {
            substring = contentString;
        }
        try {
            SessionDescription sdp = SdpFactory.getInstance().createSessionDescription(substring);
            int port = -1;
            Vector mediaDescriptions = sdp.getMediaDescriptions(true);
            for (Object description : mediaDescriptions) {
                MediaDescription mediaDescription = (MediaDescription) description;
                Media media = mediaDescription.getMedia();

                Vector mediaFormats = media.getMediaFormats(false);
                if (mediaFormats.contains("96")) {
                    port = media.getMediaPort();
                    break;
                }
            }
            log.info("[TCP主动连接对方] deviceId: {}, channelId: {}, 连接对方的地址：{}:{}, 收流模式：{}, SSRC: {}, SSRC校验：{}", device.getDeviceId(), channel.getDeviceId(), sdp.getConnection().getAddress(), port, device.getStreamMode(), ssrcInfo.getSsrc(), device.isSsrcCheck());
            Boolean result = mediaServerService.connectRtpServer(mediaServerItem, sdp.getConnection().getAddress(), port, ssrcInfo.getApp(), ssrcInfo.getZlmStream());
            log.info("[TCP主动连接对方] 结果： {}" , result);
            if (!result) {
                // 主动连接失败，结束流程， 清理数据
                inviteStreamService.complete(inviteInfo,
                        InviteErrorCode.ERROR_FOR_TCP_ACTIVE_CONNECTION_REFUSED_ERROR.getCode(),
                        InviteErrorCode.ERROR_FOR_TCP_ACTIVE_CONNECTION_REFUSED_ERROR.getMsg(), null);
                inviteStreamService.removeInviteInfo(inviteInfo);
            }
        } catch (SdpException e) {
            log.error("[TCP主动连接对方] deviceId: {}, channelId: {}, 解析200OK的SDP信息失败", device.getDeviceId(), channel.getDeviceId(), e);
            inviteStreamService.complete(inviteInfo,
                    InviteErrorCode.ERROR_FOR_SDP_PARSING_EXCEPTIONS.getCode(),
                    InviteErrorCode.ERROR_FOR_SDP_PARSING_EXCEPTIONS.getMsg(), null);
            inviteStreamService.removeInviteInfo(inviteInfo);
        }
    }

    /**
     * 点播成功时调用截图.
     *
     * @param mediaServerItemInuse media
     * @param deviceId             设备 ID
     * @param channelId            通道 ID
     * @param stream               ssrc
     */
    private void snapOnPlay(MediaServer mediaServerItemInuse, String deviceId, String channelId, String stream) {
        String path = "snap";
        String fileName = deviceId + "_" + channelId + ".jpg";
        // 请求截图
        log.info("[请求截图]: " + fileName);
        mediaServerService.getSnap(mediaServerItemInuse, MediaStreamUtil.RTP_APP, stream, 15, 1, path, fileName);
    }

    public StreamInfo onPublishHandlerForPlay(MediaServer mediaServer, MediaInfo media, Device device, DeviceChannel channel) {
        InviteInfo invite = inviteStreamService.getInviteInfoByStreamAndMediaServer(mediaServer.getId(), media.getStream());
        if (invite == null) throw new IllegalStateException("Media owner not found");
        StreamInfo stream = onPublishHandler(mediaServer, media, device, channel);
        tryActivateInvite(inviteStreamService.recordMediaArrival(invite, stream));
        return stream;
    }


    @Override
    public MediaServer getNewMediaServerItem(Device device) {
        if (device == null) {
            return null;
        }
        MediaServer mediaServerItem;
        if (ObjectUtils.isEmpty(device.getMediaServerId()) || "auto".equals(device.getMediaServerId())) {
            mediaServerItem = mediaServerService.getMediaServerForMinimumLoad(null);
        } else {
            mediaServerItem = mediaServerService.getOne(device.getMediaServerId());
        }
        if (mediaServerItem == null) {
            log.warn("点播时未找到可使用的ZLM...");
        }
        return mediaServerItem;
    }

    @Override
    public void playBack(Device device, DeviceChannel channel, String startTime,
                         String endTime, ErrorCallback<StreamInfo> callback) {
        if (device == null) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "设备不存在");
        }
        if (channel == null) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "通道不存在");
        }
        if (!userSetting.getServerId().equals(device.getServerId())) {
            redisRpcPlayService.playback(device.getServerId(), channel.getId(), startTime, endTime, callback);
            return;
        }

        MediaServer newMediaServerItem = getNewMediaServerItem(device);
        if (newMediaServerItem == null) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "未找到可用的节点");
        }
        if (device.getStreamMode().equalsIgnoreCase("TCP-ACTIVE") && ! newMediaServerItem.isRtpEnable()) {
            log.warn("[录像回放] 单端口收流时不支持TCP主动方式收流 deviceId: {},channelId:{}", device.getDeviceId(), channel.getDeviceId());
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "单端口收流时不支持TCP主动方式收流");
        }

        playBack(newMediaServerItem, device, channel, startTime, endTime, callback);
    }

    private void playBack(MediaServer mediaServer, Device device, DeviceChannel channel, String startTime,
                          String endTime, ErrorCallback<StreamInfo> callback) {
        startInvite(mediaServer, device, channel, InviteSessionType.PLAYBACK, startTime, endTime, null, false, 1, callback);
    }


    private void InviteOKHandler(SipSubscribe.EventResult eventResult, SSRCInfo ssrcInfo, MediaServer mediaServerItem,
                                 Device device, DeviceChannel channel, ErrorCallback<StreamInfo> callback,
                                 InviteInfo inviteInfo, InviteSessionType inviteSessionType){
        ResponseEvent responseEvent = (ResponseEvent) eventResult.event;
        String callId = ((SIPResponse) responseEvent.getResponse()).getCallIdHeader().getCallId();
        InviteInfo accepted = inviteRepository.sipFinal(inviteInfo.getInviteId(), callId, true).requireApplied();
        if (accepted.getLifecycle().closing()) { beginInviteCleanup(accepted, false); return; }
        String contentString = new String(responseEvent.getResponse().getRawContent());
        String ssrcInResponse = SipUtils.getSsrcFromSdp(contentString);
        // 兼容回复的消息中缺少ssrc(y字段)的情况
        if (ssrcInResponse == null) {
            ssrcInResponse = ssrcInfo.getSsrc();
        }
        if (ssrcInfo.getSsrc().equals(ssrcInResponse)) {
            // ssrc 一致
            if (mediaServerItem.isRtpEnable()) {
                // 多端口
                if (device.getStreamMode().equalsIgnoreCase("TCP-ACTIVE")) {
                    tcpActiveHandler(device, channel, contentString, mediaServerItem, ssrcInfo, callback, inviteSessionType, inviteInfo);
                }
            }else {
                // 单端口
                if (device.getStreamMode().equalsIgnoreCase("TCP-ACTIVE")) {
                    log.warn("[Invite 200OK] 单端口收流模式不支持tcp主动模式收流");
                }

            }
        }else {
            log.info("[Invite 200OK] 收到invite 200, 发现下级自定义了ssrc: {}", ssrcInResponse);
            String oldStreamId = String.format("%08x", Long.parseLong(ssrcInfo.getSsrc())).toUpperCase();
            String newStreamId = String.format("%08x", Long.parseLong(ssrcInResponse)).toUpperCase();
            if (!mediaServerItem.isRtpEnable()) { // 多端口时按照端口绑定了stream，即使stream与ssrc不一致，也不会影响
                receiveRtpServerService.refreshAuthenticateInfo(ssrcInfo, oldStreamId, newStreamId);
            }
            // ssrc 不一致
            if (mediaServerItem.isRtpEnable()) {
                // 多端口
                if (device.isSsrcCheck()) {
                    // ssrc检验
                    // 更新ssrc
                    log.info("[Invite 200OK] SSRC修正 {}->{}", ssrcInfo.getSsrc(), ssrcInResponse);
                    String zlmStream = ssrcInfo.getZlmStream();
                    // SSRC correction targets the ZLM listener, never the
                    // published/business stream. Legacy records without the
                    // listener id must fail closed instead of guessing.
                    Boolean result = zlmStream != null && !zlmStream.isEmpty()
                            && mediaServerService.updateRtpServerSSRC(
                            mediaServerItem, ssrcInfo.getApp(), zlmStream, ssrcInResponse);
                    if (!result) {
                        failInvite(inviteInfo, InviteErrorCode.ERROR_FOR_RESET_SSRC.getCode(),
                                "下级自定义了ssrc,重新设置收流信息失败");
                        return;

                    }else {
                        ssrcInfo.setSsrc(ssrcInResponse);
                        inviteInfo.setSsrcInfo(ssrcInfo);
                        inviteInfo.setStream(ssrcInfo.getStream());
                        if (device.getStreamMode().equalsIgnoreCase("TCP-ACTIVE")) {
                            if (mediaServerItem.isRtpEnable()) {
                                tcpActiveHandler(device, channel, contentString, mediaServerItem,  ssrcInfo, callback, inviteSessionType, inviteInfo);
                            }else {
                                log.warn("[Invite 200OK] 单端口收流模式不支持tcp主动模式收流");
                            }
                        }
                        inviteStreamService.updateInviteInfoForSSRC(inviteInfo, ssrcInResponse);
                    }
                } else {
                    ssrcInfo.setSsrc(ssrcInResponse);
                    inviteInfo.setSsrcInfo(ssrcInfo);
                    inviteInfo.setStream(ssrcInfo.getStream());
                    if (device.getStreamMode().equalsIgnoreCase("TCP-ACTIVE")) {
                        if (mediaServerItem.isRtpEnable()) {
                            tcpActiveHandler(device, channel, contentString, mediaServerItem,  ssrcInfo, callback, inviteSessionType, inviteInfo);
                        }else {
                            log.warn("[Invite 200OK] 单端口收流模式不支持tcp主动模式收流");
                        }
                    }
                    inviteStreamService.updateInviteInfoForSSRC(inviteInfo, ssrcInResponse);
                }
            }else {
                if (ssrcInResponse != null) {
                    // 单端口
                    // 重新订阅流上线
                    SsrcTransaction ssrcTransaction = sessionManager.getSsrcTransactionByStream(MediaStreamUtil.RTP_APP, inviteInfo.getStream());
                    if (ssrcTransaction == null) {
                        throw new IllegalStateException("Accepted SIP session disappeared during SSRC correction");
                    }
                    inviteStreamService.updateInviteInfoForSSRC(inviteInfo, ssrcInResponse);
                    ssrcTransaction.setDeviceId(device.getDeviceId());
                    ssrcTransaction.setChannelId(ssrcTransaction.getChannelId());
                    ssrcTransaction.setCallId(ssrcTransaction.getCallId());
                    ssrcTransaction.setSsrc(ssrcInResponse);
                    ssrcTransaction.setApp(MediaStreamUtil.RTP_APP);
                    ssrcTransaction.setStream(inviteInfo.getStream());
                    ssrcTransaction.setMediaServerId(mediaServerItem.getId());
                    ssrcTransaction.setSipTransactionInfo(new SipTransactionInfo((SIPResponse) responseEvent.getResponse()));
                    ssrcTransaction.setType(inviteSessionType);

                    if (!sessionManager.put(ssrcTransaction))
                        throw new IllegalStateException("Corrected SIP session commit was not confirmed");
                }
            }
        }
        // Media may arrive while SDP/SSRC processing runs; do not activate before this barrier.
        inviteStreamService.recordSipReady(inviteInfo, callId);
    }

    @Override
    public void download(Device device, DeviceChannel channel, String startTime, String endTime, int downloadSpeed, ErrorCallback<StreamInfo> callback) {

        if (!userSetting.getServerId().equals(device.getServerId())) {
            redisRpcPlayService.download(device.getServerId(), channel.getId(), startTime, endTime, downloadSpeed, callback);
            return;
        }

        MediaServer newMediaServerItem = this.getNewMediaServerItem(device);
        if (newMediaServerItem == null) {
            callback.run(InviteErrorCode.ERROR_FOR_ASSIST_NOT_READY.getCode(),
                    InviteErrorCode.ERROR_FOR_ASSIST_NOT_READY.getMsg(),
                    null);
            return;
        }

        download(newMediaServerItem, device, channel, startTime, endTime, downloadSpeed, callback);
    }


    private void download(MediaServer mediaServer, Device device, DeviceChannel channel, String startTime,
                          String endTime, int downloadSpeed, ErrorCallback<StreamInfo> callback) {
        startInvite(mediaServer, device, channel, InviteSessionType.DOWNLOAD, startTime, endTime, null, true, downloadSpeed, callback);
    }

    @Override
    public StreamInfo getDownLoadInfo(Device device, DeviceChannel channel, String stream) {


        InviteInfo inviteInfo = inviteStreamService.getInviteInfo(InviteSessionType.DOWNLOAD, channel.getId(), stream);
        if (inviteInfo == null) {
            String app = MediaStreamUtil.RTP_APP;
            StreamAuthorityInfo streamAuthorityInfo = redisCatchStorage.getStreamAuthorityInfo(app, stream);
            if (streamAuthorityInfo != null) {
                List<CloudRecordItem> allList = cloudRecordService.getAllList(null, app, stream, null, null, null, streamAuthorityInfo.getCallId(), null);
                if (allList.isEmpty()) {
                    log.warn("[获取下载进度] 未查询到录像下载的信息 {}/{}-{}", device.getDeviceId(), channel.getDeviceId(), stream);
                    return null;
                }

                String mediaServerId = allList.get(0).getMediaServerId();
                MediaServer mediaServer = mediaServerService.getOne(mediaServerId);
                if (mediaServer == null) {
                    log.warn("[获取下载进度] 未查询到录像下载的节点信息 {}/{}-{}", device.getDeviceId(), channel.getDeviceId(), stream);
                    return null;
                }
                log.warn("[获取下载进度] 发现下载已经结束，直接从数据库获取到文件 {}/{}-{}", device.getDeviceId(), channel.getDeviceId(), stream);
                DownloadFileInfo downloadFileInfo = mediaServerService.getDownloadFilePath(mediaServer, RecordInfo.getInstance(allList.get(0)));
                StreamInfo streamInfo = new StreamInfo();
                streamInfo.setDownLoadFilePath(downloadFileInfo);
                streamInfo.setApp(app);
                streamInfo.setStream(stream);
                streamInfo.setServerId(mediaServerId);
                streamInfo.setProgress(1.0);
                return streamInfo;
            }
        }

        if (inviteInfo == null || inviteInfo.getStreamInfo() == null) {
            log.warn("[获取下载进度] 未查询到录像下载的信息 {}/{}-{}", device.getDeviceId(), channel.getDeviceId(), stream);
            return null;
        }

        if (inviteInfo.getStreamInfo().getProgress() >= 1) {
            if (inviteInfo.getCleanupAt() == null) {
                inviteInfo.setCleanupAt(System.currentTimeMillis() + DOWNLOAD_CLEANUP_RETENTION_MILLIS);
                inviteStreamService.updateInviteInfo(inviteInfo);
            }
            return inviteInfo.getStreamInfo();
        }

        // 获取当前已下载时长
        MediaServer mediaServerItem = inviteInfo.getStreamInfo().getMediaServer();
        if (mediaServerItem == null) {
            log.warn("[获取下载进度] 查询录像信息时发现节点不存在");
            return null;
        }
        String app = MediaStreamUtil.RTP_APP;
        Long duration  = mediaServerService.updateDownloadProcess(mediaServerItem, app, stream);
        if (duration == null || duration == 0) {
            inviteInfo.getStreamInfo().setProgress(0);
        } else {
            String startTime = inviteInfo.getStreamInfo().getStartTime();
            String endTime = inviteInfo.getStreamInfo().getEndTime();
            // 此时start和end单位是秒
            long start = DateUtil.yyyy_MM_dd_HH_mm_ssToTimestamp(startTime);
            long end = DateUtil.yyyy_MM_dd_HH_mm_ssToTimestamp(endTime);

            BigDecimal currentCount = new BigDecimal(duration);
            BigDecimal totalCount = new BigDecimal((end - start) * 1000);
            BigDecimal divide = currentCount.divide(totalCount, 2, RoundingMode.HALF_UP);
            double process = divide.doubleValue();
            if (process > 0.999) {
                process = 1.0;
                if (inviteInfo.getCleanupAt() == null) {
                    inviteInfo.setCleanupAt(System.currentTimeMillis() + DOWNLOAD_CLEANUP_RETENTION_MILLIS);
                }
            }
            inviteInfo.getStreamInfo().setProgress(process);
        }
        inviteStreamService.updateInviteInfo(inviteInfo);
        return inviteInfo.getStreamInfo();
    }



    public StreamInfo onPublishHandler(MediaServer mediaServerItem, MediaInfo mediaInfo, Device device, DeviceChannel channel) {
        StreamInfo streamInfo = mediaServerService.getStreamInfoByAppAndStream(mediaServerItem, MediaStreamUtil.RTP_APP, mediaInfo.getStream(), mediaInfo, null);
        streamInfo.setDeviceId(device.getDeviceId());
        streamInfo.setChannelId(channel.getId());
        return streamInfo;
    }


    @Override
    public void zlmServerOffline(MediaServer mediaServer) {
        // 处理正在向上推流的上级平台
        List<SendRtpInfo> sendRtpInfos = sendRtpServerService.queryAll();
        if (!sendRtpInfos.isEmpty()) {
            for (SendRtpInfo sendRtpInfo : sendRtpInfos) {
                if (sendRtpInfo.getMediaServerId().equals(mediaServer.getId()) && sendRtpInfo.isSendToPlatform()) {
                    Platform platform = platformService.queryPlatformByServerGBId(sendRtpInfo.getTargetId());
                    CommonGBChannel channel = channelService.getOne(sendRtpInfo.getChannelId());
                    try {
                        sipCommanderFroPlatform.streamByeCmd(platform, sendRtpInfo, channel);
                    } catch (SipException | InvalidArgumentException | ParseException e) {
                        log.error("[命令发送失败] 国标级联 发送BYE: {}", e.getMessage());
                    }
                }
            }
        }
        // 处理正在观看的国标设备
        for (InviteInfo invite : inviteStreamService.getActiveInviteInfoByMediaServer(mediaServer.getId())) stopIfOwner(invite);
        List<SsrcTransaction> allSsrc = sessionManager.getAll();
        if (allSsrc.size() > 0) {
            for (SsrcTransaction ssrcTransaction : allSsrc) {
                if (ssrcTransaction.getInviteId() != null) continue;
                if (ssrcTransaction.getMediaServerId().equals(mediaServer.getId())) {
                    Device device = deviceService.getDeviceByDeviceId(ssrcTransaction.getDeviceId());
                    if (device == null) {
                        continue;
                    }
                    DeviceChannel deviceChannel = deviceChannelService.getOneById(ssrcTransaction.getChannelId());
                    if (deviceChannel == null) {
                        continue;
                    }
                    try {
                        cmder.streamByeCmd(device, deviceChannel.getDeviceId(), ssrcTransaction.getApp(),
                                ssrcTransaction.getStream(), null, null);
                    } catch (InvalidArgumentException | ParseException | SipException |
                             SsrcTransactionNotFoundException e) {
                        log.error("[zlm离线]为正在使用此zlm的设备， 发送BYE失败 {}", e.getMessage());
                    }
                }
            }
        }
    }

    @Override
    public AudioBroadcastResult audioBroadcast(String deviceId, String channelDeviceId, Boolean broadcastMode) {

        Device device = deviceService.getDeviceByDeviceId(deviceId);
        if (device == null) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "未找到设备： " + deviceId);
        }
        DeviceChannel deviceChannel = deviceChannelService.getOne(deviceId, channelDeviceId);
        if (deviceChannel == null) {
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "未找到通道： " + channelDeviceId);
        }

        if (!userSetting.getServerId().equals(device.getServerId())) {
            return redisRpcPlayService.audioBroadcast(device.getServerId(), deviceId, channelDeviceId, broadcastMode);
        }
        log.info("[语音喊话] device： {}, channel: {}", device.getDeviceId(), deviceChannel.getDeviceId());
        MediaServer mediaServerItem = mediaServerService.getMediaServerForMinimumLoad(null);
        if (broadcastMode == null) {
            broadcastMode = true;
        }
        String app = broadcastMode ? MediaStreamUtil.GB28181_BROADCAST : MediaStreamUtil.GB28181_TALK;
        String stream = device.getDeviceId() + "_" + deviceChannel.getDeviceId();
        AudioBroadcastResult audioBroadcastResult = new AudioBroadcastResult();
        audioBroadcastResult.setApp(app);
        audioBroadcastResult.setStream(stream);
        audioBroadcastResult.setStreamInfo(new StreamContent(mediaServerService.getStreamInfoByAppAndStream(mediaServerItem, app, stream, null, null, null, false)));
        if (!broadcastMode) {
            audioBroadcastResult.setPlayStreamInfo(new StreamContent(
                    mediaServerService.getStreamInfoByAppAndStream(mediaServerItem,
                            MediaStreamUtil.GB28181_TALK, stream + "_talk",
                            null, null, null, true)));
        }
        audioBroadcastResult.setCodec("G.711");
        return audioBroadcastResult;
    }

    @Override
    public boolean audioBroadcastCmd(Device device, DeviceChannel deviceChannel, MediaServer mediaServerItem, String app, String stream, int timeout, boolean isFromPlatform, AudioBroadcastEvent event) throws InvalidArgumentException, ParseException, SipException {
        Assert.notNull(device, "设备不存在");
        Assert.notNull(deviceChannel, "通道不存在");
        log.info("[语音喊话] device： {}, channel: {}", device.getDeviceId(), deviceChannel.getDeviceId());
        // 查询通道使用状态
        if (audioBroadcastManager.exit(deviceChannel.getId())) {
            SendRtpInfo sendRtpInfo = sendRtpServerService.queryByChannelId(deviceChannel.getId(), device.getDeviceId());
            if (sendRtpInfo != null && sendRtpInfo.isOnlyAudio()) {
                // 查询流是否存在，不存在则认为是异常状态
                Boolean streamReady = mediaServerService.isStreamReady(mediaServerItem, sendRtpInfo.getApp(), sendRtpInfo.getStream());
                if (streamReady) {
                    log.warn("语音广播已经开启： {}", deviceChannel.getDeviceId());
                    event.call("语音广播已经开启");
                    return false;
                } else {
                    stopAudioBroadcast(device, deviceChannel);
                }
            }
        }

        // 发送通知
        cmder.audioBroadcastCmd(device, deviceChannel.getDeviceId(), eventResultForOk -> {
            // 发送成功
            AudioBroadcastCatch audioBroadcastCatch = new AudioBroadcastCatch(device.getDeviceId(), deviceChannel.getId(), mediaServerItem, app, stream, event, AudioBroadcastCatchStatus.Ready, isFromPlatform);
            audioBroadcastManager.update(audioBroadcastCatch);
            // 等待invite消息， 超时则结束
            String key = VideoManagerConstants.BROADCAST_WAITE_INVITE +  device.getDeviceId();
            if (!SipUtils.isFrontEnd(device.getDeviceId())) {
                key += audioBroadcastCatch.getChannelId();
            }
            dynamicTask.startDelay(key, ()->{
                log.info("[语音广播]等待invite消息超时：{}/{}", device.getDeviceId(), deviceChannel.getDeviceId());
                stopAudioBroadcast(device, deviceChannel);
            }, 10*1000);
        }, eventResultForError -> {
            // 发送失败
            log.error("语音广播发送失败： {}:{}", deviceChannel.getDeviceId(), eventResultForError.msg);
            event.call("语音广播发送失败");
            stopAudioBroadcast(device, deviceChannel);
        });
        return true;
    }

    @Override
    public boolean audioBroadcastInUse(Device device, DeviceChannel channel) {
        if (audioBroadcastManager.exit(channel.getId())) {
            SendRtpInfo sendRtpInfo = sendRtpServerService.queryByChannelId(channel.getId(), device.getDeviceId());
            if (sendRtpInfo != null && sendRtpInfo.isOnlyAudio()) {
                // 查询流是否存在，不存在则认为是异常状态
                MediaServer mediaServerServiceOne = mediaServerService.getOne(sendRtpInfo.getMediaServerId());
                Boolean streamReady = mediaServerService.isStreamReady(mediaServerServiceOne, sendRtpInfo.getApp(), sendRtpInfo.getStream());
                if (streamReady) {
                    log.warn("语音广播通道使用中： {}", channel.getDeviceId());
                    return true;
                }
            }
        }
        return false;
    }


    @Override
    public void stopAudioBroadcast(Device device, DeviceChannel channel) {
        log.info("[停止对讲] 设备：{}, 通道：{}", device.getDeviceId(), channel.getDeviceId());
        List<AudioBroadcastCatch> audioBroadcastCatchList = new ArrayList<>();
        if (channel == null) {
            audioBroadcastCatchList.addAll(audioBroadcastManager.getByDeviceId(device.getDeviceId()));
        } else {
            audioBroadcastCatchList.addAll(audioBroadcastManager.getByDeviceId(device.getDeviceId()));
        }
        if (!audioBroadcastCatchList.isEmpty()) {
            for (AudioBroadcastCatch audioBroadcastCatch : audioBroadcastCatchList) {
                if (audioBroadcastCatch == null) {
                    continue;
                }
                SendRtpInfo sendRtpInfo = sendRtpServerService.queryByChannelId(channel.getId(), device.getDeviceId());
                if (sendRtpInfo != null) {
                    sendRtpServerService.delete(sendRtpInfo);
                    MediaServer mediaServer = mediaServerService.getOne(sendRtpInfo.getMediaServerId());
                    mediaServerService.stopSendRtp(mediaServer, sendRtpInfo.getApp(), sendRtpInfo.getStream(), null);
                    try {
                        cmder.streamByeCmdForDeviceInvite(device, channel.getDeviceId(), audioBroadcastCatch.getSipTransactionInfo(), null);
                    } catch (InvalidArgumentException | ParseException | SipException |
                             SsrcTransactionNotFoundException e) {
                        log.error("[消息发送失败] 发送语音喊话BYE失败");
                    }
                }

                audioBroadcastManager.del(channel.getId());
            }
        }
    }

    @Override
    public void zlmServerOnline(MediaServer mediaServer) {
        if (mediaServer == null || mediaServer.getId() == null) {
            return;
        }
        if (!mediaServer.isRtpEnable()) {
            return;
        }
        List<InviteInfo> inviteInfoList = inviteStreamService.getActiveInviteInfoByMediaServer(mediaServer.getId());
        if (inviteInfoList == null || inviteInfoList.isEmpty()) {
            return;
        }

        List<String> rtpServerList;
        try {
            rtpServerList = mediaServerService.listRtpServer(mediaServer);
        } catch (RuntimeException e) {
            log.warn("[媒体节点上线] 查询RTP监听失败，跳过清理：{}", mediaServer.getId(), e);
            return;
        }
        if (rtpServerList == null) {
            return;
        }
        for (InviteInfo inviteInfo : inviteInfoList) {
            if (inviteInfo.getLifecycle() != InviteLifecycle.ACTIVE || inviteInfo.getStreamInfo() == null) {
                continue;
            }
            if (!isReconciliableType(inviteInfo) || !hasRtpIdentity(inviteInfo)) {
                continue;
            }
            if (!mediaServer.getId().equals(resolveMediaServerId(inviteInfo))) {
                continue;
            }
            if (isCompletedDownloadRetained(inviteInfo)) {
                continue;
            }
            String streamKey = resolveActualRtpStream(inviteInfo);
            if (streamKey != null && rtpServerList.stream().noneMatch(item -> streamKey.equalsIgnoreCase(item))) {
                stopIfOwner(inviteInfo);
            }
        }
    }

    private String resolveMediaServerId(InviteInfo inviteInfo) {
        if (inviteInfo.getStreamInfo() != null && inviteInfo.getStreamInfo().getMediaServer() != null
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
        // SSRC-only legacy records are reconciled by explicit close paths
        // after listRtpServer validation, never by this broad online sweep.
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

    private boolean isCompletedDownloadRetained(InviteInfo inviteInfo) {
        return inviteInfo.getType() == InviteSessionType.DOWNLOAD
                && inviteInfo.getStreamInfo() != null
                && inviteInfo.getStreamInfo().getProgress() >= 1
                && inviteInfo.getCleanupAt() != null
                && System.currentTimeMillis() < inviteInfo.getCleanupAt();
    }

    @Override
    public void playbackPause(String streamId) throws ServiceException, InvalidArgumentException, ParseException, SipException {

        InviteInfo inviteInfo = inviteStreamService.getInviteInfoByStream(InviteSessionType.PLAYBACK, streamId);
        if (null == inviteInfo || inviteInfo.getStreamInfo() == null) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "streamId不存在");
        }
        Device device = deviceService.getDeviceByDeviceId(inviteInfo.getDeviceId());
        if (device == null) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "设备不存在");
        }
        if (!userSetting.getServerId().equals(device.getServerId())) {
            redisRpcPlayService.playbackPause(device.getServerId(), streamId);
            return;
        }

        inviteInfo.getStreamInfo().setPause(true);
        inviteStreamService.updateInviteInfo(inviteInfo);
        MediaServer mediaServerItem = inviteInfo.getStreamInfo().getMediaServer();
        if (null == mediaServerItem) {
            log.warn("mediaServer 不存在!");
            throw new ServiceException("mediaServer不存在");
        }
        // zlm 暂停RTP超时检查
        // 使用zlm中的流ID
        String streamKey = inviteInfo.getSsrcInfo().getZlmStream();
        if (!mediaServerItem.isRtpEnable()) {
            streamKey = Long.toHexString(Long.parseLong(inviteInfo.getSsrcInfo().getSsrc())).toUpperCase();
        }
        Boolean result = mediaServerService.pauseRtpCheck(mediaServerItem, streamKey);
        if (!result) {
            throw new ServiceException("暂停RTP接收失败");
        }

        DeviceChannel channel = deviceChannelService.getOneById(inviteInfo.getChannelId());
        cmder.playPauseCmd(device, channel, inviteInfo.getStreamInfo());
    }

    @Override
    public void playbackResume(String streamId) throws ServiceException, InvalidArgumentException, ParseException, SipException {
        InviteInfo inviteInfo = inviteStreamService.getInviteInfoByStream(InviteSessionType.PLAYBACK, streamId);
        if (null == inviteInfo || inviteInfo.getStreamInfo() == null) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "streamId不存在");
        }
        Device device = deviceService.getDeviceByDeviceId(inviteInfo.getDeviceId());
        if (device == null) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "设备不存在");
        }
        if (!userSetting.getServerId().equals(device.getServerId())) {
            redisRpcPlayService.playbackResume(device.getServerId(), streamId);
            return;
        }

        inviteInfo.getStreamInfo().setPause(false);
        inviteStreamService.updateInviteInfo(inviteInfo);
        MediaServer mediaServerItem = inviteInfo.getStreamInfo().getMediaServer();
        if (null == mediaServerItem) {
            log.warn("mediaServer 不存在!");
            throw new ServiceException("mediaServer不存在");
        }
        // 使用zlm中的流ID
        String streamKey = inviteInfo.getSsrcInfo().getZlmStream();
        if (!mediaServerItem.isRtpEnable()) {
            streamKey = Long.toHexString(Long.parseLong(inviteInfo.getSsrcInfo().getSsrc())).toUpperCase();
        }
        boolean result = mediaServerService.resumeRtpCheck(mediaServerItem, streamKey);
        if (!result) {
            throw new ServiceException("继续RTP接收失败");
        }
        DeviceChannel channel = deviceChannelService.getOneById(inviteInfo.getChannelId());
        cmder.playResumeCmd(device, channel, inviteInfo.getStreamInfo());
    }

    @Override
    public void playbackSeek(String streamId, long seekTime) throws InvalidArgumentException, ParseException, SipException {
        InviteInfo inviteInfo = inviteStreamService.getInviteInfoByStream(InviteSessionType.PLAYBACK, streamId);

        if (null == inviteInfo || inviteInfo.getStreamInfo() == null) {
            log.warn("streamId不存在!");
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "streamId不存在");
        }
        Device device = deviceService.getDeviceByDeviceId(inviteInfo.getDeviceId());
        DeviceChannel channel = deviceChannelService.getOneById(inviteInfo.getChannelId());
        cmder.playSeekCmd(device, channel, inviteInfo.getStreamInfo(), seekTime);
    }

    @Override
    public void playbackSpeed(String streamId, double speed) throws InvalidArgumentException, ParseException, SipException {
        InviteInfo inviteInfo = inviteStreamService.getInviteInfoByStream(InviteSessionType.PLAYBACK, streamId);

        if (null == inviteInfo || inviteInfo.getStreamInfo() == null) {
            log.warn("streamId不存在!");
            throw new ControllerException(ErrorCode.ERROR400.getCode(), "streamId不存在");
        }
        Device device = deviceService.getDeviceByDeviceId(inviteInfo.getDeviceId());
        DeviceChannel channel = deviceChannelService.getOneById(inviteInfo.getChannelId());
        cmder.playSpeedCmd(device, channel, inviteInfo.getStreamInfo(), speed);
    }

    @Override
    public void startPushStream(SendRtpInfo sendRtpInfo, DeviceChannel channel, SIPResponse sipResponse, Platform platform, CallIdHeader callIdHeader) {
        // 开始发流
        MediaServer mediaInfo = mediaServerService.getOne(sendRtpInfo.getMediaServerId());

        if (mediaInfo != null) {
            try {
                if (sendRtpInfo.isTcpActive()) {
                    mediaServerService.startSendRtpPassive(mediaInfo, sendRtpInfo, null);
                } else {
                    mediaServerService.startSendRtp(mediaInfo, sendRtpInfo);
                }
                redisCatchStorage.sendPlatformStartPlayMsg(sendRtpInfo, channel, platform);
            }catch (ControllerException e) {
                log.error("RTP推流失败: {}", e.getMessage());
                startSendRtpStreamFailHand(sendRtpInfo, platform, callIdHeader);
                return;
            }

            log.info("RTP推流成功[ {}/{} ]，{}, ", sendRtpInfo.getApp(), sendRtpInfo.getStream(),
                    sendRtpInfo.isTcpActive()?"被动发流": sendRtpInfo.getIp() + ":" + sendRtpInfo.getPort());

        }
    }

    @Override
    public void startSendRtpStreamFailHand(SendRtpInfo sendRtpInfo, Platform platform, CallIdHeader callIdHeader) {
        if (sendRtpInfo.isOnlyAudio()) {
            Device device = deviceService.getDeviceByDeviceId(sendRtpInfo.getTargetId());
            DeviceChannel deviceChannel = deviceChannelService.getOneById(sendRtpInfo.getChannelId());
            AudioBroadcastCatch audioBroadcastCatch = audioBroadcastManager.get(sendRtpInfo.getChannelId());
            if (audioBroadcastCatch != null) {
                try {
                    cmder.streamByeCmd(device, deviceChannel.getDeviceId(), audioBroadcastCatch.getSipTransactionInfo(), null);
                } catch (SipException | ParseException | InvalidArgumentException |
                         SsrcTransactionNotFoundException exception) {
                    log.error("[命令发送失败] 停止语音对讲: {}", exception.getMessage());
                }
            }
        } else {
            if (platform != null) {
                // 向上级平台
                CommonGBChannel channel = channelService.getOne(sendRtpInfo.getChannelId());
                try {
                    commanderForPlatform.streamByeCmd(platform, sendRtpInfo, channel);
                } catch (SipException | InvalidArgumentException | ParseException e) {
                    log.error("[命令发送失败] 国标级联 发送BYE: {}", e.getMessage());
                }
            }

        }
    }

    @Override
    public void talkCmd(Device device, DeviceChannel channel, MediaServer mediaServerItem, String stream, AudioBroadcastEvent event) {
        if (device == null || channel == null) {
            return;
        }
        // TODO 必须多端口模式才支持语音喊话鹤语音对讲
        log.info("[语音对讲] device： {}, channel: {}", device.getDeviceId(), channel.getDeviceId());
        // 查询通道使用状态
        if (audioBroadcastManager.exit(channel.getId())) {
            SendRtpInfo sendRtpInfo = sendRtpServerService.queryByChannelId(channel.getId(), device.getDeviceId());
            if (sendRtpInfo != null && sendRtpInfo.isOnlyAudio()) {
                // 查询流是否存在，不存在则认为是异常状态
                MediaServer mediaServer = mediaServerService.getOne(sendRtpInfo.getMediaServerId());
                Boolean streamReady = mediaServerService.isStreamReady(mediaServer, sendRtpInfo.getApp(), sendRtpInfo.getStream());
                if (streamReady) {
                    log.warn("[语音对讲] 正在语音广播，无法开启语音通话： {}", channel.getDeviceId());
                    event.call("正在语音广播");
                    return;
                } else {
                    stopAudioBroadcast(device, channel);
                }
            }
        }

        SendRtpInfo sendRtpInfo = sendRtpServerService.queryByChannelId(channel.getId(), device.getDeviceId());
        if (sendRtpInfo != null) {
            MediaServer mediaServer = mediaServerService.getOne(sendRtpInfo.getMediaServerId());
            Boolean streamReady = mediaServerService.isStreamReady(mediaServer, MediaStreamUtil.GB28181_TALK, sendRtpInfo.getReceiveStream());
            if (streamReady) {
                log.warn("[语音对讲] 进行中： {}", channel.getDeviceId());
                event.call("语音对讲进行中");
                return;
            } else {
                stopTalk(device, channel);
            }
        }

        talk(mediaServerItem, device, channel, stream, eventResult -> {
            log.warn("[语音对讲] 失败，{}/{}, 错误码 {} {}", device.getDeviceId(), channel.getDeviceId(), eventResult.statusCode, eventResult.msg);
            event.call("失败，错误码 " + eventResult.statusCode + ", " + eventResult.msg);
        }, () -> {
            log.warn("[语音对讲] 失败，{}/{} 超时", device.getDeviceId(), channel.getDeviceId());
            event.call("失败，超时 ");
        }, errorMsg -> {
            log.warn("[语音对讲] 失败，{}/{} {}", device.getDeviceId(), channel.getDeviceId(), errorMsg);
            event.call(errorMsg);
        });
    }

    private void stopTalk(Device device, DeviceChannel channel) {
        stopTalk(device, channel, null, null);
    }

    @Override
    public void stopTalk(Device device, DeviceChannel channel, Boolean streamIsReady) {
        stopTalk(device, channel, streamIsReady, null);
    }

    @Override
    public void stopTalkForDevice(Device device) {
        if (device == null || device.getDeviceId() == null) {
            return;
        }
        for (java.util.Map.Entry<Integer, TalkSsrcOwner> entry : talkSsrcLeases.entrySet()) {
            Integer channelId = entry.getKey();
            TalkSsrcOwner owner = entry.getValue();
            if (!device.getDeviceId().equals(owner.deviceId)
                    || !talkSsrcLeases.remove(channelId, owner)) {
                continue;
            }
            stopTalkTimeoutTask(owner.timeoutTaskKey);
            try {
                SendRtpInfo sendRtpInfo = sendRtpServerService.queryByChannelId(channelId, device.getDeviceId());
                if (sendRtpInfo != null) {
                    if (talkSsrcLeases.get(channelId) != null || !matchesTalkOwner(owner, sendRtpInfo)) {
                        log.warn("[设备离线] 发现新的对讲owner，跳过旧发送资源清理：deviceId={}, channelId={}",
                                device.getDeviceId(), channelId);
                        continue;
                    }
                    try {
                        MediaServer mediaServer = mediaServerService.getOne(sendRtpInfo.getMediaServerId());
                        mediaServerService.stopSendRtp(mediaServer, sendRtpInfo.getApp(),
                                sendRtpInfo.getStream(), sendRtpInfo.getSsrc());
                    } catch (RuntimeException e) {
                        log.warn("[设备离线] 停止对讲发送资源失败：deviceId={}, channelId={}",
                                device.getDeviceId(), channelId, e);
                    }
                    sendRtpServerService.deleteByChannel(channelId, device.getDeviceId());
                }
                if (owner.callId != null) {
                    sessionManager.removeByCallId(owner.callId);
                }
            } catch (RuntimeException e) {
                // Redis/send-info failures must not prevent the local lease release.
                log.warn("[设备离线] 查询对讲发送资源失败，仍释放 lease：deviceId={}, channelId={}",
                        device.getDeviceId(), channelId, e);
            } finally {
                releaseTalkLeaseDirect(owner.lease, channelId);
            }
        }
    }

    private void stopTalk(Device device, DeviceChannel channel, Boolean streamIsReady, TalkSsrcOwner expectedOwner) {
        log.info("[语音对讲] 停止， {}/{}", device.getDeviceId(), channel.getDeviceId());
        if (expectedOwner != null && talkSsrcLeases.get(channel.getId()) != expectedOwner) {
            // A timeout/error callback from an older talk must not touch a replacement owner.
            log.info("[语音对讲] 跳过过期停止回调：channelId={}, callId={}", channel.getId(), expectedOwner.callId);
            return;
        }
        SendRtpInfo sendRtpInfo = sendRtpServerService.queryByChannelId(channel.getId(), device.getDeviceId());
        if (sendRtpInfo == null) {
            if (expectedOwner != null) {
                releaseTalkLease(channel.getId(), expectedOwner);
            } else {
                // Without the send identity there is no proof that this callback owns the
                // current lease; fail closed instead of releasing a replacement talk.
                log.warn("[语音对讲] 未找到发送信息，保留当前 lease：channelId={}", channel.getId());
            }
            log.info("[语音对讲] 停止失败， 未找到发送信息，可能已经停止");
            return;
        }
        if (expectedOwner != null && !matchesTalkOwner(expectedOwner, sendRtpInfo)) {
            log.warn("[语音对讲] 发送信息不属于当前owner，跳过清理：channelId={}, callId={}",
                    channel.getId(), expectedOwner.callId);
            return;
        }
        TalkSsrcOwner currentOwner = talkSsrcLeases.get(channel.getId());
        if (expectedOwner == null && currentOwner != null && !matchesTalkOwner(currentOwner, sendRtpInfo)) {
            log.warn("[语音对讲] 发送信息不属于当前owner，跳过清理：channelId={}", channel.getId());
            return;
        }
        // 停止向设备推流
        String mediaServerId = sendRtpInfo.getMediaServerId();
        if (mediaServerId == null) {
            if (expectedOwner != null) {
                releaseTalkLease(channel.getId(), expectedOwner);
            } else {
                releaseTalkLeaseForCurrent(channel.getId(), sendRtpInfo);
            }
            return;
        }

        try {
            MediaServer mediaServer = mediaServerService.getOne(mediaServerId);

            if (streamIsReady == null || streamIsReady) {
                mediaServerService.stopSendRtp(mediaServer, sendRtpInfo.getApp(), sendRtpInfo.getStream(), sendRtpInfo.getSsrc());
            }

            SsrcTransaction ssrcTransaction = sessionManager.getSsrcTransactionByStream(sendRtpInfo.getApp(), sendRtpInfo.getStream());
            if (ssrcTransaction != null) {
                try {
                    cmder.streamByeCmd(device, channel.getDeviceId(), sendRtpInfo.getApp(), sendRtpInfo.getStream(), null, null);
                } catch (InvalidArgumentException | ParseException | SipException | SsrcTransactionNotFoundException  e) {
                    log.info("[语音对讲] 停止消息发送失败，可能已经停止");
                }
            }
            sendRtpServerService.deleteByChannel(channel.getId(), device.getDeviceId());
        } catch (RuntimeException e) {
            log.warn("[语音对讲] 停止媒体资源失败，继续释放SSRC：channelId={}", channel.getId(), e);
        } finally {
            if (expectedOwner != null) {
                releaseTalkLease(channel.getId(), expectedOwner);
            } else {
                releaseTalkLeaseForCurrent(channel.getId(), sendRtpInfo);
            }
        }
    }

    private void releaseTalkLeaseDirect(SsrcLease lease, Integer channelId) {
        if (lease == null) {
            return;
        }
        try {
            ssrcFactory.release(lease);
        } catch (RuntimeException e) {
            log.warn("[语音对讲] 释放SSRC lease失败，channelId={}", channelId, e);
        }
    }

    private void stopTalkTimeoutTask(String taskKey) {
        if (dynamicTask == null || taskKey == null) {
            return;
        }
        try {
            dynamicTask.stop(taskKey);
        } catch (RuntimeException e) {
            log.warn("[语音对讲] 停止超时任务失败，继续清理资源：taskKey={}", taskKey, e);
        }
    }

    private void releaseTalkLease(Integer channelId, TalkSsrcOwner expectedOwner) {
        if (channelId == null || expectedOwner == null || !talkSsrcLeases.remove(channelId, expectedOwner)) {
            return;
        }
        stopTalkTimeoutTask(expectedOwner.timeoutTaskKey);
        try {
            ssrcFactory.release(expectedOwner.lease);
        } catch (RuntimeException e) {
            log.warn("[语音对讲] 释放SSRC lease失败，channelId={}, callId={}", channelId, expectedOwner.callId, e);
        }
    }

    private void releaseTalkLeaseForCurrent(Integer channelId, SendRtpInfo sendRtpInfo) {
        if (channelId == null) {
            return;
        }
        TalkSsrcOwner owner = talkSsrcLeases.get(channelId);
        if (owner == null) {
            return;
        }
        if (sendRtpInfo != null && !Objects.equals(owner.stream, sendRtpInfo.getStream())) {
            return;
        }
        if (sendRtpInfo != null && sendRtpInfo.getCallId() != null
                && !Objects.equals(owner.callId, sendRtpInfo.getCallId())) {
            return;
        }
        releaseTalkLease(channelId, owner);
    }

    private boolean matchesTalkOwner(TalkSsrcOwner owner, SendRtpInfo sendRtpInfo) {
        if (owner == null || sendRtpInfo == null || !Objects.equals(owner.stream, sendRtpInfo.getStream())) {
            return false;
        }
        if (owner.sendSsrc != null && !Objects.equals(owner.sendSsrc, sendRtpInfo.getSsrc())) {
            return false;
        }
        return sendRtpInfo.getCallId() == null || owner.callId == null
                || Objects.equals(owner.callId, sendRtpInfo.getCallId());
    }

    @Override
    public void getSnap(String deviceId, String channelId, String fileName, ErrorCallback errorCallback) {
        Device device = deviceService.getDeviceByDeviceId(deviceId);
        Assert.notNull(device, "设备不存在");
        DeviceChannel channel = deviceChannelService.getOne(deviceId, channelId);
        Assert.notNull(channel, "通道不存在");
        InviteInfo inviteInfo = inviteStreamService.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, channel.getId());
        if (inviteInfo != null) {
            if (inviteInfo.getStreamInfo() != null) {
                // 已存在线直接截图
                MediaServer mediaServer = inviteInfo.getStreamInfo().getMediaServer();
                String path = "snap";
                // 请求截图
                log.info("[请求截图]: " + fileName);
                mediaServerService.getSnap(mediaServer, MediaStreamUtil.RTP_APP,  inviteInfo.getStreamInfo().getStream(), 15, 1, path, fileName);
                File snapFile = new File(path + File.separator + fileName);
                if (snapFile.exists()) {
                    errorCallback.run(InviteErrorCode.SUCCESS.getCode(), InviteErrorCode.SUCCESS.getMsg(), snapFile.getAbsoluteFile());
                }else {
                    errorCallback.run(InviteErrorCode.FAIL.getCode(), InviteErrorCode.FAIL.getMsg(), null);
                }
                return;
            }
        }

        MediaServer newMediaServerItem = getNewMediaServerItem(device);
        play(newMediaServerItem, deviceId, channelId, null, (code, msg, data)->{
            if (code == InviteErrorCode.SUCCESS.getCode()) {
                InviteInfo inviteInfoForPlay = inviteStreamService.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, channel.getId());
                if (inviteInfoForPlay != null && inviteInfoForPlay.getStreamInfo() != null) {
                    getSnap(deviceId, channelId, fileName, errorCallback);
                }else {
                    errorCallback.run(InviteErrorCode.FAIL.getCode(), InviteErrorCode.FAIL.getMsg(), null);
                }
            }else {
                errorCallback.run(InviteErrorCode.FAIL.getCode(), InviteErrorCode.FAIL.getMsg(), null);
            }
        });
    }

    @Override
    public void getSnap(CommonGBChannel channel, ErrorCallback<byte[]> errorCallback) {
        // 2016协议不支持直接获取国标通道的抓图， 只能通过点播的方式获取
        Device device = deviceService.getDevice(channel.getDataDeviceId());
        if (device == null) {
            log.warn("[快照] 未找到通道{}的设备信息", channel);
            errorCallback.run(InviteErrorCode.FAIL.getCode(), "未找到设备信息", null);
            return;
        }
        DeviceChannel deviceChannel = deviceChannelService.getOneForSourceById(channel.getGbId());
        if (deviceChannel == null) {
            log.warn("[快照] 未找到通道{}的设备信息", channel);
            errorCallback.run(InviteErrorCode.FAIL.getCode(), "未找到原始通道", null);
            return;
        }

        InviteInfo inviteInfo = inviteStreamService.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, channel.getGbId());
        if (inviteInfo != null) {
            if (inviteInfo.getStreamInfo() != null) {
                // 已存在线直接截图
                MediaServer mediaServer = inviteInfo.getStreamInfo().getMediaServer();
                String path = "snap";
                // 请求截图
                log.info("[请求截图]: 返回byte数组" );
                byte[] snapByteArray = mediaServerService.getSnap(mediaServer, MediaStreamUtil.RTP_APP,  inviteInfo.getStreamInfo().getStream(), 15, 1, path, null);
                if (snapByteArray != null) {
                    errorCallback.run(InviteErrorCode.SUCCESS.getCode(), InviteErrorCode.SUCCESS.getMsg(), snapByteArray);
                }else {
                    errorCallback.run(InviteErrorCode.FAIL.getCode(), InviteErrorCode.FAIL.getMsg(), null);
                }
                return;
            }
        }

        play(device, deviceChannel, (code, msg, data)->{
            if (code == InviteErrorCode.SUCCESS.getCode()) {
                InviteInfo inviteInfoForPlay = inviteStreamService.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, channel.getGbId());
                if (inviteInfoForPlay != null && inviteInfoForPlay.getStreamInfo() != null) {
                    byte[] snapByteArray = mediaServerService.getSnap(data.getMediaServer(), MediaStreamUtil.RTP_APP,  data.getStream(), 15, 1, null, null);
                    errorCallback.run(InviteErrorCode.SUCCESS.getCode(), InviteErrorCode.SUCCESS.getMsg(), snapByteArray);
                }else {
                    errorCallback.run(InviteErrorCode.FAIL.getCode(), InviteErrorCode.FAIL.getMsg(), null);
                }
            }else {
                errorCallback.run(InviteErrorCode.FAIL.getCode(), InviteErrorCode.FAIL.getMsg(), null);
            }
        });
    }



    @Override
    public void stop(InviteSessionType type, Device device, DeviceChannel channel, String stream) {
        if (!userSetting.getServerId().equals(device.getServerId())) {
            redisRpcPlayService.stop(device.getServerId(), type,  channel.getId(), stream);
        }else {
            log.info("[停止点播/回放/下载] {}/{}", device.getDeviceId(), channel.getDeviceId());
            InviteInfo inviteInfo = inviteStreamService.getInviteInfo(type, channel.getId(), stream);
            if (inviteInfo == null) {
                return;
            }
            stopInviteResources(inviteInfo, device, channel, stream);
        }
    }

    @Override
    public void stop(InviteInfo inviteInfo) {
        Assert.notNull(inviteInfo, "参数异常");
        DeviceChannel channel = null;
        try {
            channel = deviceChannelService.getOneForSourceById(inviteInfo.getChannelId());
        } catch (RuntimeException e) {
            log.warn("[停止点播] 查询通道失败，继续清理本地资源: {}", e.getMessage());
        }
        if (channel == null) {
            log.warn("[停止点播] 发现通道不存在");
        }
        Device device = null;
        if (channel != null) {
            try {
                device = deviceService.getDevice(channel.getDataDeviceId());
            } catch (RuntimeException e) {
                log.warn("[停止点播] 查询设备失败，继续清理本地资源: {}", e.getMessage());
            }
        }
        if (device == null) {
            log.warn("[停止点播] 发现设备不存在");
        }
        stopInviteResources(inviteInfo, device, channel, inviteInfo.getStream());
    }

    @Override
    public boolean stopIfOwner(InviteInfo expected) {
        return beginInviteCleanup(expected, false);
    }

    @Override
    public boolean stopAfterDeviceBye(InviteInfo expected) {
        return beginInviteCleanup(expected, true);
    }

    private boolean beginInviteCleanup(InviteInfo expected, boolean skipBye) {
        if (expected == null) {
            return false;
        }
        InviteInfoCleanupStore.Job job = null;
        try {
            var prepared = inviteCleanupStore.prepare(expected, true, skipBye);
            if (prepared.result() == InviteInfoDeleteResult.ABSENT) return true;
            if (prepared.result() != null || prepared.job() == null || !prepared.job().stop()) return false;
            job = inviteCleanupStore.claim(prepared.job().id());
            return job != null && processInviteCleanup(job);
        } catch (RuntimeException e) {
            log.error("[Invite清理] 停止或补偿登记失败，不能确认完成：stream={}", expected.getStream(), e);
            if (job != null) inviteCleanupStore.deferSafely(job);
            return false;
        }
    }

    private boolean processInviteCleanup(InviteInfoCleanupStore.Job initial) {
        InviteInfoCleanupStore.Job job = initial;
        for (String step : List.of("BYE", "SIP", "RTP", "STATE")) {
            job = inviteCleanupStore.refresh(job);
            if (job == null) return false;
            if (job.completed().contains(step)) continue;
            if ("SIP".equals(step) && !job.completed().contains("BYE")) continue;
            if (!inviteCleanupStore.ownsResources(job)) return false;
            InviteInfo invite = job.invite();
            boolean complete = false;
            try {
                switch (step) {
                    case "BYE" -> {
                        boolean pendingSip = invite.isSipSent() && !invite.isSipFinal()
                                && System.currentTimeMillis() < invite.getCreateTime() + invite.getExpirationTime();
                        if (pendingSip) complete = false;
                        else if (invite.isSkipBye() || !invite.isSipAccepted()) complete = true;
                        else if (inviteCleanupStore.terminateByeLocally(job)) complete = true;
                        else if (invite.getType() == InviteSessionType.BROADCAST) complete = safeSendPlatformBye(invite, job.token());
                        else {
                            Device device = invite.getSipDevice() != null ? invite.getSipDevice() : deviceService.getDeviceByDeviceId(invite.getDeviceId());
                            DeviceChannel channel;
                            if (invite.getRemoteChannelId() != null) {
                                channel = new DeviceChannel(); channel.setDeviceId(invite.getRemoteChannelId());
                            } else channel = deviceChannelService.getOneForSourceById(invite.getChannelId());
                            complete = device != null && channel != null
                                    && safeSendBye(invite, device, channel, invite.getStream(), invite.getCallId(), job.token());
                        }
                    }
                    case "SIP" -> complete = removeOwnedSipSession(invite, invite.getStream(), invite.getCallId());
                    case "RTP" -> complete = !receiveRtpServerService.isRtpResourceOpening(invite.getSsrcInfo())
                            && (!invite.isRtpOpening() || System.currentTimeMillis() >= invite.getCreateTime() + invite.getExpirationTime())
                            && closeInviteRtp(invite, invite.getStream());
                    case "STATE" -> complete = invite.getType() != InviteSessionType.PLAY || playProjection.close(invite);
                    default -> throw new IllegalStateException("Unknown cleanup step");
                }
            } catch (RuntimeException error) {
                log.warn("[Invite清理] 步骤失败，保留原代资源身份：inviteId={}, step={}", job.id(), step, error);
            }
            if (complete && !inviteCleanupStore.completeStep(job, step)) return false;
        }
        job = inviteCleanupStore.refresh(job);
        if (job == null) return false;
        if ("DONE".equals(job.phase())) {
            boolean closed = inviteCleanupStore.finish(job);
            if (closed) inviteStreamService.complete(job.invite(), InviteErrorCode.FAIL.getCode(), "Invite stopped", null);
            return closed;
        }
        inviteCleanupStore.deferSafely(job);
        return false;
    }

    private void stopInviteResources(InviteInfo inviteInfo, Device device, DeviceChannel channel,
                                     String fallbackStream) {
        beginInviteCleanup(inviteInfo, false);
    }

    @Override
    public int retryPendingInviteCleanups(int limit) {
        long started = System.nanoTime();
        int completed = 0;
        for (String id : inviteCleanupStore.pending(Math.min(100, limit))) {
            if (System.nanoTime() - started >= 500_000_000L) break;
            InviteInfoCleanupStore.Job job = null;
            try {
                job = inviteCleanupStore.claim(id);
                if (job != null && processInviteCleanup(job)) completed++;
            } catch (RuntimeException error) {
                log.warn("[Invite清理] 后台补偿未完成：inviteId={}", id, error);
                if (job != null) inviteCleanupStore.deferSafely(job);
            }
        }
        return completed;
    }

    private boolean safeSendBye(InviteInfo inviteInfo, Device device, DeviceChannel channel, String stream, String callId, String lease) {
        try {
            if (callId == null) return !inviteInfo.isSipSent();
            if (callId.isEmpty()) return true;
            String app = inviteInfo.getSsrcInfo() == null || inviteInfo.getSsrcInfo().getApp() == null
                    ? MediaStreamUtil.RTP_APP : inviteInfo.getSsrcInfo().getApp();
            if (inviteInfo.isByeAcknowledged()) return true;
            SsrcTransaction current = sessionManager.getSsrcTransactionByCallId(callId);
            if (current != null && !isOwnedSession(inviteInfo, current)) return false;
            SipTransactionInfo dialog = inviteInfo.getSipDialog() != null ? inviteInfo.getSipDialog()
                    : current == null ? null : current.getSipTransactionInfo();
            if (dialog == null || !callId.equals(dialog.getCallId())) return false;
            // This overload keeps the dialog in Redis if request construction/transmission fails.
            // The Call-ID overload deletes it before sending, which would destroy our retry data.
            var attempt = inviteRepository.beginByeAttempt(inviteInfo.getInviteId(), lease);
            if (attempt.code() == InviteWriteResult.Code.STATE_CONFLICT) return false;
            var authorized = attempt.requireApplied();
            if (authorized.isByeAcknowledged() || authorized.isSkipBye()) return true;
            if (authorized.getLifecycle() != InviteLifecycle.STOPPING || authorized.getByeTerminatedLocallyAt() != null) return false;
            cmder.streamByeCmd(device, channel.getDeviceId(), dialog,
                    event -> inviteRepository.byeAcknowledged(inviteInfo.getInviteId()).requireApplied());
            log.info("[停止点播/回放/下载] BYE已发送 {}/{}", device.getDeviceId(), channel.getDeviceId());
            return false; // Keep retry data until peer confirmation or fenced local termination.
        } catch (SsrcTransactionNotFoundException e) {
            log.debug("[停止点播] SIP会话已清理，无需重复发送BYE：stream={}", stream);
            return true;
        } catch (InvalidArgumentException | SipException | ParseException e) {
            log.warn("[命令发送失败] 停止点播/回放/下载，发送BYE: {}", e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.warn("[命令发送失败] 停止点播/回放/下载，BYE运行时异常: {}", e.getMessage());
            return false;
        }
    }


    private boolean removeOwnedSipSession(InviteInfo inviteInfo, String fallbackStream, String callId) {
        String app = MediaStreamUtil.RTP_APP;
        String stream = inviteInfo.getStream() == null ? fallbackStream : inviteInfo.getStream();
        SSRCInfo ssrcInfo = inviteInfo.getSsrcInfo();
        if (ssrcInfo != null) {
            if (ssrcInfo.getApp() != null) {
                app = ssrcInfo.getApp();
            }
            if (ssrcInfo.getStream() != null) {
                stream = ssrcInfo.getStream();
            }
        } else if (inviteInfo.getStreamInfo() != null) {
            if (inviteInfo.getStreamInfo().getApp() != null) {
                app = inviteInfo.getStreamInfo().getApp();
            }
            if (inviteInfo.getStreamInfo().getStream() != null) {
                stream = inviteInfo.getStreamInfo().getStream();
            }
        }
        if (stream == null) {
            return true;
        }
        try {
            if (callId == null) return !inviteInfo.isSipSent();
            if (callId.isEmpty()) return true;
            SsrcTransaction current = sessionManager.getSsrcTransactionByCallId(callId);
            if (current != null && isOwnedSession(inviteInfo, current)) {
                if (current.getCallId() == null || current.getCallId().isEmpty()) {
                    log.warn("[停止点播] SIP会话缺少Call-ID，未确认删除：app={}, stream={}", app, stream);
                    return false;
                }
                return sessionManager.removeByCallId(current.getCallId());
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("[停止点播] 删除SIP session失败，继续清理RTP: {}", e.getMessage());
            return false;
        }
    }

    private boolean isOwnedSession(InviteInfo inviteInfo, SsrcTransaction transaction) {
        if (inviteInfo.getInviteId() == null || !inviteInfo.getInviteId().equals(transaction.getInviteId())) return false;
        if (inviteInfo.getDeviceId() != null && !inviteInfo.getDeviceId().equals(transaction.getDeviceId())) {
            return false;
        }
        if (inviteInfo.getChannelId() != null && !inviteInfo.getChannelId().equals(transaction.getChannelId())) {
            return false;
        }
        if (inviteInfo.getType() != null && !inviteInfo.getType().equals(transaction.getType())) {
            return false;
        }
        if (inviteInfo.getMediaServerId() != null && !inviteInfo.getMediaServerId().equals(transaction.getMediaServerId())) {
            return false;
        }
        return true;
    }

    private boolean safeSendPlatformBye(InviteInfo invite, String lease) {
        try {
            if (invite.isByeAcknowledged()) return true;
            SsrcTransaction session = sessionManager.getSsrcTransactionByCallId(invite.getCallId());
            if (session != null && !isOwnedSession(invite, session)) return false;
            SipTransactionInfo dialog = invite.getSipDialog() != null ? invite.getSipDialog()
                    : session == null ? null : session.getSipTransactionInfo();
            if (dialog == null || !Objects.equals(invite.getCallId(), dialog.getCallId())) return false;
            Platform platform = invite.getSipPlatform() != null ? invite.getSipPlatform() : platformService.queryPlatformByServerGBId(invite.getPlatformId());
            if (platform == null) return false;
            CommonGBChannel channel = new CommonGBChannel();
            channel.setGbDeviceId(invite.getRemoteChannelId());
            var attempt = inviteRepository.beginByeAttempt(invite.getInviteId(), lease);
            if (attempt.code() == InviteWriteResult.Code.STATE_CONFLICT) return false;
            var authorized = attempt.requireApplied();
            if (authorized.isByeAcknowledged() || authorized.isSkipBye()) return true;
            if (authorized.getLifecycle() != InviteLifecycle.STOPPING || authorized.getByeTerminatedLocallyAt() != null) return false;
            sipCommanderFroPlatform.streamByeCmd(platform, channel, dialog,
                    event -> inviteRepository.byeAcknowledged(invite.getInviteId()).requireApplied());
            return false;
        } catch (InvalidArgumentException | SipException | ParseException error) {
            log.warn("[Invite清理] 平台BYE发送失败：inviteId={}", invite.getInviteId(), error);
            return false;
        }
    }

    private boolean closeInviteRtp(InviteInfo invite, String fallbackStream) {
        SSRCInfo resource = invite.getSsrcInfo();
        if (resource == null) return true;
        MediaServer node = mediaServerService.getOne(invite.getMediaServerId());
        if (node == null) return false;
        receiveRtpServerService.closeRtpResource(resource);
        if (node.isRtpEnable()) mediaServerService.closeRTPServer(node, resource.getApp(), resource.getZlmStream());
        mediaServerService.closeStreams(node, resource.getApp(), resource.getStream());
        List<String> listeners = mediaServerService.listRtpServer(node);
        Boolean media = mediaServerService.probeStream(node, resource.getApp(), resource.getStream());
        boolean closed = (!node.isRtpEnable() || listeners != null && !listeners.contains(resource.getZlmStream()))
                && Boolean.FALSE.equals(media);
        if (closed && resource.getLeaseId() != null) ssrcFactory.release(new SsrcLease(node.getId(),
                resource.getAllocatedSsrc(), true, resource.getLeaseId()));
        return closed;
    }

    @Override
    public void play(CommonGBChannel channel, Boolean record, ErrorCallback<StreamInfo> callback) {
        Device device = deviceService.getDevice(channel.getDataDeviceId());
        if (device == null) {
            log.warn("[点播] 未找到通道{}的设备信息", channel);
            throw new PlayException(Response.SERVER_INTERNAL_ERROR, "server internal error");
        }
        DeviceChannel deviceChannel = deviceChannelService.getOneForSourceById(channel.getGbId());

        MediaServer mediaServerItem = getNewMediaServerItem(device);
        if (mediaServerItem == null) {
            log.warn("[点播] 未找到可用的zlm deviceId: {},channelId:{}", device.getDeviceId(), deviceChannel.getDeviceId());
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "未找到可用的zlm");
        }
        play(mediaServerItem, device, deviceChannel, null, record, callback);

    }

    @Override
    public void stopPlay(InviteSessionType inviteSessionType, CommonGBChannel channel) {
        Device device = deviceService.getDevice(channel.getDataDeviceId());
        if (device == null) {
            log.warn("[停止播放] 未找到通道{}的设备信息", channel);
            throw new PlayException(Response.SERVER_INTERNAL_ERROR, "server internal error");
        }
        DeviceChannel deviceChannel = deviceChannelService.getOneForSourceById(channel.getGbId());
        stop(inviteSessionType, device, deviceChannel, null);
    }

    @Override
    public void stop(InviteSessionType inviteSessionType, CommonGBChannel channel, String stream) {
        Device device = deviceService.getDevice(channel.getDataDeviceId());
        if (device == null) {
            log.warn("[停止播放] 未找到通道{}的设备信息", channel);
            throw new PlayException(Response.SERVER_INTERNAL_ERROR, "server internal error");
        }
        DeviceChannel deviceChannel = deviceChannelService.getOneForSourceById(channel.getGbId());
        stop(inviteSessionType, device, deviceChannel, stream);
    }

    @Override
    public void playBack(CommonGBChannel channel, Long startTime, Long stopTime, ErrorCallback<StreamInfo> callback) {
        if (startTime == null || stopTime == null) {
            throw new PlayException(Response.BAD_REQUEST, "bad request");
        }
        // 国标通道
        Device device = deviceService.getDevice(channel.getDataDeviceId());
        if (device == null) {
            log.warn("[点播] 未找到通道{}的设备信息", channel);
            throw new PlayException(Response.SERVER_INTERNAL_ERROR, "server internal error");
        }
        DeviceChannel deviceChannel = deviceChannelService.getOneById(channel.getGbId());
        if (deviceChannel == null) {
            log.warn("[点播] 未找到通道{}", channel.getGbDeviceId());
            throw new PlayException(Response.SERVER_INTERNAL_ERROR, "server internal error");
        }
        String startTimeStr = DateUtil.timestampTo_yyyy_MM_dd_HH_mm_ss(startTime);
        String stopTimeStr = DateUtil.timestampTo_yyyy_MM_dd_HH_mm_ss(stopTime);
        playBack(device, deviceChannel, startTimeStr, stopTimeStr, callback);
    }

    @Override
    public void download(CommonGBChannel channel, Long startTime, Long stopTime, Integer downloadSpeed, ErrorCallback<StreamInfo> callback) {
        if (startTime == null || stopTime == null || downloadSpeed == null) {
            throw new PlayException(Response.BAD_REQUEST, "bad request");
        }
        // 国标通道
        Device device = deviceService.getDevice(channel.getDataDeviceId());
        if (device == null) {
            log.warn("[点播] 未找到通道{}的设备信息", channel);
            throw new PlayException(Response.SERVER_INTERNAL_ERROR, "server internal error");
        }
        DeviceChannel deviceChannel = deviceChannelService.getOneById(channel.getGbId());
        if (deviceChannel == null) {
            log.warn("[点播] 未找到通道{}", channel.getGbDeviceId());
            throw new PlayException(Response.SERVER_INTERNAL_ERROR, "server internal error");
        }
        String startTimeStr = DateUtil.timestampTo_yyyy_MM_dd_HH_mm_ss(startTime);
        String stopTimeStr = DateUtil.timestampTo_yyyy_MM_dd_HH_mm_ss(stopTime);
        download(device, deviceChannel, startTimeStr, stopTimeStr, downloadSpeed, callback);

    }
}
