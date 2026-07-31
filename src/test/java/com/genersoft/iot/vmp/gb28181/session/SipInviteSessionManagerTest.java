package com.genersoft.iot.vmp.gb28181.session;

import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SipInviteSessionManagerTest {

    @Test
    void putWritesV2DataAndIndexesWithSafetyTtl() {
        UserSetting settings = mock(UserSetting.class);
        when(settings.getServerId()).thenReturn("srv-1");
        when(settings.getSipInviteSessionTtlSeconds()).thenReturn(3600L);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        RedisOperations<String, Object> operations = mock(RedisOperations.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        SetOperations<String, Object> sets = mock(SetOperations.class);
        ZSetOperations<String, Object> zsets = mock(ZSetOperations.class);
        doReturn(values).when(operations).opsForValue();
        doReturn(sets).when(operations).opsForSet();
        doReturn(zsets).when(operations).opsForZSet();
        when(operations.exec()).thenReturn(List.of(Boolean.TRUE));
        when(redis.execute(any(org.springframework.data.redis.core.SessionCallback.class)))
                .thenAnswer(invocation -> invocation.<org.springframework.data.redis.core.SessionCallback<Object>>getArgument(0)
                        .execute(operations));
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(any())).thenReturn(null);

        SipInviteSessionManager manager = new SipInviteSessionManager();
        ReflectionTestUtils.setField(manager, "userSetting", settings);
        ReflectionTestUtils.setField(manager, "redisTemplate", redis);
        SsrcTransaction transaction = transaction("call-1", "device-1", "app", "stream-1");

        manager.put(transaction);

        String dataKey = VideoManagerConstants.SIP_INVITE_SESSION_V2_DATA + "srv-1:call-1";
        String streamKey = VideoManagerConstants.SIP_INVITE_SESSION_V2_STREAM + "srv-1:appstream-1";
        verify(values).set(eq(dataKey), eq(transaction), eq(3600L), eq(TimeUnit.SECONDS));
        verify(values).set(eq(streamKey), eq("call-1"), eq(3600L), eq(TimeUnit.SECONDS));
        verify(sets).add(VideoManagerConstants.SIP_INVITE_SESSION_V2_DEVICE + "srv-1:device-1", "call-1");
        assertNotNull(transaction.getExpireAt());
        assertEquals(3600L * 1000L, transaction.getExpireAt() - transaction.getCreatedAt(), 1000L);
    }

    @Test
    void constantsKeepLegacyKeysSeparateFromV2() {
        assertEquals("VMP_SIP_INVITE_SESSION_INFO:CALL_ID:", VideoManagerConstants.SIP_INVITE_SESSION_CALL_ID);
        assertEquals("VMP_SIP_INVITE_SESSION_V2:DATA:", VideoManagerConstants.SIP_INVITE_SESSION_V2_DATA);
        assertEquals("VMP_SIP_INVITE_SESSION_V2:DEVICE:", VideoManagerConstants.SIP_INVITE_SESSION_V2_DEVICE);
    }

    @Test
    void deviceLookupUsesDeviceSetAndNeverLoadsLegacyHashValues() {
        UserSetting settings = mock(UserSetting.class);
        when(settings.getServerId()).thenReturn("srv-1");
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        SetOperations<String, Object> sets = mock(SetOperations.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        SsrcTransaction transaction = transaction("call-1", "device-1", "app", "stream-1");
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForHash()).thenReturn(hashes);
        when(sets.members(VideoManagerConstants.SIP_INVITE_SESSION_V2_DEVICE + "srv-1:device-1"))
                .thenReturn(Set.of("call-1"));
        when(values.multiGet(anyList())).thenReturn(List.of(transaction));

        SipInviteSessionManager manager = new SipInviteSessionManager();
        ReflectionTestUtils.setField(manager, "userSetting", settings);
        ReflectionTestUtils.setField(manager, "redisTemplate", redis);

        assertEquals(List.of(transaction), manager.getSsrcTransactionByDeviceId("device-1"));
        verify(hashes, never()).values(any());
    }

    @Test
    void removeByCallIdDoesNotDeleteReplacementStreamOwner() {
        UserSetting settings = mock(UserSetting.class);
        when(settings.getServerId()).thenReturn("srv-1");
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        RedisOperations<String, Object> operations = mock(RedisOperations.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        SetOperations<String, Object> sets = mock(SetOperations.class);
        ZSetOperations<String, Object> zsets = mock(ZSetOperations.class);
        doReturn(values).when(operations).opsForValue();
        doReturn(sets).when(operations).opsForSet();
        doReturn(zsets).when(operations).opsForZSet();
        when(operations.exec()).thenReturn(List.of(Boolean.TRUE));
        when(redis.execute(any(org.springframework.data.redis.core.SessionCallback.class)))
                .thenAnswer(invocation -> invocation.<org.springframework.data.redis.core.SessionCallback<Object>>getArgument(0)
                        .execute(operations));
        SsrcTransaction transaction = transaction("call-1", "device-1", "app", "stream-1");
        String dataKey = VideoManagerConstants.SIP_INVITE_SESSION_V2_DATA + "srv-1:call-1";
        String streamKey = VideoManagerConstants.SIP_INVITE_SESSION_V2_STREAM + "srv-1:appstream-1";
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (dataKey.equals(key)) {
                return transaction;
            }
            if (streamKey.equals(key)) {
                return "replacement-call";
            }
            return null;
        });

        SipInviteSessionManager manager = new SipInviteSessionManager();
        ReflectionTestUtils.setField(manager, "userSetting", settings);
        ReflectionTestUtils.setField(manager, "redisTemplate", redis);

        manager.removeByCallId("call-1");

        verify(operations).delete(dataKey);
        verify(operations, never()).delete(streamKey);
    }

    @Test
    void removeByStreamIfSsrcSkipsReplacementOwner() {
        UserSetting settings = mock(UserSetting.class);
        when(settings.getServerId()).thenReturn("srv-1");
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        SsrcTransaction replacement = transaction("replacement-call", "device-1", "app", "stream-1");
        replacement.setSsrc("new-ssrc");
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key.contains("STREAM:")) {
                return "replacement-call";
            }
            return replacement;
        });

        SipInviteSessionManager manager = new SipInviteSessionManager();
        ReflectionTestUtils.setField(manager, "userSetting", settings);
        ReflectionTestUtils.setField(manager, "redisTemplate", redis);

        manager.removeByStreamIfSsrc("app", "stream-1", "old-ssrc");

        verify(redis, never()).execute(any(org.springframework.data.redis.core.SessionCallback.class));
    }

    private static SsrcTransaction transaction(String callId, String deviceId, String app, String stream) {
        SsrcTransaction transaction = new SsrcTransaction();
        transaction.setCallId(callId);
        transaction.setDeviceId(deviceId);
        transaction.setApp(app);
        transaction.setStream(stream);
        return transaction;
    }
}
