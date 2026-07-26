package com.genersoft.iot.vmp.jt1078.service.impl;

import com.genersoft.iot.vmp.common.CommonCallback;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.jt1078.cmd.JT1078Template;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class Jt1078PlayServiceCallbackBatchTest {

    @Test
    void oldTerminalCannotDrainNewRequestBatch() throws Exception {
        jt1078PlayServiceImpl service = new jt1078PlayServiceImpl();
        Class<?> batchType = Class.forName(
                "com.genersoft.iot.vmp.jt1078.service.impl.jt1078PlayServiceImpl$CallbackBatch");
        Constructor<?> constructor = batchType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object oldBatch = constructor.newInstance();
        Object newBatch = constructor.newInstance();

        Field callbacksField = batchType.getDeclaredField("callbacks");
        callbacksField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<com.genersoft.iot.vmp.common.CommonCallback<WVPResult<StreamInfo>>> callbacks =
                (List<com.genersoft.iot.vmp.common.CommonCallback<WVPResult<StreamInfo>>>) callbacksField.get(newBatch);
        AtomicInteger callbackCount = new AtomicInteger();
        callbacks.add(result -> callbackCount.incrementAndGet());

        Field batchesField = jt1078PlayServiceImpl.class.getDeclaredField("callbackBatches");
        batchesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> batches = (Map<String, Object>) batchesField.get(service);
        batches.put("play-key", newBatch);

        Method drain = jt1078PlayServiceImpl.class.getDeclaredMethod(
                "drainCallbacks", String.class, batchType);
        drain.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<com.genersoft.iot.vmp.common.CommonCallback<WVPResult<StreamInfo>>> oldResult =
                (List<com.genersoft.iot.vmp.common.CommonCallback<WVPResult<StreamInfo>>>)
                        drain.invoke(service, "play-key", oldBatch);
        assertTrue(oldResult.isEmpty());
        assertSame(newBatch, batches.get("play-key"));

        @SuppressWarnings("unchecked")
        List<com.genersoft.iot.vmp.common.CommonCallback<WVPResult<StreamInfo>>> newResult =
                (List<com.genersoft.iot.vmp.common.CommonCallback<WVPResult<StreamInfo>>>)
                        drain.invoke(service, "play-key", newBatch);
        assertEquals(1, newResult.size());
        newResult.get(0).run(new WVPResult<>());
        assertEquals(1, callbackCount.get());
    }

    @Test
    void drainedExpectedBatchStillCleansPlayResources() throws Exception {
        jt1078PlayServiceImpl service = new jt1078PlayServiceImpl();
        DynamicTask dynamicTask = mock(DynamicTask.class);
        JT1078Template template = mock(JT1078Template.class);
        IReceiveRtpServerService rtpService = mock(IReceiveRtpServerService.class);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        MediaServer mediaServer = mock(MediaServer.class);
        StreamInfo streamInfo = new StreamInfo();
        streamInfo.setMediaServer(mediaServer);
        streamInfo.setApp("rtp");
        streamInfo.setStream("zlm-stream");
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(any())).thenReturn(streamInfo);
        ReflectionTestUtils.setField(service, "dynamicTask", dynamicTask);
        ReflectionTestUtils.setField(service, "jt1078Template", template);
        ReflectionTestUtils.setField(service, "receiveRtpServerService", rtpService);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        Class<?> batchType = Class.forName(
                "com.genersoft.iot.vmp.jt1078.service.impl.jt1078PlayServiceImpl$CallbackBatch");
        Constructor<?> constructor = batchType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object oldBatch = constructor.newInstance();
        Field batchesField = jt1078PlayServiceImpl.class.getDeclaredField("callbackBatches");
        batchesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> batches = (Map<String, Object>) batchesField.get(service);
        String playKey = VideoManagerConstants.INVITE_INFO_1078_PLAY + "phone:7";
        batches.put(playKey, oldBatch);
        batches.remove(playKey, oldBatch); // The timeout callback already consumed it.

        Method stop = jt1078PlayServiceImpl.class.getDeclaredMethod(
                "stopPlayInternal", String.class, Integer.class, boolean.class, batchType);
        stop.setAccessible(true);
        stop.invoke(service, "phone", 7, false, oldBatch);

        verify(dynamicTask).stop(playKey);
        verify(template).checkTerminalStatus("phone");
        verify(template).stopLive(eq("phone"), any(), eq(6));
        verify(rtpService).closeRTPServer(mediaServer, "rtp", "zlm-stream");
        verify(redis).delete(playKey);
    }

    @Test
    void expectedBatchDoesNotCleanResourcesOwnedByNewBatch() throws Exception {
        jt1078PlayServiceImpl service = new jt1078PlayServiceImpl();
        DynamicTask dynamicTask = mock(DynamicTask.class);
        ReflectionTestUtils.setField(service, "dynamicTask", dynamicTask);

        Class<?> batchType = Class.forName(
                "com.genersoft.iot.vmp.jt1078.service.impl.jt1078PlayServiceImpl$CallbackBatch");
        Constructor<?> constructor = batchType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object oldBatch = constructor.newInstance();
        Object newBatch = constructor.newInstance();
        Field batchesField = jt1078PlayServiceImpl.class.getDeclaredField("callbackBatches");
        batchesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> batches = (Map<String, Object>) batchesField.get(service);
        String playKey = VideoManagerConstants.INVITE_INFO_1078_PLAY + "phone:7";
        batches.put(playKey, newBatch);

        Method stop = jt1078PlayServiceImpl.class.getDeclaredMethod(
                "stopPlayInternal", String.class, Integer.class, boolean.class, batchType);
        stop.setAccessible(true);
        stop.invoke(service, "phone", 7, false, oldBatch);

        verifyNoInteractions(dynamicTask);
        assertSame(newBatch, batches.get(playKey));
    }

    @Test
    void callbackNotificationUsesSnapshotWhenCallbackAddsAnotherCallback() throws Exception {
        jt1078PlayServiceImpl service = new jt1078PlayServiceImpl();
        List<CommonCallback<WVPResult<StreamInfo>>> callbacks =
                Collections.synchronizedList(new ArrayList<>());
        AtomicInteger callbackCount = new AtomicInteger();
        callbacks.add(result -> {
            callbackCount.incrementAndGet();
            callbacks.add(result2 -> callbackCount.incrementAndGet());
        });

        Method notify = jt1078PlayServiceImpl.class.getDeclaredMethod(
                "notifyCallbacks", List.class, WVPResult.class);
        notify.setAccessible(true);
        assertDoesNotThrow(() -> notify.invoke(service, callbacks, new WVPResult<StreamInfo>()));
        assertEquals(1, callbackCount.get());
    }
}
