package com.genersoft.iot.vmp.gb28181.task;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InviteInfoIndexBackfillTaskTest {
    @Test void missingSchemaFailsStartupInsteadOfBackfillingOldNamespace() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class); RedisConnectionFactory redis=mock(RedisConnectionFactory.class);
        when(redis.getConnection()).thenReturn(mock(RedisConnection.class));
        when(jdbc.queryForList(anyString())).thenThrow(new IllegalStateException("missing column"));
        assertThrows(IllegalStateException.class,()->new InviteLifecyclePrerequisites(jdbc,redis).afterSingletonsInstantiated());
    }
    @Test void clusterIsRejectedBeforeServingTraffic() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class); RedisConnectionFactory redis=mock(RedisConnectionFactory.class);
        when(redis.getConnection()).thenReturn(mock(RedisClusterConnection.class));
        assertThrows(IllegalStateException.class,()->new InviteLifecyclePrerequisites(jdbc,redis).afterSingletonsInstantiated());
        verifyNoInteractions(jdbc);
    }
    @Test void standaloneWithMigratedSchemaPassesReadOnlyCheck() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class); RedisConnectionFactory redis=mock(RedisConnectionFactory.class);
        when(redis.getConnection()).thenReturn(mock(RedisConnection.class));
        assertDoesNotThrow(()->new InviteLifecyclePrerequisites(jdbc,redis).afterSingletonsInstantiated());
        verify(jdbc).queryForList(contains("WHERE 1=0"));
    }
}
