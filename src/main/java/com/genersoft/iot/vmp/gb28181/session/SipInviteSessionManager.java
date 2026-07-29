package com.genersoft.iot.vmp.gb28181.session;

import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 管理视频预览、回放等 SIP Invite 会话。
 *
 * <p>V2 DATA key 是会话内容的唯一来源，STREAM、DEVICE 和 EXPIRE 只保存索引。
 * 旧的两个 Hash 在迁移期只读，读取成功后按需迁移到 V2。</p>
 */
@Slf4j
@Component
public class SipInviteSessionManager {

    private static final int TRANSACTION_RETRIES = 3;
    private static final int LEGACY_SCAN_COUNT = 200;
    private static final int V2_RANGE_BATCH = 500;
    private static final long DEFAULT_TTL_SECONDS = 7 * 24 * 60 * 60L;
    private static final long META_GRACE_SECONDS = 60 * 60L;

    @FunctionalInterface
    private interface TransactionWork {
        Boolean execute(RedisOperations<String, Object> operations);
    }

    @Autowired
    private UserSetting userSetting;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** 添加一个点播/回放事务。新写入只进入 V2。 */
    public void put(SsrcTransaction transaction) {
        putInternal(transaction, true);
    }

    private boolean putInternal(SsrcTransaction transaction, boolean allowStreamReplacement) {
        if (transaction == null || isBlank(transaction.getCallId())) {
            throw new IllegalArgumentException("SIP Invite session callId is required");
        }
        long now = System.currentTimeMillis();
        long ttl = ttlSeconds();
        if (transaction.getCreatedAt() == null) {
            transaction.setCreatedAt(now);
        }
        transaction.setExpireAt(now + TimeUnit.SECONDS.toMillis(ttl));

        String callId = transaction.getCallId();
        String dataKey = dataKey(callId);
        String streamKey = streamKey(transaction.getApp(), transaction.getStream());
        String deviceKey = deviceKey(transaction.getDeviceId());
        String metadataKey = metadataKey(callId);
        Map<String, String> metadata = metadata(transaction);
        java.util.concurrent.atomic.AtomicBoolean skipped = new java.util.concurrent.atomic.AtomicBoolean();
        boolean completed = executeTransaction(operations -> {
            operations.watch(List.of(streamKey, dataKey, metadataKey));
            String oldCallId = asString(operations.opsForValue().get(streamKey));
            SsrcTransaction old = asTransaction(operations.opsForValue().get(dataKey));
            if (old == null) {
                old = transactionFromMetadata(operations.opsForValue().get(metadataKey), callId);
            }
            if (old != null && isBlank(old.getCallId())) {
                old.setCallId(isBlank(oldCallId) ? callId : oldCallId);
            }
            if (!isBlank(oldCallId) && !oldCallId.equals(callId)) {
                String oldDataKey = dataKey(oldCallId);
                String oldMetadataKey = metadataKey(oldCallId);
                operations.watch(List.of(oldDataKey, oldMetadataKey));
                old = asTransaction(operations.opsForValue().get(oldDataKey));
                if (old == null) {
                    old = transactionFromMetadata(operations.opsForValue().get(oldMetadataKey), oldCallId);
                }
                if (old != null && isBlank(old.getCallId())) {
                    old.setCallId(oldCallId);
                }
                if (!allowStreamReplacement) {
                    skipped.set(true);
                    return true;
                }
            }
            if (!allowStreamReplacement && old != null && !sameIdentity(old, transaction)) {
                skipped.set(true);
                return true;
            }
            operations.multi();
            if (old != null && !sameIdentity(old, transaction)) {
                queueDeleteSession(operations, old, old.getCallId(), oldCallId == null || oldCallId.equals(old.getCallId()));
            } else if (!isBlank(oldCallId) && !oldCallId.equals(callId)) {
                operations.delete(dataKey(oldCallId));
                operations.delete(metadataKey(oldCallId));
                operations.delete(streamKey);
                operations.opsForZSet().remove(expireKey(), oldCallId);
            }
            operations.opsForValue().set(dataKey, transaction, ttl, TimeUnit.SECONDS);
            operations.opsForValue().set(streamKey, callId, ttl, TimeUnit.SECONDS);
            if (!isBlank(transaction.getDeviceId())) {
                operations.opsForSet().add(deviceKey, callId);
            }
            operations.opsForZSet().add(expireKey(), callId, transaction.getExpireAt());
            operations.opsForValue().set(metadataKey, metadata, ttl + META_GRACE_SECONDS, TimeUnit.SECONDS);
            return operations.exec() != null;
        });
        return completed && !skipped.get();
    }

