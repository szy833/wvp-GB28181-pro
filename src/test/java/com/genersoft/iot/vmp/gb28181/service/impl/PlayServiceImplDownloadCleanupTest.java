package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionStatus;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.event.SipSubscribe;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.bean.RecordInfo;
import com.genersoft.iot.vmp.media.event.hook.HookData;
import com.genersoft.iot.vmp.media.event.hook.HookSubscribe;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.ICloudRecordService;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.service.bean.DownloadFileInfo;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sip.ResponseEvent;
import javax.sip.message.Response;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayServiceImplDownloadCleanupTest {

    @Test
    void recordHookSetsFifteenMinuteDeadlineForCompletedDownload() throws Exception {
        Fixture fixture = fixture(1.0);
        long before = System.currentTimeMillis();

        fixture.invokeDownloadAndRecordHook();

        long cleanupAt = fixture.invite.getCleanupAt();
        assertTrue(cleanupAt >= before + 15 * 60 * 1000L);
        assertTrue(cleanupAt <= System.currentTimeMillis() + 15 * 60 * 1000L);
        assertSame(fixture.file, fixture.invite.getStreamInfo().getDownLoadFilePath());
        verify(fixture.invites).updateInviteInfo(fixture.invite);
    }

    @Test
    void recordHookDoesNotSetDeadlineBeforeDownloadCompletes() throws Exception {
        Fixture fixture = fixture(0.5);

        fixture.invokeDownloadAndRecordHook();

        assertNull(fixture.invite.getCleanupAt());
    }

    @Test
    void nonReadyUpdateWithoutCleanupAtPreservesExistingDeadline() {
        InviteInfo existing = invite(InviteSessionStatus.ok, 1.0);
        existing.setCleanupAt(1_725_000_123_456L);
        InviteInfo incoming = invite(InviteSessionStatus.ok, 1.0);
        incoming.setStreamInfo(existing.getStreamInfo());
        InviteStreamServiceImpl service = spy(new InviteStreamServiceImpl());
        org.springframework.data.redis.core.RedisTemplate<String, Object> redis = mock(org.springframework.data.redis.core.RedisTemplate.class);
        org.springframework.data.redis.core.HashOperations<String, Object, Object> hash = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        doReturn(existing).when(service).getInviteInfo(any(), anyInt(), anyString());

        service.updateInviteInfo(incoming);

        assertEquals(existing.getCleanupAt(), 1_725_000_123_456L);
    }

    @Test
    void readyDownloadStartsWithNoDeadline() {
        InviteInfo ready = invite(InviteSessionStatus.ready, 0.0);
        assertNull(ready.getCleanupAt());
    }

    private static Fixture fixture(double progress) throws Exception {
        PlayServiceImpl service = new PlayServiceImpl();
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IReceiveRtpServerService receiveRtp = mock(IReceiveRtpServerService.class);
        ISIPCommander commander = mock(ISIPCommander.class);
        HookSubscribe subscribe = mock(HookSubscribe.class);
        IMediaServerService media = mock(IMediaServerService.class);
        UserSetting settings = mock(UserSetting.class);
        SipInviteSessionManager sessions = mock(SipInviteSessionManager.class);
        ICloudRecordService cloudRecord = mock(ICloudRecordService.class);
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("media-1");
        mediaServer.setRtpEnable(true);
        Device device = new Device();
        device.setDeviceId("device-1");
        device.setStreamMode("UDP");
        DeviceChannel channel = new DeviceChannel();
        channel.setId(1);
        channel.setDeviceId("channel-1");
        SSRCInfo ssrc = new SSRCInfo(1234, "00000001", "rtp", "stream-1");
        InviteInfo invite = InviteInfo.getInviteInfo("device-1", 1, "stream-1", ssrc,
                "media-1", "127.0.0.1", 1234, "UDP", InviteSessionType.DOWNLOAD,
                InviteSessionStatus.ok);
        StreamInfo streamInfo = new StreamInfo();
        streamInfo.setProgress(progress);
        invite.setStreamInfo(streamInfo);
        DownloadFileInfo file = new DownloadFileInfo();
        when(receiveRtp.openGbRTPServerForDownload(any(), any(), any(), anyString(), anyString(), any())).thenReturn(ssrc);
        when(settings.getPlayTimeout()).thenReturn(10);
        when(invites.getInviteInfo(any(), anyInt(), anyString())).thenReturn(invite);
        when(media.getDownloadFilePath(any(), any())).thenReturn(file);
        AtomicReference<HookSubscribe.Event> hookEvent = new AtomicReference<>();
        AtomicReference<SipSubscribe.Event> okEvent = new AtomicReference<>();
        doAnswer(invocation -> {
            hookEvent.set(invocation.getArgument(1));
            return null;
        }).when(subscribe).addSubscribe(any(), any());
        doAnswer(invocation -> {
            okEvent.set(invocation.getArgument(8));
            return null;
        }).when(commander).downloadStreamCmd(any(), any(), any(), any(), anyString(), anyString(), anyInt(), any(), any(), anyLong());

        ReflectionTestUtils.setField(service, "inviteStreamService", invites);
        ReflectionTestUtils.setField(service, "receiveRtpServerService", receiveRtp);
        ReflectionTestUtils.setField(service, "cmder", commander);
        ReflectionTestUtils.setField(service, "subscribe", subscribe);
        ReflectionTestUtils.setField(service, "mediaServerService", media);
        ReflectionTestUtils.setField(service, "userSetting", settings);
        ReflectionTestUtils.setField(service, "sessionManager", sessions);
        ReflectionTestUtils.setField(service, "cloudRecordService", cloudRecord);
        return new Fixture(service, mediaServer, device, channel, invites, invite, file, hookEvent, okEvent);
    }

    private static InviteInfo invite(InviteSessionStatus status, double progress) {
        SSRCInfo ssrc = new SSRCInfo(1234, "00000001", "rtp", "stream-1");
        InviteInfo invite = InviteInfo.getInviteInfo("device-1", 1, "stream-1", ssrc,
                "media-1", "127.0.0.1", 1234, "UDP", InviteSessionType.DOWNLOAD, status);
        StreamInfo streamInfo = new StreamInfo();
        streamInfo.setProgress(progress);
        invite.setStreamInfo(streamInfo);
        return invite;
    }

    private record Fixture(PlayServiceImpl service, MediaServer mediaServer, Device device,
                           DeviceChannel channel, IInviteStreamService invites,
                           InviteInfo invite, DownloadFileInfo file,
                           AtomicReference<HookSubscribe.Event> hookEvent,
                           AtomicReference<SipSubscribe.Event> okEvent) {
        void invokeDownloadAndRecordHook() {
            ReflectionTestUtils.invokeMethod(service, "download", mediaServer, device, channel,
                    "2026-07-27 00:00:00", "2026-07-27 00:01:00", 1,
                    (com.genersoft.iot.vmp.service.bean.ErrorCallback<StreamInfo>) (code, msg, data) -> {});
            assertNotNull(okEvent.get());
            Response response = mock(Response.class);
            when(response.getRawContent()).thenReturn(new byte[0]);
            ResponseEvent responseEvent = mock(ResponseEvent.class);
            when(responseEvent.getResponse()).thenReturn(response);
            SipSubscribe.EventResult eventResult = new SipSubscribe.EventResult();
            eventResult.event = responseEvent;
            okEvent.get().response(eventResult);
            assertNotNull(hookEvent.get());
            HookData hookData = new HookData();
            RecordInfo recordInfo = new RecordInfo();
            hookData.setRecordInfo(recordInfo);
            hookEvent.get().response(hookData);
        }
    }
}
