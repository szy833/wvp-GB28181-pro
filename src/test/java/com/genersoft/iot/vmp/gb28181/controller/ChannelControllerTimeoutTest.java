package com.genersoft.iot.vmp.gb28181.controller;

import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelPlayService;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelService;
import com.genersoft.iot.vmp.vmanager.bean.StreamContent;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.async.DeferredResult;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChannelControllerTimeoutTest {

    @Test
    void playTimeoutStopsTheCommonChannel() {
        Fixture fixture = fixture();
        DeferredResult<WVPResult<StreamContent>> result = fixture.controller.play(
                mock(HttpServletRequest.class), 1);

        timeoutCallback(result).run();

        verify(fixture.channelPlayService).stopPlay(fixture.channel);
    }

    @Test
    void playbackTimeoutStopsTheCommonChannelWithoutGuessingStream() {
        Fixture fixture = fixture();
        DeferredResult<WVPResult<StreamContent>> result = fixture.controller.playback(
                mock(HttpServletRequest.class), 1, "2026-07-26 00:00:00", "2026-07-26 00:01:00");

        timeoutCallback(result).run();

        verify(fixture.channelPlayService).stopPlayback(fixture.channel, null);
    }

    @Test
    void commonChannelTimeoutSwallowsStopFailure() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("stop failed")).when(fixture.channelPlayService)
                .stopPlay(fixture.channel);
        DeferredResult<WVPResult<StreamContent>> result = fixture.controller.play(
                mock(HttpServletRequest.class), 1);

        assertDoesNotThrow(() -> timeoutCallback(result).run());
    }

    @SuppressWarnings("unchecked")
    private static Runnable timeoutCallback(DeferredResult<WVPResult<StreamContent>> result) {
        return (Runnable) ReflectionTestUtils.getField(result, "timeoutCallback");
    }

    private static Fixture fixture() {
        ChannelController controller = new ChannelController();
        IGbChannelService channels = mock(IGbChannelService.class);
        IGbChannelPlayService playService = mock(IGbChannelPlayService.class);
        UserSetting settings = mock(UserSetting.class);
        CommonGBChannel channel = new CommonGBChannel();
        channel.setGbId(1);
        when(channels.getOne(1)).thenReturn(channel);
        when(settings.getPlayTimeout()).thenReturn(10000);
        when(settings.getRecordSip()).thenReturn(Boolean.TRUE);
        doNothing().when(playService).play(eq(channel), isNull(), eq(Boolean.TRUE), any());
        doNothing().when(playService).playback(eq(channel), anyLong(), anyLong(), any());
        ReflectionTestUtils.setField(controller, "channelService", channels);
        ReflectionTestUtils.setField(controller, "channelPlayService", playService);
        ReflectionTestUtils.setField(controller, "userSetting", settings);
        return new Fixture(controller, playService, channel);
    }

    private record Fixture(ChannelController controller, IGbChannelPlayService channelPlayService,
                           CommonGBChannel channel) {
    }
}
