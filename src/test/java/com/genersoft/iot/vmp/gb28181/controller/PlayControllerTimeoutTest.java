package com.genersoft.iot.vmp.gb28181.controller;

import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.service.IDeviceChannelService;
import com.genersoft.iot.vmp.gb28181.service.IDeviceService;
import com.genersoft.iot.vmp.gb28181.service.IPlayService;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import com.genersoft.iot.vmp.gb28181.transmit.callback.DeferredResultHolder;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import com.genersoft.iot.vmp.vmanager.bean.StreamContent;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.async.DeferredResult;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PlayControllerTimeoutTest {

    @Test
    void timeoutDelegatesToUnifiedPlayStopWithCanonicalStream() {
        Fixture fixture = fixture();

        DeferredResult<WVPResult<StreamContent>> result = fixture.controller.play(
                mock(HttpServletRequest.class), "device-1", "path-channel");
        Runnable timeoutCallback = timeoutCallback(result);

        timeoutCallback.run();

        verify(fixture.playService).stop(InviteSessionType.PLAY, fixture.device, fixture.channel,
                "device-1_channel-device-1");
        verify(fixture.deviceChannelService, never()).stopPlay(fixture.channel.getId());
    }

    @Test
    void timeoutSwallowsStopRuntimeException() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("stop failed")).when(fixture.playService)
                .stop(eq(InviteSessionType.PLAY), eq(fixture.device), eq(fixture.channel),
                        eq("device-1_channel-device-1"));

        DeferredResult<WVPResult<StreamContent>> result = fixture.controller.play(
                mock(HttpServletRequest.class), "device-1", "path-channel");
        Runnable timeoutCallback = timeoutCallback(result);

        assertDoesNotThrow(timeoutCallback::run);
        verify(fixture.playService).stop(InviteSessionType.PLAY, fixture.device, fixture.channel,
                "device-1_channel-device-1");
    }

    @SuppressWarnings("unchecked")
    private static Runnable timeoutCallback(DeferredResult<WVPResult<StreamContent>> result) {
        return (Runnable) ReflectionTestUtils.getField(result, "timeoutCallback");
    }

    private static Fixture fixture() {
        PlayController controller = new PlayController();
        IPlayService playService = mock(IPlayService.class);
        IDeviceChannelService deviceChannelService = mock(IDeviceChannelService.class);
        IDeviceService deviceService = mock(IDeviceService.class);
        UserSetting userSetting = mock(UserSetting.class);
        Device device = new Device();
        device.setDeviceId("device-1");
        DeviceChannel channel = new DeviceChannel();
        channel.setId(7);
        channel.setDeviceId("channel-device-1");

        when(deviceService.getDeviceByDeviceId("device-1")).thenReturn(device);
        when(deviceChannelService.getOne("device-1", "path-channel")).thenReturn(channel);
        when(userSetting.getPlayTimeout()).thenReturn(10000);
        doNothing().when(playService).play(eq(device), eq(channel), any());

        ReflectionTestUtils.setField(controller, "playService", playService);
        ReflectionTestUtils.setField(controller, "deviceChannelService", deviceChannelService);
        ReflectionTestUtils.setField(controller, "deviceService", deviceService);
        ReflectionTestUtils.setField(controller, "userSetting", userSetting);
        ReflectionTestUtils.setField(controller, "sessionManager", mock(SipInviteSessionManager.class));
        ReflectionTestUtils.setField(controller, "resultHolder", mock(DeferredResultHolder.class));
        ReflectionTestUtils.setField(controller, "mediaServerService", mock(IMediaServerService.class));
        return new Fixture(controller, playService, deviceChannelService, device, channel);
    }

    private record Fixture(PlayController controller, IPlayService playService,
                           IDeviceChannelService deviceChannelService,
                           Device device, DeviceChannel channel) {
    }
}
