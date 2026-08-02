package com.genersoft.iot.vmp.storager.impl;

import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.media.bean.MediaInfo;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedisCatchStorageImplSafetyTest {

    @Test
    void pushCacheWritesAnExpiration() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisCatchStorageImpl storage = newStorage(redis, mock(RedisTemplate.class), mock(StringRedisTemplate.class));

        storage.addPushListItem("rtp", "stream", new MediaInfo());

        verify(values).set(anyString(), any(MediaInfo.class), any());
    }

    @Test
    void removeAllDeviceDoesNotUseBlockingKeysCommand() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        Cursor<Map.Entry<Object, Object>> cursor = mock(Cursor.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.scan(anyString(), any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);
        StringRedisTemplate strings = mock(StringRedisTemplate.class);
        Cursor<String> stringCursor = mock(Cursor.class);
        when(strings.scan(any(ScanOptions.class))).thenReturn(stringCursor);
        when(stringCursor.hasNext()).thenReturn(false);
        RedisCatchStorageImpl storage = newStorage(redis, mock(RedisTemplate.class), strings);

        storage.removeAllDevice();

        verify(strings, never()).keys(anyString());
    }

    private static RedisCatchStorageImpl newStorage(RedisTemplate<String, Object> redis,
                                                     RedisTemplate<String, Long> longs,
                                                     StringRedisTemplate strings) {
        return new RedisCatchStorageImpl(null, new UserSetting(), redis, longs, strings);
    }
}
