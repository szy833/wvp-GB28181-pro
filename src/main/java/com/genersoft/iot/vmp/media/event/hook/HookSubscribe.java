package com.genersoft.iot.vmp.media.event.hook;

import com.genersoft.iot.vmp.media.event.media.*;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * zlm hook事件的参数
 * @author lin
 */
@Component
public class HookSubscribe {

    /**
     * 订阅数据过期时间
     */
    private final long subscribeExpire = 5 * 60 * 1000;


    @FunctionalInterface
    public interface Event{
        void response(HookData data);
    }

    /**
     * 流到来的处理
     */
    @Async
    @EventListener
    public void onApplicationEvent(MediaArrivalEvent event) {
        if (event.getSchema() == null || "rtsp".equals(event.getSchema())) {
            sendNotify(HookType.on_media_arrival, event);
        }

    }

    /**
     * 流结束事件
     */
    @Async
    @EventListener
    public void onApplicationEvent(MediaDepartureEvent event) {
        if (event.getSchema() == null || "rtsp".equals(event.getSchema())) {
            sendNotify(HookType.on_media_departure, event);
        }

    }
    /**
     * 推流鉴权事件
     */
    @Async
    @EventListener
    public void onApplicationEvent(MediaPublishEvent event) {
        sendNotify(HookType.on_publish, event);
    }
    /**
     * 生成录像文件事件
     */
    @Async
    @EventListener
    public void onApplicationEvent(MediaRecordMp4Event event) {
        sendNotify(HookType.on_record_mp4, event);
    }

    private final Map<String, Event> allSubscribes = new ConcurrentHashMap<>();
    private final Map<String, Hook> allHook = new ConcurrentHashMap<>();

    private void sendNotify(HookType hookType, MediaEvent event) {
        String mediaServerId = event.getMediaServer() == null ? null : event.getMediaServer().getId();
        Hook paramHook = Hook.getInstance(hookType, event.getApp(), event.getStream(), mediaServerId);
        Event hookSubscribeEvent = allSubscribes.get(paramHook.toString());
        if (hookSubscribeEvent == null && event.getMediaServer() != null
                && event.getMediaServer().getServerId() != null) {
            // Some legacy integrations keyed hooks by the WVP server ID.
            paramHook = Hook.getInstance(hookType, event.getApp(), event.getStream(),
                    event.getMediaServer().getServerId());
            hookSubscribeEvent = allSubscribes.get(paramHook.toString());
        }
        if (hookSubscribeEvent == null && mediaServerId != null) {
            // Legacy subscriptions created before media-server-aware keys.
            paramHook = Hook.getInstance(hookType, event.getApp(), event.getStream());
            hookSubscribeEvent = allSubscribes.get(paramHook.toString());
        }
        if (hookSubscribeEvent != null) {
            HookData data = HookData.getInstance(event);
            hookSubscribeEvent.response(data);
        }
    }

    public void addSubscribe(Hook hook, HookSubscribe.Event event) {
        if (hook == null || event == null) {
            return;
        }
        if (hook.getExpireTime() == null) {
            hook.setExpireTime(System.currentTimeMillis() + subscribeExpire);
        }
        synchronized (allSubscribes) {
            // Legacy callers do not have an owner handle; never let them
            // overwrite a callback that another request already owns.
            if (allSubscribes.putIfAbsent(hook.toString(), event) == null) {
                allHook.putIfAbsent(hook.toString(), hook);
            }
        }
    }

    public HookSubscriptionHandle addSubscribeWithHandle(Hook hook, HookSubscribe.Event event) {
        if (hook == null || event == null) {
            throw new IllegalArgumentException("hook and event are required");
        }
        if (hook.getExpireTime() == null) {
            hook.setExpireTime(System.currentTimeMillis() + subscribeExpire);
        }
        synchronized (allSubscribes) {
            if (allSubscribes.containsKey(hook.toString())) {
                throw new IllegalStateException("Hook already subscribed: " + hook);
            }
            allSubscribes.put(hook.toString(), event);
            allHook.put(hook.toString(), hook);
        }
        return new HookSubscriptionHandle(hook.toString(), event, hook);
    }

    public void removeSubscribe(Hook hook) {
        if (hook == null) {
            return;
        }
        allSubscribes.remove(hook.toString());
        allHook.remove(hook.toString());
    }

    public boolean removeSubscribe(HookSubscriptionHandle handle) {
        if (handle == null) {
            return false;
        }
        boolean removed = allSubscribes.remove(handle.getKey(), handle.getEvent());
        if (removed) {
            if (handle.getHook() != null) {
                allHook.remove(handle.getKey(), handle.getHook());
            }
        }
        return removed;
    }

    public boolean removeSubscribe(Hook hook, Event expectedEvent) {
        if (hook == null || expectedEvent == null) {
            return false;
        }
        boolean removed = allSubscribes.remove(hook.toString(), expectedEvent);
        if (removed) {
            allHook.remove(hook.toString(), hook);
        }
        return removed;
    }

    /**
     * 对订阅数据进行过期清理
     */
    @Scheduled(fixedRate=subscribeExpire)   //每5分钟执行一次
    public void execute(){
        long expireTime = System.currentTimeMillis();
        for (Hook hook : allHook.values()) {
            if (hook.getExpireTime() < expireTime) {
                synchronized (allSubscribes) {
                    if (allHook.get(hook.toString()) == hook) {
                        Event event = allSubscribes.get(hook.toString());
                        allSubscribes.remove(hook.toString(), event);
                        allHook.remove(hook.toString(), hook);
                    }
                }
            }
        }
    }

    public List<Hook> getAll() {
        return new ArrayList<>(allHook.values());
    }
}
