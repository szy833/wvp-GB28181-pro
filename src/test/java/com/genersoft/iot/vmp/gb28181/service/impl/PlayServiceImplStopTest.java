package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionStatus;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.common.enums.MediaStreamUtil;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.gb28181.service.IDeviceChannelService;
import com.genersoft.iot.vmp.gb28181.service.IDeviceService;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlayServiceImplStopTest {

    @Test
    void stopIfOwnerCleansResourcesAfterClaimingCurrentOwner() {
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IReceiveRtpServerService rtp = mock(IReceiveRtpServerService.class);
        PlayServiceImpl service = service(invites, mock(ISIPCommander.class), mock(IDeviceChannelService.class),
                rtp, mock(UserSetting.class), mock(SipInviteSessionManager.class));
        InviteInfo invite = invite(device(), channel());
        when(invites.removeInviteInfoIfSame(invite)).thenReturn(true);

        assertTrue(service.stopIfOwner(invite));

        verify(invites).removeInviteInfoIfSame(invite);
        verify(rtp).closeRtpResource(invite.getSsrcInfo());
    }

    @Test
    void stopIfOwnerDoesNotTouchReplacementOwner() {
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IReceiveRtpServerService rtp = mock(IReceiveRtpServerService.class);
        ISIPCommander commander = mock(ISIPCommander.class);
        SipInviteSessionManager sessions = mock(SipInviteSessionManager.class);
        PlayServiceImpl service = service(invites, commander, mock(IDeviceChannelService.class), rtp,
                mock(UserSetting.class), sessions);
        InviteInfo invite = invite(device(), channel());
        when(invites.removeInviteInfoIfSame(invite)).thenReturn(false);

        assertFalse(service.stopIfOwner(invite));

        verifyNoInteractions(rtp, commander, sessions);
    }

    @Test
    void stopIfOwnerSkipsResourcesWhenCompareDeleteFails() {
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IReceiveRtpServerService rtp = mock(IReceiveRtpServerService.class);
        PlayServiceImpl service = service(invites, mock(ISIPCommander.class), mock(IDeviceChannelService.class),
                rtp, mock(UserSetting.class), mock(SipInviteSessionManager.class));
        InviteInfo invite = invite(device(), channel());
        when(invites.removeInviteInfoIfSame(invite)).thenThrow(new IllegalStateException("redis failed"));

        assertFalse(service.stopIfOwner(invite));

        verifyNoInteractions(rtp);
    }

    @Test
    void stopIfOwnerRestoresInviteWhenResourceCleanupFails() {
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IReceiveRtpServerService rtp = mock(IReceiveRtpServerService.class);
        PlayServiceImpl service = service(invites, mock(ISIPCommander.class), mock(IDeviceChannelService.class),
                rtp, mock(UserSetting.class), mock(SipInviteSessionManager.class));
        InviteInfo invite = invite(device(), channel());
        when(invites.removeInviteInfoIfSame(invite)).thenReturn(true);
        doThrow(new IllegalStateException("rtp close failed")).when(rtp).closeRtpResource(invite.getSsrcInfo());

        assertFalse(service.stopIfOwner(invite));

        verify(invites).restoreInviteInfoIfAbsent(invite);
    }

    @Test
    void mediaServerOnlineUsesOwnerCleanupForMissingRtp() {
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IReceiveRtpServerService rtp = mock(IReceiveRtpServerService.class);
        IMediaServerService mediaServers = mock(IMediaServerService.class);
        PlayServiceImpl service = service(invites, mock(ISIPCommander.class), mock(IDeviceChannelService.class),
                rtp, mock(UserSetting.class), mock(SipInviteSessionManager.class));
        ReflectionTestUtils.setField(service, "mediaServerService", mediaServers);
        InviteInfo invite = invite(device(), channel());
        invite.setStreamInfo(new com.genersoft.iot.vmp.common.StreamInfo());
        invite.getSsrcInfo().setZlmStream("stream-1");
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("media-1");
        mediaServer.setRtpEnable(true);
        when(invites.getActiveInviteInfoByMediaServer("media-1")).thenReturn(java.util.List.of(invite));
        when(mediaServers.listRtpServer(mediaServer)).thenReturn(java.util.List.of());
        when(invites.removeInviteInfoIfSame(invite)).thenReturn(true);

        service.zlmServerOnline(mediaServer);

        verify(invites).removeInviteInfoIfSame(invite);
        verify(invites, never()).getAllInviteInfo();
        verify(rtp).closeRtpResource(invite.getSsrcInfo());
    }

    @Test
    void mediaServerOnlineUsesStreamInfoMediaServerWhenBothIdsArePresent() {
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IReceiveRtpServerService rtp = mock(IReceiveRtpServerService.class);
        IMediaServerService mediaServers = mock(IMediaServerService.class);
        PlayServiceImpl service = service(invites, mock(ISIPCommander.class), mock(IDeviceChannelService.class),
                rtp, mock(UserSetting.class), mock(SipInviteSessionManager.class));
        ReflectionTestUtils.setField(service, "mediaServerService", mediaServers);
        InviteInfo invite = invite(device(), channel());
        com.genersoft.iot.vmp.common.StreamInfo streamInfo = new com.genersoft.iot.vmp.common.StreamInfo();
        MediaServer streamMediaServer = new MediaServer();
        streamMediaServer.setId("media-stream");
        streamMediaServer.setRtpEnable(true);
        streamInfo.setMediaServer(streamMediaServer);
        invite.setStreamInfo(streamInfo);
        invite.getSsrcInfo().setZlmStream("stream-1");
        when(invites.getActiveInviteInfoByMediaServer("media-stream")).thenReturn(java.util.List.of(invite));
        when(mediaServers.listRtpServer(streamMediaServer)).thenReturn(java.util.List.of());
        when(invites.removeInviteInfoIfSame(invite)).thenReturn(true);

        service.zlmServerOnline(streamMediaServer);

        verify(invites).removeInviteInfoIfSame(invite);
        verify(rtp).closeRtpResource(invite.getSsrcInfo());
    }

    @Test
    void mediaServerOnlineFallsBackWhenStreamInfoMediaServerIdIsEmpty() {
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IReceiveRtpServerService rtp = mock(IReceiveRtpServerService.class);
        IMediaServerService mediaServers = mock(IMediaServerService.class);
        PlayServiceImpl service = service(invites, mock(ISIPCommander.class), mock(IDeviceChannelService.class),
                rtp, mock(UserSetting.class), mock(SipInviteSessionManager.class));
        ReflectionTestUtils.setField(service, "mediaServerService", mediaServers);
        InviteInfo invite = invite(device(), channel());
        com.genersoft.iot.vmp.common.StreamInfo streamInfo = new com.genersoft.iot.vmp.common.StreamInfo();
        MediaServer streamMediaServer = new MediaServer();
        streamMediaServer.setId("");
        streamInfo.setMediaServer(streamMediaServer);
        invite.setStreamInfo(streamInfo);
        invite.getSsrcInfo().setZlmStream("stream-1");
        when(invites.getActiveInviteInfoByMediaServer("media-1")).thenReturn(java.util.List.of(invite));
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("media-1");
        mediaServer.setRtpEnable(true);
        when(mediaServers.listRtpServer(mediaServer)).thenReturn(java.util.List.of());
        when(invites.removeInviteInfoIfSame(invite)).thenReturn(true);

        service.zlmServerOnline(mediaServer);

        verify(invites).removeInviteInfoIfSame(invite);
        verify(rtp).closeRtpResource(invite.getSsrcInfo());
    }

    @Test
    void byeRuntimeExceptionStillClosesRtp() throws Exception {
        IInviteStreamService invites = mock(IInviteStreamService.class);
        ISIPCommander commander = mock(ISIPCommander.class);
        IDeviceChannelService channels = mock(IDeviceChannelService.class);
        IReceiveRtpServerService rtp = mock(IReceiveRtpServerService.class);
        UserSetting settings = mock(UserSetting.class);
        PlayServiceImpl service = service(invites, commander, channels, rtp, settings, mock(SipInviteSessionManager.class));

        Device device = device();
        DeviceChannel channel = channel();
        InviteInfo invite = invite(device, channel);
        Integer channelId = channel.getId();
        String channelDeviceId = channel.getDeviceId();
        when(invites.getInviteInfo(InviteSessionType.PLAY, channelId, "stream-1")).thenReturn(invite);
        doThrow(new IllegalStateException("bye failed")).when(commander)
                .streamByeCmd(device, channelDeviceId, MediaStreamUtil.RTP_APP, "stream-1", null, null);

        assertDoesNotThrow(() -> service.stop(InviteSessionType.PLAY, device, channel, "stream-1"));

        verify(channels).stopPlay(channel.getId());
        verify(rtp).closeRtpResource(invite.getSsrcInfo());
    }

    @Test
    void inviteRemovalFailureDoesNotSkipRtpCleanup() {
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IReceiveRtpServerService rtp = mock(IReceiveRtpServerService.class);
        PlayServiceImpl service = service(invites, mock(ISIPCommander.class), mock(IDeviceChannelService.class),
                rtp, mock(UserSetting.class), mock(SipInviteSessionManager.class));

        Device device = device();
        DeviceChannel channel = channel();
        InviteInfo invite = invite(device, channel);
        when(invites.getInviteInfo(InviteSessionType.PLAY, channel.getId(), "stream-1")).thenReturn(invite);
        doThrow(new IllegalStateException("redis failed")).when(invites).removeInviteInfo(invite);

        assertDoesNotThrow(() -> service.stop(InviteSessionType.PLAY, device, channel, "stream-1"));

        verify(rtp).closeRtpResource(invite.getSsrcInfo());
    }

    @Test
    void missingDeviceOrChannelStillRemovesInviteAndClosesRtp() {
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IDeviceChannelService channels = mock(IDeviceChannelService.class);
        IDeviceService devices = mock(IDeviceService.class);
        IReceiveRtpServerService rtp = mock(IReceiveRtpServerService.class);
        PlayServiceImpl service = service(invites, mock(ISIPCommander.class), channels, rtp,
                mock(UserSetting.class), mock(SipInviteSessionManager.class));
        ReflectionTestUtils.setField(service, "deviceService", devices);

        InviteInfo invite = invite(device(), channel());
        when(channels.getOneForSourceById(invite.getChannelId())).thenReturn(null);

        assertDoesNotThrow(() -> service.stop(invite));

        verify(invites).removeInviteInfo(invite);
        verify(rtp).closeRtpResource(invite.getSsrcInfo());
        verifyNoInteractions(devices);
    }

    @Test
    void stopPlayFailureStillRemovesOwnedSessionBeforeRtpClose() {
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IDeviceChannelService channels = mock(IDeviceChannelService.class);
        IReceiveRtpServerService rtp = mock(IReceiveRtpServerService.class);
        SipInviteSessionManager sessions = mock(SipInviteSessionManager.class);
        PlayServiceImpl service = service(invites, mock(ISIPCommander.class), channels, rtp,
                mock(UserSetting.class), sessions);

        Device device = device();
        DeviceChannel channel = channel();
        InviteInfo invite = invite(device, channel);
        SsrcTransaction transaction = transaction(device, channel, invite);
        Integer channelId = channel.getId();
        when(invites.getInviteInfo(InviteSessionType.PLAY, channelId, "stream-1")).thenReturn(invite);
        when(sessions.getSsrcTransactionByStream(MediaStreamUtil.RTP_APP, "stream-1")).thenReturn(transaction);
        doThrow(new IllegalStateException("state reset failed")).when(channels).stopPlay(channelId);

        assertDoesNotThrow(() -> service.stop(InviteSessionType.PLAY, device, channel, "stream-1"));

        verify(sessions).removeByStream(MediaStreamUtil.RTP_APP, "stream-1");
        verify(rtp).closeRtpResource(invite.getSsrcInfo());
    }

    @Test
    void rtpCloseFailureDoesNotHideSessionCleanup() {
        IInviteStreamService invites = mock(IInviteStreamService.class);
        IReceiveRtpServerService rtp = mock(IReceiveRtpServerService.class);
        SipInviteSessionManager sessions = mock(SipInviteSessionManager.class);
        PlayServiceImpl service = service(invites, mock(ISIPCommander.class), mock(IDeviceChannelService.class),
                rtp, mock(UserSetting.class), sessions);

        Device device = device();
        DeviceChannel channel = channel();
        InviteInfo invite = invite(device, channel);
        SsrcTransaction transaction = transaction(device, channel, invite);
        when(invites.getInviteInfo(InviteSessionType.PLAY, channel.getId(), "stream-1")).thenReturn(invite);
        when(sessions.getSsrcTransactionByStream(MediaStreamUtil.RTP_APP, "stream-1"))
                .thenReturn(transaction);
        SSRCInfo ssrcInfo = invite.getSsrcInfo();
        doThrow(new IllegalStateException("rtp close failed")).when(rtp).closeRtpResource(ssrcInfo);

        assertDoesNotThrow(() -> service.stop(InviteSessionType.PLAY, device, channel, "stream-1"));

        verify(sessions).removeByStream(MediaStreamUtil.RTP_APP, "stream-1");
    }

    @Test
    void stalePlayFailureDoesNotRemoveReplacementInvite() {
        IInviteStreamService invites = mock(IInviteStreamService.class);
        PlayServiceImpl service = service(invites, mock(ISIPCommander.class), mock(IDeviceChannelService.class),
                mock(IReceiveRtpServerService.class), mock(UserSetting.class), mock(SipInviteSessionManager.class));
        Device device = device();
        DeviceChannel channel = channel();
        InviteInfo replacement = invite(device, channel);
        replacement.getSsrcInfo().setResourceId("new-resource");
        SSRCInfo staleOwner = new SSRCInfo(1235, "00000002", MediaStreamUtil.RTP_APP, "stream-1");
        staleOwner.setResourceId("old-resource");
        when(invites.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, channel.getId()))
                .thenReturn(replacement);

        ReflectionTestUtils.invokeMethod(service, "removePlayInviteInfoIfOwned", channel.getId(), staleOwner);

        verify(invites, never()).removeInviteInfo(replacement);
    }

    private static PlayServiceImpl service(IInviteStreamService invites, ISIPCommander commander,
                                           IDeviceChannelService channels, IReceiveRtpServerService rtp,
                                           UserSetting settings, SipInviteSessionManager sessions) {
        PlayServiceImpl service = new PlayServiceImpl();
        ReflectionTestUtils.setField(service, "inviteStreamService", invites);
        ReflectionTestUtils.setField(service, "cmder", commander);
        ReflectionTestUtils.setField(service, "deviceChannelService", channels);
        ReflectionTestUtils.setField(service, "receiveRtpServerService", rtp);
        ReflectionTestUtils.setField(service, "userSetting", settings);
        ReflectionTestUtils.setField(service, "sessionManager", sessions);
        when(settings.getServerId()).thenReturn("server-1");
        return service;
    }

    private static Device device() {
        Device device = new Device();
        device.setServerId("server-1");
        device.setDeviceId("device-1");
        return device;
    }

    private static DeviceChannel channel() {
        DeviceChannel channel = new DeviceChannel();
        channel.setId(1);
        channel.setDeviceId("channel-1");
        channel.setDataDeviceId(1);
        return channel;
    }

    private static InviteInfo invite(Device device, DeviceChannel channel) {
        SSRCInfo ssrcInfo = new SSRCInfo(1234, "00000001", MediaStreamUtil.RTP_APP, "stream-1");
        ssrcInfo.setResourceId("resource-1");
        ssrcInfo.setMediaServerId("media-1");
        InviteInfo invite = InviteInfo.getInviteInfo(device.getDeviceId(), channel.getId(), "stream-1",
                ssrcInfo, "media-1", "127.0.0.1", 1234, "UDP", InviteSessionType.PLAY,
                InviteSessionStatus.ok);
        return invite;
    }

    private static SsrcTransaction transaction(Device device, DeviceChannel channel, InviteInfo invite) {
        SsrcTransaction transaction = new SsrcTransaction();
        transaction.setDeviceId(device.getDeviceId());
        transaction.setChannelId(channel.getId());
        transaction.setType(invite.getType());
        transaction.setApp(invite.getSsrcInfo().getApp());
        transaction.setStream(invite.getSsrcInfo().getStream());
        transaction.setSsrc(invite.getSsrcInfo().getSsrc());
        transaction.setMediaServerId(invite.getMediaServerId());
        transaction.setCallId("call-1");
        return transaction;
    }
}
