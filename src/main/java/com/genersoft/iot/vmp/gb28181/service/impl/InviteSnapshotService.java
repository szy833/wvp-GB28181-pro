package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteLifecycle;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.concurrent.*;

/** Optional thumbnails cannot block or fail Invite activation. */
@Slf4j
@Service
public class InviteSnapshotService {
    private final InviteRepository repository;
    private final IMediaServerService media;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "invite-snapshot"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public InviteSnapshotService(InviteRepository repository, IMediaServerService media) {
        this.repository = repository; this.media = media;
    }

    public void submit(InviteInfo invite) {
        if (invite == null || invite.getType() != InviteSessionType.PLAY) return;
        try {
            worker.execute(() -> {
                try {
                    InviteInfo current = repository.get(invite.getInviteId());
                    if (current == null || current.getLifecycle() != InviteLifecycle.ACTIVE) return;
                    media.getSnap(media.getOne(current.getMediaServerId()), "rtp", current.getStream(), 15, 1,
                            "snap", current.getDeviceId() + "_" + current.getRemoteChannelId() + ".jpg");
                } catch (RuntimeException failure) {
                    log.warn("[Invite截图] 可选截图失败，不改变播放结果：inviteId={}", invite.getInviteId(), failure);
                }
            });
        } catch (RejectedExecutionException saturated) {
            log.debug("[Invite截图] 截图队列已满或关闭，跳过可选任务：inviteId={}", invite.getInviteId());
        }
    }

    @PreDestroy public void close() { worker.shutdownNow(); }
}
