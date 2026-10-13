package com.genersoft.iot.vmp.gb28181.task;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fail before serving traffic, rather than discovering missing fencing columns during a play. */
@Component
public class InviteLifecyclePrerequisites implements SmartInitializingSingleton {
    private final JdbcTemplate jdbc;
    private final RedisConnectionFactory redis;

    public InviteLifecyclePrerequisites(JdbcTemplate jdbc, RedisConnectionFactory redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try (var connection = redis.getConnection()) {
            if (connection instanceof RedisClusterConnection)
                throw new IllegalStateException("Invite lifecycle requires standalone/Sentinel Redis; Redis Cluster is not supported");
        }
        try {
            jdbc.queryForList("SELECT play_generation_seq,play_owner_epoch,play_owner_id,play_owner_state "
                    + "FROM wvp_device_channel WHERE 1=0");
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invite生命周期数据库未就绪：请先排空旧会话，备份并执行对应数据库的 更新-Invite生命周期 SQL", failure);
        }
    }
}
