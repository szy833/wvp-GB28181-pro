package com.genersoft.iot.vmp.gb28181.controller;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.service.IDeviceChannelService;
import com.genersoft.iot.vmp.gb28181.service.IDeviceService;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.service.IPlayService;
import com.genersoft.iot.vmp.gb28181.transmit.callback.DeferredResultHolder;
import com.genersoft.iot.vmp.gb28181.transmit.callback.RequestMessage;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import com.genersoft.iot.vmp.vmanager.bean.StreamContent;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.async.DeferredResult;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PlaybackAndDownloadTimeoutTest {

    @Test
    void playbackTimeoutStopsTheCurrentInviteOwner() {
        PlaybackFixture fixture = playbackFixture();
        DeferredResult<WVPResult<StreamContent>> result = fixture.controller.start(
                mock(HttpServletRequest.class), "device-1", "channel-1", "start", "end");

        timeoutCallback(result).run();

        verify(fixture.resultHolder).invokeResult(any(RequestMessage.class));
        verify(fixture.playService).stop(fixture.invite);
    }

    @Test
    void downloadTimeoutStopsTheCurrentInviteOwner() {
        DownloadFixture fixture = downloadFixture();
        DeferredResult<WVPResult<StreamContent>> result = fixture.controller.download(
                mock(HttpServletRequest.class), "device-1", "channel-1", "start", "end", "1");

        timeoutCallback(result).run();

        verify(fixture.resultHolder).invokeResult(any(RequestMessage.class));
        verify(fixture.playService).stop(fixture.invite);
    }

    @Test
    void playbackTimeoutSwallowsStopFailure() {
        PlaybackFixture fixture = playbackFixture();
        doThrow(new IllegalStateException("stop failed")).when(fixture.playService)
                .stop(fixture.invite);
        DeferredResult<WVPResult<StreamContent>> result = fixture.controller.start(
                mock(HttpServletRequest.class), "device-1", "channel-1", "start", "end");

        assertDoesNotThrow(() -> timeoutCallback(result).run());
        verify(fixture.resultHolder).invokeResult(any(RequestMessage.class));
    }

    @SuppressWarnings("unchecked")
    private static Runnable timeoutCallback(DeferredResult<WVPResult<StreamContent>> result) {
        return (Runnable) ReflectionTestUtils.getField(result, "timeoutCallback");
    }

    private static PlaybackFixture playbackFixture() {
        PlaybackController controller = new PlaybackController();
        IPlayService playService = mock(IPlayService.class);
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IDeviceService devices = mock(IDeviceService.class);
        IDeviceChannelService channels = mock(IDeviceChannelService.class);
        DeferredResultHolder resultHolder = mock(DeferredResultHolder.class);
        UserSetting settings = mock(UserSetting.class);
        Device device = device();
        DeviceChannel channel = channel();
        InviteInfo invite = invite(InviteSessionType.PLAYBACK, device, channel);

        when(settings.getPlayTimeout()).thenReturn(10000);
        when(devices.getDeviceByDeviceId("device-1")).thenReturn(device);
        when(channels.getOne("device-1", "channel-1")).thenReturn(channel);
        when(invites.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAYBACK, channel.getId()))
                .thenReturn(invite);
        doNothing().when(playService).playBack(eq(device), eq(channel), eq("start"), eq("end"), any());
        set(controller, playService, invites, devices, channels, resultHolder, settings);
        return new PlaybackFixture(controller, playService, invites, resultHolder, invite);
    }

    private static DownloadFixture downloadFixture() {
        GBRecordController controller = new GBRecordController();
        IPlayService playService = mock(IPlayService.class);
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IDeviceService devices = mock(IDeviceService.class);
        IDeviceChannelService channels = mock(IDeviceChannelService.class);
        DeferredResultHolder resultHolder = mock(DeferredResultHolder.class);
        UserSetting settings = mock(UserSetting.class);
        Device device = device();
        DeviceChannel channel = channel();
        InviteInfo invite = invite(InviteSessionType.DOWNLOAD, device, channel);

        when(devices.getDeviceByDeviceId("device-1")).thenReturn(device);
        when(channels.getOne("device-1", "channel-1")).thenReturn(channel);
        when(invites.getInviteInfoByDeviceAndChannel(InviteSessionType.DOWNLOAD, channel.getId()))
                .thenReturn(invite);
        set(controller, playService, invites, devices, channels, resultHolder, settings);
        return new DownloadFixture(controller, playService, invites, resultHolder, invite);
    }

    private static void set(PlaybackController controller, IPlayService playService,
                            IInviteStreamService invites, IDeviceService devices,
                            IDeviceChannelService channels, DeferredResultHolder holder,
                            UserSetting settings) {
        ReflectionTestUtils.setField(controller, "playService", playService);
        ReflectionTestUtils.setField(controller, "inviteStreamService", invites);
        ReflectionTestUtils.setField(controller, "deviceService", devices);
        ReflectionTestUtils.setField(controller, "channelService", channels);
        ReflectionTestUtils.setField(controller, "resultHolder", holder);
        ReflectionTestUtils.setField(controller, "userSetting", settings);
    }

    private static void set(GBRecordController controller, IPlayService playService,
                            IInviteStreamService invites, IDeviceService devices,
                            IDeviceChannelService channels, DeferredResultHolder holder,
                            UserSetting settings) {
        ReflectionTestUtils.setField(controller, "playService", playService);
        ReflectionTestUtils.setField(controller, "inviteStreamService", invites);
        ReflectionTestUtils.setField(controller, "deviceService", devices);
        ReflectionTestUtils.setField(controller, "channelService", channels);
        ReflectionTestUtils.setField(controller, "resultHolder", holder);
        ReflectionTestUtils.setField(controller, "userSetting", settings);
    }

    private static Device device() {
        Device device = new Device();
        device.setDeviceId("device-1");
        return device;
    }

    private static DeviceChannel channel() {
        DeviceChannel channel = new DeviceChannel();
        channel.setId(1);
        channel.setDeviceId("channel-1");
        return channel;
    }

    private static InviteInfo invite(InviteSessionType type, Device device, DeviceChannel channel) {
        SSRCInfo ssrcInfo = new SSRCInfo(1234, "00000001", "rtp", "stream-1");
        return InviteInfo.getInviteInfo(device.getDeviceId(), channel.getId(), "stream-1", ssrcInfo,
                "media-1", "127.0.0.1", 1234, "UDP", type,
                com.genersoft.iot.vmp.common.InviteSessionStatus.ready);
    }

    private record PlaybackFixture(PlaybackController controller, IPlayService playService,
                                   IInviteStreamService invites, DeferredResultHolder resultHolder,
                                   InviteInfo invite) {
    }

    private record DownloadFixture(GBRecordController controller, IPlayService playService,
                                   IInviteStreamService invites, DeferredResultHolder resultHolder,
                                   InviteInfo invite) {
    }
}