    public SsrcTransaction getSsrcTransactionByStream(String app, String stream) {
        String streamKey = streamKey(app, stream);
        String callId = asString(redisTemplate.opsForValue().get(streamKey));
        if (!isBlank(callId)) {
            SsrcTransaction current = getV2ByCallId(callId);
            if (current != null) {
                return touchIfNeeded(current);
            }
            // Remove only the owner observed above; a concurrent replacement must survive.
            removeOrphanStreamIfOwner(streamKey, callId);
        }
        SsrcTransaction legacy = asTransaction(redisTemplate.opsForHash()
                .get(legacyStreamHashKey(), streamField(app, stream)));
        return touchIfNeeded(migrateLegacy(legacy));
    }

    private void removeOrphanStreamIfOwner(String streamKey, String callId) {
        executeTransaction(operations -> {
            operations.watch(streamKey);
            if (!callId.equals(asString(operations.opsForValue().get(streamKey)))) {
                return true;
            }
            operations.multi();
            operations.delete(streamKey);
            return operations.exec() != null;
        });
    }

    public SsrcTransaction getSsrcTransactionByCallId(String callId) {
        if (isBlank(callId)) {
            return null;
        }
        SsrcTransaction current = getV2ByCallId(callId);
        if (current != null) {
            return touchIfNeeded(current);
        }
        SsrcTransaction legacy = asTransaction(redisTemplate.opsForHash().get(legacyCallIdHashKey(), callId));
        return touchIfNeeded(migrateLegacy(legacy));
    }

