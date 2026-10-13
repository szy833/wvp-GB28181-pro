package com.genersoft.iot.vmp.gb28181.transmit.cmd.impl;

import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.gb28181.event.SipSubscribe;
import com.genersoft.iot.vmp.gb28181.service.impl.InviteRepository;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class InviteSipSessionCommitter {
    private InviteSipSessionCommitter() { }

    static boolean confirm(InviteRepository repository, SipInviteSessionManager sessions,
                           SsrcTransaction session, SipSubscribe.Event error) {
        try {
            // Keep the accepted dialog durable even if session indexing fails or STOPPING wins.
            repository.sipFinal(session.getInviteId(), session.getCallId(), true,
                    session.getSipTransactionInfo()).requireApplied();
            if (!sessions.put(session)) throw new IllegalStateException("SIP session commit was not confirmed");
            repository.sipSessionConfirmed(session.getInviteId(), session.getCallId()).requireApplied();
            return true;
        } catch (RuntimeException failure) {
            log.warn("[Invite SIP确认] 未完成：inviteId={}, callId={}", session.getInviteId(), session.getCallId(), failure);
            if (error == null) throw failure;
            SipSubscribe.EventResult<Object> result = new SipSubscribe.EventResult<>();
            result.type = SipSubscribe.EventResultType.failedResult;
            result.statusCode = -1;
            result.callId = session.getCallId();
            result.msg = "SIP session persistence failed";
            error.response(result);
            return false;
        }
    }
}
