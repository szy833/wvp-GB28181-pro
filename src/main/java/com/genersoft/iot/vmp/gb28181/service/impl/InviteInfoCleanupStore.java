package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.conf.UserSetting;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static com.genersoft.iot.vmp.gb28181.service.impl.InviteRepository.bytes;
import static com.genersoft.iot.vmp.gb28181.service.impl.InviteRepository.text;

/** The primary remains STOPPING until every independent resource cleanup step is acknowledged. */
@Slf4j
@Component
public class InviteInfoCleanupStore {
    private static final long LEASE_MILLIS = 30_000;
    private final RedisTemplate<String, Object> redis;
    private final InviteRepository repository;
    public InviteInfoCleanupStore(RedisTemplate<String, Object> redis, UserSetting settings) {
        this.redis = redis;
        this.repository = new InviteRepository(redis, settings);
    }
    public record Job(String id, InviteInfo invite, String token, String phase, boolean stop, boolean skipBye,
                      String callId, Set<String> completed) { }
    public record Preparation(InviteInfoDeleteResult result, Job job) { }

    public Preparation prepare(InviteInfo expected, boolean stop, boolean skipBye) {
        InviteWriteResult result = repository.beginStop(expected, skipBye);
        if (result.code() == InviteWriteResult.Code.NOT_FOUND) return new Preparation(InviteInfoDeleteResult.ABSENT, null);
        InviteInfo current = result.requireApplied();
        if (current.getLifecycle() == InviteLifecycle.CLOSED) return new Preparation(InviteInfoDeleteResult.ABSENT, null);
        return new Preparation(null, load(current.getInviteId(), null));
    }

