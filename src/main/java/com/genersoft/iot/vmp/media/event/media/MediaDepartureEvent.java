package com.genersoft.iot.vmp.media.event.media;

import com.genersoft.iot.vmp.media.abl.bean.hook.ABLHookParam;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.zlm.dto.hook.OnStreamChangedHookParam;

/**
 * 流离开事件
 */
public class MediaDepartureEvent extends MediaEvent {
    private String originUrl;

    public MediaDepartureEvent(Object source) {
        super(source);
    }

    public static MediaDepartureEvent getInstance(Object source, OnStreamChangedHookParam hookParam, MediaServer mediaServer){
        MediaDepartureEvent mediaDepartureEven = new MediaDepartureEvent(source);
        mediaDepartureEven.setApp(hookParam.getApp());
        mediaDepartureEven.setStream(hookParam.getStream());
        mediaDepartureEven.setSchema(hookParam.getSchema());
        mediaDepartureEven.setMediaServer(mediaServer);
        mediaDepartureEven.setOriginUrl(hookParam.getOriginUrl());
        return mediaDepartureEven;
    }

    public static MediaDepartureEvent getInstance(Object source, ABLHookParam hookParam, MediaServer mediaServer){
        MediaDepartureEvent mediaDepartureEven = new MediaDepartureEvent(source);
        mediaDepartureEven.setApp(hookParam.getApp());
        mediaDepartureEven.setStream(hookParam.getStream());
        mediaDepartureEven.setMediaServer(mediaServer);
        return mediaDepartureEven;
    }

    public String getOriginUrl() {
        return originUrl;
    }

    public void setOriginUrl(String originUrl) {
        this.originUrl = originUrl;
    }
}
