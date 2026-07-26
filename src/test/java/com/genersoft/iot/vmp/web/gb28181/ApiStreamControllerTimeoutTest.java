package com.genersoft.iot.vmp.web.gb28181;

import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.service.IDeviceChannelService;
import com.genersoft.iot.vmp.gb28181.service.IDeviceService;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.service.IPlayService;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.async.DeferredResult;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ApiStreamControllerTimeoutTest {

    @Test
    void timeoutDelegatesToUnifiedPlayStop() {
        Fixture fixture = fixture();
        DeferredResult<?> result = ReflectionTestUtils.invokeMethod(fixture.controller, "start", "device-1",
                null, "channel-path", null, null, null, null, null, null);

        timeoutCallback(result).run();

        verify(fixture.playService).stop(InviteSessionType.PLAY, fixture.device, fixture.channel,
                "device-1_channel-1");
        verifyNoInteractions(fixture.invites);
        verify(fixture.channels, never()).stopPlay(fixture.channel.getId());
    }

    @Test
    void timeoutSwallowsStopFailure() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("stop failed")).when(fixture.playService)
                .stop(eq(InviteSessionType.PLAY), eq(fixture.device), eq(fixture.channel), eq("device-1_channel-1"));
        DeferredResult<?> result = ReflectionTestUtils.invokeMethod(fixture.controller, "start", "device-1",
                null, "channel-path", null, null, null, null, null, null);

        assertDoesNotThrow(() -> timeoutCallback(result).run());
        verify(fixture.playService).stop(InviteSessionType.PLAY, fixture.device, fixture.channel,
                "device-1_channel-1");
    }

    @SuppressWarnings("unchecked")
    private static Runnable timeoutCallback(DeferredResult<?> result) {
        return (Runnable) ReflectionTestUtils.getField(result, "timeoutCallback");
    }

    private static Fixture fixture() {
        ApiStreamController controller = new ApiStreamController();
        IPlayService playService = mock(IPlayService.class);
        IDeviceService devices = mock(IDeviceService.class);
        IDeviceChannelService channels = mock(IDeviceChannelService.class);
        IInviteStreamService invites = mock(IInviteStreamService.class);
        UserSetting settings = mock(UserSetting.class);
        Device device = new Device();
        device.setDeviceId("device-1");
        device.setOnLine(true);
        DeviceChannel channel = new DeviceChannel();
        channel.setId(1);
        channel.setDeviceId("channel-1");
        channel.setStatus("ON");
        when(settings.getPlayTimeout()).thenReturn(10000);
        when(devices.getDeviceByDeviceId("device-1")).thenReturn(device);
        when(channels.getOne("device-1", "channel-path")).thenReturn(channel);
        when(playService.getNewMediaServerItem(device)).thenReturn(null);
        when(playService.play(any(), eq("device-1"), eq("channel-path"), eq(null), any())).thenReturn(null);
        ReflectionTestUtils.setField(controller, "playService", playService);
        ReflectionTestUtils.setField(controller, "deviceService", devices);
        ReflectionTestUtils.setField(controller, "deviceChannelService", channels);
        ReflectionTestUtils.setField(controller, "inviteStreamService", invites);
        ReflectionTestUtils.setField(controller, "userSetting", settings);
        return new Fixture(controller, playService, devices, channels, invites, device, channel);
    }

    private record Fixture(ApiStreamController controller, IPlayService playService,
                           IDeviceService devices, IDeviceChannelService channels,
                           IInviteStreamService invites, Device device, DeviceChannel channel) {
    }
}
