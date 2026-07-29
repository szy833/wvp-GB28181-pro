package com.genersoft.iot.vmp.service.impl;

import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.common.enums.MediaStreamUtil;
import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.session.SSRCFactory;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.bean.ResultForOnPublish;
import com.genersoft.iot.vmp.media.event.hook.Hook;
import com.genersoft.iot.vmp.media.event.hook.HookData;
import com.genersoft.iot.vmp.media.event.hook.HookSubscribe;
import com.genersoft.iot.vmp.media.event.hook.HookType;
import com.genersoft.iot.vmp.media.event.media.MediaArrivalEvent;
import com.genersoft.iot.vmp.media.event.media.MediaDepartureEvent;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import com.genersoft.iot.vmp.service.bean.InviteErrorCode;
import com.genersoft.iot.vmp.service.bean.RTPServerParam;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import com.genersoft.iot.vmp.service.bean.RtpResourceContext;
import com.genersoft.iot.vmp.service.bean.RtpServerOpenResult;
import com.genersoft.iot.vmp.gb28181.session.SsrcLease;
import com.genersoft.iot.vmp.media.event.hook.HookSubscriptionHandle;
import com.genersoft.iot.vmp.utils.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import java.util.Arrays;
import java.util.Locale;

@Slf4j
@Service
public class RtpServerServiceImpl implements IReceiveRtpServerService {

