package com.genersoft.iot.vmp.conf.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/** Small adapter around Redis Stream operations used by message consumers. */
@Service
@RequiredArgsConstructor
public class RedisStreamMessageService {

    private final StringRedisTemplate redisTemplate;

    public RecordId append(String stream, String body, String source) {
        Map<String, String> fields = new HashMap<>();
        fields.put("body", body);
        fields.put("source", source == null ? "unknown" : source);
        fields.put("publishedAt", Instant.now().toString());
        return operations().add(StreamRecords.string(fields).withStreamKey(stream));
    }

    public List<MapRecord<String, String, String>> read(String stream, String group,
                                                         String consumer, int count, Duration block) {
        StreamReadOptions options = StreamReadOptions.empty().count(Math.max(1, count));
        if (block != null && !block.isNegative() && !block.isZero()) {
            options = options.block(block);
        }
        List<MapRecord<String, String, String>> records = operations().read(
                Consumer.from(group, consumer), options,
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        return records == null ? List.of() : records;
    }

    public long ack(String stream, String group, RecordId id) {
        Long result = operations().acknowledge(stream, group, id);
        return result == null ? 0L : result;
    }

    public List<MapRecord<String, String, String>> reclaim(String stream, String group,
                                                            String consumer, Duration minIdle,
                                                            int count) {
        if (minIdle == null || minIdle.isNegative()) {
            throw new IllegalArgumentException("minIdle must be non-negative");
        }
        int limit = Math.max(1, count);
        var pendingMessages = operations().pending(stream, group, Range.unbounded(), limit);
        if (pendingMessages == null) {
            return List.of();
        }
        List<PendingMessage> pending = pendingMessages
                .stream()
                .filter(item -> item.getElapsedTimeSinceLastDelivery().compareTo(minIdle) >= 0)
                .toList();
        if (pending.isEmpty()) {
            return List.of();
        }
        RecordId[] ids = pending.stream().map(PendingMessage::getId).toArray(RecordId[]::new);
        List<MapRecord<String, String, String>> records = operations()
                .claim(stream, group, consumer, minIdle, ids);
        return records == null ? List.of() : records;
    }

    public void ensureGroup(String stream, String group) {
        try {
            operations().createGroup(stream, ReadOffset.from("0-0"), group);
        } catch (DataAccessException e) {
            if (containsErrorCode(e, "BUSYGROUP")) {
                return;
            }
            if (!containsErrorCode(e, "NOGROUP")) {
                throw e;
            }
            // StreamOperations.createGroup does not expose MKSTREAM. Use the
            // connection command so a first startup can create an empty stream
            // without adding a synthetic record that consumers would process.
            try {
                redisTemplate.execute((RedisCallback<String>) connection -> connection.xGroupCreate(
                        stream.getBytes(StandardCharsets.UTF_8), group,
                        ReadOffset.from("0-0"), true));
            } catch (DataAccessException retryException) {
                if (!containsErrorCode(retryException, "BUSYGROUP")) {
                    throw retryException;
                }
            }
        }
    }

    private boolean containsErrorCode(Throwable error, String code) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(code)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private StreamOperations<String, String, String> operations() {
        return redisTemplate.opsForStream();
    }
}
