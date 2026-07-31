package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.gb28181.service.IDeviceChannelService;
import com.genersoft.iot.vmp.gb28181.service.IPlayService;
import com.genersoft.iot.vmp.gb28181.session.AudioBroadcastManager;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import com.genersoft.iot.vmp.gb28181.task.deviceStatus.DeviceStatusManager;
import com.genersoft.iot.vmp.gb28181.task.deviceSubscribe.SubscribeTaskRunner;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.service.ISendRtpServerService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.*;

class DeviceServiceImplOfflineTest {

    @Test
    void cleanOfflineDeviceStopsTalkBeforeGenericSsrcCleanup() {
        DeviceServiceImpl service = new DeviceServiceImpl();
        SubscribeTaskRunner subscribeTaskRunner = mock(SubscribeTaskRunner.class);
        DeviceStatusManager deviceStatusManager = mock(DeviceStatusManager.class);
        SipInviteSessionManager sessionManager = mock(SipInviteSessionManager.class);
        IReceiveRtpServerService receiveRtp = mock(IReceiveRtpServerService.class);
        AudioBroadcastManager audioBroadcastManager = mock(AudioBroadcastManager.class);
        IDeviceChannelService deviceChannelService = mock(IDeviceChannelService.class);
        IPlayService playService = mock(IPlayService.class);

        Device device = new Device();
        device.setDeviceId("device-1");
        DeviceChannel channel = new DeviceChannel();
        channel.setId(2);
        channel.setDeviceId("channel-1");
        SsrcTransaction transaction = new SsrcTransaction();
        transaction.setCallId("call-talk");
        transaction.setDeviceId("device-1");
        transaction.setChannelId(2);
        transaction.setType(com.genersoft.iot.vmp.common.InviteSessionType.TALK);
        transaction.setMediaServerId("media-1");
        transaction.setApp("talk");
        transaction.setStream("device-1_channel-1");
        transaction.setSsrc("00000002");

        when(sessionManager.getSsrcTransactionByDeviceId("device-1")).thenReturn(List.of(transaction));
        when(deviceChannelService.getOneForSourceById(2)).thenReturn(channel);
        when(audioBroadcastManager.getByDeviceId("device-1")).thenReturn(List.of());

        ReflectionTestUtils.setField(service, "subscribeTaskRunner", subscribeTaskRunner);
        ReflectionTestUtils.setField(service, "deviceStatusManager", deviceStatusManager);
        ReflectionTestUtils.setField(service, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(service, "receiveRtpServerService", receiveRtp);
        ReflectionTestUtils.setField(service, "audioBroadcastManager", audioBroadcastManager);
        ReflectionTestUtils.setField(service, "deviceChannelService", deviceChannelService);
        ReflectionTestUtils.setField(service, "playService", playService);

        ReflectionTestUtils.invokeMethod(service, "cleanOfflineDevice", device);

        verify(playService).stopTalk(device, channel, null);
        verify(playService).stopTalkForDevice(device);
        verify(receiveRtp, never()).closeRTPServerBySsrcId(any(), any(), any());
        verify(sessionManager).removeByCallId("call-talk");
    }
}
