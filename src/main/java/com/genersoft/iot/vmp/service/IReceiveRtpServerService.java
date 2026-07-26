package com.genersoft.iot.vmp.service;

import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.bean.ResultForOnPublish;
import com.genersoft.iot.vmp.media.event.hook.HookData;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import com.genersoft.iot.vmp.service.bean.RTPServerParam;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import com.genersoft.iot.vmp.service.bean.RtpServerOpenResult;

public interface IReceiveRtpServerService {

    SSRCInfo openGbRTPServer(MediaServer mediaServer, String streamId, String presetSSRC, int tcpMode,
                             boolean playback, boolean ssrcCheck, boolean onlyAuto, boolean disableAuto,
                             ErrorCallback<OpenRTPServerResult> callback);

    SSRCInfo openGbRTPServerForPlay(MediaServer mediaServer, Device device, DeviceChannel channel,
                                    String presetSSRC, boolean record, ErrorCallback<OpenRTPServerResult> callback);

    SSRCInfo openGbRTPServerForPlayback(MediaServer mediaServer, Device device, DeviceChannel channel,
                                        String startTime, String endTime, ErrorCallback<OpenRTPServerResult> callback);

    SSRCInfo openGbRTPServerForDownload(MediaServer mediaServer, Device device, DeviceChannel channel,
                                        String startTime, String endTime, ErrorCallback<OpenRTPServerResult> callback);

    String getPlaybackStream(Device device, DeviceChannel channel, String startTime, String endTime);

    SSRCInfo openGbRTPServerForBroadcast(MediaServer mediaServer, Platform platform, CommonGBChannel channel,
                                         ErrorCallback<OpenRTPServerResult> callback);

    int openCommonRTPServer(RTPServerParam rtpServerParam, ErrorCallback<HookData> callback);

    default RtpServerOpenResult openCommonRTPServerWithHandle(RTPServerParam rtpServerParam, ErrorCallback<HookData> callback) {
        int port = openCommonRTPServer(rtpServerParam, callback);
        return new RtpServerOpenResult(port, null, rtpServerParam == null ? null : rtpServerParam.getStreamId(),
                rtpServerParam == null ? null : rtpServerParam.getStreamId());
    }

    default void closeRTPServer(RtpServerOpenResult result) {
        if (result != null && result.getContext() != null) {
            result.getContext().close("external close");
        }
    }

    default void closeRTPServer(SSRCInfo info) {
        if (info != null && info.getResourceId() != null) {
            // Resource IDs are owner handles. Never downgrade a stale owner to
            // the unqualified media-server/app/stream close path.
            closeRTPServer(new RtpServerOpenResult(info.getPort(), info.getResourceId(), info.getStream(), info.getZlmStream()));
        } else if (info != null && info.getMediaServerId() != null && info.getZlmStream() != null) {
            closeRTPServerByMediaServerId(info.getMediaServerId(), info.getApp(), info.getZlmStream());
        }
    }

    void closeRTPServer(MediaServer mediaServer, String app, String stream);

    void closeRTPServerByMediaServerId(String mediaServerId, String app, String stream);

    void addAuthenticateInfoForGb28181Talk(MediaServer mediaServer, String streamId);

    void addAuthenticateInfo(String streamId, String streamReplace, Boolean enableAudio, Boolean enableMp4, Integer mp4MaxSecond);

    default void addAuthenticateInfo(RtpServerOpenResult result, String streamId, String streamReplace,
                                     Boolean enableAudio, Boolean enableMp4, Integer mp4MaxSecond) {
        if (result != null && result.isSuccess()) {
            addAuthenticateInfo(streamId, streamReplace, enableAudio, enableMp4, mp4MaxSecond);
        }
    }

    ResultForOnPublish getAuthenticateInfo(String streamId);

    void refreshAuthenticateInfo(String oldStreamId, String newStreamId);

    default void refreshAuthenticateInfo(SSRCInfo info, String oldStreamId, String newStreamId) {
        refreshAuthenticateInfo(oldStreamId, newStreamId);
    }
}