    /**
     * 设备查询只读取该设备的 Set；迁移期 Set 为空时才分批扫描旧 Hash。
     */
    public List<SsrcTransaction> getSsrcTransactionByDeviceId(String deviceId) {
        Map<String, SsrcTransaction> result = new LinkedHashMap<>();
        Set<Object> callIds = redisTemplate.opsForSet().members(deviceKey(deviceId));
        boolean legacyScanNeeded = callIds == null || callIds.isEmpty();
        if (callIds != null) {
            List<String> batchCallIds = callIds.stream().map(SipInviteSessionManager::asString)
                    .filter(callId -> !isBlank(callId)).toList();
            List<Object> batchValues = batchCallIds.isEmpty() ? List.of()
                    : redisTemplate.opsForValue().multiGet(batchCallIds.stream().map(this::dataKey).toList());
            for (int i = 0; i < batchCallIds.size(); i++) {
                String callId = batchCallIds.get(i);
                SsrcTransaction transaction = batchValues != null && i < batchValues.size()
                        ? asTransaction(batchValues.get(i)) : null;
                if (transaction == null) {
                    redisTemplate.opsForSet().remove(deviceKey(deviceId), callId);
                    legacyScanNeeded = true;
                    continue;
                }
                if (deviceId == null || deviceId.equals(transaction.getDeviceId())) {
                    result.put(transaction.getCallId(), transaction);
                } else {
                    redisTemplate.opsForSet().remove(deviceKey(deviceId), callId);
                    legacyScanNeeded = true;
                }
            }
        }
        if (legacyScanNeeded) {
            for (SsrcTransaction transaction : scanLegacyAndMigrate(deviceId, LEGACY_SCAN_COUNT)) {
                if (transaction != null && (deviceId == null || deviceId.equals(transaction.getDeviceId()))) {
                    result.put(transaction.getCallId(), transaction);
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    /** 删除流对应会话，且只允许当前 stream owner 删除。 */
    public void removeByStream(String app, String stream) {
        SsrcTransaction transaction = getSsrcTransactionByStream(app, stream);
        if (transaction == null || isBlank(transaction.getCallId())) {
            return;
        }
        String streamKey = streamKey(app, stream);
        String callId = transaction.getCallId();
        executeTransaction(operations -> {
            operations.watch(List.of(streamKey, dataKey(callId)));
            String owner = asString(operations.opsForValue().get(streamKey));
            if (!callId.equals(owner)) {
                return true;
            }
            SsrcTransaction current = asTransaction(operations.opsForValue().get(dataKey(callId)));
            operations.multi();
            queueDeleteSession(operations, current == null ? transaction : current, callId, true);
            return operations.exec() != null;
        });
    }

    /** 删除 Call-ID 对应会话，重复调用安全。 */
    public void removeByCallId(String callId) {
        if (isBlank(callId)) {
            return;
        }
        SsrcTransaction transaction = getSsrcTransactionByCallId(callId);
        if (transaction == null) {
            return;
        }
        String dataKey = dataKey(callId);
        String streamKey = streamKey(transaction.getApp(), transaction.getStream());
        executeTransaction(operations -> {
            operations.watch(List.of(dataKey, streamKey, expireKey()));
            SsrcTransaction current = asTransaction(operations.opsForValue().get(dataKey));
            if (current == null || !callId.equals(current.getCallId())) {
                return true;
            }
            String owner = asString(operations.opsForValue().get(streamKey));
            operations.multi();
            queueDeleteSession(operations, current, callId, callId.equals(owner));
            return operations.exec() != null;
        });
    }

    /**
     * 返回当前 V2 会话并在迁移期发现旧 Hash 中尚未迁移的记录。
     */
    public List<SsrcTransaction> getAll() {
        Map<String, SsrcTransaction> result = new LinkedHashMap<>();
        List<String> batchCallIds = new ArrayList<>(V2_RANGE_BATCH);
        try (Cursor<ZSetOperations.TypedTuple<Object>> cursor = redisTemplate.opsForZSet()
                .scan(expireKey(), ScanOptions.scanOptions().count(V2_RANGE_BATCH).build())) {
            while (cursor.hasNext()) {
                ZSetOperations.TypedTuple<Object> tuple = cursor.next();
                String callId = tuple == null ? null : asString(tuple.getValue());
                if (!isBlank(callId)) {
                    batchCallIds.add(callId);
                }
                if (batchCallIds.size() >= V2_RANGE_BATCH) {
                    readV2Batch(batchCallIds, result);
                    batchCallIds.clear();
                }
            }
            readV2Batch(batchCallIds, result);
        } catch (RuntimeException e) {
            log.warn("[SIP Invite会话] 扫描 EXPIRE 索引失败", e);
        }
        // Migration is deliberately bounded here; the scheduled task drains the old Hash asynchronously.
        for (SsrcTransaction transaction : scanLegacyAndMigrate(null, LEGACY_SCAN_COUNT)) {
            if (transaction != null) {
                result.put(transaction.getCallId(), transaction);
            }
        }
        return new ArrayList<>(result.values());
    }

    private void readV2Batch(List<String> callIds, Map<String, SsrcTransaction> result) {
        if (callIds == null || callIds.isEmpty()) {
            return;
        }
        List<Object> values = redisTemplate.opsForValue().multiGet(callIds.stream().map(this::dataKey).toList());
        for (int i = 0; i < callIds.size(); i++) {
            String callId = callIds.get(i);
            SsrcTransaction transaction = values != null && i < values.size() ? asTransaction(values.get(i)) : null;
            if (transaction != null) {
                result.put(transaction.getCallId(), transaction);
            } else {
                redisTemplate.opsForZSet().remove(expireKey(), callId);
            }
        }
    }

    /** 清理到期会话，返回本轮删除数。 */
    public int cleanupExpiredSessions(int limit) {
        int batchSize = Math.max(1, Math.min(limit, 500));
        long now = System.currentTimeMillis();
        Set<Object> expired = redisTemplate.opsForZSet().rangeByScore(expireKey(), 0, now, 0, batchSize);
        if (expired == null || expired.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (Object value : expired) {
            if (removeExpiredIfOwner(asString(value), now)) {
                removed++;
            }
        }
        return removed;
    }

    /** 迁移旧 Call-ID Hash 的一批记录，供定时任务调用。 */
    public int migrateLegacyBatch(int limit) {
        return scanLegacyAndMigrate(null, Math.max(1, limit)).size();
    }

    private boolean removeExpiredIfOwner(String callId, long now) {
        if (isBlank(callId)) {
            return false;
        }
        String dataKey = dataKey(callId);
        String metadataKey = metadataKey(callId);
        java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
        boolean completed = executeTransaction(operations -> {
            operations.watch(List.of(dataKey, metadataKey, expireKey()));
            Double score = operations.opsForZSet().score(expireKey(), callId);
            if (score == null || score > now) {
                return true;
            }
            SsrcTransaction transaction = asTransaction(operations.opsForValue().get(dataKey));
            if (transaction == null) {
                transaction = transactionFromMetadata(operations.opsForValue().get(metadataKey), callId);
            }
            String streamKey = transaction == null ? null : streamKey(transaction.getApp(), transaction.getStream());
            if (streamKey != null) {
                operations.watch(streamKey);
            }
            String owner = streamKey == null ? null : asString(operations.opsForValue().get(streamKey));
            operations.multi();
            expired.set(true);
            if (transaction == null) {
                operations.delete(dataKey);
                operations.delete(metadataKey);
                operations.opsForZSet().remove(expireKey(), callId);
            } else {
                queueDeleteSession(operations, transaction, callId, callId.equals(owner));
            }
            return operations.exec() != null;
        });
        return completed && expired.get();
    }

    private List<SsrcTransaction> scanLegacyAndMigrate(String deviceId, int limit) {
        List<SsrcTransaction> migrated = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().count(LEGACY_SCAN_COUNT).build();
        try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash()
                .scan(legacyCallIdHashKey(), options)) {
            int scanBudget = Math.max(LEGACY_SCAN_COUNT, Math.min(limit, 500) * 4);
            int scanned = 0;
            while (cursor.hasNext() && migrated.size() < limit && scanned++ < scanBudget) {
                Map.Entry<Object, Object> entry = cursor.next();
                SsrcTransaction transaction = asTransaction(entry.getValue());
                if (transaction == null || isBlank(transaction.getCallId())) {
                    log.warn("[SIP Invite迁移] 旧记录无法解析，保留原字段：{}", entry.getKey());
                    continue;
                }
                if (deviceId != null && !deviceId.equals(transaction.getDeviceId())) {
                    continue;
                }
                SsrcTransaction result = migrateLegacy(transaction);
                if (result != null) {
                    migrated.add(result);
                }
            }
        } catch (RuntimeException e) {
            log.warn("[SIP Invite迁移] 扫描旧 Call-ID Hash 失败", e);
        }
        return migrated;
    }

    private SsrcTransaction migrateLegacy(SsrcTransaction transaction) {
        if (transaction == null || isBlank(transaction.getCallId())) {
            return null;
        }
        SsrcTransaction current = getV2ByCallId(transaction.getCallId());
        String currentStreamOwner = asString(redisTemplate.opsForValue()
                .get(streamKey(transaction.getApp(), transaction.getStream())));
        if (current != null) {
            // V2 is authoritative.  Never replace a live record with stale legacy data.
            if (sameIdentity(current, transaction)) {
                deleteLegacyFields(transaction);
                return current;
            }
            log.warn("[SIP Invite迁移] 发现活动 V2 会话，跳过旧记录：callId={}, streamOwner={}",
                    transaction.getCallId(), currentStreamOwner);
            return null;
        }
        if (!isBlank(currentStreamOwner) && !transaction.getCallId().equals(currentStreamOwner)) {
            log.warn("[SIP Invite迁移] 流已被 V2 会话占用，保留旧字段：callId={}, owner={}",
                    transaction.getCallId(), currentStreamOwner);
            return null;
        }
        if (!putInternal(transaction, false)) {
            return null;
        }
        SsrcTransaction persisted = getV2ByCallId(transaction.getCallId());
        String streamOwner = asString(redisTemplate.opsForValue().get(streamKey(transaction.getApp(), transaction.getStream())));
        Set<Object> members = isBlank(transaction.getDeviceId()) ? Set.of()
                : redisTemplate.opsForSet().members(deviceKey(transaction.getDeviceId()));
        Double expiry = redisTemplate.opsForZSet().score(expireKey(), transaction.getCallId());
        boolean deviceIndexValid = isBlank(transaction.getDeviceId())
                || (members != null && members.contains(transaction.getCallId()));
        if (persisted == null || !transaction.getCallId().equals(streamOwner)
                || !deviceIndexValid || expiry == null) {
            return null;
        }
        deleteLegacyFields(transaction);
        return persisted;
    }

    private void deleteLegacyFields(SsrcTransaction transaction) {
        executeTransaction(operations -> {
            operations.multi();
            operations.opsForHash().delete(legacyCallIdHashKey(), transaction.getCallId());
            operations.opsForHash().delete(legacyStreamHashKey(), streamField(transaction.getApp(), transaction.getStream()));
            return operations.exec() != null;
        });
    }

    private SsrcTransaction getV2ByCallId(String callId) {
        return isBlank(callId) ? null : asTransaction(redisTemplate.opsForValue().get(dataKey(callId)));
    }

    /** Refreshes only sessions close to expiry so a continuously used stream is not cut off at seven days. */
    private SsrcTransaction touchIfNeeded(SsrcTransaction transaction) {
        if (transaction == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        long ttl = ttlSeconds();
        if (transaction.getExpireAt() != null
                && transaction.getExpireAt() - now > TimeUnit.SECONDS.toMillis(Math.max(1, ttl / 2))) {
            return transaction;
        }
        String callId = transaction.getCallId();
        String dataKey = dataKey(callId);
        String streamKey = streamKey(transaction.getApp(), transaction.getStream());
        String metadataKey = metadataKey(callId);
        long expireAt = now + TimeUnit.SECONDS.toMillis(ttl);
        boolean refreshed = executeTransaction(operations -> {
            operations.watch(List.of(dataKey, streamKey, metadataKey, expireKey()));
            SsrcTransaction current = asTransaction(operations.opsForValue().get(dataKey));
            if (current == null || !callId.equals(current.getCallId())) {
                return true;
            }
            String owner = asString(operations.opsForValue().get(streamKey));
            if (owner != null && !callId.equals(owner)) {
                return true;
            }
            current.setExpireAt(expireAt);
            operations.multi();
            operations.opsForValue().set(dataKey, current, ttl, TimeUnit.SECONDS);
            operations.opsForValue().set(streamKey, callId, ttl, TimeUnit.SECONDS);
            operations.opsForValue().set(metadataKey, metadata(current), ttl + META_GRACE_SECONDS, TimeUnit.SECONDS);
            operations.opsForZSet().add(expireKey(), callId, expireAt);
            return operations.exec() != null;
        });
        if (refreshed) {
            transaction.setExpireAt(expireAt);
        }
        return transaction;
    }

    private void queueDeleteSession(RedisOperations<String, Object> operations, SsrcTransaction transaction,
                                    String callId, boolean removeStream) {
        operations.delete(dataKey(callId));
        operations.delete(metadataKey(callId));
        if (removeStream && transaction != null) {
            operations.delete(streamKey(transaction.getApp(), transaction.getStream()));
        }
        if (transaction != null && !isBlank(transaction.getDeviceId())) {
            operations.opsForSet().remove(deviceKey(transaction.getDeviceId()), callId);
        }
        operations.opsForZSet().remove(expireKey(), callId);
    }

    @SuppressWarnings("unchecked")
    private boolean executeTransaction(TransactionWork callback) {
        for (int attempt = 1; attempt <= TRANSACTION_RETRIES; attempt++) {
            try {
                Boolean completed = redisTemplate.execute(new SessionCallback<Boolean>() {
                    @Override
                    public <K, V> Boolean execute(RedisOperations<K, V> operations) {
                        return callback.execute((RedisOperations<String, Object>) operations);
                    }
                });
                if (Boolean.TRUE.equals(completed)) {
                    return true;
                }
            } catch (RuntimeException e) {
                if (attempt == TRANSACTION_RETRIES) {
                    log.error("[SIP Invite会话] Redis事务失败，重试耗尽", e);
                }
            }
        }
        return false;
    }

    private String serverId() {
        return userSetting == null || isBlank(userSetting.getServerId()) ? "000000" : userSetting.getServerId();
    }

    private long ttlSeconds() {
        long configured = userSetting == null ? DEFAULT_TTL_SECONDS : userSetting.getSipInviteSessionTtlSeconds();
        return configured > 0 ? configured : DEFAULT_TTL_SECONDS;
    }

    private String dataKey(String callId) {
        return VideoManagerConstants.SIP_INVITE_SESSION_V2_DATA + serverId() + ":" + callId;
    }

    private String streamKey(String app, String stream) {
        return VideoManagerConstants.SIP_INVITE_SESSION_V2_STREAM + serverId() + ":" + streamField(app, stream);
    }

    private String deviceKey(String deviceId) {
        return VideoManagerConstants.SIP_INVITE_SESSION_V2_DEVICE + serverId() + ":" + (deviceId == null ? "" : deviceId);
    }

    private String expireKey() {
        return VideoManagerConstants.SIP_INVITE_SESSION_V2_EXPIRE + serverId();
    }

    private String metadataKey(String callId) {
        return VideoManagerConstants.SIP_INVITE_SESSION_V2_META + serverId() + ":" + callId;
    }

    private String legacyCallIdHashKey() {
        return VideoManagerConstants.SIP_INVITE_SESSION_CALL_ID + serverId();
    }

    private String legacyStreamHashKey() {
        return VideoManagerConstants.SIP_INVITE_SESSION_STREAM + serverId();
    }

    private static String streamField(String app, String stream) {
        return String.valueOf(app) + String.valueOf(stream);
    }

    private static SsrcTransaction asTransaction(Object value) {
        return value instanceof SsrcTransaction ? (SsrcTransaction) value : null;
    }

    private static Map<String, String> metadata(SsrcTransaction transaction) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("deviceId", transaction.getDeviceId());
        metadata.put("app", transaction.getApp());
        metadata.put("stream", transaction.getStream());
        return metadata;
    }

    private static SsrcTransaction transactionFromMetadata(Object value, String callId) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        SsrcTransaction transaction = new SsrcTransaction();
        transaction.setCallId(callId);
        transaction.setDeviceId(stringValue(map.get("deviceId")));
        transaction.setApp(stringValue(map.get("app")));
        transaction.setStream(stringValue(map.get("stream")));
        return transaction;
    }

    private static boolean sameIdentity(SsrcTransaction first, SsrcTransaction second) {
        return first != null && second != null
                && java.util.Objects.equals(first.getCallId(), second.getCallId())
                && java.util.Objects.equals(first.getDeviceId(), second.getDeviceId())
                && java.util.Objects.equals(first.getApp(), second.getApp())
                && java.util.Objects.equals(first.getStream(), second.getStream());
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
