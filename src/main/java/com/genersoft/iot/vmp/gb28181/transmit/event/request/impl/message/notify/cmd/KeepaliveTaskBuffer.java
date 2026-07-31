package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl.message.notify.cmd;

import java.util.HashMap;
import java.util.Map;

/**
 * Buffers the latest heartbeat timestamp for each device without retaining
 * duplicate device objects between scheduled flushes.
 */
final class KeepaliveTaskBuffer {

    record PendingKeepalive(String deviceId, long timestamp) {
    }

    private final int maxEntries;
    private final Map<String, PendingKeepalive> pending = new HashMap<>();

    KeepaliveTaskBuffer(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    synchronized boolean offer(String deviceId, long timestamp) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        PendingKeepalive current = pending.get(deviceId);
        if (current == null && pending.size() >= maxEntries) {
            return false;
        }
        if (current == null || timestamp >= current.timestamp()) {
            pending.put(deviceId, new PendingKeepalive(deviceId, timestamp));
        }
        return true;
    }

    synchronized Map<String, PendingKeepalive> snapshot() {
        return new HashMap<>(pending);
    }

    synchronized void removeSnapshot(Map<String, PendingKeepalive> snapshot) {
        for (Map.Entry<String, PendingKeepalive> entry : snapshot.entrySet()) {
            if (pending.get(entry.getKey()) == entry.getValue()) {
                pending.remove(entry.getKey());
            }
        }
    }

    synchronized int size() {
        return pending.size();
    }
}
