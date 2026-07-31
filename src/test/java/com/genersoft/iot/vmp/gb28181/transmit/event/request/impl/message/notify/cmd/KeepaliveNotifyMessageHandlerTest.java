package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl.message.notify.cmd;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KeepaliveNotifyMessageHandlerTest {

    @Test
    void keepsHeartbeatArrivingDuringRedisFlush() {
        KeepaliveNotifyMessageHandler handler = new KeepaliveNotifyMessageHandler();
        KeepaliveTaskBuffer buffer = new KeepaliveTaskBuffer(10);
        IRedisCatchStorage redis = mock(IRedisCatchStorage.class);
        ReflectionTestUtils.setField(handler, "pendingKeepalives", buffer);
        ReflectionTestUtils.setField(handler, "redisCatchStorage", redis);
        buffer.offer("device-1", 100L);

        doAnswer(invocation -> {
            buffer.offer("device-1", 200L);
            return null;
        }).when(redis).updateDeviceKeepaliveTimeStamp(anyList());

        handler.executeUpdateDeviceList();

        assertEquals(200L, buffer.snapshot().get("device-1").timestamp());
        verify(redis).updateDeviceKeepaliveTimeStamp(org.mockito.ArgumentMatchers.argThat(devices -> {
            List<Device> deviceList = (List<Device>) devices;
            return deviceList.size() == 1
                    && "device-1".equals(deviceList.get(0).getDeviceId())
                    && deviceList.get(0).getKeepaliveTimeStamp() == 100L;
        }));
    }

    @Test
    void keepsSnapshotWhenRedisUpdateFails() {
        KeepaliveNotifyMessageHandler handler = new KeepaliveNotifyMessageHandler();
        KeepaliveTaskBuffer buffer = new KeepaliveTaskBuffer(10);
        IRedisCatchStorage redis = mock(IRedisCatchStorage.class);
        ReflectionTestUtils.setField(handler, "pendingKeepalives", buffer);
        ReflectionTestUtils.setField(handler, "redisCatchStorage", redis);
        buffer.offer("device-1", 100L);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(redis).updateDeviceKeepaliveTimeStamp(anyList());

        handler.executeUpdateDeviceList();

        assertEquals(100L, buffer.snapshot().get("device-1").timestamp());
    }
}
