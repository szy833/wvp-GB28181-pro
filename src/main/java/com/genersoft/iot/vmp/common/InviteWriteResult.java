package com.genersoft.iot.vmp.common;

public record InviteWriteResult(Code code, InviteInfo invite) {
    public enum Code { APPLIED, ALREADY_APPLIED, OWNER_CHANGED, STATE_CONFLICT, NOT_FOUND, COMMIT_UNKNOWN }

    public boolean applied() { return code == Code.APPLIED || code == Code.ALREADY_APPLIED; }

    public InviteInfo requireApplied() {
        if (!applied()) throw new IllegalStateException("Invite mutation not confirmed: " + code);
        return invite;
    }
}
