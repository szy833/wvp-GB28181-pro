package com.genersoft.iot.vmp.conf.redis;

import com.genersoft.iot.vmp.common.CommonCallback;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.redis.bean.RedisRpcRequest;
import com.genersoft.iot.vmp.conf.redis.bean.RedisRpcResponse;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RedisRpcConfigTest {

    private RedisRpcConfig config;
    private UserSetting userSetting;
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setUp() {
        config = new RedisRpcConfig();
        userSetting = new UserSetting();
        userSetting.setServerId("wvp-a");
        userSetting.setRedisRpcCallbackTtl(30);
        redisTemplate = mock(RedisTemplate.class);
        ReflectionTestUtils.setField(config, "userSetting", userSetting);
        ReflectionTestUtils.setField(config, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(config, "taskExecutor", mock(TaskExecutor.class));
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.invokeMethod(config, "shutdown");
    }

    @Test
    void synchronousTimeoutReturnsErrorAndRemovesPendingRequest() {
        RedisRpcResponse response = config.request(request("sync"), 1, TimeUnit.MILLISECONDS);

        assertEquals(ErrorCode.ERROR486.getCode(), response.getStatusCode());
        assertEquals(0, config.getCallbackCount());
    }

    @Test
    void requestSequenceIsOutsideLegacyOneToThousandRange() {
        RedisRpcRequest request = request("sequence");

        config.request(request, 1, TimeUnit.MILLISECONDS);

        assertTrue(request.getSn() > 1_000);
    }

    @Test
    void interruptedSynchronousRequestPreservesInterruptFlag() {
        Thread.currentThread().interrupt();

        RedisRpcResponse response = config.request(request("interrupt"), 1, TimeUnit.SECONDS);

        assertEquals(ErrorCode.ERROR486.getCode(), response.getStatusCode());
        assertTrue(Thread.interrupted());
    }

    @Test
    void responseFromAnotherRequesterIsIgnored() {
        AtomicInteger callbackCount = new AtomicInteger();
        RedisRpcRequest request = request("foreign");
        config.request(request, callback(response -> callbackCount.incrementAndGet()));

        RedisRpcResponse response = request.getResponse();
        response.setFromId("wvp-b");

        assertFalse(config.response(response));
        assertEquals(0, callbackCount.get());
        assertEquals(1, config.getCallbackCount());
    }

    @Test
    void matchingResponseConsumesCallbackOnce() {
        AtomicInteger callbackCount = new AtomicInteger();
        RedisRpcRequest request = request("matching");
        config.request(request, callback(response -> callbackCount.incrementAndGet()));

        RedisRpcResponse response = request.getResponse();
        response.setFromId("wvp-a");

        assertTrue(config.response(response));
        assertFalse(config.response(response));
        assertEquals(1, callbackCount.get());
        assertEquals(0, config.getCallbackCount());
    }

    @Test
    void callbackExceptionDoesNotKeepPendingRequest() {
        RedisRpcRequest request = request("callback-error");
        config.request(request, response -> {
            throw new IllegalStateException("callback failure");
        });

        RedisRpcResponse response = request.getResponse();
        response.setFromId("wvp-a");

        assertTrue(config.response(response));
        assertEquals(0, config.getCallbackCount());
    }

    @Test
    void callbackExpiresAndLateResponseIsIgnored() throws Exception {
        AtomicInteger callbackCount = new AtomicInteger();
        RedisRpcRequest request = request("expiry");
        config.request(request, callback(response -> callbackCount.incrementAndGet()));

        Thread.sleep(120);

        assertEquals(0, config.getCallbackCount());
        assertFalse(config.response(request.getResponse()));
        assertEquals(1, callbackCount.get());
    }

    private RedisRpcRequest request(String uri) {
        RedisRpcRequest request = new RedisRpcRequest();
        request.setFromId("wvp-a");
        request.setUri(uri);
        return request;
    }

    private CommonCallback<RedisRpcResponse> callback(CommonCallback<RedisRpcResponse> callback) {
        return callback;
    }
}
