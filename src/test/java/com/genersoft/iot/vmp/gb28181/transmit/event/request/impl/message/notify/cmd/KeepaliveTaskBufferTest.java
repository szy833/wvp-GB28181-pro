package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl.message.notify.cmd;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeepaliveTaskBufferTest {

    @Test
    void keepsOnlyTheLatestTimestampForEachDevice() {
        KeepaliveTaskBuffer buffer = new KeepaliveTaskBuffer(10);

        assertTrue(buffer.offer("device-1", 100L));
        assertTrue(buffer.offer("device-1", 200L));

        Map<String, KeepaliveTaskBuffer.PendingKeepalive> snapshot = buffer.snapshot();
        assertEquals(1, snapshot.size());
        assertEquals(200L, snapshot.get("device-1").timestamp());
    }

    @Test
    void rejectsNewDevicesWhenCapacityIsReached() {
        KeepaliveTaskBuffer buffer = new KeepaliveTaskBuffer(1);

        assertTrue(buffer.offer("device-1", 100L));
        assertFalse(buffer.offer("device-2", 200L));
        assertEquals(1, buffer.size());
    }

    @Test
    void removesOnlyTheSnapshotAndKeepsAConcurrentReplacement() {
        KeepaliveTaskBuffer buffer = new KeepaliveTaskBuffer(10);
        buffer.offer("device-1", 100L);
        Map<String, KeepaliveTaskBuffer.PendingKeepalive> snapshot = buffer.snapshot();

        buffer.offer("device-1", 200L);
        buffer.removeSnapshot(snapshot);

        assertEquals(1, buffer.size());
        assertEquals(200L, buffer.snapshot().get("device-1").timestamp());
    }
}
