package com.genersoft.iot.vmp.gb28181.service.impl;

import com.alibaba.fastjson2.JSON;
import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.common.enums.MediaStreamUtil;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.dao.DeviceChannelMapper;
import com.genersoft.iot.vmp.gb28181.dao.DeviceMapper;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.media.event.media.MediaDepartureEvent;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

@Slf4j
@Service
public class InviteStreamServiceImpl implements IInviteStreamService {

    private static final int INDEX_SCAN_COUNT = 20;
    private static final int BACKFILL_SCAN_COUNT = 200;
    private static final long EXPIRE_BATCH_SIZE = 200L;
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end return 0",
            Long.class);

    private record PrimaryScanResult(List<InviteInfo> invites, boolean complete) {
        private static PrimaryScanResult incomplete() {
            return new PrimaryScanResult(Collections.emptyList(), false);
        }
    }

    private final Map<String, List<ErrorCallback<StreamInfo>>> inviteErrorCallbackMap = new ConcurrentHashMap<>();

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private UserSetting userSetting;

    @Autowired
    private DeviceMapper deviceMapper;

    @Autowired
    private DeviceChannelMapper deviceChannelMapper;

    /**
     * 流离开的处理
     */
    /**
     * Kept as a reusable cleanup helper; media departure dispatch is owned by
     * PlayServiceImpl so InviteInfo cannot be removed before BYE/session cleanup.
     */
    public void onApplicationEvent(MediaDepartureEvent event) {
        if ("rtsp".equals(event.getSchema()) && MediaStreamUtil.isGB28181(event.getApp(), event.getStream())) {
            InviteInfo inviteInfo = getInviteInfoByStream(null, event.getStream());
            if (inviteInfo != null && (inviteInfo.getType() == InviteSessionType.PLAY || inviteInfo.getType() == InviteSessionType.PLAYBACK)) {
                try {
                    removeInviteInfo(inviteInfo);
                    Device device = deviceMapper.getDeviceByDeviceId(inviteInfo.getDeviceId());
                    if (device != null) {
                        deviceChannelMapper.stopPlayById(inviteInfo.getChannelId());
                    }
                } catch (Exception e) {
                    log.error("[流离开] 清理Invite异常: deviceId={}, channelId={}, stream={}",
                            inviteInfo.getDeviceId(), inviteInfo.getChannelId(), inviteInfo.getStream(), e);
                }
            }
        }
    }

    @Override
    public void updateInviteInfo(InviteInfo inviteInfo) {
        if (inviteInfo == null) {
            log.warn("[更新Invite信息]，参数不全： null");
            return;
        }
        if (InviteSessionStatus.ready == inviteInfo.getStatus()) {
            updateInviteInfo(inviteInfo, Long.valueOf(userSetting.getPlayTimeout()) * 2);
        } else {
            updateInviteInfo(inviteInfo, null);
        }
    }

    @Override
    public void updateInviteInfo(InviteInfo inviteInfo, Long time) {
        if (inviteInfo == null || inviteInfo.getDeviceId() == null || inviteInfo.getChannelId() == null
                || inviteInfo.getType() == null || inviteInfo.getStream() == null || inviteInfo.getStream().isEmpty()) {
            log.warn("[更新Invite信息]，参数不全： {}", JSON.toJSON(inviteInfo));
            return;
        }
        String key = VideoManagerConstants.INVITE_PREFIX;
        String objectKey = InviteInfoRedisIndex.primaryField(inviteInfo);
        boolean indexesReady = inviteIndexesReady();
        for (int attempt = 0; attempt < 3; attempt++) {
            Boolean completed = redisTemplate.execute(new SessionCallback<>() {
                @Override
                @SuppressWarnings("unchecked")
                public Boolean execute(RedisOperations operations) {
                    watchInviteMutation(operations, objectKey, indexesReady);
                    Object current = operations.opsForHash().get(key, objectKey);
                    InviteInfo inviteInfoForUpdate;
                    if (InviteSessionStatus.ready == inviteInfo.getStatus()) {
                        inviteInfoForUpdate = inviteInfo;
                    } else if (current instanceof InviteInfo currentInvite) {
                        inviteInfoForUpdate = mergeInviteInfo(currentInvite, inviteInfo);
                    } else {
                        operations.unwatch();
                        log.warn("[更新Invite信息]，未从缓存中读取到Invite信息： deviceId: {}, channel: {}, stream: {}",
                                inviteInfo.getDeviceId(), inviteInfo.getChannelId(), inviteInfo.getStream());
                        return false;
                    }
                    if (inviteInfoForUpdate.getCreateTime() == null) {
                        inviteInfoForUpdate.setCreateTime(System.currentTimeMillis());
                    }
                    if (time != null && time > 0) {
                        inviteInfoForUpdate.setExpirationTime(time);
                    }
                    operations.multi();
                    if (current instanceof InviteInfo currentInvite) {
                        removeDerivedIndexes(operations, currentInvite);
                    }
                    operations.opsForHash().put(key, objectKey, inviteInfoForUpdate);
                    addDerivedIndexes(operations, inviteInfoForUpdate);
                    touchInviteVersion(operations, objectKey);
                    return operations.exec() == null ? null : true;
                }
            });
            if (completed != null) {
                return;
            }
        }
        log.warn("[更新Invite信息]，事务冲突重试耗尽： deviceId: {}, channel: {}, stream: {}",
                inviteInfo.getDeviceId(), inviteInfo.getChannelId(), inviteInfo.getStream());
    }

    private InviteInfo mergeInviteInfo(InviteInfo existing, InviteInfo incoming) {
        InviteInfo merged = JSON.parseObject(JSON.toJSONString(existing), InviteInfo.class);
        if (incoming.getStreamInfo() != null) {
            merged.setStreamInfo(incoming.getStreamInfo());
        }
        if (incoming.getSsrcInfo() != null) {
            merged.setSsrcInfo(incoming.getSsrcInfo());
        }
        if (incoming.getStreamMode() != null) {
            merged.setStreamMode(incoming.getStreamMode());
        }
        if (incoming.getReceiveIp() != null) {
            merged.setReceiveIp(incoming.getReceiveIp());
        }
        if (incoming.getReceivePort() != null) {
            merged.setReceivePort(incoming.getReceivePort());
        }
        if (incoming.getStatus() != null) {
            merged.setStatus(incoming.getStatus());
        }
        if (incoming.getCleanupAt() != null) {
            merged.setCleanupAt(incoming.getCleanupAt());
        }
        return merged;
    }

    @Override
    public InviteInfo updateInviteInfoForStream(InviteInfo inviteInfo, String stream) {
        if (inviteInfo == null || stream == null || stream.isEmpty()) {
            return null;
        }
        String key = VideoManagerConstants.INVITE_PREFIX;
        String oldField = InviteInfoRedisIndex.primaryField(inviteInfo);
        String newField = InviteInfoRedisIndex.primaryField(inviteInfo.getType(), inviteInfo.getChannelId(), stream)
                .orElseThrow();
        boolean indexesReady = inviteIndexesReady();
        for (int attempt = 0; attempt < 3; attempt++) {
            InviteInfo[] updated = new InviteInfo[1];
            Boolean completed = redisTemplate.execute(new SessionCallback<>() {
                @Override
                @SuppressWarnings("unchecked")
                public Boolean execute(RedisOperations operations) {
                    watchInviteMutation(operations, oldField, indexesReady);
                    if (!oldField.equals(newField)) {
                        watchInviteMutation(operations, newField, indexesReady);
                    }
                    Object current = operations.opsForHash().get(key, oldField);
                    if (!(current instanceof InviteInfo currentInvite)) {
                        operations.unwatch();
                        return false;
                    }
                    InviteInfo destinationInvite = null;
                    if (!oldField.equals(newField)) {
                        Object destination = operations.opsForHash().get(key, newField);
                        if (destination instanceof InviteInfo destinationValue) {
                            destinationInvite = destinationValue;
                        }
                    }
                    operations.multi();
                    operations.opsForHash().delete(key, oldField);
                    removeDerivedIndexes(operations, currentInvite);
                    if (destinationInvite != null) {
                        removeDerivedIndexes(operations, destinationInvite);
                    }
                    currentInvite.setStream(stream);
                    if (currentInvite.getSsrcInfo() != null) {
                        currentInvite.getSsrcInfo().setStream(stream);
                    }
                    if (InviteSessionStatus.ready == inviteInfo.getStatus()) {
                        currentInvite.setExpirationTime((long) (userSetting.getPlayTimeout() * 2));
                    }
                    if (currentInvite.getCreateTime() == null) {
                        currentInvite.setCreateTime(System.currentTimeMillis());
                    }
                    operations.opsForHash().put(key, newField, currentInvite);
                    addDerivedIndexes(operations, currentInvite);
                    if (oldField.equals(newField)) {
                        touchInviteVersion(operations, oldField);
                    } else {
                        deleteInviteVersion(operations, oldField);
                        touchInviteVersion(operations, newField);
                    }
                    if (operations.exec() == null) {
                        return null;
                    }
                    updated[0] = currentInvite;
                    return true;
                }
            });
            if (completed != null) {
                return Boolean.TRUE.equals(completed) ? updated[0] : null;
            }
        }
        log.warn("[更新Invite流]，事务冲突重试耗尽： deviceId: {}, channel: {}, stream: {}",
                inviteInfo.getDeviceId(), inviteInfo.getChannelId(), inviteInfo.getStream());
        return null;
    }

    @Override
    public InviteInfo getInviteInfo(InviteSessionType type, Integer channelId, String stream) {
        String key = VideoManagerConstants.INVITE_PREFIX;
        if (type != null && channelId != null && stream != null) {
            String primaryField = InviteInfoRedisIndex.primaryField(type, channelId, stream).orElse(null);
            if (primaryField == null) {
                return null;
            }
            Object value = redisTemplate.opsForHash().get(key, primaryField);
            if (value instanceof InviteInfo inviteInfo
                    && InviteInfoRedisIndex.matchesPrimaryField(inviteInfo, primaryField)) {
                return inviteInfo;
            }
            if (value != null) {
                log.warn("[Redis-InviteInfo] 主记录字段与内容不匹配，跳过: field={}, value={}", primaryField, value);
            }
            return null;
        }
        String indexKey = null;
        Predicate<InviteInfo> matches = null;
        if (stream != null) {
            indexKey = VideoManagerConstants.INVITE_INDEX_STREAM_PREFIX + stream;
            matches = inviteInfo -> (type == null || inviteInfo.getType() == type)
                    && (channelId == null || Objects.equals(inviteInfo.getChannelId(), channelId))
                    && stream.equals(inviteInfo.getStream());
        } else if (type != null && channelId != null) {
            indexKey = VideoManagerConstants.INVITE_INDEX_CHANNEL_PREFIX + type + ":" + channelId;
            matches = inviteInfo -> inviteInfo.getType() == type && Objects.equals(inviteInfo.getChannelId(), channelId);
        }
        if (indexKey != null) {
            try {
                InviteInfo indexedInvite = findIndexedInvite(indexKey, matches);
                if (indexedInvite != null || inviteIndexesReady()) {
                    return indexedInvite;
                }
            } catch (RuntimeException e) {
                // An index read failure must not be reported as a normal miss.
                log.warn("[Redis-InviteInfo] 索引读取失败，回退主Hash: key={}", indexKey, e);
            }
        }
        InviteInfo scannedInvite = getInviteInfoByScan(type, channelId, stream);
        if (scannedInvite != null) {
            refreshDerivedIndexes(scannedInvite);
        }
        return scannedInvite;
    }

    private InviteInfo getInviteInfoByScan(InviteSessionType type, Integer channelId, String stream) {
        String key = VideoManagerConstants.INVITE_PREFIX;
        String keyPattern = (type != null ? type : "*") +
                ":" + (channelId != null ? channelId : "*") +
                ":" + (stream != null ? stream : "*");
        ScanOptions options = ScanOptions.scanOptions().match(keyPattern).count(INDEX_SCAN_COUNT).build();
        InviteInfo match = null;
        boolean complete = false;
        try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash().scan(key, options)) {
            while (cursor.hasNext()) {
                Map.Entry<Object, Object> entry = cursor.next();
                if (entry.getValue() instanceof InviteInfo inviteInfo) {
                    if (InviteInfoRedisIndex.matchesPrimaryField(inviteInfo, String.valueOf(entry.getKey()))
                            && (type == null || inviteInfo.getType() == type)
                            && (channelId == null || Objects.equals(inviteInfo.getChannelId(), channelId))
                            && (stream == null || stream.equals(inviteInfo.getStream()))) {
                        match = inviteInfo;
                    }
                } else {
                    // Keep malformed primary data intact; its derived memberships cannot be derived safely.
                    log.warn("[Redis-InviteInfo] 发现脏数据，跳过: key={}, value={}", entry.getKey(), entry.getValue());
                }
            }
            complete = true;
        } catch (Exception e) {
            log.error("[Redis-InviteInfo] 查询异常: ", e);
        }
        return complete ? match : null;
    }

    private InviteInfo findIndexedInvite(String indexKey, Predicate<InviteInfo> matches) {
        try (Cursor<Object> cursor = redisTemplate.opsForSet().scan(indexKey,
                ScanOptions.scanOptions().count(INDEX_SCAN_COUNT).build())) {
            while (cursor != null && cursor.hasNext()) {
                Object field = cursor.next();
                Object value = redisTemplate.opsForHash().get(VideoManagerConstants.INVITE_PREFIX, field);
                if (field instanceof String primaryField
                        && value instanceof InviteInfo inviteInfo
                        && InviteInfoRedisIndex.matchesPrimaryField(inviteInfo, primaryField)
                        && InviteInfoRedisIndex.representsIndexKey(inviteInfo, indexKey)
                        && matches.test(inviteInfo)) {
                    return inviteInfo;
                }
                removeStaleIndexMemberIfPrimaryStillStale(indexKey, field);
            }
        }
        return null;
    }

    private List<InviteInfo> findIndexedInvites(String indexKey, Predicate<InviteInfo> matches) {
        List<InviteInfo> result = new ArrayList<>();
        try (Cursor<Object> cursor = redisTemplate.opsForSet().scan(indexKey,
                ScanOptions.scanOptions().count(INDEX_SCAN_COUNT).build())) {
            while (cursor != null && cursor.hasNext()) {
                Object field = cursor.next();
                Object value = redisTemplate.opsForHash().get(VideoManagerConstants.INVITE_PREFIX, field);
                if (field instanceof String primaryField
                        && value instanceof InviteInfo inviteInfo
                        && InviteInfoRedisIndex.matchesPrimaryField(inviteInfo, primaryField)
                        && InviteInfoRedisIndex.representsIndexKey(inviteInfo, indexKey)
                        && matches.test(inviteInfo)) {
                    result.add(inviteInfo);
                } else {
                    removeStaleIndexMemberIfPrimaryStillStale(indexKey, field);
                }
            }
        }
        return result;
    }

    private PrimaryScanResult findInvitesByPrimaryScan(Predicate<InviteInfo> matches) {
        List<InviteInfo> result = new ArrayList<>();
        try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash().scan(
                VideoManagerConstants.INVITE_PREFIX, ScanOptions.scanOptions().count(BACKFILL_SCAN_COUNT).build())) {
            while (cursor.hasNext()) {
                Map.Entry<Object, Object> entry = cursor.next();
                Object value = entry.getValue();
                if (entry.getKey() instanceof String field
                        && value instanceof InviteInfo inviteInfo
                        && InviteInfoRedisIndex.matchesPrimaryField(inviteInfo, field)
                        && matches.test(inviteInfo)) {
                    result.add(inviteInfo);
                }
            }
            return new PrimaryScanResult(result, true);
        } catch (Exception e) {
            log.error("[Redis-InviteInfo] 主Hash扫描异常，放弃本次扫描结果", e);
            return PrimaryScanResult.incomplete();
        }
    }

    private void removeStaleIndexMemberIfPrimaryStillStale(String indexKey, Object field) {
        if (!(field instanceof String primaryField)) {
            return;
        }
        try {
            boolean indexesReady = inviteIndexesReady();
            redisTemplate.execute(new SessionCallback<>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(RedisOperations operations) {
                    watchInviteMutation(operations, primaryField, indexesReady);
                    Object current = operations.opsForHash().get(VideoManagerConstants.INVITE_PREFIX, primaryField);
                    if (current instanceof InviteInfo inviteInfo
                            && InviteInfoRedisIndex.matchesPrimaryField(inviteInfo, primaryField)
                            && InviteInfoRedisIndex.representsIndexKey(inviteInfo, indexKey)) {
                        operations.unwatch();
                        return null;
                    }
                    operations.multi();
                    operations.opsForSet().remove(indexKey, primaryField);
                    if (current == null) {
                        deleteInviteVersion(operations, primaryField);
                    } else {
                        touchInviteVersion(operations, primaryField);
                    }
                    return operations.exec();
                }
            });
        } catch (RuntimeException e) {
            log.warn("[Redis-InviteInfo] 清理陈旧索引成员失败: key={}, field={}", indexKey, primaryField, e);
        }
    }

    private boolean refreshDerivedIndexes(InviteInfo snapshot) {
        if (snapshot == null) {
            return false;
        }
        String primaryField;
        try {
            primaryField = InviteInfoRedisIndex.primaryField(snapshot);
        } catch (IllegalArgumentException e) {
            return false;
        }
        boolean indexesReady = inviteIndexesReady();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Boolean refreshed = redisTemplate.execute(new SessionCallback<>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public Boolean execute(RedisOperations operations) {
                        watchInviteMutation(operations, primaryField, indexesReady);
                        Object current = operations.opsForHash().get(VideoManagerConstants.INVITE_PREFIX, primaryField);
                        if (!(current instanceof InviteInfo currentInvite)
                                || !InviteInfoRedisIndex.matchesPrimaryField(currentInvite, primaryField)) {
                            operations.unwatch();
                            return true;
                        }
                        operations.multi();
                        removeDerivedIndexes(operations, currentInvite);
                        addDerivedIndexes(operations, currentInvite);
                        touchInviteVersion(operations, primaryField);
                        return operations.exec() == null ? null : true;
                    }
                });
                if (refreshed != null) {
                    return refreshed;
                }
            } catch (RuntimeException e) {
                log.warn("[Redis-InviteInfo] 派生索引修复失败，保留主记录: field={}", primaryField, e);
                return false;
            }
        }
        return false;
    }

    @Override
    public boolean inviteIndexesReady() {
        try {
            return VideoManagerConstants.INVITE_INDEX_READY_VALUE.equals(
                    redisTemplate.opsForValue().get(VideoManagerConstants.INVITE_INDEX_READY));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean inviteIndexesBackfilled() {
        try {
            Object marker = redisTemplate.opsForValue().get(VideoManagerConstants.INVITE_INDEX_READY);
            return VideoManagerConstants.INVITE_INDEX_READY_VALUE.equals(marker)
                    || VideoManagerConstants.INVITE_INDEX_BACKFILLED_VALUE.equals(marker);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean rebuildInviteIndexes() {
        if (inviteIndexesBackfilled()) {
            return true;
        }
        return backfillInviteIndexes(true);
    }

    @Override
    public boolean activateInviteIndexes() {
        if (inviteIndexesReady()) {
            return true;
        }
        // Operators invoke this only after every legacy writer has been removed from service.
        if (!backfillInviteIndexes(true)) {
            return false;
        }
        try {
            redisTemplate.opsForValue().set(VideoManagerConstants.INVITE_INDEX_READY,
                    VideoManagerConstants.INVITE_INDEX_READY_VALUE);
            return true;
        } catch (RuntimeException e) {
            log.warn("[Redis-InviteInfo] 写入索引就绪标记失败", e);
            return false;
        }
    }

    private boolean backfillInviteIndexes(boolean markBackfilled) {
        String token = UUID.randomUUID().toString();
        Boolean locked;
        try {
            locked = redisTemplate.opsForValue().setIfAbsent(VideoManagerConstants.INVITE_INDEX_BACKFILL_LOCK,
                    token, Duration.ofMinutes(5));
        } catch (RuntimeException e) {
            log.warn("[Redis-InviteInfo] 获取索引回填锁失败", e);
            return false;
        }
        if (!Boolean.TRUE.equals(locked)) {
            return false;
        }
        try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash().scan(
                VideoManagerConstants.INVITE_PREFIX, ScanOptions.scanOptions().count(BACKFILL_SCAN_COUNT).build())) {
            while (cursor.hasNext()) {
                Map.Entry<Object, Object> entry = cursor.next();
                if (!(entry.getKey() instanceof String field) || !(entry.getValue() instanceof InviteInfo inviteInfo)) {
                    log.warn("[Redis-InviteInfo] 索引回填跳过非法记录: key={}, value={}", entry.getKey(), entry.getValue());
                    continue;
                }
                String expectedField;
                try {
                    expectedField = InviteInfoRedisIndex.primaryField(inviteInfo);
                } catch (IllegalArgumentException e) {
                    log.warn("[Redis-InviteInfo] 索引回填跳过主键不完整记录: key={}", field);
                    continue;
                }
                if (!field.equals(expectedField)) {
                    log.warn("[Redis-InviteInfo] 索引回填跳过字段不匹配记录: key={}", field);
                    continue;
                }
                if (!refreshDerivedIndexes(inviteInfo)) {
                    return false;
                }
            }
            if (markBackfilled) {
                redisTemplate.opsForValue().set(VideoManagerConstants.INVITE_INDEX_READY,
                        VideoManagerConstants.INVITE_INDEX_BACKFILLED_VALUE);
            }
            return true;
        } catch (Exception e) {
            log.warn("[Redis-InviteInfo] 索引回填失败，保持扫描兜底", e);
            return false;
        } finally {
            try {
                redisTemplate.execute(COMPARE_AND_DELETE,
                        Collections.singletonList(VideoManagerConstants.INVITE_INDEX_BACKFILL_LOCK), token);
            } catch (RuntimeException e) {
                log.warn("[Redis-InviteInfo] 释放索引回填锁失败", e);
            }
        }
    }

    @Override
    public List<InviteInfo> getAllInviteInfo() {
        PrimaryScanResult scan = findInvitesByPrimaryScan(inviteInfo -> true);
        if (!scan.complete()) {
            log.warn("[Redis-InviteInfo] 全量快照不完整，返回空结果以保留主记录");
            return Collections.emptyList();
        }
        return scan.invites();
    }

    @Override
    public List<InviteInfo> getActiveInviteInfoByMediaServer(String mediaServerId) {
        String indexKey = InviteInfoRedisIndex.activeMediaKey(mediaServerId);
        if (indexKey == null) {
            return Collections.emptyList();
        }
        if (inviteIndexesReady()) {
            try {
                return findIndexedInvites(indexKey, inviteInfo -> InviteInfoRedisIndex.countsAsActive(inviteInfo)
                        && mediaServerId.equals(InviteInfoRedisIndex.mediaServerId(inviteInfo)));
            } catch (RuntimeException e) {
                log.warn("[Redis-InviteInfo] 活跃索引读取失败，回退主Hash: key={}", indexKey, e);
            }
        }
        PrimaryScanResult scan = findInvitesByPrimaryScan(inviteInfo -> InviteInfoRedisIndex.countsAsActive(inviteInfo)
                && mediaServerId.equals(InviteInfoRedisIndex.mediaServerId(inviteInfo)));
        return scan.complete() ? scan.invites() : Collections.emptyList();
    }

    @Override
    public InviteInfo getInviteInfoByDeviceAndChannel(InviteSessionType type, Integer channelId) {
        return getInviteInfo(type, channelId, null);
    }

    @Override
    public InviteInfo getInviteInfoByStream(InviteSessionType type, String stream) {
        return getInviteInfo(type, null, stream);
    }

    @Override
    public InviteInfo getInviteInfoByStreamAndMediaServer(String mediaServerId, String stream) {
        if (mediaServerId == null || stream == null) {
            // A departure event without its node identity is ambiguous in a
            // multi-node deployment; never fall back to another node's stream.
            return null;
        }
        String indexKey = VideoManagerConstants.INVITE_INDEX_STREAM_PREFIX + stream;
        Predicate<InviteInfo> matches = inviteInfo -> stream.equals(inviteInfo.getStream())
                && mediaServerId.equals(inviteInfo.getMediaServerId());
        try {
            List<InviteInfo> indexed = findIndexedInvites(indexKey, matches);
            if (!indexed.isEmpty() || inviteIndexesReady()) {
                return indexed.isEmpty() ? null : indexed.get(0);
            }
        } catch (RuntimeException e) {
            log.warn("[Redis-InviteInfo] 按媒体节点查询索引失败，回退主Hash：stream={}, mediaServerId={}",
                    stream, mediaServerId, e);
        }
        PrimaryScanResult scanned = findInvitesByPrimaryScan(matches);
        if (scanned.complete() && !scanned.invites().isEmpty()) {
            InviteInfo result = scanned.invites().get(0);
            refreshDerivedIndexes(result);
            return result;
        }
        return null;
    }

    @Override
    public void removeInviteInfo(InviteSessionType type, Integer channelId, String stream) {
        String key = VideoManagerConstants.INVITE_PREFIX;
        if (type == null && channelId == null && stream == null) {
            List<InviteInfo> snapshots = getAllInviteInfo();
            if (snapshots != null) {
                for (InviteInfo snapshot : snapshots) {
                    if (snapshot != null) {
                        removeInviteInfoIfSame(snapshot);
                    }
                }
            }
            return;
        }
        InviteInfo inviteInfo = getInviteInfo(type, channelId, stream);
        if (inviteInfo != null) {
            removeInviteInfoIfSame(inviteInfo);
        }
    }

    @Override
    public void removeInviteInfoByDeviceAndChannel(InviteSessionType inviteSessionType, Integer channelId) {
        removeInviteInfo(inviteSessionType, channelId, null);
    }

    @Override
    public void removeInviteInfo(InviteInfo inviteInfo) {
        if (inviteInfo != null) {
            removeInviteInfoIfSame(inviteInfo);
        }
    }

    @Override
    public boolean removeInviteInfoIfSame(InviteInfo expected) {
        if (expected == null || expected.getType() == null || expected.getChannelId() == null
                || expected.getStream() == null) {
            return false;
        }
        String key = VideoManagerConstants.INVITE_PREFIX;
        String objectKey = expected.getType() + ":" + expected.getChannelId() + ":" + expected.getStream();
        try {
            boolean indexesReady = inviteIndexesReady();
            Boolean removed = redisTemplate.execute(new SessionCallback<>() {
                @Override
                @SuppressWarnings("unchecked")
                public Boolean execute(RedisOperations operations) {
                    watchInviteMutation(operations, objectKey, indexesReady);
                    Object current = operations.opsForHash().get(key, objectKey);
                    if (!(current instanceof InviteInfo currentInvite) || !sameOwner(currentInvite, expected)) {
                        operations.unwatch();
                        return false;
                    }
                    operations.multi();
                    operations.opsForHash().delete(key, objectKey);
                    removeDerivedIndexes(operations, currentInvite);
                    deleteInviteVersion(operations, objectKey);
                    List<Object> results = operations.exec();
                    return results != null && !results.isEmpty() && toLong(results.get(0)) > 0;
                }
            });
            return Boolean.TRUE.equals(removed);
        } catch (Exception e) {
            log.warn("[Redis-InviteInfo] 条件删除失败，保留数据：key={}, field={}", key, objectKey, e);
            return false;
        }
    }

    @Override
    public boolean restoreInviteInfoIfAbsent(InviteInfo inviteInfo) {
        if (inviteInfo == null || inviteInfo.getType() == null || inviteInfo.getChannelId() == null
                || inviteInfo.getStream() == null) {
            return false;
        }
        String key = VideoManagerConstants.INVITE_PREFIX;
        String objectKey = InviteInfoRedisIndex.primaryField(inviteInfo);
        boolean indexesReady = inviteIndexesReady();
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                Boolean restored = redisTemplate.execute(new SessionCallback<>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public Boolean execute(RedisOperations operations) {
                        watchInviteMutation(operations, objectKey, indexesReady);
                        if (operations.opsForHash().get(key, objectKey) != null) {
                            operations.unwatch();
                            return false;
                        }
                        operations.multi();
                        operations.opsForHash().put(key, objectKey, inviteInfo);
                        addDerivedIndexes(operations, inviteInfo);
                        touchInviteVersion(operations, objectKey);
                        return operations.exec() == null ? null : true;
                    }
                });
                if (restored != null) {
                    return Boolean.TRUE.equals(restored);
                }
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("[Redis-InviteInfo] 清理失败后恢复记录异常：key={}, field={}", key, objectKey, e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private void addDerivedIndexes(RedisOperations operations, InviteInfo inviteInfo) {
        String field = InviteInfoRedisIndex.primaryField(inviteInfo);
        addToSet(operations, InviteInfoRedisIndex.channelIndexKey(inviteInfo), field);
        addToSet(operations, InviteInfoRedisIndex.streamIndexKey(inviteInfo), field);
        addToSet(operations, InviteInfoRedisIndex.ssrcIndexKey(inviteInfo), field);
        addToSet(operations, InviteInfoRedisIndex.deviceIndexKey(inviteInfo), field);
        if (InviteInfoRedisIndex.countsAsActive(inviteInfo)) {
            addToSet(operations, InviteInfoRedisIndex.activeMediaKey(inviteInfo), field);
        }
        InviteInfoRedisIndex.deadline(inviteInfo).ifPresent(deadline ->
                operations.opsForZSet().add(VideoManagerConstants.INVITE_EXPIRE_AT, field, deadline));
    }

    @SuppressWarnings("unchecked")
    private void removeDerivedIndexes(RedisOperations operations, InviteInfo inviteInfo) {
        String field = InviteInfoRedisIndex.primaryField(inviteInfo);
        removeFromSet(operations, InviteInfoRedisIndex.channelIndexKey(inviteInfo), field);
        removeFromSet(operations, InviteInfoRedisIndex.streamIndexKey(inviteInfo), field);
        removeFromSet(operations, InviteInfoRedisIndex.ssrcIndexKey(inviteInfo), field);
        removeFromSet(operations, InviteInfoRedisIndex.deviceIndexKey(inviteInfo), field);
        removeFromSet(operations, InviteInfoRedisIndex.activeMediaKey(inviteInfo), field);
        operations.opsForZSet().remove(VideoManagerConstants.INVITE_EXPIRE_AT, field);
    }

    @SuppressWarnings("unchecked")
    private void addToSet(RedisOperations operations, String key, String field) {
        if (key != null) {
            operations.opsForSet().add(key, field);
        }
    }

    @SuppressWarnings("unchecked")
    private void removeFromSet(RedisOperations operations, String key, String field) {
        if (key != null) {
            operations.opsForSet().remove(key, field);
        }
    }

    private boolean sameOwner(InviteInfo current, InviteInfo expected) {
        String currentOwner = current.getSsrcInfo() == null ? null : current.getSsrcInfo().getResourceId();
        String expectedOwner = expected.getSsrcInfo() == null ? null : expected.getSsrcInfo().getResourceId();
        if (currentOwner != null || expectedOwner != null) {
            return currentOwner != null && currentOwner.equals(expectedOwner);
        }
        return Objects.equals(current, expected);
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0;
    }

    private String inviteVersionKey(String primaryField) {
        return VideoManagerConstants.INVITE_VERSION_PREFIX + primaryField;
    }

    @SuppressWarnings("unchecked")
    private void watchInviteMutation(RedisOperations operations, String primaryField, boolean indexesReady) {
        operations.watch(inviteVersionKey(primaryField));
        if (!indexesReady) {
            operations.watch(VideoManagerConstants.INVITE_PREFIX);
        }
    }

    @SuppressWarnings("unchecked")
    private void touchInviteVersion(RedisOperations operations, String primaryField) {
        if (primaryField == null || primaryField.isEmpty()) {
            return;
        }
        var values = operations.opsForValue();
        if (values != null) {
            values.increment(inviteVersionKey(primaryField));
        }
    }

    @SuppressWarnings("unchecked")
    private void deleteInviteVersion(RedisOperations operations, String primaryField) {
        if (primaryField == null || primaryField.isEmpty()) {
            return;
        }
        operations.delete(inviteVersionKey(primaryField));
    }

    @Override
    public void once(InviteSessionType type, Integer channelId, String stream, ErrorCallback<StreamInfo> callback) {
        String key = buildKey(type, channelId, stream);
        List<ErrorCallback<StreamInfo>> callbacks = inviteErrorCallbackMap.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        callbacks.add(callback);

    }

    private String buildKey(InviteSessionType type, Integer channelId, String stream) {
        String key = type + ":" + channelId;
        // 如果ssrc未null那么可以实现一个通道只能一次操作，ssrc不为null则可以支持一个通道多次invite
        if (stream != null) {
            key += (":" + stream);
        }
        return key;
    }


    @Override
    public void clearInviteInfo(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return;
        }
        List<InviteInfo> inviteInfoList;
        if (inviteIndexesReady()) {
            String indexKey = VideoManagerConstants.INVITE_INDEX_DEVICE_PREFIX + deviceId;
            try {
                inviteInfoList = findIndexedInvites(indexKey,
                        inviteInfo -> deviceId.equals(inviteInfo.getDeviceId()));
            } catch (RuntimeException e) {
                log.warn("[Redis-InviteInfo] 设备索引读取失败，回退主Hash: key={}", indexKey, e);
                PrimaryScanResult scan = findInvitesByPrimaryScan(inviteInfo -> deviceId.equals(inviteInfo.getDeviceId()));
                if (!scan.complete()) {
                    log.warn("[Redis-InviteInfo] 设备清理扫描不完整，保留所有记录: deviceId={}", deviceId);
                    return;
                }
                inviteInfoList = scan.invites();
            }
        } else {
            PrimaryScanResult scan = findInvitesByPrimaryScan(inviteInfo -> deviceId.equals(inviteInfo.getDeviceId()));
            if (!scan.complete()) {
                log.warn("[Redis-InviteInfo] 设备清理扫描不完整，保留所有记录: deviceId={}", deviceId);
                return;
            }
            inviteInfoList = scan.invites();
        }
        for (InviteInfo inviteInfo : inviteInfoList) {
            removeInviteInfoIfSame(inviteInfo);
        }
    }

    @Override
    public int clearActiveInviteInfoByDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return 0;
        }
        String indexKey = VideoManagerConstants.INVITE_INDEX_DEVICE_PREFIX + deviceId;
        List<InviteInfo> inviteInfoList;
        long now = System.currentTimeMillis();
        Predicate<InviteInfo> shouldRemove = inviteInfo -> !isRetainedCompletedDownload(inviteInfo, now);
        if (inviteIndexesReady()) {
            try {
                inviteInfoList = findIndexedInvites(indexKey, shouldRemove);
            } catch (RuntimeException e) {
                log.warn("[Redis-InviteInfo] 设备离线索引读取失败，回退主Hash: key={}", indexKey, e);
                PrimaryScanResult scan = findInvitesByPrimaryScan(
                        inviteInfo -> deviceId.equals(inviteInfo.getDeviceId()) && shouldRemove.test(inviteInfo));
                if (!scan.complete()) {
                    log.warn("[Redis-InviteInfo] 设备离线清理扫描不完整，保留所有记录: deviceId={}", deviceId);
                    return 0;
                }
                inviteInfoList = scan.invites();
            }
        } else {
            PrimaryScanResult scan = findInvitesByPrimaryScan(
                    inviteInfo -> deviceId.equals(inviteInfo.getDeviceId()) && shouldRemove.test(inviteInfo));
            if (!scan.complete()) {
                log.warn("[Redis-InviteInfo] 设备离线清理扫描不完整，保留所有记录: deviceId={}", deviceId);
                return 0;
            }
            inviteInfoList = scan.invites();
        }
        int removed = 0;
        for (InviteInfo inviteInfo : inviteInfoList) {
            if (removeInviteInfoIfSame(inviteInfo)) {
                removed++;
            }
        }
        return removed;
    }

    private boolean isRetainedCompletedDownload(InviteInfo inviteInfo, long now) {
        return inviteInfo != null
                && inviteInfo.getType() == InviteSessionType.DOWNLOAD
                && inviteInfo.getStreamInfo() != null
                && inviteInfo.getStreamInfo().getProgress() >= 1
                && inviteInfo.getCleanupAt() != null
                && now < inviteInfo.getCleanupAt();
    }

    @Override
    public int getStreamInfoCount(String mediaServerId) {
        if (inviteIndexesReady()) {
            String activeMediaKey = InviteInfoRedisIndex.activeMediaKey(mediaServerId);
            if (activeMediaKey == null) {
                return 0;
            }
            try {
                Long count = redisTemplate.opsForSet().size(activeMediaKey);
                return count == null ? 0 : Math.toIntExact(count);
            } catch (RuntimeException e) {
                log.warn("[Redis-InviteInfo] 活跃数量索引读取失败，回退主Hash: key={}", activeMediaKey, e);
            }
        }
        return getActiveInviteInfoByMediaServer(mediaServerId).size();
    }

    @Override
    public void call(InviteSessionType type, Integer channelId, String stream, int code, String msg, StreamInfo data) {
        String key = buildSubStreamKey(type, channelId, stream);
        List<ErrorCallback<StreamInfo>> callbacks = inviteErrorCallbackMap.get(key);
        if (callbacks == null || callbacks.isEmpty()) {
            return;
        }
        for (ErrorCallback<StreamInfo> callback : callbacks) {
            if (callback != null) {
                callback.run(code, msg, data);
            }
        }
        inviteErrorCallbackMap.remove(key);
    }


    private String buildSubStreamKey(InviteSessionType type, Integer channelId, String stream) {
        String key = type + ":" + channelId;
        if (stream != null) {
            key += (":" + stream);
        }
        return key;
    }

    @Override
    public InviteInfo getInviteInfoBySSRC(String ssrc) {
        if (ssrc == null || ssrc.isEmpty()) {
            return null;
        }
        try {
            InviteInfo indexedInvite = findIndexedInvite(VideoManagerConstants.INVITE_INDEX_SSRC_PREFIX + ssrc,
                    inviteInfo -> inviteInfo.getSsrcInfo() != null && ssrc.equals(inviteInfo.getSsrcInfo().getSsrc()));
            if (indexedInvite != null || inviteIndexesReady()) {
                return indexedInvite;
            }
        } catch (RuntimeException e) {
            log.warn("[Redis-InviteInfo] SSRC索引读取失败，回退主Hash: ssrc={}", ssrc, e);
        }
        PrimaryScanResult scan = findInvitesByPrimaryScan(inviteInfo -> inviteInfo.getSsrcInfo() != null
                && ssrc.equals(inviteInfo.getSsrcInfo().getSsrc()));
        if (!scan.complete() || scan.invites().isEmpty()) {
            return null;
        }
        InviteInfo scannedInvite = scan.invites().get(0);
        refreshDerivedIndexes(scannedInvite);
        return scannedInvite;
    }

    @Override
    public InviteInfo updateInviteInfoForSSRC(InviteInfo inviteInfo, String ssrc) {
        if (inviteInfo == null) {
            return null;
        }
        String key = VideoManagerConstants.INVITE_PREFIX;
        String objectKey = InviteInfoRedisIndex.primaryField(inviteInfo);
        boolean indexesReady = inviteIndexesReady();
        for (int attempt = 0; attempt < 3; attempt++) {
            InviteInfo[] updated = new InviteInfo[1];
            Boolean completed = redisTemplate.execute(new SessionCallback<>() {
                @Override
                @SuppressWarnings("unchecked")
                public Boolean execute(RedisOperations operations) {
                    watchInviteMutation(operations, objectKey, indexesReady);
                    Object current = operations.opsForHash().get(key, objectKey);
                    if (!(current instanceof InviteInfo currentInvite)) {
                        operations.unwatch();
                        return false;
                    }
                    operations.multi();
                    removeDerivedIndexes(operations, currentInvite);
                    if (currentInvite.getSsrcInfo() != null) {
                        currentInvite.getSsrcInfo().setSsrc(ssrc);
                    }
                    operations.opsForHash().put(key, objectKey, currentInvite);
                    addDerivedIndexes(operations, currentInvite);
                    touchInviteVersion(operations, objectKey);
                    if (operations.exec() == null) {
                        return null;
                    }
                    updated[0] = currentInvite;
                    return true;
                }
            });
            if (completed != null) {
                return Boolean.TRUE.equals(completed) ? updated[0] : null;
            }
        }
        log.warn("[更新Invite SSRC]，事务冲突重试耗尽： deviceId: {}, channel: {}, stream: {}",
                inviteInfo.getDeviceId(), inviteInfo.getChannelId(), inviteInfo.getStream());
        return null;
    }

    @Scheduled(fixedRate = 10000)   //定时检测,清理错误的redis数据,防止因为错误数据导致的点播不可用
    public void execute(){
        if (inviteIndexesReady()) {
            executeDeadlineCleanup();
        } else {
            executeLegacyCleanup();
        }
    }

    private void executeDeadlineCleanup() {
        long now = System.currentTimeMillis();
        Set<Object> fields;
        try {
            fields = redisTemplate.opsForZSet().rangeByScore(VideoManagerConstants.INVITE_EXPIRE_AT,
                    0D, now, 0, EXPIRE_BATCH_SIZE);
        } catch (Exception e) {
            log.error("[定时清理Invite] Redis读取到期索引失败，保留所有数据", e);
            return;
        }
        if (fields == null || fields.isEmpty()) {
            return;
        }
        for (Object field : fields) {
            Object value;
            try {
                value = redisTemplate.opsForHash().get(VideoManagerConstants.INVITE_PREFIX, field);
            } catch (Exception e) {
                log.error("[定时清理Invite] 读取主记录失败，保留数据: {}", field, e);
                continue;
            }
            if (!(value instanceof InviteInfo inviteInfo)) {
                removeDeadlineMemberIfPrimaryStillStale(field);
                continue;
            }
            processInviteExpiry(inviteInfo, now, field);
        }
    }

    private void executeLegacyCleanup() {
        List<Map.Entry<Object, Object>> snapshot = new ArrayList<>();
        try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash().scan(
                VideoManagerConstants.INVITE_PREFIX, ScanOptions.scanOptions().count(BACKFILL_SCAN_COUNT).build())) {
            while (cursor.hasNext()) {
                snapshot.add(cursor.next());
            }
        } catch (Exception e) {
            log.error("[定时清理Invite] Redis读取失败，放弃本次清理并保留所有数据", e);
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<Object, Object> entry : snapshot) {
            processInviteExpiry(entry.getValue(), now, entry.getKey());
        }
    }

    private void processInviteExpiry(Object value, long now, Object field) {
        if (!(value instanceof InviteInfo inviteInfo)) {
            log.warn("[定时清理Invite] 跳过未知数据类型：{}", value);
            return;
        }
        try {
            if (inviteInfo.getType() == InviteSessionType.DOWNLOAD
                    && inviteInfo.getStreamInfo() != null
                    && inviteInfo.getStreamInfo().getProgress() >= 1
                    && inviteInfo.getCleanupAt() != null) {
                if (now >= inviteInfo.getCleanupAt()) {
                    removeInviteInfoIfSame(inviteInfo);
                }
                return;
            }
            if (inviteInfo.getStreamInfo() != null) {
                return;
            }
            if (inviteInfo.getCreateTime() == null || inviteInfo.getExpirationTime() == null) {
                removeInviteInfoIfSame(inviteInfo);
                return;
            }
            if (now >= inviteInfo.getCreateTime() + inviteInfo.getExpirationTime()) {
                removeInviteInfoIfSame(inviteInfo);
            } else if (field instanceof String) {
                refreshDerivedIndexes(inviteInfo);
            }
        } catch (Exception e) {
            log.error("[定时清理Invite] 处理异常，保留该数据: {}", value, e);
        }
    }

    private void removeDeadlineMemberIfPrimaryStillStale(Object field) {
        if (!(field instanceof String primaryField)) {
            return;
        }
        try {
            boolean indexesReady = inviteIndexesReady();
            redisTemplate.execute(new SessionCallback<>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(RedisOperations operations) {
                    watchInviteMutation(operations, primaryField, indexesReady);
                    Object current = operations.opsForHash().get(VideoManagerConstants.INVITE_PREFIX, primaryField);
                    if (current instanceof InviteInfo inviteInfo && InviteInfoRedisIndex.deadline(inviteInfo).isPresent()) {
                        operations.unwatch();
                        return null;
                    }
                    operations.multi();
                    operations.opsForZSet().remove(VideoManagerConstants.INVITE_EXPIRE_AT, primaryField);
                    if (current == null) {
                        deleteInviteVersion(operations, primaryField);
                    } else {
                        touchInviteVersion(operations, primaryField);
                    }
                    return operations.exec();
                }
            });
        } catch (RuntimeException e) {
            log.warn("[Redis-InviteInfo] 清理陈旧到期成员失败: field={}", primaryField, e);
        }
    }
}
