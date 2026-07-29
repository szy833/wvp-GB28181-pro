package com.genersoft.iot.vmp.gb28181.task;

import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Incrementally migrates legacy SIP Invite Hash records into the V2 indexes. */
@Slf4j
@Component
public class SipInviteSessionMigrationTask {

    @Autowired
    private SipInviteSessionManager sessionManager;

    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    public void execute() {
        try {
            int migrated = sessionManager.migrateLegacyBatch(500);
            if (migrated > 0) {
                log.info("[SIP Invite迁移] 本轮迁移 {} 条旧会话", migrated);
            }
        } catch (RuntimeException e) {
            log.warn("[SIP Invite迁移] 本轮迁移失败，等待下一轮重试", e);
        }
    }
}
