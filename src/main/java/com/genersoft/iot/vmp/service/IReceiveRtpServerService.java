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
        closeRtpResource(info);
    }

    /**
     * Closes an RTP resource by owner handle and reports whether a resource was
     * actually claimed. A stale owner must not fall back to an unqualified stream.
     */
    default boolean closeRtpResource(SSRCInfo info) {
        if (info != null && info.getResourceId() != null) {
            // Resource IDs are owner handles. Never downgrade a stale owner to
            // the unqualified media-server/app/stream close path.
            closeRTPServer(new RtpServerOpenResult(info.getPort(), info.getResourceId(), info.getStream(), info.getZlmStream()));
            return false;
        } else if (info != null && info.getMediaServerId() != null && info.getZlmStream() != null) {
            return closeRTPServerByZlmStreamId(info.getMediaServerId(), info.getApp(), info.getZlmStream());
        }
        return false;
    }

    void closeRTPServer(MediaServer mediaServer, String app, String stream);

    void closeRTPServerByMediaServerId(String mediaServerId, String app, String stream);

    /**
     * Closes the ZLM RTP listener identified by its actual listener stream id.
     * This is intentionally separate from the published/business stream.
     */
    default boolean closeRTPServerByZlmStream(MediaServer mediaServer, String app, String zlmStream) {
        closeRTPServer(mediaServer, app, zlmStream);
        return mediaServer != null && zlmStream != null;
    }

    /**
     * Closes a published stream by business id without guessing a ZLM listener id.
     */
    default boolean closeRTPServerByBusinessStream(MediaServer mediaServer, String app, String businessStream) {
        closeRTPServer(mediaServer, app, businessStream);
        return mediaServer != null && businessStream != null;
    }

    /**
     * Cleans a legacy published stream only when no tracked RTP owner claims it.
     * This is for stale records and must not terminate a newer owner.
     */
    default boolean closeRTPServerByBusinessStreamIfUnowned(MediaServer mediaServer, String app,
                                                              String businessStream) {
        // Implementations must perform an owner check; do not downgrade to
        // the normal business-stream close when that contract is unavailable.
        return false;
    }

    default boolean closeRTPServerByZlmStreamId(String mediaServerId, String app, String zlmStream) {
        return false;
    }

    /**
     * Legacy id-only cleanup. Implementations must treat the stream as
     * unowned and avoid closing a newer owner that reused the business id.
     */
    default boolean closeRTPServerByBusinessStreamId(String mediaServerId, String app, String businessStream) {
        return false;
    }

    /**
     * Reconciles a legacy transaction by deriving and validating its ZLM stream
     * from the decimal SSRC before attempting a listener close.
     */
    default boolean closeRTPServerBySsrcId(String mediaServerId, String app, String ssrc) {
        return false;
    }

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
