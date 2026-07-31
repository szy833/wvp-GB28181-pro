package com.genersoft.iot.vmp.conf.redis;

import com.alibaba.fastjson2.JSON;
import com.genersoft.iot.vmp.common.CommonCallback;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.redis.bean.RedisRpcClassHandler;
import com.genersoft.iot.vmp.conf.redis.bean.RedisRpcMessage;
import com.genersoft.iot.vmp.conf.redis.bean.RedisRpcRequest;
import com.genersoft.iot.vmp.conf.redis.bean.RedisRpcResponse;
import com.genersoft.iot.vmp.service.redisMsg.dto.RpcController;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class RedisRpcConfig implements MessageListener {

    public final static String REDIS_REQUEST_CHANNEL_KEY = "WVP_REDIS_REQUEST_CHANNEL_KEY";

    @Autowired
    private UserSetting userSetting;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private ConcurrentLinkedQueue<Message> taskQueue = new ConcurrentLinkedQueue<>();

    @Autowired
    private TaskExecutor taskExecutor;

    private final static Map<String, RedisRpcClassHandler> protocolHash = new HashMap<>();

    public void addHandler(String path, RedisRpcClassHandler handler) {
        protocolHash.put(path, handler);
    }

//    @Override
//    public void run(String... args) throws Exception {
//        List<Class<?>> classList = ClassUtil.getClassList("com.genersoft.iot.vmp.service.redisMsg.control", RedisRpcController.class);
//        for (Class<?> handlerClass : classList) {
//            String controllerPath = handlerClass.getAnnotation(RedisRpcController.class).value();
//            Object bean = ClassUtil.getBean(controllerPath, handlerClass);
//            // 扫描其下的方法
//            Method[] methods = handlerClass.getDeclaredMethods();
//            for (Method method : methods) {
//                RedisRpcMapping annotation = method.getAnnotation(RedisRpcMapping.class);
//                if (annotation != null) {
//                    String methodPath =  annotation.value();
//                    if (methodPath != null) {
//                        protocolHash.put(controllerPath + "/" + methodPath, new RedisRpcClassHandler(bean, method));
//                    }
//                }
//
//            }
//
//        }
//        for (String s : protocolHash.keySet()) {
//            System.out.println(s);
//        }
//        if (log.isDebugEnabled()) {
//            log.debug("消息ID缓存表 protocolHash:{}", protocolHash);
//        }
//    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        boolean isEmpty = taskQueue.isEmpty();
        taskQueue.offer(message);
        if (isEmpty) {
            taskExecutor.execute(() -> {
                while (!taskQueue.isEmpty()) {
                    Message msg = taskQueue.poll();
                    try {
                        RedisRpcMessage redisRpcMessage = JSON.parseObject(new String(msg.getBody()), RedisRpcMessage.class);
                        if (redisRpcMessage.getRequest() != null) {
                            handlerRequest(redisRpcMessage.getRequest());
                        } else if (redisRpcMessage.getResponse() != null){
                            handlerResponse(redisRpcMessage.getResponse());
                        } else {
                            log.error("[redis-rpc]解析失败 {}", JSON.toJSONString(redisRpcMessage));
                        }
                    } catch (Exception e) {
                        log.error("[redis-rpc]解析异常 {}",new String(msg.getBody()), e);
                    }
                }
            });
        }
    }

    private void handlerResponse(RedisRpcResponse response) {
        if (userSetting.getServerId().equals(response.getToId())) {
            return;
        }
        log.info("[redis-rpc] << {}", response);
        response(response);
    }

    private void handlerRequest(RedisRpcRequest request) {
        try {
            if (userSetting.getServerId().equals(request.getFromId())) {
                return;
            }
            log.info("[redis-rpc] << {}", request);
            RedisRpcClassHandler redisRpcClassHandler = protocolHash.get(request.getUri());
            if (redisRpcClassHandler == null) {
                log.error("[redis-rpc] 路径: {}不存在", request.getUri());
                return;
            }
            RpcController controller = redisRpcClassHandler.getController();
            Method method = redisRpcClassHandler.getMethod();
            // 没有携带目标ID的可以理解为哪个wvp有结果就哪个回复，携带目标ID，但是如果是不存在的uri则直接回复404
            if (userSetting.getServerId().equals(request.getToId())) {
                if (method == null) {
                    // 回复404结果
                    RedisRpcResponse response = request.getResponse();
                    response.setStatusCode(ErrorCode.ERROR404.getCode());
                    sendResponse(response);
                    return;
                }
                RedisRpcResponse response = (RedisRpcResponse)method.invoke(controller, request);
                if(response != null) {
                    sendResponse(response);
                }
            }else {
                if (method == null) {
                    // 回复404结果
                    RedisRpcResponse response = request.getResponse();
                    response.setStatusCode(ErrorCode.ERROR404.getCode());
                    sendResponse(response);
                    return;
                }
                RedisRpcResponse response = (RedisRpcResponse)method.invoke(controller, request);
                if (response != null) {
                    sendResponse(response);
                }
            }
        }catch (Exception e) {
            log.error("[redis-rpc ] 处理请求失败 ", e);
            RedisRpcResponse response = request.getResponse();
            response.setStatusCode(ErrorCode.ERROR100.getCode());
            sendResponse(response);
        }
    }

    private void sendResponse(RedisRpcResponse response){
        log.info("[redis-rpc] >> {}", response);
        response.setToId(userSetting.getServerId());
        RedisRpcMessage message = new RedisRpcMessage();
        message.setResponse(response);
        redisTemplate.convertAndSend(REDIS_REQUEST_CHANNEL_KEY, message);
    }

    private void sendRequest(RedisRpcRequest request){
        log.info("[redis-rpc] >> {}", request);
        RedisRpcMessage message = new RedisRpcMessage();
        message.setRequest(request);
        redisTemplate.convertAndSend(REDIS_REQUEST_CHANNEL_KEY, message);
    }

    private static final long MIN_REQUEST_SN = 1_000_000L;

    private final AtomicLong requestSequence = new AtomicLong(
            ThreadLocalRandom.current().nextLong(MIN_REQUEST_SN, Long.MAX_VALUE - 1));
    private final Map<Long, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService callbackTimeoutExecutor =
            Executors.newSingleThreadScheduledExecutor(new RedisRpcThreadFactory());

    private static final class RedisRpcThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "redis-rpc-timeout");
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class PendingRequest {
        private final SynchronousQueue<RedisRpcResponse> queue;
        private final CommonCallback<RedisRpcResponse> callback;
        private volatile ScheduledFuture<?> timeoutFuture;

        private PendingRequest(SynchronousQueue<RedisRpcResponse> queue,
                               CommonCallback<RedisRpcResponse> callback) {
            this.queue = queue;
            this.callback = callback;
        }

        private static PendingRequest synchronous() {
            return new PendingRequest(new SynchronousQueue<>(), null);
        }

        private static PendingRequest asynchronous(CommonCallback<RedisRpcResponse> callback) {
            return new PendingRequest(null, callback);
        }

        private void cancelTimeout() {
            ScheduledFuture<?> future = timeoutFuture;
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    public RedisRpcResponse request(RedisRpcRequest request, long timeOut) {
        return request(request, timeOut, TimeUnit.SECONDS);
    }

    public RedisRpcResponse request(RedisRpcRequest request, long timeOut, TimeUnit timeUnit) {
        PendingRequest pendingRequest = PendingRequest.synchronous();
        request.setSn(register(pendingRequest));

        try {
            sendRequest(request);
            RedisRpcResponse response = pendingRequest.queue.poll(timeOut, timeUnit);
            if (response == null) {
                return timeoutResponse(request);
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[redis rpc interrupted] uri: {}, sn: {}", request.getUri(), request.getSn());
            return timeoutResponse(request);
        } finally {
            removePending(request.getSn(), pendingRequest);
        }
    }

    public void request(RedisRpcRequest request, CommonCallback<RedisRpcResponse> callback) {
        request(request, callback, userSetting.getRedisRpcCallbackTtl(), TimeUnit.MILLISECONDS);
    }

    public void request(RedisRpcRequest request, CommonCallback<RedisRpcResponse> callback,
                        long timeOut, TimeUnit timeUnit) {
        PendingRequest pendingRequest = PendingRequest.asynchronous(callback);
        request.setSn(register(pendingRequest));
        long timeoutMillis = Math.max(1L, timeUnit.toMillis(timeOut));
        ScheduledFuture<?> timeoutFuture = callbackTimeoutExecutor.schedule(
                () -> expireCallback(request, pendingRequest), timeoutMillis, TimeUnit.MILLISECONDS);
        pendingRequest.timeoutFuture = timeoutFuture;
        if (pendingRequests.get(request.getSn()) != pendingRequest) {
            timeoutFuture.cancel(false);
            return;
        }
        try {
            sendRequest(request);
        } catch (RuntimeException e) {
            if (removePending(request.getSn(), pendingRequest)) {
                pendingRequest.cancelTimeout();
            }
            throw e;
        }
    }

    public Boolean response(RedisRpcResponse response) {
        if (response == null || !isResponseForCurrentServer(response)) {
            return false;
        }
        PendingRequest pendingRequest = pendingRequests.remove(response.getSn());
        if (pendingRequest == null) {
            return false;
        }
        pendingRequest.cancelTimeout();
        if (pendingRequest.queue != null) {
            try {
                return pendingRequest.queue.offer(response, 2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("{}", e.getMessage(), e);
            }
        } else if (pendingRequest.callback != null) {
            try {
                pendingRequest.callback.run(response);
            } catch (Exception e) {
                log.error("[redis-rpc] callback执行异常, sn: {}", response.getSn(), e);
            }
            return true;
        }
        return false;
    }

    private long register(PendingRequest pendingRequest) {
        while (true) {
            long sn = nextRequestSn();
            if (pendingRequests.putIfAbsent(sn, pendingRequest) == null) {
                return sn;
            }
        }
    }

    private long nextRequestSn() {
        long sn = requestSequence.incrementAndGet();
        if (sn > 0) {
            return sn;
        }
        requestSequence.compareAndSet(sn, MIN_REQUEST_SN);
        return requestSequence.incrementAndGet();
    }

    private RedisRpcResponse timeoutResponse(RedisRpcRequest request) {
        RedisRpcResponse response = request.getResponse();
        response.setStatusCode(ErrorCode.ERROR486.getCode());
        return response;
    }

    private boolean isResponseForCurrentServer(RedisRpcResponse response) {
        return userSetting.getServerId() != null
                && userSetting.getServerId().equals(response.getFromId());
    }

    private void expireCallback(RedisRpcRequest request, PendingRequest pendingRequest) {
        if (!removePending(request.getSn(), pendingRequest)) {
            return;
        }
        RedisRpcResponse response = timeoutResponse(request);
        try {
            pendingRequest.callback.run(response);
        } catch (Exception e) {
            log.error("[redis-rpc] callback超时处理异常, sn: {}", request.getSn(), e);
        }
    }

    private boolean removePending(long sn, PendingRequest pendingRequest) {
        boolean removed = pendingRequests.remove(sn, pendingRequest);
        if (removed) {
            pendingRequest.cancelTimeout();
        }
        return removed;
    }

    public void removeCallback(long key)  {
        PendingRequest pendingRequest = pendingRequests.remove(key);
        if (pendingRequest != null) {
            pendingRequest.cancelTimeout();
        }
    }


    public int getCallbackCount(){
        int count = 0;
        for (PendingRequest pendingRequest : pendingRequests.values()) {
            if (pendingRequest.callback != null) {
                count++;
            }
        }
        return count;
    }

    @PreDestroy
    private void shutdown() {
        callbackTimeoutExecutor.shutdownNow();
    }




//    @Scheduled(fixedRate = 1000)   //每1秒执行一次
//    public void execute(){
//        logger.info("pendingRequests的长度: " + pendingRequests.size());
//        logger.info("HOOK监听的长度: " + hookSubscribe.size());
//        logger.info("");
//    }
}