    public Job claim(String id) {
        String token = UUID.randomUUID().toString();
        long code = repository.eval("""
                local raw=redis.call('GET',KEYS[1])
                if not raw or cjson.decode(raw).lifecycle~='STOPPING' then return 0 end
                local jobType=redis.call('TYPE',KEYS[3]).ok
                local pendingType=redis.call('TYPE',KEYS[4]).ok
                if (jobType~='none' and jobType~='hash') or (pendingType~='none' and pendingType~='zset') then
                  return redis.error_reply('Invite cleanup key type mismatch')
                end
                if not redis.call('SET',KEYS[2],ARGV[1],'NX','PX',30000) then return 0 end
                redis.call('HSETNX',KEYS[3],'phase','BYE')
                local now=redis.call('TIME')
                redis.call('ZADD',KEYS[4],tonumber(now[1])*1000+math.floor(tonumber(now[2])/1000)+tonumber(ARGV[2]),ARGV[3])
                return 1
                """, List.of(repository.recordKey(id), repository.leaseKey(id), repository.jobKey(id), repository.pendingKey()),
                bytes(token), bytes(LEASE_MILLIS), bytes(id));
        return code == 1 ? load(id, token) : null;
    }
    public Job refresh(Job job) { return load(job.id(), job.token()); }
    private Job load(String id, String token) {
        InviteInfo invite = repository.get(id);
        if (invite == null || invite.getLifecycle() != InviteLifecycle.STOPPING) return null;
        Map<byte[], byte[]> fields = redis.execute((RedisCallback<Map<byte[], byte[]>>) c -> c.hashCommands().hGetAll(bytes(repository.jobKey(id))));
        Map<String, String> values = new HashMap<>();
        if (fields != null) fields.forEach((key, value) -> values.put(text(key), text(value)));
        Set<String> completed = new HashSet<>();
        for (String step : List.of("BYE", "SIP", "RTP", "STATE")) if ("1".equals(values.get(step))) completed.add(step);
        String phase = List.of("BYE", "SIP", "RTP", "STATE").stream().filter(step -> !completed.contains(step)).findFirst().orElse("DONE");
        return new Job(id, invite, token, phase, true, invite.isSkipBye(), invite.getCallId(), Set.copyOf(completed));
    }
    public boolean completeStep(Job job, String step) {
        if (!List.of("BYE", "SIP", "RTP", "STATE").contains(step)) throw new IllegalArgumentException("Unknown cleanup step");
        return repository.eval("""
                if redis.call('GET',KEYS[2])~=ARGV[1] then return 0 end
                local jobType=redis.call('TYPE',KEYS[1]).ok
                local pendingType=redis.call('TYPE',KEYS[3]).ok
                if (jobType~='none' and jobType~='hash') or (pendingType~='none' and pendingType~='zset') then
                  return redis.error_reply('Invite cleanup key type mismatch')
                end
                local now=redis.call('TIME')
                redis.call('HSET',KEYS[1],ARGV[2],'1')
                redis.call('PEXPIRE',KEYS[2],ARGV[3])
                redis.call('ZADD',KEYS[3],tonumber(now[1])*1000+math.floor(tonumber(now[2])/1000)+tonumber(ARGV[3]),ARGV[4])
                return 1
                """, List.of(repository.jobKey(job.id()), repository.leaseKey(job.id()), repository.pendingKey()),
                bytes(job.token()), bytes(step), bytes(LEASE_MILLIS), bytes(job.id())) == 1;
    }
    public boolean ownsResources(Job job) {
        byte[] token = redis.execute((RedisCallback<byte[]>) c -> c.stringCommands().get(bytes(repository.leaseKey(job.id()))));
        InviteInfo invite = repository.get(job.id());
        return Objects.equals(text(token), job.token()) && invite != null && invite.getLifecycle() == InviteLifecycle.STOPPING;
    }
    public boolean finish(Job job) {
        Job latest = refresh(job);
        return latest != null && "DONE".equals(latest.phase()) && repository.close(job.id(), job.token()).applied();
    }
    public boolean terminateByeLocally(Job job) {
        if (!job.completed().containsAll(Set.of("RTP", "STATE"))) return false;
        InviteWriteResult result = repository.terminateByeLocally(job.id(), job.token());
        if (result.code() == InviteWriteResult.Code.STATE_CONFLICT) return false;
        InviteInfo invite = result.requireApplied();
        log.warn("[Invite清理] BYE等待到期，本地终止但未收到对端确认：inviteId={}, callId={}, firstSentAt={}, terminatedAt={}",
                job.id(), invite.getCallId(), invite.getByeFirstSentAt(), invite.getByeTerminatedLocallyAt());
        return true;
    }
    public void defer(Job job) {
        repository.eval("""
                if redis.call('GET',KEYS[2])~=ARGV[1] then return 0 end
                local jobType=redis.call('TYPE',KEYS[1]).ok
                local pendingType=redis.call('TYPE',KEYS[3]).ok
                if (jobType~='none' and jobType~='hash') or (pendingType~='none' and pendingType~='zset') then
                  return redis.error_reply('Invite cleanup key type mismatch')
                end
                local raw=redis.call('GET',KEYS[4])
                if not raw then return 0 end
                local invite=cjson.decode(raw)
                if invite.lifecycle~='STOPPING' then return 0 end
                local time=redis.call('TIME')
                local now=tonumber(time[1])*1000+math.floor(tonumber(time[2])/1000)
                local n=redis.call('HINCRBY',KEYS[1],'attempts',1)
                local delay=math.min(60000,1000*2^math.min(n,6))+tonumber(ARGV[3])
                if invite.byeClockSource=='REDIS' and type(invite.byeFirstSentAt)=='number' and invite.byeFirstSentAt>0
                   and invite.sipAccepted and invite.sipFinal and not invite.byeAcknowledged and not invite.skipBye
                   and not invite.rtpOpening and redis.call('HGET',KEYS[1],'RTP')=='1' and redis.call('HGET',KEYS[1],'STATE')=='1' then
                  local remaining=invite.byeFirstSentAt+tonumber(ARGV[4])-now
                  delay=math.min(delay,remaining>0 and remaining or 1000)
                end
                redis.call('ZADD',KEYS[3],now+delay,ARGV[2])
                redis.call('DEL',KEYS[2])
                return 1
                """, List.of(repository.jobKey(job.id()), repository.leaseKey(job.id()), repository.pendingKey(), repository.recordKey(job.id())),
                bytes(job.token()), bytes(job.id()), bytes(ThreadLocalRandom.current().nextInt(501)), bytes(InviteRepository.BYE_WAIT_MILLIS));
    }
    public void deferSafely(Job job) {
        try { defer(job); }
        catch (RuntimeException error) { log.error("[Invite清理] 保留未完成意图等待租约恢复：inviteId={}", job.id(), error); }
    }
    public List<String> pending(int limit) { return repository.pending(limit); }
    static boolean sameOwner(InviteInfo current, InviteInfo expected) {
        return current != null && expected != null && current.getInviteId() != null && current.getInviteId().equals(expected.getInviteId());
    }
}
