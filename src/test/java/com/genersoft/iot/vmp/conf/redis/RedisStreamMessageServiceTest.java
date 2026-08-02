package com.genersoft.iot.vmp.conf.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStreamMessageServiceTest {

    private StringRedisTemplate redisTemplate;
    private StreamOperations<String, String, String> streamOperations;
    private RedisStreamMessageService service;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        streamOperations = mock(StreamOperations.class);
        doReturn(streamOperations).when(redisTemplate).opsForStream();
        service = new RedisStreamMessageService(redisTemplate);
    }

    @Test
    void appendWritesMessageBodySourceAndPublishTime() {
        when(streamOperations.add(any(MapRecord.class))).thenReturn(RecordId.of("1-0"));

        RecordId id = service.append("stream:gps", "{\"id\":\"channel-1\"}", "pubsub");

        assertEquals("1-0", id.toString());
        var recordCaptor = org.mockito.ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOperations).add(recordCaptor.capture());
        MapRecord<?, ?, ?> record = recordCaptor.getValue();
        assertEquals("stream:gps", record.getStream());
        assertEquals("{\"id\":\"channel-1\"}", record.getValue().get("body"));
        assertEquals("pubsub", record.getValue().get("source"));
        org.junit.jupiter.api.Assertions.assertNotNull(record.getValue().get("publishedAt"));
    }

    @Test
    void readUsesConsumerGroupAndLastConsumedOffset() {
        List<MapRecord<String, String, String>> expected = List.of();
        when(streamOperations.read(any(Consumer.class), any(StreamReadOptions.class),
                any(StreamOffset.class))).thenReturn(expected);

        List<MapRecord<String, String, String>> actual = service.read(
                "stream:gps", "wvp", "consumer-1", 20, Duration.ofMillis(50));

        assertEquals(expected, actual);
        verify(streamOperations).read(
                eq(Consumer.from("wvp", "consumer-1")), any(StreamReadOptions.class),
                eq(StreamOffset.create(
                        "stream:gps", ReadOffset.lastConsumed())));
    }

    @Test
    void acknowledgeDelegatesToStreamOperations() {
        RecordId id = RecordId.of("2-0");
        when(streamOperations.acknowledge("stream:gps", "wvp", id)).thenReturn(1L);

        assertEquals(1L, service.ack("stream:gps", "wvp", id));
        verify(streamOperations).acknowledge("stream:gps", "wvp", id);
    }

    @Test
    void ensureGroupIgnoresExistingBusyGroup() {
        when(streamOperations.createGroup("stream:gps", ReadOffset.from("0-0"), "wvp"))
                .thenThrow(new DataAccessResourceFailureException("BUSYGROUP Consumer Group name already exists"));

        service.ensureGroup("stream:gps", "wvp");

        verify(streamOperations).createGroup("stream:gps", ReadOffset.from("0-0"), "wvp");
    }

    @Test
    void ensureGroupPropagatesUnexpectedRedisErrors() {
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("READONLY You can't write against a read only replica");
        when(streamOperations.createGroup("stream:gps", ReadOffset.from("0-0"), "wvp"))
                .thenThrow(failure);

        assertThrows(DataAccessResourceFailureException.class,
                () -> service.ensureGroup("stream:gps", "wvp"));
    }

    @Test
    void ensureGroupCreatesMissingStreamWithMkStream() {
        when(streamOperations.createGroup("stream:gps", ReadOffset.from("0-0"), "wvp"))
                .thenThrow(new DataAccessResourceFailureException("NOGROUP No such key"));
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn("OK");

        service.ensureGroup("stream:gps", "wvp");

        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void reclaimClaimsOnlyPendingRecordsOlderThanIdleThreshold() {
        RecordId oldId = RecordId.of("3-0");
        RecordId freshId = RecordId.of("4-0");
        PendingMessage old = new PendingMessage(oldId, Consumer.from("wvp", "old"),
                Duration.ofSeconds(30), 2);
        PendingMessage fresh = new PendingMessage(freshId, Consumer.from("wvp", "fresh"),
                Duration.ofSeconds(2), 1);
        when(streamOperations.pending(eq("stream:gps"), eq("wvp"), any(Range.class), eq(10L)))
                .thenReturn(new PendingMessages("wvp", List.of(old, fresh)));
        MapRecord<String, String, String> claimed = MapRecord.create("stream:gps", Map.of("body", "gps"))
                .withId(oldId);
        when(streamOperations.claim("stream:gps", "wvp", "consumer-1", Duration.ofSeconds(10), oldId))
                .thenReturn(List.of(claimed));

        List<MapRecord<String, String, String>> result = service.reclaim(
                "stream:gps", "wvp", "consumer-1", Duration.ofSeconds(10), 10);

        assertEquals(List.of(claimed), result);
        verify(streamOperations).claim("stream:gps", "wvp", "consumer-1", Duration.ofSeconds(10), oldId);
    }

    @Test
    void reclaimRejectsNegativeIdleDuration() {
        assertThrows(IllegalArgumentException.class,
                () -> service.reclaim("stream:gps", "wvp", "consumer-1", Duration.ofSeconds(-1), 10));
    }
}
