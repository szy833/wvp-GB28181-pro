package com.genersoft.iot.vmp.gb28181.service.impl;

import com.alibaba.fastjson2.JSON;
import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.conf.UserSetting;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Single Invite protocol: raw UTF-8 keys/JSON, immutable identities, bounded field CAS, no legacy reads. */
@Repository
public class InviteRepository {
    static final long BYE_WAIT_MILLIS = 32_000;
    static final long BYE_RETRY_MILLIS = 10_000;
    private static final String SCRIPT = """
            local function valid(k,t)
              local a=redis.call('TYPE',k).ok
              return a=='none' or a==t
            end
            if not valid(KEYS[1],'string') or not valid(KEYS[2],'string') or
               not valid(KEYS[3],'set') or not valid(KEYS[4],'zset') or
               not valid(KEYS[5],'zset') or not valid(KEYS[6],'string') or not valid(KEYS[7],'hash') or not valid(KEYS[8],'zset') or not valid(KEYS[9],'set') or not valid(KEYS[10],'hash') then
              return redis.error_reply('Invite lifecycle key type mismatch')
            end
            for i=11,#KEYS do
              if not valid(KEYS[i],'set') then return redis.error_reply('Invite index key type mismatch') end
            end
            local current=redis.call('GET',KEYS[1])
            local owner=redis.call('GET',KEYS[2])
            if ARGV[1]=='CREATE' then
              if current then return 3 end
              if owner and owner~=ARGV[4] then return 2 end
              local now=redis.call('TIME')
              if tonumber(ARGV[6])<=tonumber(now[1])*1000+math.floor(tonumber(now[2])/1000) then return 3 end
            else
              if not current then return 4 end
              if current~=ARGV[2] then return 5 end
              if ARGV[1]~='CLOSE' and ARGV[1]~='STOP' and ARGV[1]~='LATE' and ARGV[1]~='RESOURCE' and ARGV[1]~='RESULT' and ARGV[1]~='LOCAL_BYE' and ARGV[1]~='BYE_ATTEMPT' and owner~=ARGV[4] then return 2 end
            end
            local payload=ARGV[3]
            if ARGV[1]=='LOCAL_BYE' or ARGV[1]=='BYE_ATTEMPT' then
              local prior=cjson.decode(current)
              local now=redis.call('TIME')
              local millis=tonumber(now[1])*1000+math.floor(tonumber(now[2])/1000)
              if prior.lifecycle~='STOPPING' or not prior.sipAccepted or not prior.sipFinal or prior.byeAcknowledged or prior.skipBye or
                 ARGV[8]=='' or redis.call('GET',KEYS[6])~=ARGV[8] then return 3 end
              if ((prior.byeFirstSentAt and prior.byeFirstSentAt~=cjson.null) or (prior.byeSentAt and prior.byeSentAt~=cjson.null))
                 and prior.byeClockSource~='REDIS' then
                return redis.error_reply('BYE clock source unknown: drain old active invites before upgrade')
              end
              local candidate=cjson.decode(payload)
              if candidate.byeClockSource~='REDIS' or type(candidate.byeOperationId)~='string' or candidate.byeOperationId=='' then return 3 end
              -- Patch only generated scalar placeholders; re-encoding the whole JSON can change empty media arrays.
              local function stamp(name,value)
                local count
                payload,count=string.gsub(payload,'("'..name..'":)%-1([,}])',function(head,tail)
                  return head..string.format('%.0f',value)..tail
                end)
                if count~=1 then error('Missing unique BYE timestamp placeholder: '..name) end
              end
              if ARGV[1]=='BYE_ATTEMPT' then
                if not prior.sipDialog or prior.sipDialog==cjson.null or prior.sipDialog.callId~=prior.callId or
                   prior.byeTerminatedLocallyAt and prior.byeTerminatedLocallyAt~=cjson.null then return 3 end
                if type(prior.byeSentAt)=='number' and millis<prior.byeSentAt+tonumber(ARGV[11]) then return 3 end
                stamp('byeFirstSentAt',type(prior.byeFirstSentAt)=='number' and prior.byeFirstSentAt or millis)
                stamp('byeSentAt',millis)
              else
                if prior.rtpOpening or prior.byeClockSource~='REDIS' or
                   type(prior.byeFirstSentAt)~='number' or prior.byeFirstSentAt<=0 or millis<prior.byeFirstSentAt+tonumber(ARGV[10]) or
                   redis.call('HGET',KEYS[7],'RTP')~='1' or redis.call('HGET',KEYS[7],'STATE')~='1' then return 3 end
                stamp('byeTerminatedLocallyAt',millis)
              end
            end
            if ARGV[1]=='CLOSE' then
              if ARGV[8]=='' or redis.call('GET',KEYS[6])~=ARGV[8] then return 3 end
              for _,step in ipairs({'BYE','SIP','RTP','STATE'}) do
                if redis.call('HGET',KEYS[7],step)~='1' then return 3 end
              end
            end
            if ARGV[1]=='LATE' then
              redis.call('HDEL',KEYS[7],'BYE','SIP')
              redis.call('DEL',KEYS[6])
            end
            if ARGV[1]=='RESOURCE' then
              redis.call('HDEL',KEYS[7],'RTP')
              redis.call('DEL',KEYS[6])
            end
            redis.call('SET',KEYS[1],payload)
            if ARGV[1]=='LOCAL_BYE' then redis.call('HSET',KEYS[7],'BYE','1') end
            redis.call('SREM',KEYS[9],ARGV[4])
            local next=cjson.decode(payload)
            if next.lifecycle=='ACTIVE' then redis.call('SADD',KEYS[9],ARGV[4]) end
            if next.lifecycle=='CLOSED' then redis.call('HDEL',KEYS[10],ARGV[4])
            elseif next.ssrcInfo and next.ssrcInfo.allocatedSsrc then
              redis.call('HSET',KEYS[10],ARGV[4],next.ssrcInfo.allocatedSsrc)
            end
            local oldCount=tonumber(ARGV[7])
            for i=11,10+oldCount do redis.call('SREM',KEYS[i],ARGV[4]) end
            if ARGV[1]=='CLOSE' then
              if owner==ARGV[4] then redis.call('DEL',KEYS[2]) end
              redis.call('SREM',KEYS[3],ARGV[4])
              redis.call('ZREM',KEYS[8],ARGV[4])
              redis.call('ZADD',KEYS[4],ARGV[6],ARGV[4])
              redis.call('ZREM',KEYS[5],ARGV[4])
              redis.call('DEL',KEYS[6],KEYS[7])
            elseif ARGV[1]=='RESULT' then
              redis.call('ZADD',KEYS[4],ARGV[6],ARGV[4])
            else
              if ARGV[1]=='CREATE' then redis.call('SET',KEYS[2],ARGV[4]) end
              redis.call('SADD',KEYS[3],ARGV[4])
              redis.call('ZADD',KEYS[8],'NX',ARGV[5],ARGV[4])
              if tonumber(ARGV[6])>0 then redis.call('ZADD',KEYS[4],ARGV[6],ARGV[4])
              else redis.call('ZREM',KEYS[4],ARGV[4]) end
              if ARGV[1]=='STOP' or ARGV[1]=='LATE' or ARGV[1]=='RESOURCE' then
                local now=redis.call('TIME')
                redis.call('ZADD',KEYS[5],tonumber(now[1])*1000+math.floor(tonumber(now[2])/1000),ARGV[4])
              end
            end
            for i=11+oldCount,#KEYS do redis.call('SADD',KEYS[i],ARGV[4]) end
            return 1
            """;
    private final RedisTemplate<String, Object> redis;
    private final String prefix;

