package com.genersoft.iot.vmp.conf;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 动态定时任务
 * @author lin
 */
@Slf4j
@Component
public class DynamicTask {

    @Autowired
    private TaskScheduler taskScheduler;

    private final Map<String, ScheduledFuture<?>> futureMap = new ConcurrentHashMap<>();
    private final Map<String, Runnable> runnableMap = new ConcurrentHashMap<>();
    // Fixed stripes keep per-task lifecycle locking bounded under UUID-heavy workloads.
    private static final int OWNER_LOCK_STRIPES = 64;
    private final Object[] ownerLocks = new Object[OWNER_LOCK_STRIPES];

    public DynamicTask() {
        for (int i = 0; i < ownerLocks.length; i++) {
            ownerLocks[i] = new Object();
        }
    }

    private Object lockFor(String key) {
        return ownerLocks[(key.hashCode() & Integer.MAX_VALUE) % OWNER_LOCK_STRIPES];
    }


    /**
     * 循环执行的任务
     * @param key 任务ID
     * @param task 任务
     * @param cycleForCatalog 间隔 毫秒
     * @return
     */
    public void startCron(String key, Runnable task, int cycleForCatalog) {
        if(ObjectUtils.isEmpty(key)) {
            return;
        }
        ScheduledFuture<?> future = futureMap.get(key);
        if (future != null) {
            if (future.isCancelled()) {
                log.debug("任务【{}】已存在但是关闭状态！！！", key);
            } else {
                log.debug("任务【{}】已存在且已启动！！！", key);
                return;
            }
        }
        // scheduleWithFixedDelay 必须等待上一个任务结束才开始计时period， cycleForCatalog表示执行的间隔

        future = taskScheduler.scheduleAtFixedRate(task, new Date(System.currentTimeMillis() + cycleForCatalog), cycleForCatalog);
        if (future != null){
            futureMap.put(key, future);
            runnableMap.put(key, task);
            log.debug("任务【{}】启动成功！！！", key);
        }else {
            log.debug("任务【{}】启动失败！！！", key);
        }
    }

    /**
     * 延时任务
     * @param key 任务ID
     * @param task 任务
     * @param delay 延时 /毫秒
     * @return
     */
    public void startDelay(String key, Runnable task, int delay) {
        startDelayWithHandle(key, task, delay);
    }

    /** Starts a one-shot task and returns the owner handle used for conditional cleanup. */
    public ScheduledFuture<?> startDelayWithHandle(String key, Runnable task, int delay) {
        if (ObjectUtils.isEmpty(key) || task == null) {
            return null;
        }
        Object lock = lockFor(key);
        synchronized (lock) {
            ScheduledFuture<?> previous = futureMap.remove(key);
            if (previous != null) {
                runnableMap.remove(key);
                previous.cancel(false);
            }
            Instant startInstant = Instant.now().plusMillis(TimeUnit.MILLISECONDS.toMillis(delay));
            ScheduledFuture<?> future = taskScheduler.schedule(task, startInstant);
            if (future != null) {
                futureMap.put(key, future);
                runnableMap.put(key, task);
                log.debug("任务【{}】启动成功！！！", key);
            } else {
                log.debug("任务【{}】启动失败！！！", key);
            }
            return future;
        }
    }

    public boolean stop(String key) {
        if(ObjectUtils.isEmpty(key)) {
            return false;
        }
        ScheduledFuture<?> future = futureMap.get(key);
        if (future == null) {
            return false;
        }
        return stop(key, future);
    }

    /** Stops only the future that still owns the key. */
    public boolean stop(String key, ScheduledFuture<?> expectedFuture) {
        if (ObjectUtils.isEmpty(key) || expectedFuture == null) {
            return false;
        }
        Object lock = lockFor(key);
        synchronized (lock) {
            if (!futureMap.remove(key, expectedFuture)) {
                return false;
            }
            runnableMap.remove(key);
            // cancel can return false for an already completed future; ownership was
            // nevertheless removed, which is the important lifecycle guarantee.
            expectedFuture.cancel(false);
            return true;
        }
    }

    public boolean contains(String key) {
        if(ObjectUtils.isEmpty(key)) {
            return false;
        }
        return futureMap.get(key) != null;
    }

    public Set<String> getAllKeys() {
        return futureMap.keySet();
    }

    public Runnable get(String key) {
        if(ObjectUtils.isEmpty(key)) {
            return null;
        }
        return runnableMap.get(key);
    }

    /**
     * 每五分钟检查失效的任务，并移除
     */
    @Scheduled(cron="0 0/5 * * * ?")
    public void execute(){
        if (futureMap.size() > 0) {
            for (String key : futureMap.keySet()) {
                Object lock = lockFor(key);
                synchronized (lock) {
                    ScheduledFuture<?> future = futureMap.get(key);
                    if (future != null && (future.isDone() || future.isCancelled())) {
                        if (futureMap.remove(key, future)) {
                            runnableMap.remove(key);
                        }
                    }
                }
            }
        }
    }

    public boolean isAlive(String key) {
        return futureMap.get(key) != null && !futureMap.get(key).isDone() && !futureMap.get(key).isCancelled();
    }
}
