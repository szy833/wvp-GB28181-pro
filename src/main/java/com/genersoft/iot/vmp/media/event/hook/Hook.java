package com.genersoft.iot.vmp.media.event.hook;

import lombok.Getter;
import lombok.Setter;

/**
 * zlm hook事件的参数
 * @author lin
 */
@Getter
@Setter
public class Hook {

    private HookType hookType;

    private String app;

    private String stream;

    private String mediaServerId;

    private Long expireTime;


    public static Hook getInstance(HookType hookType, String app, String stream) {
        Hook hookSubscribe = new Hook();
        hookSubscribe.setApp(app);
        hookSubscribe.setStream(stream);
        hookSubscribe.setHookType(hookType);
        hookSubscribe.setExpireTime(System.currentTimeMillis() + 5 * 60 * 1000);
        return hookSubscribe;
    }

    public static Hook getInstance(HookType hookType, String app, String stream, String mediaServer) {
        Hook hook = Hook.getInstance(hookType, app, stream);
        hook.setMediaServerId(mediaServer);
        return hook;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Hook) {
            Hook param = (Hook) obj;
            return java.util.Objects.equals(param.getHookType(), this.hookType)
                    && java.util.Objects.equals(param.getApp(), this.app)
                    && java.util.Objects.equals(param.getStream(), this.stream)
                    && java.util.Objects.equals(param.getMediaServerId(), this.mediaServerId);
        }else {
            return false;
        }
    }

    @Override
    public String toString() {
        return this.getHookType() + ":" + this.getMediaServerId() + ":" + this.getApp() + ":" + this.getStream();
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(hookType, mediaServerId, app, stream);
    }
}
