package com.genersoft.iot.vmp.service.impl;

import com.genersoft.iot.vmp.conf.DynamicTask;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.gb28181.session.SSRCFactory;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.event.hook.Hook;
import com.genersoft.iot.vmp.media.event.hook.HookSubscribe;
import com.genersoft.iot.vmp.media.event.hook.HookSubscriptionHandle;
import com.genersoft.iot.vmp.media.event.media.MediaDepartureEvent;
import com.genersoft.iot.vmp.media.event.media.MediaArrivalEvent;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.media.zlm.dto.hook.OnStreamChangedHookParam;
import com.genersoft.iot.vmp.service.bean.RTPServerParam;
import com.genersoft.iot.vmp.service.bean.RtpServerOpenResult;
import com.genersoft.iot.vmp.service.bean.RtpResourceContext;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.lang.reflect.Method;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RtpServerServiceImplTest {

    @Test
    void departureEventPreservesOriginUrl() {
        OnStreamChangedHookParam param = new OnStreamChangedHookParam();
        param.setApp("rtp");
        param.setStream("business");
        param.setSchema("rtsp");
        param.setOriginUrl("rtp://127.0.0.1/rtp/0000007B");
        MediaServer server = new MediaServer();
        server.setId("media-1");

        MediaDepartureEvent event = MediaDepartureEvent.getInstance(this, param, server);

        assertEquals("rtp://127.0.0.1/rtp/0000007B", event.getOriginUrl());
    }

    @Test
    void invalidPlayArgumentsDoNotAllocateSsrcLease() {
        RtpServerServiceImpl service = new RtpServerServiceImpl();
        SSRCFactory ssrcFactory = mock(SSRCFactory.class);
        MediaServer server = new MediaServer();
        server.setId("media-1");
        AtomicInteger callbacks = new AtomicInteger();
        ReflectionTestUtils.setField(service, "ssrcFactory", ssrcFactory);

        service.openGbRTPServerForPlay(server, null, null, null, false,
                (code, msg, data) -> callbacks.incrementAndGet());

        assertEquals(1, callbacks.get());
        verifyNoInteractions(ssrcFactory);
    }

    @Test
    void departureWhileWaitingNotifiesFailureOnce() {
        RtpServerServiceImpl service = new RtpServerServiceImpl();
        AtomicInteger callbacks = new AtomicInteger();
        RtpResourceContext context = new RtpResourceContext(
                "resource-1", "business", "0000007B",
                (code, msg, data) -> callbacks.incrementAndGet(), () -> {});
        assertTrue(context.transitionTo(com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA));
        installOwner(service, "media-1:rtp:business", context);
        installOwner(service, "media-1:rtp:0000007B", context);

        MediaDepartureEvent event = departure("media-1", "rtp", "business",
                "rtp://127.0.0.1/rtp/0000007B");
        service.onApplicationEvent(event);

        assertEquals(com.genersoft.iot.vmp.service.bean.RtpResourceState.FAILED, context.getState());
        assertEquals(1, callbacks.get());
    }

    @Test
    void successfulDepartureStillRunsTerminalCleanup() {
        RtpServerServiceImpl service = new RtpServerServiceImpl();
        AtomicInteger cleanup = new AtomicInteger();
        RtpResourceContext context = new RtpResourceContext(
                "resource-1", "business", "business", (code, msg, data) -> {}, cleanup::incrementAndGet);
        assertTrue(context.transitionTo(com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA));
        assertTrue(context.transitionTo(com.genersoft.iot.vmp.service.bean.RtpResourceState.SUCCESS));
        installOwner(service, "media-1:rtp:business", context);

        service.onApplicationEvent(departure("media-1", "rtp", "business", null));

        assertEquals(com.genersoft.iot.vmp.service.bean.RtpResourceState.CLOSED, context.getState());
        assertEquals(1, cleanup.get());
    }

    @Test
    void departureWithoutOriginUrlMatchesBusinessStreamOwner() {
        RtpServerServiceImpl service = new RtpServerServiceImpl();
        AtomicInteger cleanup = new AtomicInteger();
        RtpResourceContext context = new RtpResourceContext(
                "resource-business-owner", "business", "0000007B", (code, msg, data) -> {}, cleanup::incrementAndGet);
        assertTrue(context.transitionTo(com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA));
        assertTrue(context.transitionTo(com.genersoft.iot.vmp.service.bean.RtpResourceState.SUCCESS));
        installOwner(service, "media-1:rtp:business", context);
        installOwner(service, "media-1:rtp:0000007B", context);

        service.onApplicationEvent(departure("media-1", "rtp", "business", null));

        assertEquals(com.genersoft.iot.vmp.service.bean.RtpResourceState.CLOSED, context.getState());
        assertEquals(1, cleanup.get());
    }

    @Test
    void staleDepartureForOldZlmStreamCannotCloseCurrentOwner() {
        RtpServerServiceImpl service = new RtpServerServiceImpl();
        RtpResourceContext current = new RtpResourceContext(
                "resource-current", "business", "000000C8", (code, msg, data) -> {}, () -> {});
        assertTrue(current.transitionTo(com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA));
        installOwner(service, "media-1:rtp:business", current);
        installOwner(service, "media-1:rtp:000000C8", current);

        service.onApplicationEvent(departure("media-1", "rtp", "business",
                "rtp://127.0.0.1/rtp/0000007B"));

        assertEquals(com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA, current.getState());
    }

    @Test
    void nonReplacedDepartureCanMatchActualZlmStream() {
        RtpServerServiceImpl service = new RtpServerServiceImpl();
        AtomicInteger cleanup = new AtomicInteger();
        RtpResourceContext context = new RtpResourceContext(
                "resource-actual-zlm", "external-stream", "0000007B",
                (code, msg, data) -> {}, cleanup::incrementAndGet);
        assertTrue(context.transitionTo(com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA));
        assertTrue(context.transitionTo(com.genersoft.iot.vmp.service.bean.RtpResourceState.SUCCESS));
        installOwner(service, "media-1:rtp:external-stream", context);
        installOwner(service, "media-1:rtp:0000007B", context);

        service.onApplicationEvent(departure("media-1", "rtp", "0000007B",
                "rtp://127.0.0.1/rtp/0000007B"));

        assertEquals(com.genersoft.iot.vmp.service.bean.RtpResourceState.CLOSED, context.getState());
        assertEquals(1, cleanup.get());
    }

    private static MediaDepartureEvent departure(String mediaServerId, String app, String stream, String originUrl) {
        OnStreamChangedHookParam param = new OnStreamChangedHookParam();
        param.setMediaServerId(mediaServerId);
        param.setApp(app);
        param.setStream(stream);
        param.setSchema("rtsp");
        param.setOriginUrl(originUrl);
        MediaServer server = new MediaServer();
        server.setId(mediaServerId);
        return MediaDepartureEvent.getInstance(RtpServerServiceImplTest.class, param, server);
    }

    @SuppressWarnings("unchecked")
    private static void installOwner(RtpServerServiceImpl service, String key, RtpResourceContext context) {
        Map<String, RtpResourceContext> owners =
                (Map<String, RtpResourceContext>) ReflectionTestUtils.getField(service, "resourceOwners");
        owners.put(key, context);
    }

    @Test
    void remoteExceptionRollsBackAllOwners() {
        IMediaServerService media = mock(IMediaServerService.class);
        DynamicTask tasks = mock(DynamicTask.class);
        SSRCFactory ssrc = mock(SSRCFactory.class);
        UserSetting settings = mock(UserSetting.class);
        HookSubscribe hooks = mock(HookSubscribe.class);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        MediaServer server = mock(MediaServer.class);
        when(server.getId()).thenReturn("media-1");
        when(server.isRtpEnable()).thenReturn(true);
        when(settings.getPlayTimeout()).thenReturn(1000);
        when(tasks.startDelayWithHandle(anyString(), any(Runnable.class), anyInt()))
                .thenReturn(mock(ScheduledFuture.class));
        when(hooks.addSubscribeWithHandle(any(), any())).thenReturn(
                new HookSubscriptionHandle("key", data -> {}));
        when(media.createRTPServer(any(), anyString(), anyString(), anyLong(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any()))
                .thenThrow(new RuntimeException("zlm unavailable"));

        RtpServerServiceImpl service = new RtpServerServiceImpl();
        ReflectionTestUtils.setField(service, "mediaServerService", media);
        ReflectionTestUtils.setField(service, "dynamicTask", tasks);
        ReflectionTestUtils.setField(service, "ssrcFactory", ssrc);
        ReflectionTestUtils.setField(service, "userSetting", settings);
        ReflectionTestUtils.setField(service, "subscribe", hooks);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        AtomicInteger callbacks = new AtomicInteger();
        RTPServerParam param = new RTPServerParam(server, "rtp", "business", 123L, null,
                false, false, false, 0);
        RtpServerOpenResult result = service.openCommonRTPServerWithHandle(param,
                (code, msg, data) -> callbacks.incrementAndGet());

        assertTrue(result.isFailure());
        assertEquals(1, callbacks.get());
        verify(media).closeRTPServer(server, "rtp", result.getZlmStreamId());
    }

    @Test
    void refreshMovesOnlyTheCurrentAuthOwner() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        RtpServerServiceImpl service = new RtpServerServiceImpl();
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        RtpResourceContext context = new RtpResourceContext(
                "resource-1", "business", "zlm", (code, msg, data) -> {}, () -> {});
        context.markAuthWritten(VideoManagerConstants.RTP_AUTHENTICATE + ":old", "owner-1");
        @SuppressWarnings("unchecked")
        java.util.Map<String, RtpResourceContext> active =
                (java.util.Map<String, RtpResourceContext>) ReflectionTestUtils.getField(service, "activeResources");
        active.put("resource-1", context);
        doReturn(1L).when(redis).execute(any(), anyList(), anyString());

        SSRCInfo info = new SSRCInfo(1000, "0001", "rtp", "business");
        info.setResourceId("resource-1");
        service.refreshAuthenticateInfo(info, "old", "new");

        assertEquals(VideoManagerConstants.RTP_AUTHENTICATE + ":new", context.getAuthKey());
        verify(redis).execute(any(), anyList(), eq("owner-1"));
    }

    @Test
    void staleResourceIdMustNotFallBackToUnownedRtpClose() {
        IMediaServerService media = mock(IMediaServerService.class);
        MediaServer server = mock(MediaServer.class);
        when(server.getId()).thenReturn("media-1");
        RtpServerServiceImpl service = new RtpServerServiceImpl();
        ReflectionTestUtils.setField(service, "mediaServerService", media);

        SSRCInfo stale = new SSRCInfo(1234, "0001", "rtp", "business");
        stale.setResourceId("resource-already-closed");
        stale.setMediaServerId("media-1");
        stale.setZlmStream("0001");
        when(media.getOne("media-1")).thenReturn(server);

        service.closeRTPServer(stale);

        verify(media, never()).closeRTPServer(any(), anyString(), anyString());
        verify(media).closeStreams(server, "rtp", "business");
    }

    @Test
    void legacySsrcOnlyCloseUsesValidatedListener() {
        IMediaServerService media = mock(IMediaServerService.class);
        MediaServer server = mock(MediaServer.class);
        when(server.getId()).thenReturn("media-1");
        when(server.isRtpEnable()).thenReturn(true);
        when(media.getOne("media-1")).thenReturn(server);
        when(media.listRtpServer(server)).thenReturn(java.util.List.of("0000007B"));

        RtpServerServiceImpl service = new RtpServerServiceImpl();
        ReflectionTestUtils.setField(service, "mediaServerService", media);

        SSRCInfo legacy = new SSRCInfo(1234, "123", "rtp", "business");
        legacy.setMediaServerId("media-1");

        assertTrue(service.closeRtpResource(legacy));
        verify(media).listRtpServer(server);
        verify(media).closeRTPServer(server, "rtp", "0000007B");
        verify(media).closeStreams(server, "rtp", "business");
    }

    @Test
    void legacyBusinessCloseDoesNotCloseListener() {
        IMediaServerService media = mock(IMediaServerService.class);
        MediaServer server = mock(MediaServer.class);
        when(server.getId()).thenReturn("media-1");
        when(server.isRtpEnable()).thenReturn(true);

        RtpServerServiceImpl service = new RtpServerServiceImpl();
        ReflectionTestUtils.setField(service, "mediaServerService", media);

        service.closeRTPServer(server, "rtp", "business");

        verify(media).closeStreams(server, "rtp", "business");
        verify(media, never()).closeRTPServer(any(), anyString(), anyString());
    }

    @Test
    void mediaArrivalHookUsesPublishedStreamId() {
        IMediaServerService media = mock(IMediaServerService.class);
        DynamicTask tasks = mock(DynamicTask.class);
        SSRCFactory ssrc = mock(SSRCFactory.class);
        UserSetting settings = mock(UserSetting.class);
        HookSubscribe hooks = mock(HookSubscribe.class);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        MediaServer server = mock(MediaServer.class);
        when(server.getId()).thenReturn("media-1");
        when(server.isRtpEnable()).thenReturn(true);
        when(settings.getPlayTimeout()).thenReturn(1000);
        when(tasks.startDelayWithHandle(anyString(), any(Runnable.class), anyInt()))
                .thenReturn(mock(ScheduledFuture.class));
        when(hooks.addSubscribeWithHandle(any(), any())).thenReturn(
                new HookSubscriptionHandle("key", data -> {}));
        when(media.createRTPServer(any(), anyString(), anyString(), anyLong(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(1234);

        RtpServerServiceImpl service = new RtpServerServiceImpl();
        ReflectionTestUtils.setField(service, "mediaServerService", media);
        ReflectionTestUtils.setField(service, "dynamicTask", tasks);
        ReflectionTestUtils.setField(service, "ssrcFactory", ssrc);
        ReflectionTestUtils.setField(service, "userSetting", settings);
        ReflectionTestUtils.setField(service, "subscribe", hooks);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        RTPServerParam param = new RTPServerParam(server, "rtp", "business-stream", 123L, null,
                false, false, false, 0);
        service.openCommonRTPServerWithHandle(param, (code, msg, data) -> {});

        ArgumentCaptor<Hook> hookCaptor = ArgumentCaptor.forClass(Hook.class);
        verify(hooks).addSubscribeWithHandle(hookCaptor.capture(), any());
        assertEquals("business-stream", hookCaptor.getValue().getStream());
        verify(media).createRTPServer(eq(server), eq("rtp"), eq("0000007B"), anyLong(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    void mediaArrivalCompletesWaitingResourceOnlyOnce() {
        RtpServerServiceImpl service = new RtpServerServiceImpl();
        AtomicInteger callbacks = new AtomicInteger();
        RtpResourceContext context = new RtpResourceContext(
                "resource-arrival", "business-stream", "0000007B",
                (code, msg, data) -> callbacks.incrementAndGet(), () -> {});
        assertTrue(context.transitionTo(com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA));
        installOwner(service, "media-1:rtp:business-stream", context);

        MediaServer server = new MediaServer();
        server.setId("media-1");
        MediaArrivalEvent event = new MediaArrivalEvent(this);
        event.setMediaServer(server);
        event.setApp("rtp");
        event.setStream("business-stream");
        event.setSchema("rtsp");

        service.onApplicationEvent(event);
        service.onApplicationEvent(event);

        assertEquals(com.genersoft.iot.vmp.service.bean.RtpResourceState.SUCCESS, context.getState());
        assertEquals(1, callbacks.get());
    }

    @Test
    void terminalCleanupUsesZlmListenerAndBusinessPublishedStream() {
        IMediaServerService media = mock(IMediaServerService.class);
        DynamicTask tasks = mock(DynamicTask.class);
        SSRCFactory ssrc = mock(SSRCFactory.class);
        UserSetting settings = mock(UserSetting.class);
        HookSubscribe hooks = mock(HookSubscribe.class);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        MediaServer server = mock(MediaServer.class);
        when(server.getId()).thenReturn("media-1");
        when(server.isRtpEnable()).thenReturn(true);
        when(settings.getPlayTimeout()).thenReturn(1000);
        when(tasks.startDelayWithHandle(anyString(), any(Runnable.class), anyInt()))
                .thenReturn(mock(ScheduledFuture.class));
        when(hooks.addSubscribeWithHandle(any(), any())).thenReturn(
                new HookSubscriptionHandle("key", data -> {}));
        when(media.createRTPServer(any(), anyString(), anyString(), anyLong(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(1234);

        RtpServerServiceImpl service = new RtpServerServiceImpl();
        ReflectionTestUtils.setField(service, "mediaServerService", media);
        ReflectionTestUtils.setField(service, "dynamicTask", tasks);
        ReflectionTestUtils.setField(service, "ssrcFactory", ssrc);
        ReflectionTestUtils.setField(service, "userSetting", settings);
        ReflectionTestUtils.setField(service, "subscribe", hooks);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);

        RTPServerParam param = new RTPServerParam(server, "rtp", "business-stream", 123L, null,
                false, false, false, 0);
        RtpServerOpenResult result = service.openCommonRTPServerWithHandle(param,
                (code, msg, data) -> {});

        assertTrue(result.isSuccess());
        service.closeRTPServer(result);

        verify(media).closeRTPServer(server, "rtp", "0000007B");
        verify(media).closeStreams(server, "rtp", "business-stream");
        verify(media, never()).closeStreams(server, "rtp", "0000007B");
    }

    @Test
    void businessStreamFallbackDoesNotIssueAmbiguousZlmClose() throws Exception {
        IMediaServerService media = mock(IMediaServerService.class);
        MediaServer server = mock(MediaServer.class);
        when(server.getId()).thenReturn("media-1");
        when(server.isRtpEnable()).thenReturn(true);

        RtpServerServiceImpl service = new RtpServerServiceImpl();
        ReflectionTestUtils.setField(service, "mediaServerService", media);

        Method closeByBusiness = RtpServerServiceImpl.class.getMethod(
                "closeRTPServerByBusinessStream", MediaServer.class, String.class, String.class);
        Object closed = closeByBusiness.invoke(service, server, "rtp", "business-stream");

        assertEquals(Boolean.FALSE, closed);
        verify(media, never()).closeRTPServer(any(), anyString(), anyString());
        verify(media).closeStreams(server, "rtp", "business-stream");
    }

    @Test
    void staleBusinessFallbackCannotCloseCurrentOwner() {
        IMediaServerService media = mock(IMediaServerService.class);
        MediaServer server = mock(MediaServer.class);
        when(server.getId()).thenReturn("media-1");

        RtpServerServiceImpl service = new RtpServerServiceImpl();
        ReflectionTestUtils.setField(service, "mediaServerService", media);
        RtpResourceContext current = new RtpResourceContext(
                "resource-current", "business-stream", "000000C8", (code, msg, data) -> {}, () -> {});
        assertTrue(current.transitionTo(com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA));
        installOwner(service, "media-1:rtp:business-stream", current);

        assertFalse(service.closeRTPServerByBusinessStreamIfUnowned(server, "rtp", "business-stream"));
        assertEquals(com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA, current.getState());
        verify(media, never()).closeStreams(any(), anyString(), anyString());
    }

    @Test
    void legacySsrcWithoutListenerCannotCloseCurrentOwner() {
        IMediaServerService media = mock(IMediaServerService.class);
        MediaServer server = mock(MediaServer.class);
        when(server.getId()).thenReturn("media-1");
        when(server.isRtpEnable()).thenReturn(true);
        when(media.getOne("media-1")).thenReturn(server);
        when(media.listRtpServer(server)).thenReturn(java.util.List.of());

        RtpServerServiceImpl service = new RtpServerServiceImpl();
        ReflectionTestUtils.setField(service, "mediaServerService", media);
        RtpResourceContext current = new RtpResourceContext(
                "resource-current", "business-stream", "000000C8", (code, msg, data) -> {}, () -> {});
        assertTrue(current.transitionTo(com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA));
        installOwner(service, "media-1:rtp:business-stream", current);

        SSRCInfo legacy = new SSRCInfo(1234, "123", "rtp", "business-stream");
        legacy.setMediaServerId("media-1");
        assertFalse(service.closeRtpResource(legacy));
        assertEquals(com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA, current.getState());
        verify(media, never()).closeStreams(any(), anyString(), anyString());
    }

    @Test
    void handlelessZlmRecordCannotCloseCurrentListenerOwner() {
        IMediaServerService media = mock(IMediaServerService.class);
        MediaServer server = mock(MediaServer.class);
        when(server.getId()).thenReturn("media-1");
        when(server.isRtpEnable()).thenReturn(true);
        when(media.getOne("media-1")).thenReturn(server);

        RtpServerServiceImpl service = new RtpServerServiceImpl();
        ReflectionTestUtils.setField(service, "mediaServerService", media);
        RtpResourceContext current = new RtpResourceContext(
                "resource-current", "new-business", "0000007B", (code, msg, data) -> {}, () -> {});
        assertTrue(current.transitionTo(com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA));
        installOwner(service, "media-1:rtp:0000007B", current);

        SSRCInfo stale = new SSRCInfo(1234, "123", "rtp", "old-business");
        stale.setMediaServerId("media-1");
        stale.setZlmStream("0000007B");

        assertFalse(service.closeRtpResource(stale));
        assertEquals(com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA, current.getState());
        verify(media, never()).closeRTPServer(any(), anyString(), anyString());
    }

    @Test
    void legacySsrcCloseRequiresAValidatedZlmListener() {
        IMediaServerService media = mock(IMediaServerService.class);
        MediaServer server = mock(MediaServer.class);
        when(server.getId()).thenReturn("media-1");
        when(server.isRtpEnable()).thenReturn(true);
        when(media.getOne("media-1")).thenReturn(server);

        RtpServerServiceImpl service = new RtpServerServiceImpl();
        ReflectionTestUtils.setField(service, "mediaServerService", media);

        when(media.listRtpServer(server)).thenReturn(java.util.List.of("0000007B"));
        assertTrue(service.closeRTPServerBySsrcId("media-1", "rtp", "123"));
        verify(media).closeRTPServer(server, "rtp", "0000007B");

        reset(media);
        when(server.getId()).thenReturn("media-1");
        when(server.isRtpEnable()).thenReturn(true);
        when(media.getOne("media-1")).thenReturn(server);
        when(media.listRtpServer(server)).thenReturn(java.util.List.of("000000C8"));
        assertFalse(service.closeRTPServerBySsrcId("media-1", "rtp", "123"));
        verify(media, never()).closeRTPServer(any(), anyString(), anyString());
    }

    @Test
    void mediaArrivalMatchesZlmStreamId() {
        RtpServerServiceImpl service = new RtpServerServiceImpl();
        AtomicInteger callbacks = new AtomicInteger();
        RtpResourceContext context = new RtpResourceContext(
                "resource-zlm-arrival", "business-stream", "0000007B",
                (code, msg, data) -> callbacks.incrementAndGet(), () -> {});
        assertTrue(context.transitionTo(com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA));
        installOwner(service, "media-1:rtp:0000007B", context);

        MediaServer server = new MediaServer();
        server.setId("media-1");
        MediaArrivalEvent event = new MediaArrivalEvent(this);
        event.setMediaServer(server);
        event.setApp("rtp");
        event.setStream("0000007B");
        event.setSchema("rtsp");

        service.onApplicationEvent(event);

        assertEquals(com.genersoft.iot.vmp.service.bean.RtpResourceState.SUCCESS, context.getState());
        assertEquals(1, callbacks.get());
    }

    @Test
    void nonRtspMediaArrivalDoesNotCompleteResource() {
        RtpServerServiceImpl service = new RtpServerServiceImpl();
        RtpResourceContext context = new RtpResourceContext(
                "resource-non-rtsp", "business-stream", "0000007B", (code, msg, data) -> {}, () -> {});
        assertTrue(context.transitionTo(com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA));
        installOwner(service, "media-1:rtp:business-stream", context);

        MediaServer server = new MediaServer();
        server.setId("media-1");
        MediaArrivalEvent event = new MediaArrivalEvent(this);
        event.setMediaServer(server);
        event.setApp("rtp");
        event.setStream("business-stream");
        event.setSchema("rtmp");

        service.onApplicationEvent(event);

        assertEquals(com.genersoft.iot.vmp.service.bean.RtpResourceState.WAITING_MEDIA, context.getState());
    }
}