    private final static String TIMEOUT_TASK_KEY_PREFIX = "RTP_SERVER_TIMEOUT_TASK";
    private final ConcurrentHashMap<String, RtpResourceContext> activeResources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RtpResourceContext> resourceOwners = new ConcurrentHashMap<>();
    private static final DefaultRedisScript<Long> AUTH_COMPARE_DELETE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[2]) == ARGV[1] then "
                    + "redis.call('del', KEYS[1]); redis.call('del', KEYS[2]); return 1; "
                    + "end; return 0;", Long.class);
    private static final DefaultRedisScript<Long> AUTH_COMPARE_MOVE = new DefaultRedisScript<>(
            "local oldOwner = redis.call('get', KEYS[2]); "
                    + "if oldOwner == false or oldOwner ~= ARGV[1] then return 0; end; "
                    + "local newOwner = redis.call('get', KEYS[4]); "
                    + "local newValue = redis.call('get', KEYS[3]); "
                    + "if newOwner == false and newValue ~= false then return -1; end; "
                    + "if newOwner ~= false and newOwner ~= ARGV[1] then return -1; end; "
                    + "local value = redis.call('get', KEYS[1]); "
                    + "if value == false then return 0; end; "
                    + "redis.call('set', KEYS[3], value, 'EX', 60); "
                    + "redis.call('set', KEYS[4], ARGV[1], 'EX', 60); "
                    + "redis.call('del', KEYS[1], KEYS[2]); return 1;", Long.class);

    @Autowired
    private IMediaServerService mediaServerService;

    @Autowired
    private DynamicTask dynamicTask;

    @Autowired
    private SSRCFactory ssrcFactory;

    @Autowired
    private UserSetting userSetting;

    @Autowired
    private HookSubscribe subscribe;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 流到来的处理
     */
    @Async
    @org.springframework.context.event.EventListener
    public void onApplicationEvent(MediaArrivalEvent event) {
        if (event == null || event.getMediaServer() == null || event.getApp() == null || event.getStream() == null) {
            return;
        }
        if (event.getSchema() != null && !"rtsp".equalsIgnoreCase(event.getSchema())) {
            return;
        }
        String ownerKey = resourceOwnerKey(event.getMediaServer().getId(), event.getApp(), event.getStream());
        RtpResourceContext context = resourceOwners.get(ownerKey);
        if (context == null) {
            log.debug("[RTP媒体到达] 未匹配资源：mediaServer={}, app={}, stream={}, schema={}",
                    event.getMediaServer().getId(), event.getApp(), event.getStream(), event.getSchema());
            return;
        }
        log.info("[RTP媒体到达] 已匹配资源：resourceId={}, state={}, mediaServer={}, app={}, stream={}, schema={}",
                context.getResourceId(), context.getState(), event.getMediaServer().getId(), event.getApp(),
                event.getStream(), event.getSchema());
        context.onMediaArrival(HookData.getInstance(event));
    }

    /**
     * 流离开的处理
     */
    @Async
    @EventListener
    public void onApplicationEvent(MediaDepartureEvent event) {
        if (event == null || event.getMediaServer() == null || event.getApp() == null || event.getStream() == null) {
            return;
        }
        RtpResourceContext context = findDepartureOwner(event);
        if (context == null) {
            log.debug("[RTP媒体离开] 未匹配资源：mediaServer={}, app={}, stream={}, schema={}, originUrl={}",
                    event.getMediaServer().getId(), event.getApp(), event.getStream(), event.getSchema(),
                    event.getOriginUrl());
            return;
        }
        com.genersoft.iot.vmp.service.bean.RtpResourceState state = context.getState();
        log.info("[RTP媒体离开] 已匹配资源：resourceId={}, state={}, mediaServer={}, app={}, stream={}, schema={}, originUrl={}",
                context.getResourceId(), state, event.getMediaServer().getId(), event.getApp(), event.getStream(),
                event.getSchema(), event.getOriginUrl());
        if (state == com.genersoft.iot.vmp.service.bean.RtpResourceState.REGISTERING
                || state == com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA) {
            context.completeDeparture(InviteErrorCode.FAIL.getCode(), "媒体流已离开", null);
        } else if (state == com.genersoft.iot.vmp.service.bean.RtpResourceState.SUCCESS) {
            context.close("media departure");
        }
    }

    private RtpResourceContext findDepartureOwner(MediaDepartureEvent event) {
        String mediaServerId = event.getMediaServer().getId();
        String app = event.getApp();
        String zlmStreamId = extractRtpStreamId(event.getOriginUrl());
        if (zlmStreamId != null) {
            RtpResourceContext context = resourceOwners.get(resourceOwnerKey(mediaServerId, app, zlmStreamId));
            if (context != null && (event.getStream().equals(context.getBusinessStreamId())
                    || event.getStream().equalsIgnoreCase(context.getZlmStreamId()))) {
                return context;
            }
            return null;
        }

        RtpResourceContext context = resourceOwners.get(resourceOwnerKey(mediaServerId, app, event.getStream()));
        if (context != null && event.getStream().equals(context.getZlmStreamId())) {
            return context;
        }
        return null;
    }

    private String extractRtpStreamId(String originUrl) {
        if (originUrl == null) {
            return null;
        }
        int marker = originUrl.lastIndexOf("/rtp/");
        if (marker < 0) {
            return null;
        }
        String streamId = originUrl.substring(marker + 5);
        int slash = streamId.indexOf('/');
        if (slash >= 0) {
            streamId = streamId.substring(0, slash);
        }
        int query = streamId.indexOf('?');
        if (query >= 0) {
            streamId = streamId.substring(0, query);
        }
        return streamId.isEmpty() ? null : streamId.toUpperCase(Locale.ROOT);
    }

    private String resourceOwnerKey(String mediaServerId, String app, String stream) {
        return mediaServerId + ":" + app + ":" + stream;
    }

    @Override
    public SSRCInfo openGbRTPServer(MediaServer mediaServer, String streamId, String presetSSRC, int tcpMode,
                                    boolean playback, boolean ssrcCheck, boolean onlyAuto, boolean disableAuto,
                                    ErrorCallback<OpenRTPServerResult> callback) {
        if (callback == null) {
            log.warn("[开启国标RTP收流] 失败，回调为NULL");
            return null;
        }
        if (mediaServer == null) {
            log.warn("[开启国标RTP收流] 失败，媒体节点为NULL");
            callback.run(InviteErrorCode.FAIL.getCode(), "媒体节点为NULL", null);
            return null;
        }

        // 获取 mediaServer 可用的 ssrc
        final String ssrc;
        final SsrcLease lease;
        if (presetSSRC != null) {
            ssrc = presetSSRC;
            lease = null;
        } else {
            lease = playback ? ssrcFactory.allocatePlaybackLease(mediaServer) : ssrcFactory.allocatePlayLease(mediaServer);
            ssrc = lease == null ? null : lease.getSsrc();
        }
        if (ssrc == null) {
            callback.run(InviteErrorCode.ERROR_FOR_SSRC_UNAVAILABLE.getCode(), InviteErrorCode.ERROR_FOR_SSRC_UNAVAILABLE.getMsg(), null);
            return null;
        }
        if (streamId == null) {
            streamId = String.format("%08x", Long.parseLong(ssrc)).toUpperCase();
        }
        if (ssrcCheck && tcpMode > 0) {
            // 目前zlm不支持 tcp模式更新ssrc，暂时关闭ssrc校验
            log.warn("[openRTPServer] 平台对接时下级可能自定义ssrc，但是tcp模式zlm收流目前无法更新ssrc，可能收流超时，此时请使用udp收流或者关闭ssrc校验");
        }

        SSRCInfo ssrcInfo = new SSRCInfo(0, ssrc, MediaStreamUtil.RTP_APP, streamId);
        ssrcInfo.setMediaServerId(mediaServer.getId());
        RTPServerParam rtpServerParam = new RTPServerParam(mediaServer, MediaStreamUtil.RTP_APP, streamId, Long.parseLong(ssrc), null, onlyAuto, disableAuto, false, tcpMode);
        rtpServerParam.setSsrcLease(lease);
        rtpServerParam.setSsrcCheck(ssrcCheck);
        RtpServerOpenResult openResult = openCommonRTPServerWithHandle(rtpServerParam, ((code, msg, data) -> {
            if (code == InviteErrorCode.SUCCESS.getCode()) {
                OpenRTPServerResult openRTPServerResult = new OpenRTPServerResult();
                openRTPServerResult.setHookData(data);
                openRTPServerResult.setSsrcInfo(ssrcInfo);
                callback.run(InviteErrorCode.SUCCESS.getCode(), InviteErrorCode.SUCCESS.getMsg(), openRTPServerResult);
            } else {
                OpenRTPServerResult openRTPServerResult = new OpenRTPServerResult();
                openRTPServerResult.setSsrcInfo(ssrcInfo);
                callback.run(code, msg, openRTPServerResult);
            }
        }));
        ssrcInfo.setPort(openResult.getPort());
        ssrcInfo.setResourceId(openResult.getResourceId());
        ssrcInfo.setZlmStream(openResult.getZlmStreamId());
        return ssrcInfo;
    }

    @Override
    public SSRCInfo openGbRTPServerForPlay(MediaServer mediaServer, Device device, DeviceChannel channel,
                                           String presetSSRC, boolean record, ErrorCallback<OpenRTPServerResult> callback) {
        if (callback == null) {
            log.warn("[开启国标点播RTP收流] 失败，回调为NULL");
            return null;
        }
        if (mediaServer == null) {
            log.warn("[开启国标点播RTP收流] 失败，媒体节点为NULL");
            callback.run(InviteErrorCode.FAIL.getCode(), "媒体节点为NULL", null);
            return null;
        }

        // 获取 mediaServer 可用的 ssrc
        final String ssrc;
        final SsrcLease lease;
        if (presetSSRC != null) {
            ssrc = presetSSRC;
            lease = null;
        } else {
            lease = ssrcFactory.allocatePlayLease(mediaServer);
            ssrc = lease == null ? null : lease.getSsrc();
        }
        if (ssrc == null) {
            callback.run(InviteErrorCode.ERROR_FOR_SSRC_UNAVAILABLE.getCode(), InviteErrorCode.ERROR_FOR_SSRC_UNAVAILABLE.getMsg(), null);
            return null;
        }

        String streamId = String.format("%08x", Long.parseLong(ssrc)).toUpperCase();
        String streamReplace = String.format("%s_%s", device.getDeviceId(), channel.getDeviceId());

        int tcpMode = device.getStreamMode().equals("TCP-ACTIVE")? 2: (device.getStreamMode().equals("TCP-PASSIVE")? 1:0);

        if (device.isSsrcCheck() && tcpMode > 0) {
            log.warn("[开启国标点播RTP收流] 平台对接时下级可能自定义ssrc，但是tcp模式zlm收流目前无法更新ssrc，可能收流超时，此时请使用udp收流或者关闭ssrc校验");
        }

        SSRCInfo ssrcInfo = new SSRCInfo(0, ssrc, MediaStreamUtil.RTP_APP, streamReplace);
        ssrcInfo.setMediaServerId(mediaServer.getId());
        RtpServerOpenResult holder = openRtpServer(mediaServer, ssrcInfo, lease, Long.parseLong(ssrc), !channel.isHasAudio(), false, tcpMode, callback, device.isSsrcCheck());
        if (holder != null && holder.isSuccess()) {
            addAuthenticateInfo(holder, streamId, streamReplace, channel.isHasAudio(), record, null);
        }
        return ssrcInfo;
    }

    @Override
    public SSRCInfo openGbRTPServerForPlayback(MediaServer mediaServer, Device device, DeviceChannel channel,
                                               String startTime, String endTime, ErrorCallback<OpenRTPServerResult> callback) {
        if (callback == null) {
            log.warn("[开启国标回放RTP收流] 失败，回调为NULL");
            return null;
        }
        if (mediaServer == null) {
            log.warn("[开启国标回放RTP收流] 失败，媒体节点为NULL");
            callback.run(InviteErrorCode.FAIL.getCode(), "媒体节点为NULL", null);
            return null;
        }

        // 获取 mediaServer 可用的 ssrc
        SsrcLease lease = ssrcFactory.allocatePlaybackLease(mediaServer);
        String ssrc = lease == null ? null : lease.getSsrc();
        if (ssrc == null) {
            callback.run(InviteErrorCode.ERROR_FOR_SSRC_UNAVAILABLE.getCode(), InviteErrorCode.ERROR_FOR_SSRC_UNAVAILABLE.getMsg(), null);
            return null;
        }

        String streamId = String.format("%08x", Long.parseLong(ssrc)).toUpperCase();
        String streamReplace = getPlaybackStream(device, channel, startTime, endTime);

        int tcpMode = device.getStreamMode().equals("TCP-ACTIVE")? 2: (device.getStreamMode().equals("TCP-PASSIVE")? 1:0);

        if (device.isSsrcCheck() && tcpMode > 0) {
            log.warn("[开启国标回放RTP收流] 平台对接时下级可能自定义ssrc，但是tcp模式zlm收流目前无法更新ssrc，可能收流超时，此时请使用udp收流或者关闭ssrc校验");
        }

        SSRCInfo ssrcInfo = new SSRCInfo(0, ssrc, MediaStreamUtil.RTP_APP, streamReplace);
        ssrcInfo.setMediaServerId(mediaServer.getId());
        RtpServerOpenResult openResult = openRtpServer(mediaServer, ssrcInfo, lease, Long.parseLong(ssrc), !channel.isHasAudio(), false, tcpMode, callback, device.isSsrcCheck());
        if (openResult.isSuccess()) {
            addAuthenticateInfo(openResult, streamId, streamReplace, channel.isHasAudio(), false,null);
        }
        return ssrcInfo;
    }

    @Override
    public String getPlaybackStream(Device device, DeviceChannel channel, String startTime, String endTime) {
        String startTimeStr = startTime.replace("-", "")
                .replace(":", "")
                .replace(" ", "");
        String endTimeTimeStr = endTime.replace("-", "")
                .replace(":", "")
                .replace(" ", "");

        return device.getDeviceId() + "_" + channel.getDeviceId() + "_" + startTimeStr + "_" + endTimeTimeStr;
    }

    @Override
    public SSRCInfo openGbRTPServerForDownload(MediaServer mediaServer, Device device, DeviceChannel channel,
                                               String startTime, String endTime, ErrorCallback<OpenRTPServerResult> callback) {
        if (callback == null) {
            log.warn("[开启国标录像下载RTP收流] 失败，回调为NULL");
            return null;
        }
        if (mediaServer == null) {
            log.warn("[开启国标录像下载RTP收流] 失败，媒体节点为NULL");
            callback.run(InviteErrorCode.FAIL.getCode(), "媒体节点为NULL", null);
            return null;
        }

        int tcpMode = device.getStreamMode().equals("TCP-ACTIVE")? 2: (device.getStreamMode().equals("TCP-PASSIVE")? 1:0);

        // 获取 mediaServer 可用的 ssrc
        SsrcLease lease = ssrcFactory.allocatePlaybackLease(mediaServer);
        String ssrc = lease == null ? null : lease.getSsrc();
        if (ssrc == null) {
            callback.run(InviteErrorCode.ERROR_FOR_SSRC_UNAVAILABLE.getCode(), InviteErrorCode.ERROR_FOR_SSRC_UNAVAILABLE.getMsg(), null);
            return null;
        }

        String streamId = String.format("%08x", Long.parseLong(ssrc)).toUpperCase();
        String streamReplace = String.format("%s_%s_%s_%s", device.getDeviceId(), channel.getDeviceId(),
                startTime.replace("-", "").replace(":", "").replace(" ", ""),
                endTime.replace("-", "").replace(":", "").replace(" ", ""));

        if (device.isSsrcCheck() && tcpMode > 0) {
            log.warn("[开启国标录像下载RTP收流] 平台对接时下级可能自定义ssrc，但是tcp模式zlm收流目前无法更新ssrc，可能收流超时，此时请使用udp收流或者关闭ssrc校验");
        }

        SSRCInfo ssrcInfo = new SSRCInfo(0, ssrc, MediaStreamUtil.RTP_APP, streamReplace);
        ssrcInfo.setMediaServerId(mediaServer.getId());
        RtpServerOpenResult openResult = openRtpServer(mediaServer, ssrcInfo, lease, Long.parseLong(ssrc), !channel.isHasAudio(), false, tcpMode, callback, device.isSsrcCheck());

        long difference = DateUtil.getDifference(startTime, endTime) / 1000;

        if (openResult.isSuccess()) {
            addAuthenticateInfo(openResult, streamId, streamReplace, channel.isHasAudio(), true,  (int) difference);
        }
        return ssrcInfo;
    }

    @Override
    public SSRCInfo openGbRTPServerForBroadcast(MediaServer mediaServer, Platform platform, CommonGBChannel channel,
                                                ErrorCallback<OpenRTPServerResult> callback) {
        if (callback == null) {
            log.warn("[开启国标喊话RTP收流] 失败，回调为NULL");
            return null;
        }
        if (mediaServer == null) {
            log.warn("[开启国标喊话RTP收流] 失败，媒体节点为NULL");
            callback.run(InviteErrorCode.FAIL.getCode(), "媒体节点为NULL", null);
            return null;
        }

        String streamId = null;
        if (mediaServer.isRtpEnable()) {
            streamId = String.format("%s_%s", platform.getServerGBId(), channel.getGbDeviceId());
        }
        // 默认不进行SSRC校验， TODO 后续可改为配置
        int tcpMode;
        if (userSetting.getBroadcastForPlatform().equalsIgnoreCase("TCP-PASSIVE")) {
            tcpMode = 1;
        }else if (userSetting.getBroadcastForPlatform().equalsIgnoreCase("TCP-ACTIVE")) {
            tcpMode = 2;
        } else {
            tcpMode = 0;
        }

        // 获取 mediaServer 可用的 ssrc
        SsrcLease lease = ssrcFactory.allocatePlayLease(mediaServer);
        String ssrc = lease == null ? null : lease.getSsrc();
        if (ssrc == null) {
            callback.run(InviteErrorCode.ERROR_FOR_SSRC_UNAVAILABLE.getCode(), InviteErrorCode.ERROR_FOR_SSRC_UNAVAILABLE.getMsg(), null);
            return null;
        }

        SSRCInfo ssrcInfo = new SSRCInfo(0, ssrc, MediaStreamUtil.RTP_APP, streamId);
        ssrcInfo.setMediaServerId(mediaServer.getId());
        openRtpServer(mediaServer, ssrcInfo, lease, Long.parseLong(ssrc), false, true, tcpMode, callback, false);
        return ssrcInfo;
    }

    private RtpServerOpenResult openRtpServer(MediaServer mediaServer, SSRCInfo ssrcInfo, SsrcLease lease,
                                              Long checkSsrc, boolean disableAuto, boolean onlyAuto, int tcpMode,
                               ErrorCallback<OpenRTPServerResult> callback) {
        return openRtpServer(mediaServer, ssrcInfo, lease, checkSsrc, disableAuto, onlyAuto, tcpMode, callback, false);
    }

    private RtpServerOpenResult openRtpServer(MediaServer mediaServer, SSRCInfo ssrcInfo, SsrcLease lease, Long checkSsrc,
                               boolean disableAuto, boolean onlyAuto, int tcpMode,
                               ErrorCallback<OpenRTPServerResult> callback, boolean ssrcCheck) {

        RTPServerParam rtpServerParam = new RTPServerParam(mediaServer, MediaStreamUtil.RTP_APP, ssrcInfo.getStream(), checkSsrc, null, onlyAuto, disableAuto, false, tcpMode);
        rtpServerParam.setSsrcCheck(ssrcCheck);
        rtpServerParam.setSsrcLease(lease);
        AtomicBoolean callbackReady = new AtomicBoolean(false);
        AtomicReference<Runnable> pendingCallback = new AtomicReference<>();
        RtpServerOpenResult result = openCommonRTPServerWithHandle(rtpServerParam, ((code, msg, data) -> {
            Runnable action = () -> {
                OpenRTPServerResult openRTPServerResult = new OpenRTPServerResult();
                openRTPServerResult.setHookData(code == InviteErrorCode.SUCCESS.getCode() ? data : null);
                openRTPServerResult.setSsrcInfo(ssrcInfo);
                callback.run(code, msg, openRTPServerResult);
            };
            if (callbackReady.get()) {
                action.run();
            } else {
                pendingCallback.set(action);
                if (callbackReady.get()) {
                    Runnable pending = pendingCallback.getAndSet(null);
                    if (pending != null) pending.run();
                }
            }
        }));
        ssrcInfo.setPort(result.getPort());
        ssrcInfo.setResourceId(result.getResourceId());
        ssrcInfo.setZlmStream(result.getZlmStreamId());
        callbackReady.set(true);
        Runnable pending = pendingCallback.getAndSet(null);
        if (pending != null) pending.run();
        return result;
    }

    @Override
    public int openCommonRTPServer(RTPServerParam rtpServerParam, ErrorCallback<HookData> callback) {
        return openCommonRTPServerWithHandle(rtpServerParam, callback).getPort();
    }

    @Override
    public RtpServerOpenResult openCommonRTPServerWithHandle(RTPServerParam param, ErrorCallback<HookData> callback) {
        if (callback == null) {
            log.warn("[开启RTP收流] 失败，回调为NULL");
            if (param != null && ssrcFactory != null) {
                ssrcFactory.release(param.getSsrcLease());
            }
            return new RtpServerOpenResult(-1, null, param == null ? null : param.getStreamId(),
                    param == null ? null : param.getStreamId());
        }
        if (param == null || param.getMediaServer() == null) {
            callback.run(InviteErrorCode.FAIL.getCode(), "媒体节点为NULL", null);
            if (param != null && ssrcFactory != null) {
                ssrcFactory.release(param.getSsrcLease());
            }
            return new RtpServerOpenResult(-1, null, param == null ? null : param.getStreamId(),
                    param == null ? null : param.getStreamId());
        }
        final MediaServer mediaServer = param.getMediaServer();
        final String businessStreamId = param.getStreamId();
        final String zlmStreamId = resolveZlmStreamId(param);
        final String resourceId = UUID.randomUUID().toString();
        final String taskKey = String.format("%s_%s_%s_%s", TIMEOUT_TASK_KEY_PREFIX,
                mediaServer.getId(), param.getApp(), businessStreamId);
        // on_stream_changed uses the final published stream (stream_replace),
        // which is the business stream carried by RTPServerParam. Keep the
        // ZLM RTP listener stream separate for create/close operations.
        final Hook hook = Hook.getInstance(HookType.on_media_arrival, param.getApp(), businessStreamId, mediaServer.getId());
        final AtomicBoolean mayHaveCreatedInZlm = new AtomicBoolean(false);
        final AtomicBoolean creationFinished = new AtomicBoolean(false);
        final RtpResourceContext context = new RtpResourceContext(resourceId, businessStreamId, zlmStreamId,
                callback, () -> {});
        activeResources.put(resourceId, context);
        final String ownerKey = mediaServer.getId() + ":" + param.getApp() + ":" + businessStreamId;
        final String zlmOwnerKey = mediaServer.getId() + ":" + param.getApp() + ":" + zlmStreamId;
        context.setSuccessCleanup(() -> {
            try { context.cleanupRegisteredResources(); }
            catch (Exception e) { log.debug("清理RTP任务和Hook失败", e); }
        });
        context.setTerminalCleanup(() -> {
            try { context.cleanupRegisteredResources(); }
            catch (Exception e) { log.debug("清理RTP任务和Hook失败", e); }
            if (mayHaveCreatedInZlm.get() && resourceOwners.get(ownerKey) == context) {
                if (mediaServer.isRtpEnable()) {
                    try { mediaServerService.closeRTPServer(mediaServer, param.getApp(), zlmStreamId); }
                    catch (Exception e) { log.warn("回滚RTP Server失败: {}", e.getMessage()); }
                }
                try { mediaServerService.closeStreams(mediaServer, param.getApp(), zlmStreamId); }
                catch (Exception e) { log.warn("回滚媒体流失败: {}", e.getMessage()); }
            }
            try {
                synchronized (context) {
                    if (context.getAuthKey() != null) {
                        String authOwnerKey = context.getAuthKey() + ":owner";
                        redisTemplate.execute(AUTH_COMPARE_DELETE,
                                Arrays.asList(context.getAuthKey(), authOwnerKey),
                                String.valueOf(context.getAuthOwner()));
                    }
                }
            } catch (Exception e) { log.warn("回滚RTP鉴权失败: {}", e.getMessage()); }
            try { ssrcFactory.release(param.getSsrcLease()); }
            catch (Exception e) { log.warn("释放SSRC失败: {}", e.getMessage()); }
            if (creationFinished.get()) {
                activeResources.remove(resourceId, context);
                resourceOwners.remove(ownerKey, context);
                if (!zlmOwnerKey.equals(ownerKey)) {
                    resourceOwners.remove(zlmOwnerKey, context);
                }
            }
        });
        if (resourceOwners.putIfAbsent(ownerKey, context) != null) {
            creationFinished.set(true);
            context.completeFailure(InviteErrorCode.FAIL.getCode(), "RTP资源正在创建中", null);
            return new RtpServerOpenResult(-1, resourceId, businessStreamId, zlmStreamId, context);
        }
        if (!zlmOwnerKey.equals(ownerKey) && resourceOwners.putIfAbsent(zlmOwnerKey, context) != null) {
            resourceOwners.remove(ownerKey, context);
            creationFinished.set(true);
            context.completeFailure(InviteErrorCode.FAIL.getCode(), "RTP资源正在创建中", null);
            return new RtpServerOpenResult(-1, resourceId, businessStreamId, zlmStreamId, context);
        }
        try {
            ScheduledFuture<?> task = dynamicTask.startDelayWithHandle(taskKey,
                    () -> context.completeTimeout(InviteErrorCode.ERROR_FOR_STREAM_TIMEOUT.getCode(),
                            InviteErrorCode.ERROR_FOR_STREAM_TIMEOUT.getMsg(), null), userSetting.getPlayTimeout());
            if (!context.registerTask(taskKey, task, dynamicTask)) {
                creationFinished.set(true);
                removeResourceIndexes(resourceId, ownerKey, zlmOwnerKey, context);
                return new RtpServerOpenResult(-1, resourceId, businessStreamId, zlmStreamId, context);
            }
            if (task == null) {
                creationFinished.set(true);
                context.completeFailure(InviteErrorCode.FAIL.getCode(), "RTP超时任务创建失败", null);
                removeResourceIndexes(resourceId, ownerKey, zlmOwnerKey, context);
                return new RtpServerOpenResult(-1, resourceId, businessStreamId, zlmStreamId, context);
            }
            HookSubscriptionHandle hookHandle = subscribe.addSubscribeWithHandle(hook, contextData -> {
                log.info("[RTP媒体到达] 收到Hook回调：resourceId={}, stateBefore={}, mediaServer={}, app={}, stream={}, schema={}",
                        context.getResourceId(), context.getState(),
                        contextData == null || contextData.getMediaServer() == null ? null : contextData.getMediaServer().getId(),
                        contextData == null ? null : contextData.getApp(),
                        contextData == null ? null : contextData.getStream(),
                        contextData == null ? null : contextData.getSchema());
                context.onMediaArrival(contextData);
                log.info("[RTP媒体到达] Hook回调处理完成：resourceId={}, stateAfter={}",
                        context.getResourceId(), context.getState());
            });
            if (!context.registerHook(hookHandle, subscribe)) {
                creationFinished.set(true);
                removeResourceIndexes(resourceId, ownerKey, zlmOwnerKey, context);
                return new RtpServerOpenResult(-1, resourceId, businessStreamId, zlmStreamId, context);
            }
            int port;
            if (mediaServer.isRtpEnable()) {
                long checkSsrc = param.getSsrc() == null ? 0L : (param.isSsrcCheck() ? param.getSsrc() : 0L);
                // A remote call may create the listener before its response fails.
                mayHaveCreatedInZlm.set(true);
                try {
                    port = mediaServerService.createRTPServer(mediaServer, param.getApp(), zlmStreamId, checkSsrc,
                            param.getPort(), param.isOnlyAuto(), param.isDisableAudio(), param.isReUsePort(), param.getTcpMode());
                } catch (RuntimeException e) {
                    throw e;
                }
            } else {
                port = mediaServer.getRtpProxyPort();
                mayHaveCreatedInZlm.set(port >= 0);
            }
            if (port > 0) {
                creationFinished.set(true);
                if (context.getState() != com.genersoft.iot.vmp.service.bean.RtpResourceState.REGISTERING) {
                    cleanupLateCreation(mediaServer, param.getApp(), zlmStreamId, ownerKey, zlmOwnerKey,
                            resourceId, context, taskKey, param.getSsrcLease());
                    return new RtpServerOpenResult(-1, resourceId, businessStreamId, zlmStreamId, context);
                }
                if (!context.markWaitingMedia(port, zlmStreamId)) {
                    // An external close may win between task registration and
                    // the state transition; do not expose a successful port.
                    cleanupLateCreation(mediaServer, param.getApp(), zlmStreamId, ownerKey, zlmOwnerKey,
                            resourceId, context, taskKey, param.getSsrcLease());
                    return new RtpServerOpenResult(-1, resourceId, businessStreamId, zlmStreamId, context);
                }
                return new RtpServerOpenResult(port, resourceId, businessStreamId, zlmStreamId, context);
            }
            if (port == 0) {
                creationFinished.set(true);
                if (context.getState() == com.genersoft.iot.vmp.service.bean.RtpResourceState.REGISTERING) {
                    context.completeFailure(InviteErrorCode.ERROR_FOR_RESOURCE_EXHAUSTION.getCode(), "开启RTPServer失败", null);
                } else {
                    cleanupLateCreation(mediaServer, param.getApp(), zlmStreamId, ownerKey, zlmOwnerKey,
                            resourceId, context, taskKey, param.getSsrcLease());
                }
            } else {
                creationFinished.set(true);
                if (context.getState() == com.genersoft.iot.vmp.service.bean.RtpResourceState.REGISTERING) {
                    context.completeFailure(InviteErrorCode.FAIL.getCode(), "媒体节点创建RTPServer失败", null);
                } else {
                    cleanupLateCreation(mediaServer, param.getApp(), zlmStreamId, ownerKey, zlmOwnerKey,
                            resourceId, context, taskKey, param.getSsrcLease());
                }
            }
            return new RtpServerOpenResult(port, resourceId, businessStreamId, zlmStreamId, context);
        } catch (Exception e) {
            creationFinished.set(true);
            if (context.getState() == com.genersoft.iot.vmp.service.bean.RtpResourceState.REGISTERING) {
                context.completeFailure(InviteErrorCode.FAIL.getCode(), e.getMessage() == null ? "开启RTPServer异常" : e.getMessage(), null);
            } else {
                cleanupLateCreation(mediaServer, param.getApp(), zlmStreamId, ownerKey, zlmOwnerKey,
                        resourceId, context, taskKey, param.getSsrcLease());
            }
            return new RtpServerOpenResult(-1, resourceId, businessStreamId, zlmStreamId, context);
        }
    }

    private String resolveZlmStreamId(RTPServerParam param) {
        if (param.getMediaServer().isRtpEnable() && param.getSsrc() != null && param.getSsrc() != 0) {
            return String.format("%08x", param.getSsrc()).toUpperCase();
        }
        return param.getStreamId();
    }

    /** Completes rollback when close/timeout won while the synchronous create call was still running. */
    private void cleanupLateCreation(MediaServer mediaServer, String app, String zlmStreamId,
                                     String ownerKey, String zlmOwnerKey, String resourceId,
                                     RtpResourceContext context, String taskKey, SsrcLease lease) {
        if (resourceOwners.get(ownerKey) == context) {
            if (mediaServer.isRtpEnable()) {
                try { mediaServerService.closeRTPServer(mediaServer, app, zlmStreamId); }
                catch (Exception e) { log.warn("关闭延迟创建的RTP Server失败: {}", e.getMessage()); }
            }
            try { mediaServerService.closeStreams(mediaServer, app, zlmStreamId); }
            catch (Exception e) { log.warn("关闭延迟创建的媒体流失败: {}", e.getMessage()); }
        }
        try { context.cleanupRegisteredResources(); }
        catch (Exception e) { log.debug("清理延迟创建任务和Hook失败", e); }
        try { ssrcFactory.release(lease); }
        catch (Exception e) { log.warn("释放延迟创建SSRC失败: {}", e.getMessage()); }
        removeResourceIndexes(resourceId, ownerKey, zlmOwnerKey, context);
    }

    private void removeResourceIndexes(String resourceId, String ownerKey, String zlmOwnerKey,
                                       RtpResourceContext context) {
        activeResources.remove(resourceId, context);
        resourceOwners.remove(ownerKey, context);
        resourceOwners.remove(zlmOwnerKey, context);
    }

    @Override
    public void closeRTPServer(RtpServerOpenResult result) {
        if (result == null) return;
        RtpResourceContext context = result.getContext();
        if (context != null) {
            context.close("external close");
            return;
        }
        if (result.getResourceId() != null) {
            RtpResourceContext active = activeResources.get(result.getResourceId());
            if (active != null) active.close("external close");
        }
    }

    @Override
    public void closeRTPServer(SSRCInfo info) {
        if (info == null) return;
        if (info.getResourceId() != null) {
            RtpResourceContext context = activeResources.get(info.getResourceId());
            if (context != null) {
                context.close("owner close");
            }
            // A stale owner must never fall back to an unqualified stream close;
            // that key may already belong to a newer RTP resource.
            return;
        }
        if (info.getMediaServerId() != null) {
            MediaServer server = mediaServerService.getOne(info.getMediaServerId());
            if (server != null) {
                String zlmStream = info.getZlmStream() == null ? info.getStream() : info.getZlmStream();
                closeRTPServer(server, info.getApp(), zlmStream);
            }
        }
    }

    @Override
    public void closeRTPServer(MediaServer mediaServer, String app, String stream) {
        if (mediaServer == null) {
            return;
        }
        RtpResourceContext owned = resourceOwners.get(mediaServer.getId() + ":" + app + ":" + stream);
        if (owned != null) {
            owned.close("legacy close");
            return;
        }
        String timeOutTaskKey = String.format("%s_%s_%s_%s", TIMEOUT_TASK_KEY_PREFIX, mediaServer.getId(), app, stream);
        if (dynamicTask.contains(timeOutTaskKey)) {
            dynamicTask.stop(timeOutTaskKey);
        }
        if (mediaServer.isRtpEnable()) {
            mediaServerService.closeRTPServer(mediaServer, app, stream);
        }
        mediaServerService.closeStreams(mediaServer, app, stream);
    }

    @Override
    public void closeRTPServerByMediaServerId(String mediaServerId, String app, String stream) {
        MediaServer mediaServer = mediaServerService.getOne(mediaServerId);
        if (mediaServer == null) {
            return;
        }
        closeRTPServer(mediaServer, app, stream);
    }

    @Override
    public void addAuthenticateInfoForGb28181Talk(MediaServer mediaServer, String streamId) {
        String streamReplace = null;

        if (!mediaServer.isRtpEnable() ) {
            streamReplace = streamId;
        }

        addAuthenticateInfo(streamId, streamReplace, true, false, null);
    }

    @Override
    public void addAuthenticateInfo(String streamId, String streamReplace, Boolean enableAudio, Boolean enableMp4, Integer mp4MaxSecond) {
        writeAuthenticateInfo(null, streamId, streamReplace, enableAudio, enableMp4, mp4MaxSecond);
     }

     @Override
     public void addAuthenticateInfo(RtpServerOpenResult result, String streamId, String streamReplace,
                                     Boolean enableAudio, Boolean enableMp4, Integer mp4MaxSecond) {
         if (result == null || !result.isSuccess()) {
             return;
         }
         writeAuthenticateInfo(result, streamId, streamReplace, enableAudio, enableMp4, mp4MaxSecond);
     }

     private void writeAuthenticateInfo(RtpServerOpenResult result, String streamId, String streamReplace,
                                        Boolean enableAudio, Boolean enableMp4, Integer mp4MaxSecond) {
        String key = null;
        String ownerToken = null;
        try {
            ResultForOnPublish hookResultForOnPublish = new ResultForOnPublish();
            hookResultForOnPublish.setStream_replace(streamReplace);
            hookResultForOnPublish.setEnable_audio(enableAudio);
            hookResultForOnPublish.setEnable_mp4(enableMp4);
            if (mp4MaxSecond != null) {
                // mp4MaxSecond 需要比实际的流时长略长一些，避免因为流时长超过mp4MaxSecond而生成文件切片
                hookResultForOnPublish.setMp4_max_second(mp4MaxSecond + 10);
            }

            key = String.format("%s:%s", VideoManagerConstants.RTP_AUTHENTICATE, streamId);
            ownerToken = UUID.randomUUID().toString();
            RtpResourceContext context = result == null ? null : result.getContext();
            if (context != null) {
                synchronized (context) {
                    if (!context.isAuthWritable()) {
                        throw new IllegalStateException("RTP resource is no longer writable");
                    }
                    writeAuthValue(key, hookResultForOnPublish, ownerToken);
                    if (!context.markAuthWritten(key, ownerToken)) {
                        deleteAuthIfOwner(key, ownerToken);
                    }
                }
            } else {
                writeAuthValue(key, hookResultForOnPublish, ownerToken);
            }
        } catch (RuntimeException e) {
            if (key != null && ownerToken != null) {
                try {
                    deleteAuthIfOwner(key, ownerToken);
                } catch (RuntimeException cleanupError) {
                    log.warn("清理部分写入的RTP鉴权失败: {}", cleanupError.getMessage());
                }
            }
            if (result != null && result.getContext() != null) {
                result.getContext().close("authentication write failed");
            }
            throw e;
        }
     }

     private void writeAuthValue(String key, ResultForOnPublish value, String ownerToken) {
         String ownerKey = key + ":owner";
         redisTemplate.opsForValue().set(ownerKey, ownerToken);
         redisTemplate.opsForValue().set(key, value);
         redisTemplate.expire(key, 60, TimeUnit.SECONDS);
         redisTemplate.expire(ownerKey, 60, TimeUnit.SECONDS);
     }

     private void deleteAuthIfOwner(String key, String ownerToken) {
         redisTemplate.execute(AUTH_COMPARE_DELETE, Arrays.asList(key, key + ":owner"), ownerToken);
     }

     @Override
     public ResultForOnPublish getAuthenticateInfo(String streamId) {
         String key = String.format("%s:%s", VideoManagerConstants.RTP_AUTHENTICATE, streamId);
         Object obj = redisTemplate.opsForValue().get(key);
         if (obj instanceof ResultForOnPublish) {
             return (ResultForOnPublish) obj;
         }
         return null;
     }

     @Override
     public void refreshAuthenticateInfo(String oldStreamId, String newStreamId) {
         String oldKey = authKey(oldStreamId);
         RtpResourceContext context = activeResources.values().stream()
                 .filter(item -> oldKey != null && oldKey.equals(item.getAuthKey()))
                 .findFirst().orElse(null);
         refreshAuthenticateInfo(context, oldStreamId, newStreamId);
     }

     @Override
     public void refreshAuthenticateInfo(SSRCInfo info, String oldStreamId, String newStreamId) {
         RtpResourceContext context = info == null || info.getResourceId() == null
                 ? null : activeResources.get(info.getResourceId());
         refreshAuthenticateInfo(context, oldStreamId, newStreamId);
     }

     private void refreshAuthenticateInfo(RtpResourceContext context, String oldStreamId, String newStreamId) {
         if (context == null || oldStreamId == null || newStreamId == null || oldStreamId.equals(newStreamId)) {
             return;
         }
         String oldKey = authKey(oldStreamId);
         String newKey = authKey(newStreamId);
         synchronized (context) {
             if (!context.isAuthWritable() || !oldKey.equals(context.getAuthKey())
                     || context.getAuthOwner() == null) {
                 log.debug("[刷新RTP鉴权信息] owner 不可迁移: {} -> {}", oldStreamId, newStreamId);
                 return;
             }
             Long moved = redisTemplate.execute(AUTH_COMPARE_MOVE,
                     Arrays.asList(oldKey, oldKey + ":owner", newKey, newKey + ":owner"),
                     String.valueOf(context.getAuthOwner()));
             if (Long.valueOf(1).equals(moved)) {
                 if (context.moveAuthKey(oldKey, newKey)) {
                     log.info("[刷新RTP鉴权信息] {} -> {}", oldStreamId, newStreamId);
                 } else {
                     // The lifecycle owner may have changed between the script and
                     // the in-memory update. Never leave an ownerless new key behind.
                     try {
                         deleteAuthIfOwner(newKey, String.valueOf(context.getAuthOwner()));
                     } catch (RuntimeException cleanupError) {
                         log.warn("[刷新RTP鉴权信息] 回滚新鉴权 key 失败: {}", cleanupError.getMessage());
                     }
                     log.warn("[刷新RTP鉴权信息] owner 状态已变化: {} -> {}", oldStreamId, newStreamId);
                 }
             } else {
                 log.warn("[刷新RTP鉴权信息] owner 校验失败: {} -> {}", oldStreamId, newStreamId);
             }
         }
     }

     private String authKey(String streamId) {
         return streamId == null ? null : String.format("%s:%s", VideoManagerConstants.RTP_AUTHENTICATE, streamId);
     }
}
