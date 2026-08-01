package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.gb28181.dao.DeviceMapper;
import com.genersoft.iot.vmp.gb28181.event.device.DeviceOfflineEvent;
import com.genersoft.iot.vmp.gb28181.service.IDeviceChannelService;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.service.IPlayService;
import com.genersoft.iot.vmp.gb28181.session.AudioBroadcastManager;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import com.genersoft.iot.vmp.gb28181.task.deviceStatus.DeviceStatusManager;
import com.genersoft.iot.vmp.gb28181.task.deviceSubscribe.SubscribeTaskRunner;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.service.ISendRtpServerService;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;

class DeviceServiceImplOfflineTest {

    @Test
    void onlineRenewalOnlyRefreshesStatusWhenRegistrationInfoIsUnchanged() {
        DeviceServiceImpl service = new DeviceServiceImpl();
        DeviceStatusManager deviceStatusManager = mock(DeviceStatusManager.class);
        DeviceMapper deviceMapper = mock(DeviceMapper.class);
        IRedisCatchStorage redisCatchStorage = mock(IRedisCatchStorage.class);
        Device device = onlineDevice();

        ReflectionTestUtils.setField(service, "deviceStatusManager", deviceStatusManager);
        ReflectionTestUtils.setField(service, "deviceMapper", deviceMapper);
        ReflectionTestUtils.setField(service, "redisCatchStorage", redisCatchStorage);

        service.onlineRenewal(device, false);

        verify(deviceStatusManager).add(eq("device-1"), anyLong());
        verifyNoInteractions(deviceMapper, redisCatchStorage);
    }

    @Test
    void onlineRenewalPersistsChangedRegistrationInfo() {
        DeviceServiceImpl service = new DeviceServiceImpl();
        DeviceStatusManager deviceStatusManager = mock(DeviceStatusManager.class);
        DeviceMapper deviceMapper = mock(DeviceMapper.class);
        IRedisCatchStorage redisCatchStorage = mock(IRedisCatchStorage.class);
        Device device = onlineDevice();

        ReflectionTestUtils.setField(service, "deviceStatusManager", deviceStatusManager);
        ReflectionTestUtils.setField(service, "deviceMapper", deviceMapper);
        ReflectionTestUtils.setField(service, "redisCatchStorage", redisCatchStorage);

        service.onlineRenewal(device, true);

        verify(deviceStatusManager).add(eq("device-1"), anyLong());
        verify(deviceMapper).update(device);
        verify(redisCatchStorage).updateDevice(device);
    }

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
        IInviteStreamService inviteStreamService = mock(IInviteStreamService.class);

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
        ReflectionTestUtils.setField(service, "inviteStreamService", inviteStreamService);

        ReflectionTestUtils.invokeMethod(service, "cleanOfflineDevice", device);

        verify(playService).stopTalk(device, channel, null);
        verify(playService).stopTalkForDevice(device);
        verify(inviteStreamService).clearActiveInviteInfoByDeviceId("device-1");
        verify(receiveRtp, never()).closeRTPServerBySsrcId(any(), any(), any());
        verify(sessionManager).removeByCallId("call-talk");
    }

    @Test
    void offlineEventSkipsDeviceThatRegisteredAgain() {
        DeviceServiceImpl service = spy(new DeviceServiceImpl());
        IRedisCatchStorage redisCatchStorage = mock(IRedisCatchStorage.class);
        DeviceStatusManager deviceStatusManager = mock(DeviceStatusManager.class);
        Device device = onlineDevice();
        DeviceOfflineEvent event = new DeviceOfflineEvent(this);
        event.setDeviceIds(Set.of("device-1"));

        when(redisCatchStorage.getDeviceList(event.getDeviceIds())).thenReturn(List.of(device));
        when(deviceStatusManager.contains("device-1")).thenReturn(true);
        doNothing().when(service).offline(anyList());
        ReflectionTestUtils.setField(service, "redisCatchStorage", redisCatchStorage);
        ReflectionTestUtils.setField(service, "deviceStatusManager", deviceStatusManager);

        service.onApplicationEvent(event);

        verify(service, never()).offline(anyList());
    }

    private Device onlineDevice() {
        Device device = new Device();
        device.setDeviceId("device-1");
        device.setOnLine(true);
        device.setExpires(60);
        device.setHeartBeatInterval(60);
        device.setHeartBeatCount(3);
        return device;
    }
}
