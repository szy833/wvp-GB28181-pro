package com.genersoft.iot.vmp.media.service.impl;

import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaNodeServerService;
import com.genersoft.iot.vmp.media.service.bean.MediaStreamCountResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaServerServiceImplTest {

    @Test
    void successfulReconciliationWritesAbsoluteCount() {
        MediaServerServiceImpl service = serviceWith(MediaStreamCountResult.success(3));
        MediaServer mediaServer = mediaServer();
        ZSetOperations<String, Object> zset = zset(service);

        service.reconcileMediaServerLoad(mediaServer);

        verify(zset).add(onlineKey(), "media-1", 3D);
    }

    @Test
    void failedReconciliationLeavesExistingCountUntouched() {
        MediaServerServiceImpl service = serviceWith(MediaStreamCountResult.failure("timeout"));
        MediaServer mediaServer = mediaServer();
        ZSetOperations<String, Object> zset = zset(service);

        assertDoesNotThrow(() -> service.reconcileMediaServerLoad(mediaServer));

        verify(zset, org.mockito.Mockito.never()).add(eq(onlineKey()), eq("media-1"), anyDouble());
    }

    @Test
    void resetInitializesMemberAndSchedulesReconciliation() {
        MediaServerServiceImpl service = serviceWith(MediaStreamCountResult.success(0));
        MediaServer mediaServer = mediaServer();
        ZSetOperations<String, Object> zset = zset(service);
        when(zset.score(onlineKey(), "media-1")).thenReturn(null);

        service.resetOnlineServerItem(mediaServer);

        verify(zset, times(2)).add(onlineKey(), "media-1", 0D);
    }

    @Test
    void removeCountDoesNotLeaveNegativeLoad() {
        MediaServerServiceImpl service = serviceWith(MediaStreamCountResult.success(0));
        ZSetOperations<String, Object> zset = zset(service);
        when(zset.incrementScore(onlineKey(), "media-1", -1D)).thenReturn(-1D);

        service.removeCount("media-1");

        verify(zset).add(onlineKey(), "media-1", 0D);
    }

    private static MediaServerServiceImpl serviceWith(MediaStreamCountResult result) {
        MediaServerServiceImpl service = new MediaServerServiceImpl();
        UserSetting userSetting = new UserSetting();
        userSetting.setServerId("server-1");
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ZSetOperations<String, Object> zset = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zset);
        IMediaNodeServerService nodeService = mock(IMediaNodeServerService.class);
        when(nodeService.countActiveStreams(org.mockito.ArgumentMatchers.any())).thenReturn(result);
        ReflectionTestUtils.setField(service, "userSetting", userSetting);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "nodeServerServiceMap", Map.of("zlm", nodeService));
        ReflectionTestUtils.setField(service, "taskExecutor", (TaskExecutor) Runnable::run);
        return service;
    }

    private static MediaServer mediaServer() {
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("media-1");
        mediaServer.setType("zlm");
        mediaServer.setStatus(true);
        return mediaServer;
    }

    @SuppressWarnings("unchecked")
    private static ZSetOperations<String, Object> zset(MediaServerServiceImpl service) {
        RedisTemplate<String, Object> redisTemplate = (RedisTemplate<String, Object>)
                ReflectionTestUtils.getField(service, "redisTemplate");
        return redisTemplate.opsForZSet();
    }

    private static String onlineKey() {
        return VideoManagerConstants.ONLINE_MEDIA_SERVERS_PREFIX + "server-1";
    }
}
