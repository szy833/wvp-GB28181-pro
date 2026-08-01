package com.genersoft.iot.vmp.utils.redis;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.connection.RedisConnection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisUtilTest {

    @Test
    void scanUsesCallerProvidedGlobPatternWithoutAddingWildcards() {
        RedisTemplate redisTemplate = mock(RedisTemplate.class);
        RedisConnection connection = mock(RedisConnection.class);
        Cursor<byte[]> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(false);

        String pattern = "VMP_SIGNALLING_STREAM_server_PUSH_*_*_media";
        ScanOptions[] capturedOptions = new ScanOptions[1];
        doAnswer(invocation -> {
            RedisCallback callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        }).when(redisTemplate).execute(any(RedisCallback.class));
        doAnswer(invocation -> {
            capturedOptions[0] = invocation.getArgument(0);
            return cursor;
        }).when(connection).scan(any(ScanOptions.class));

        RedisUtil.scan(redisTemplate, pattern);

        assertEquals(pattern, capturedOptions[0].getPattern());
    }
}
