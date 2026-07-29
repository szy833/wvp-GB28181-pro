package com.genersoft.iot.vmp.gb28181.task;

import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds InviteInfo indexes in the background while legacy primary-Hash fallback remains enabled.
 */
@Component
public class InviteInfoIndexBackfillTask {

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    private IInviteStreamService inviteStreamService;

    @Autowired
    private TaskExecutor taskExecutor;

    @Scheduled(initialDelay = 5_000, fixedDelay = 60_000)
    public void scheduleBackfill() {
        if (inviteStreamService.inviteIndexesReady() || inviteStreamService.inviteIndexesBackfilled()
                || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            taskExecutor.execute(() -> {
                try {
                    inviteStreamService.rebuildInviteIndexes();
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            // Releasing the guard here allows the next scheduled tick to retry after rejection.
            running.set(false);
        }
    }
}
