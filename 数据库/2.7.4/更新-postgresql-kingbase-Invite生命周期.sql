-- 停止接收点播并排空会话后执行；不清除历史播放标记，不自动迁移旧 Redis 会话。
ALTER TABLE wvp_device_channel
    ADD COLUMN play_generation_seq BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN play_owner_epoch BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN play_owner_id VARCHAR(64),
    ADD COLUMN play_owner_state VARCHAR(16) NOT NULL DEFAULT 'CLOSED';