    public InviteRepository(RedisTemplate<String, Object> redis, UserSetting settings) {
        this.redis = redis;
        this.prefix = "WVP:INVITE:" + settings.getServerId() + ":";
    }

    public InviteWriteResult createAndClaim(InviteInfo draft) {
        validate(draft);
        if (draft.getLifecycle() != InviteLifecycle.PREPARING) return result(3, draft);
        InviteInfo existing = get(draft.getInviteId());
        if (existing != null) {
            return new InviteWriteResult(!existing.getLifecycle().closing()
                    && Objects.equals(existing.getBusinessSlot(), draft.getBusinessSlot())
                    && Objects.equals(existing.getStream(), draft.getStream())
                    && Objects.equals(existing.getInviteId(), ownerId(draft.getBusinessSlot()))
                    ? InviteWriteResult.Code.ALREADY_APPLIED : InviteWriteResult.Code.STATE_CONFLICT, existing);
        }
        draft.setRevision(1);
        draft.setLastOperation("create");
        return commit("CREATE", null, draft, null);
    }

    public InviteWriteResult patch(String id, String operation, Consumer<InviteInfo> mutation) {
        return mutate(id, operation, false, mutation);
    }

    private InviteWriteResult mutate(String id, String operation, boolean stop, Consumer<InviteInfo> mutation) {
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] raw = readRaw(id);
            if (raw == null) return result(4, null);
            InviteInfo current = decode(raw);
            if (current.getLifecycle().closing() && (!stop || current.getLifecycle() == InviteLifecycle.CLOSED)) {
                if (stop && current.getLifecycle() == InviteLifecycle.CLOSED) return new InviteWriteResult(InviteWriteResult.Code.ALREADY_APPLIED, current);
                return result(3, current);
            }
            InviteInfo next = decode(raw);
            mutation.accept(next);
            checkTransition(current, next);
            next.setRevision(current.getRevision() + 1);
            next.setLastOperation(operation);
            InviteWriteResult result = commit(next.getLifecycle() == InviteLifecycle.STOPPING ? "STOP" : "UPDATE", raw, next, null);
            if (result.code() != InviteWriteResult.Code.COMMIT_UNKNOWN) return result;
            byte[] observedRaw = readRaw(id);
            if (Arrays.equals(observedRaw, JSON.toJSONBytes(next))) {
                InviteInfo observed = decode(observedRaw);
                return new InviteWriteResult(InviteWriteResult.Code.ALREADY_APPLIED, observed);
            }
        }
        return new InviteWriteResult(InviteWriteResult.Code.COMMIT_UNKNOWN, get(id));
    }

    public InviteWriteResult beginStop(InviteInfo expected, boolean skipBye) {
        validate(expected);
        return mutate(expected.getInviteId(), "stop", true, current -> {
            current.setLifecycle(InviteLifecycle.STOPPING);
            current.setSkipBye(current.isSkipBye() || skipBye);
        });
    }

    public InviteWriteResult sipFinal(String id, String callId, boolean accepted) {
        return sipFinal(id, callId, accepted, null);
    }

    public InviteWriteResult sipFinal(String id, String callId, boolean accepted,
                                      com.genersoft.iot.vmp.gb28181.bean.SipTransactionInfo dialog) {
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] raw = readRaw(id);
            if (raw == null) return result(4, null);
            InviteInfo current = decode(raw);
            if (!Objects.equals(current.getCallId(), callId)) throw new IllegalArgumentException("Call-ID mismatch");
            if (current.getLifecycle() == InviteLifecycle.CLOSED && !accepted)
                return new InviteWriteResult(InviteWriteResult.Code.ALREADY_APPLIED, current);
            if (current.isSipFinal() && (current.isSipAccepted() || !accepted))
                return new InviteWriteResult(InviteWriteResult.Code.ALREADY_APPLIED, current);
            boolean late = accepted && current.getLifecycle().closing() && !current.isSipAccepted();
            current.setSipFinal(true);
            current.setSipAccepted(current.isSipAccepted() || accepted);
            if (dialog != null) {
                if (!Objects.equals(callId, dialog.getCallId())) throw new IllegalArgumentException("Dialog mismatch");
                current.setSipDialog(dialog);
            }
            if (late) {
                // Reopen cleanup only, never reclaim the business owner or reactivate media.
                current.setLifecycle(InviteLifecycle.STOPPING);
                current.setByeAcknowledged(false);
                current.setByeSentAt(null);
                current.setByeFirstSentAt(null);
                current.setByeTerminatedLocallyAt(null);
                current.setByeLocalTerminationReason(null);
                current.setByeClockSource(null);
                current.setByeOperationId(null);
            }
            current.setRevision(current.getRevision() + 1);
            current.setLastOperation("sip-final");
            InviteWriteResult result = commit(late ? "LATE" : current.getLifecycle().closing() ? "STOP" : "UPDATE", raw, current, null);
            if (result.code() != InviteWriteResult.Code.COMMIT_UNKNOWN) return result;
        }
        return new InviteWriteResult(InviteWriteResult.Code.COMMIT_UNKNOWN, get(id));
    }

    public InviteWriteResult beginByeAttempt(String id, String lease) {
        String operation = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] raw = readRaw(id);
            if (raw == null) return result(4, null);
            InviteInfo next = decode(raw);
            next.setByeFirstSentAt(-1L);
            next.setByeSentAt(-1L);
            next.setByeClockSource("REDIS");
            next.setByeOperationId(operation);
            next.setRevision(next.getRevision() + 1);
            next.setLastOperation("bye-attempt");
            InviteWriteResult result = commit("BYE_ATTEMPT", raw, next, lease);
            if (result.code() != InviteWriteResult.Code.COMMIT_UNKNOWN) return result;
        }
        return new InviteWriteResult(InviteWriteResult.Code.COMMIT_UNKNOWN, get(id));
    }

    public InviteWriteResult terminateByeLocally(String id, String lease) {
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] raw = readRaw(id);
            if (raw == null) return result(4, null);
            InviteInfo next = decode(raw);
            if (next.getLifecycle() != InviteLifecycle.STOPPING || !next.isSipAccepted() || !next.isSipFinal() || next.isByeAcknowledged()
                    || next.getByeFirstSentAt() == null || next.getByeFirstSentAt() <= 0) return result(3, next);
            next.setByeTerminatedLocallyAt(-1L);
            next.setByeLocalTerminationReason("BYE_WAIT_EXPIRED");
            next.setByeOperationId(UUID.randomUUID().toString());
            next.setRevision(next.getRevision() + 1);
            next.setLastOperation("bye-local-termination");
            InviteWriteResult result = commit("LOCAL_BYE", raw, next, lease);
            if (result.code() != InviteWriteResult.Code.COMMIT_UNKNOWN) return result;
        }
        return new InviteWriteResult(InviteWriteResult.Code.COMMIT_UNKNOWN, get(id));
    }

    public InviteWriteResult sipSessionConfirmed(String id, String callId) {
        return patch(id, "sip-session-confirmed", current -> {
            if (!current.isSipAccepted() || !Objects.equals(current.getCallId(), callId))
                throw new IllegalStateException("SIP session requires an accepted matching dialog");
            current.setSipSessionConfirmed(true);
        });
    }

    public InviteWriteResult byeAcknowledged(String id) {
        return mutate(id, "bye-ack", true, current -> current.setByeAcknowledged(true));
    }

    public InviteWriteResult resourceOpened(String id, com.genersoft.iot.vmp.service.bean.SSRCInfo info) {
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] raw = readRaw(id);
            if (raw == null) return result(4, null);
            InviteInfo current = decode(raw);
            if (current.getSsrcInfo() == null || !Objects.equals(current.getSsrcInfo().getResourceId(), info.getResourceId()))
                throw new IllegalArgumentException("RTP resource mismatch");
            if (!current.isRtpOpening()) return new InviteWriteResult(InviteWriteResult.Code.ALREADY_APPLIED, current);
            current.setSsrcInfo(info);
            current.setReceivePort(info.getPort());
            current.setRtpOpening(false);
            boolean closing = current.getLifecycle().closing();
            current.setLifecycle(closing ? InviteLifecycle.STOPPING : InviteLifecycle.WAITING_MEDIA);
            current.setRevision(current.getRevision() + 1);
            current.setLastOperation("rtp-opened");
            InviteWriteResult result = commit(closing ? "RESOURCE" : "UPDATE", raw, current, null);
            if (result.code() != InviteWriteResult.Code.COMMIT_UNKNOWN) return result;
        }
        return new InviteWriteResult(InviteWriteResult.Code.COMMIT_UNKNOWN, get(id));
    }

    public InviteWriteResult close(String id, String lease) {
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] raw = readRaw(id);
            if (raw == null) return result(4, null);
            InviteInfo next = decode(raw);
            if (next.getLifecycle() == InviteLifecycle.CLOSED) return new InviteWriteResult(InviteWriteResult.Code.ALREADY_APPLIED, next);
            if (next.getLifecycle() != InviteLifecycle.STOPPING) return result(3, next);
            next.setLifecycle(InviteLifecycle.CLOSED);
            if (next.getType() == InviteSessionType.DOWNLOAD && next.getCleanupAt() == null)
                next.setCleanupAt(System.currentTimeMillis() + 900_000);
            next.setTerminalExpiresAt(System.currentTimeMillis() + 86_400_000);
            next.setRevision(next.getRevision() + 1);
            InviteWriteResult result = commit("CLOSE", raw, next, lease);
            if (result.code() != InviteWriteResult.Code.COMMIT_UNKNOWN) return result;
        }
        return new InviteWriteResult(InviteWriteResult.Code.COMMIT_UNKNOWN, get(id));
    }

    private InviteWriteResult commit(String mode, byte[] old, InviteInfo next, String lease) {
        boolean redisStamped = "BYE_ATTEMPT".equals(mode) || "LOCAL_BYE".equals(mode);
        List<String> oldIndexes = old == null ? List.of() : indexes(decode(old));
        List<String> newIndexes = next.getLifecycle() == InviteLifecycle.CLOSED ? retainedIndexes(next) : indexes(next);
        List<String> keys = new ArrayList<>(List.of(recordKey(next.getInviteId()), ownerKey(next.getBusinessSlot()),
                prefix + "ACTIVE", prefix + "DUE", prefix + "PENDING", leaseKey(next.getInviteId()), jobKey(next.getInviteId()), prefix + "RECONCILE", prefix + "PLAYING:" + next.getMediaServerId(), prefix + "SSRC_RESERVATIONS:" + next.getMediaServerId()));
        keys.addAll(oldIndexes);
        keys.addAll(newIndexes);
        long deadline = next.getLifecycle() == InviteLifecycle.CLOSED ? next.getTerminalExpiresAt()
                : next.getLifecycle() == InviteLifecycle.ACTIVE || next.getLifecycle() == InviteLifecycle.STOPPING ? 0
                : next.getCreateTime() + next.getExpirationTime();
        long retention = next.getCleanupAt() == null ? 86_400_000 : Math.max(1, next.getCleanupAt() - System.currentTimeMillis());
        long code;
        try {
            code = eval(SCRIPT, keys, bytes(mode), old == null ? new byte[0] : old, JSON.toJSONBytes(next),
                    bytes(next.getInviteId()), bytes(applicationTimeMillis()), bytes(deadline), bytes(oldIndexes.size()),
                    bytes(lease == null ? "" : lease), bytes(retention), bytes(BYE_WAIT_MILLIS), bytes(BYE_RETRY_MILLIS));
        } catch (RuntimeException error) {
            // The caller must not proceed with side effects until it has read back a confirmed operation.
            byte[] observed;
            try { observed = readRaw(next.getInviteId()); }
            catch (RuntimeException unavailable) { throw new IllegalStateException("Invite commit/readback unavailable: " + next.getInviteId(), error); }
            if (redisStamped ? confirmsByeOperation(observed, next) : Arrays.equals(observed, JSON.toJSONBytes(next))) {
                return new InviteWriteResult(InviteWriteResult.Code.ALREADY_APPLIED, decode(observed));
            }
            throw new IllegalStateException("Invite commit not confirmed: " + next.getInviteId(), error);
        }
        if (redisStamped) {
            if (code != 1) return result(code, old == null ? null : decode(old));
            byte[] observed = readRaw(next.getInviteId());
            if (!confirmsByeOperation(observed, next))
                throw new IllegalStateException("BYE operation readback not confirmed: " + next.getInviteId());
            return result(1, decode(observed));
        }
        return result(code, next);
    }

    private boolean confirmsByeOperation(byte[] raw, InviteInfo expected) {
        if (raw == null) return false;
        InviteInfo observed = decode(raw);
        return Objects.equals(expected.getInviteId(), observed.getInviteId())
                && expected.getByeOperationId() != null
                && Objects.equals(expected.getByeOperationId(), observed.getByeOperationId())
                && "REDIS".equals(observed.getByeClockSource())
                && observed.getByeFirstSentAt() != null && observed.getByeFirstSentAt() > 0
                && observed.getByeSentAt() != null && observed.getByeSentAt() > 0
                && (expected.getByeTerminatedLocallyAt() == null || observed.getByeTerminatedLocallyAt() != null
                    && observed.getByeTerminatedLocallyAt() > 0);
    }

    protected long applicationTimeMillis() { return System.currentTimeMillis(); }

    private InviteWriteResult result(long code, InviteInfo value) {
        return new InviteWriteResult(switch ((int) code) {
            case 1 -> InviteWriteResult.Code.APPLIED;
            case 2 -> InviteWriteResult.Code.OWNER_CHANGED;
            case 3 -> InviteWriteResult.Code.STATE_CONFLICT;
            case 4 -> InviteWriteResult.Code.NOT_FOUND;
            default -> InviteWriteResult.Code.COMMIT_UNKNOWN;
        }, value);
    }

    public InviteInfo get(String id) { byte[] raw = readRaw(id); return raw == null ? null : decode(raw); }
    public InviteInfo owner(String slot) {
        String id = ownerId(slot);
        if (id == null) return null;
        InviteInfo invite = get(id);
        if (invite == null) throw new IllegalStateException("Invite owner pointer has no primary: " + slot);
        return invite;
    }
    public List<String> activeIds(int limit) { return members(prefix + "ACTIVE", limit); }
    public int activeCount(String node) {
        Long count = redis.execute((RedisCallback<Long>) c -> c.setCommands().sCard(bytes(prefix + "PLAYING:" + node)));
        return Math.toIntExact(count == null ? 0 : count);
    }
    public List<String> reservedSsrcs(String node) {
        return redis.execute((RedisCallback<List<String>>) c -> c.hashCommands()
                .hVals(bytes(prefix + "SSRC_RESERVATIONS:" + node)).stream().map(InviteRepository::text).toList());
    }
    public List<InviteInfo> reconciliationBatch(int limit) {
        long now = System.currentTimeMillis();
        List<String> ids = range(prefix + "RECONCILE", limit, now);
        for (String id : ids) eval("""
                if not redis.call('ZSCORE',KEYS[1],ARGV[1]) then return 0 end
                redis.call('ZADD',KEYS[1],ARGV[2],ARGV[1])
                return 1
                """, List.of(prefix + "RECONCILE"), bytes(id), bytes(now + 30_000));
        return ids.stream().map(this::get).filter(Objects::nonNull).toList();
    }
    public List<InviteInfo> find(String dimension, String value) {
        return members(indexKey(dimension, value), Integer.MAX_VALUE).stream().map(this::get)
                .filter(Objects::nonNull)
                .filter(invite -> indexes(invite).contains(indexKey(dimension, value)))
                .filter(invite -> !"CHANNEL".equals(dimension) || Objects.equals(invite.getInviteId(), ownerId(invite.getBusinessSlot())))
                .toList();
    }
    private String ownerId(String slot) {
        return text(redis.execute((RedisCallback<byte[]>) c -> c.stringCommands().get(bytes(ownerKey(slot)))));
    }

    public InviteWriteResult downloadResult(InviteInfo expected) {
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] raw = readRaw(expected.getInviteId());
            if (raw == null) return result(4, null);
            InviteInfo next = decode(raw);
            if (next.getType() != InviteSessionType.DOWNLOAD || expected.getStreamInfo() == null
                    || !Objects.equals(next.getStream(), expected.getStreamInfo().getStream())) return result(3, next);
            next.setStreamInfo(expected.getStreamInfo());
            if (expected.getCleanupAt() != null) next.setCleanupAt(expected.getCleanupAt());
            else if (next.getCleanupAt() == null) next.setCleanupAt(System.currentTimeMillis() + 900_000);
            next.setRevision(next.getRevision() + 1);
            next.setLastOperation("download-result");
            InviteWriteResult result = commit(next.getLifecycle() == InviteLifecycle.CLOSED ? "RESULT"
                    : next.getLifecycle() == InviteLifecycle.STOPPING ? "STOP" : "UPDATE", raw, next, null);
            if (result.code() != InviteWriteResult.Code.COMMIT_UNKNOWN) return result;
        }
        return new InviteWriteResult(InviteWriteResult.Code.COMMIT_UNKNOWN, get(expected.getInviteId()));
    }

    public boolean purgeTerminal(String id, long now) {
        InviteInfo invite = get(id);
        if (invite == null) return false;
        return eval("""
                local raw=redis.call('GET',KEYS[1])
                if not raw or raw~=ARGV[1] then return 0 end
                local v=cjson.decode(raw)
                if v.lifecycle~='CLOSED' or tonumber(v.terminalExpiresAt)>tonumber(ARGV[2]) then return 0 end
                for i=3,#KEYS do redis.call('SREM',KEYS[i],ARGV[3]) end
                redis.call('ZREM',KEYS[2],ARGV[3])
                redis.call('DEL',KEYS[1])
                return 1
                """, purgeKeys(invite),
                JSON.toJSONBytes(invite), bytes(now), bytes(id)) == 1;
    }
    public List<String> due(int limit, long now) { return range(prefix + "DUE", limit, now); }
    public List<String> pending(int limit) {
        if (limit <= 0) return List.of();
        List<byte[]> values = redis.execute((RedisCallback<List<byte[]>>) c -> c.scriptingCommands().eval(bytes("""
                local now=redis.call('TIME')
                return redis.call('ZRANGEBYSCORE',KEYS[1],0,tonumber(now[1])*1000+math.floor(tonumber(now[2])/1000),
                  'LIMIT',0,tonumber(ARGV[1]))
                """), ReturnType.MULTI, 1, bytes(pendingKey()), bytes(Math.min(200, limit))));
        if (values == null) throw new IllegalStateException("Missing Invite pending scan result");
        return values.stream().map(InviteRepository::text).toList();
    }
    private List<String> range(String key, int limit, long now) {
        return redis.execute((RedisCallback<List<String>>) c -> c.zSetCommands().zRangeByScore(bytes(key), 0, now, 0,
                Math.max(0, Math.min(200, limit))).stream().map(InviteRepository::text).toList());
    }
    private List<String> members(String key, int limit) {
        return redis.execute((RedisCallback<List<String>>) c -> {
            List<String> values = new ArrayList<>();
            try (var cursor = c.setCommands().sScan(bytes(key), org.springframework.data.redis.core.ScanOptions.scanOptions().count(100).build())) {
                while (cursor.hasNext() && values.size() < limit) values.add(text(cursor.next()));
            }
            return values;
        });
    }
    private List<String> indexes(InviteInfo invite) {
        List<String> keys = new ArrayList<>(retainedIndexes(invite));
        keys.add(indexKey("CHANNEL", invite.getType() + ":" + invite.getChannelId()));
        if (invite.getDeviceId() != null) keys.add(indexKey("DEVICE", invite.getDeviceId()));
        keys.add(indexKey("MEDIA", invite.getMediaServerId()));
        if (invite.getSsrcInfo() != null && invite.getSsrcInfo().getSsrc() != null) keys.add(indexKey("SSRC", invite.getSsrcInfo().getSsrc()));
        if (invite.getCallId() != null) keys.add(indexKey("CALL", invite.getCallId()));
        return keys;
    }
    private List<String> retainedIndexes(InviteInfo invite) {
        List<String> keys = new ArrayList<>();
        if (invite.getLifecycle() != InviteLifecycle.CLOSED || invite.getType() == InviteSessionType.DOWNLOAD
                && invite.getCleanupAt() != null && invite.getCleanupAt() > System.currentTimeMillis())
            keys.add(indexKey("STREAM", invite.getStream()));
        if (invite.getLifecycle() == InviteLifecycle.CLOSED && invite.getCallId() != null) keys.add(indexKey("CALL", invite.getCallId()));
        return keys;
    }
    private List<String> purgeKeys(InviteInfo invite) {
        List<String> keys = new ArrayList<>(List.of(recordKey(invite.getInviteId()), prefix + "DUE", indexKey("STREAM", invite.getStream())));
        if (invite.getCallId() != null) keys.add(indexKey("CALL", invite.getCallId()));
        return keys;
    }
    private static void checkTransition(InviteInfo current, InviteInfo next) {
        if (!Objects.equals(current.getInviteId(), next.getInviteId()) || !Objects.equals(current.getBusinessSlot(), next.getBusinessSlot())
                || !Objects.equals(current.getStream(), next.getStream()) || !Objects.equals(current.getDeviceId(), next.getDeviceId())
                || !Objects.equals(current.getChannelId(), next.getChannelId()) || current.getType() != next.getType()
                || !Objects.equals(current.getMediaServerId(), next.getMediaServerId())
                || current.getCallId() != null && !Objects.equals(current.getCallId(), next.getCallId())
                || current.getPlayEpoch() != 0 && current.getPlayEpoch() != next.getPlayEpoch())
            throw new IllegalArgumentException("Invite identity cannot be mutated");
        if (current.getSsrcInfo() != null) {
            var before = current.getSsrcInfo(); var after = next.getSsrcInfo();
            if (after == null || !Objects.equals(before.getResourceId(), after.getResourceId())
                    || !Objects.equals(before.getZlmStream(), after.getZlmStream())
                    || !Objects.equals(before.getAllocatedSsrc(), after.getAllocatedSsrc())
                    || !Objects.equals(before.getLeaseId(), after.getLeaseId())
                    || !Objects.equals(before.getApp(), after.getApp()) || !Objects.equals(before.getStream(), after.getStream())
                    || !Objects.equals(before.getMediaServerId(), after.getMediaServerId()))
                throw new IllegalArgumentException("RTP resource identity cannot be replaced");
        }
        InviteLifecycle from = current.getLifecycle(), to = next.getLifecycle();
        boolean valid = from == to || to == InviteLifecycle.STOPPING
                || from == InviteLifecycle.PREPARING && to == InviteLifecycle.WAITING_MEDIA
                || from == InviteLifecycle.WAITING_MEDIA && to == InviteLifecycle.ACTIVATING
                || from == InviteLifecycle.ACTIVATING && to == InviteLifecycle.ACTIVE;
        if (!valid || current.isSipAccepted() && !next.isSipAccepted() || current.isMediaArrived() && !next.isMediaArrived()
                || current.isSipSessionConfirmed() && !next.isSipSessionConfirmed() || current.isSipReady() && !next.isSipReady())
            throw new IllegalStateException("Invalid Invite transition: " + from + " -> " + to);
        if (next.isSipSessionConfirmed() && !next.isSipAccepted() || next.isSipReady() && !next.isSipSessionConfirmed())
            throw new IllegalStateException("SIP facts are not confirmed in order");
        if ((to == InviteLifecycle.ACTIVATING || to == InviteLifecycle.ACTIVE)
                && (!next.isSipAccepted() || !next.isSipSessionConfirmed() || !next.isSipReady()
                || !next.isMediaArrived() || next.getStreamInfo() == null))
            throw new IllegalStateException("Activation requires the committed SIP session, processed answer and media confirmation");
    }
    public String recordKey(String id) { return prefix + "DATA:" + id; }
    public String jobKey(String id) { return prefix + "JOB:" + id; }
    public String leaseKey(String id) { return prefix + "LEASE:" + id; }
    public String pendingKey() { return prefix + "PENDING"; }
    private String ownerKey(String slot) { return prefix + "OWNER:" + slot; }
    private String indexKey(String dimension, String value) { return prefix + "INDEX:" + dimension + ":" + value; }
    private byte[] readRaw(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("inviteId required");
        return redis.execute((RedisCallback<byte[]>) c -> c.stringCommands().get(bytes(recordKey(id))));
    }
    public long eval(String script, List<String> keys, byte[]... args) {
        byte[][] values = new byte[keys.size() + args.length][];
        for (int i = 0; i < keys.size(); i++) values[i] = bytes(keys.get(i));
        System.arraycopy(args, 0, values, keys.size(), args.length);
        Long result = redis.execute((RedisCallback<Long>) c -> c.scriptingCommands().eval(bytes(script), ReturnType.INTEGER, keys.size(), values));
        if (result == null) throw new IllegalStateException("Missing Invite script result");
        return result;
    }
    private static void validate(InviteInfo value) {
        if (value == null || value.getInviteId() == null || value.getBusinessSlot() == null || value.getType() == null
                || value.getChannelId() == null || value.getLifecycle() == null || value.getCreateTime() == null
                || value.getExpirationTime() == null) throw new IllegalArgumentException("Incomplete Invite identity");
    }
    public static byte[] bytes(Object value) { return String.valueOf(value).getBytes(StandardCharsets.UTF_8); }
    public static String text(byte[] value) { return value == null ? null : new String(value, StandardCharsets.UTF_8); }
    private static InviteInfo decode(byte[] value) { return JSON.parseObject(value, InviteInfo.class); }
}
