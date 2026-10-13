package com.genersoft.iot.vmp.gb28181.task;

import com.genersoft.iot.vmp.gb28181.service.IPlayService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Scheduler only submits work; slow Redis/media operations run on a dedicated bounded worker. */
@Slf4j
@Component
public class InviteInfoPendingCleanupTask {
    private final IPlayService playService;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "invite-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    public InviteInfoPendingCleanupTask(IPlayService playService) { this.playService = playService; }

    @Scheduled(initialDelay = 1_000, fixedDelay = 1_000)
    public void schedule() {
        if (!running.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    int count = playService.retryPendingInviteCleanups(100);
                    if (count > 0) log.info("[Invite清理] 本轮补偿完成 {} 条", count);
                } catch (RuntimeException e) {
                    log.warn("[Invite清理] 读取待办失败，等待下一轮", e);
                } finally { running.set(false); }
            });
        } catch (RuntimeException e) {
            running.set(false);
            log.warn("[Invite清理] 补偿提交失败", e);
        }
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
