package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.gb28181.dao.InvitePlayProjectionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.Objects;

@Service
public class InvitePlayProjectionStore {
    private final InvitePlayProjectionMapper mapper;
    public InvitePlayProjectionStore(InvitePlayProjectionMapper mapper) { this.mapper = mapper; }

    @Transactional
    public long allocateEpoch(int channel) {
        Long sequence = mapper.lockSequence(channel);
        if (sequence == null || mapper.incrementSequence(channel) != 1) throw new IllegalStateException("Channel not found: " + channel);
        return Math.addExact(sequence, 1);
    }
    public void reserve(InviteInfo invite) {
        if (mapper.reserve(invite.getChannelId(), invite.getPlayEpoch(), invite.getInviteId()) == 1) return;
        if (!matches(invite, "PREPARING", false)) throw new IllegalStateException("Play projection reserve rejected");
    }
    public void activate(InviteInfo invite) {
        if (mapper.activate(invite.getChannelId(), invite.getPlayEpoch(), invite.getInviteId(), invite.getStream()) == 1) return;
        if (!matches(invite, "PLAYING", true)) throw new IllegalStateException("Play projection activation rejected");
    }
    public boolean close(InviteInfo invite) {
        if (invite.getPlayEpoch() == 0) return true; // No SQL reservation was allowed before the epoch was persisted.
        if (mapper.close(invite.getChannelId(), invite.getPlayEpoch(), invite.getInviteId()) == 1) return true;
        Map<String, Object> value = mapper.projection(invite.getChannelId());
        return value == null || value.isEmpty() || number(value, "epoch") > invite.getPlayEpoch() || matches(invite, "CLOSED", false);
    }
    private boolean matches(InviteInfo invite, String state, boolean stream) {
        Map<String, Object> value = mapper.projection(invite.getChannelId());
        return value != null && number(value, "epoch") == invite.getPlayEpoch()
                && Objects.equals(get(value, "owner"), invite.getInviteId()) && Objects.equals(get(value, "state"), state)
                && (!stream || Objects.equals(get(value, "stream"), invite.getStream()));
    }
    private static Object get(Map<String, Object> value, String key) {
        return value.containsKey(key) ? value.get(key) : value.get(key.toUpperCase(java.util.Locale.ROOT));
    }
    private static long number(Map<String, Object> value, String key) { return ((Number) get(value, key)).longValue(); }
}
