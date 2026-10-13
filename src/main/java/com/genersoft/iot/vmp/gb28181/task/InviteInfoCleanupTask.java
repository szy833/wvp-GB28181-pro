package com.genersoft.iot.vmp.gb28181.task;

import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.gb28181.service.IPlayService;
import com.genersoft.iot.vmp.gb28181.service.impl.InviteRepository;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persistent round-robin work list; no full Invite/media index scan on a scheduler thread. */
@Slf4j
@Component
public class InviteInfoCleanupTask {
    private final InviteRepository repository;
    private final IMediaServerService media;
    private final IPlayService play;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "invite-reconcile");
        thread.setDaemon(true);
        return thread;
    });
    public InviteInfoCleanupTask(InviteRepository repository, IMediaServerService media, IPlayService play) {
        this.repository = repository; this.media = media; this.play = play;
    }
    @Scheduled(initialDelay = 1000, fixedDelay = 1000)
    public void execute() {
        if (!running.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    for (InviteInfo invite : repository.reconciliationBatch(100)) {
                        try {
                            if (invite.getLifecycle() != InviteLifecycle.ACTIVE) continue;
                            MediaServer node = media.getOne(invite.getMediaServerId());
                            if (node == null || !node.isStatus()) continue;
                            Boolean ready = media.probeStream(node, "rtp", invite.getStream());
                            if (Boolean.FALSE.equals(ready)) play.stopIfOwner(invite);
                        } catch (RuntimeException unknown) {
                            log.warn("[Invite对账] 探测未知，保留资源等待下一轮：inviteId={}", invite.getInviteId(), unknown);
                        }
                    }
                } catch (RuntimeException failure) {
                    log.warn("[Invite对账] 读取待办失败", failure);
                } finally { running.set(false); }
            });
        } catch (RejectedExecutionException failure) { running.set(false); }
    }
    @PreDestroy public void close() { worker.shutdownNow(); }
}
