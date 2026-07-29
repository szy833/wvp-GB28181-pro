package com.genersoft.iot.vmp.gb28181.task;

import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Removes sessions left behind by a crashed SIP/RTP request. */
@Slf4j
@Component
public class SipInviteSessionCleanupTask {

    @Autowired
    private SipInviteSessionManager sessionManager;

    @Scheduled(fixedDelay = 30_000)
    public void execute() {
        try {
            int removed = sessionManager.cleanupExpiredSessions(200);
            if (removed > 0) {
                log.info("[SIP Invite会话] 本轮过期清理 {} 条", removed);
            }
        } catch (RuntimeException e) {
            log.warn("[SIP Invite会话] 过期清理失败，等待下一轮重试", e);
        }
    }
}
