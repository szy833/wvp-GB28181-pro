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

    /** True only for callers intentionally waiting on any media node. */
    private boolean global;

    private Long expireTime;


    public static Hook getInstance(HookType hookType, String app, String stream) {
        return getGlobalInstance(hookType, app, stream);
    }

    public static Hook getGlobalInstance(HookType hookType, String app, String stream) {
        Hook hookSubscribe = new Hook();
        hookSubscribe.setApp(app);
        hookSubscribe.setStream(stream);
        hookSubscribe.setHookType(hookType);
        hookSubscribe.setGlobal(true);
        hookSubscribe.setExpireTime(System.currentTimeMillis() + 5 * 60 * 1000);
        return hookSubscribe;
    }

    public static Hook getInstance(HookType hookType, String app, String stream, String mediaServer) {
        if (mediaServer == null || mediaServer.isBlank()) {
            throw new IllegalArgumentException("mediaServerId不能为空，需使用getGlobalInstance表示全局订阅");
        }
        Hook hook = new Hook();
        hook.setApp(app);
        hook.setStream(stream);
        hook.setHookType(hookType);
        hook.setMediaServerId(mediaServer);
        hook.setGlobal(false);
        hook.setExpireTime(System.currentTimeMillis() + 5 * 60 * 1000);
        return hook;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Hook) {
            Hook param = (Hook) obj;
            return java.util.Objects.equals(param.getHookType(), this.hookType)
                    && java.util.Objects.equals(param.getApp(), this.app)
                    && java.util.Objects.equals(param.getStream(), this.stream)
                    && java.util.Objects.equals(param.getMediaServerId(), this.mediaServerId)
                    && param.isGlobal() == this.global;
        }else {
            return false;
        }
    }

    @Override
    public String toString() {
        return this.getHookType() + ":" + (this.global ? "GLOBAL" : this.getMediaServerId())
                + ":" + this.getApp() + ":" + this.getStream();
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(hookType, mediaServerId, global, app, stream);
    }
}
