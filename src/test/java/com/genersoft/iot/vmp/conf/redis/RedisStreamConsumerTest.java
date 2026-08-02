package com.genersoft.iot.vmp.conf.redis;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisStreamConsumerTest {

    @Test
    void handledRecordIsAcknowledged() throws Exception {
        RedisStreamMessageService service = mock(RedisStreamMessageService.class);
        RedisStreamConfig config = new RedisStreamConfig();
        config.setBlockMillis(100);
        MapRecord<String, String, String> record = MapRecord.create("stream", java.util.Map.of("body", "x"))
                .withId(org.springframework.data.redis.connection.stream.RecordId.of("1-0"));
        when(service.read(any(), any(), any(), anyInt(), any())).thenReturn(List.of(record), List.of());
        CountDownLatch handled = new CountDownLatch(1);
        RedisStreamConsumer consumer = new RedisStreamConsumer(service, config);

        consumer.start("stream", "group", "consumer", item -> {
            handled.countDown();
            return true;
        });
        org.junit.jupiter.api.Assertions.assertTrue(handled.await(2, TimeUnit.SECONDS));
        verify(service, timeout(1000)).ack("stream", "group", record.getId());
        consumer.stop();
    }

    @Test
    void failedHandlerLeavesRecordPending() throws Exception {
        RedisStreamMessageService service = mock(RedisStreamMessageService.class);
        RedisStreamConfig config = new RedisStreamConfig();
        config.setBlockMillis(100);
        MapRecord<String, String, String> record = MapRecord.create("stream", java.util.Map.of("body", "x"));
        when(service.read(any(), any(), any(), anyInt(), any())).thenReturn(List.of(record));
        CountDownLatch handled = new CountDownLatch(1);
        RedisStreamConsumer consumer = new RedisStreamConsumer(service, config);
        consumer.start("stream", "group", "consumer", item -> {
            handled.countDown();
            return false;
        });
        org.junit.jupiter.api.Assertions.assertTrue(handled.await(2, TimeUnit.SECONDS));
        verify(service, after(200).never()).ack(any(), any(), any());
        consumer.stop();
    }
}
