package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.gb28181.bean.RecordInfo;
import com.genersoft.iot.vmp.gb28181.event.record.RecordInfoEndEvent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertSame;

class DeviceChannelServiceRecordEventTest {

    @Test
    void buffersEndEventUntilTheQueryThreadStartsWaiting() throws Exception {
        DeviceChannelServiceImpl service = new DeviceChannelServiceImpl();
        Map<String, BlockingQueue<RecordInfo>> waiters = new ConcurrentHashMap<>();
        BlockingQueue<RecordInfo> waiter = new LinkedBlockingQueue<>(1);
        waiters.put("record42", waiter);
        ReflectionTestUtils.setField(service, "topicSubscribers", waiters);

        RecordInfo recordInfo = new RecordInfo();
        recordInfo.setSn("42");
        RecordInfoEndEvent event = new RecordInfoEndEvent(this);
        event.setRecordInfo(recordInfo);

        service.onApplicationEvent(event);

        assertSame(recordInfo, waiter.poll());
    }
}
