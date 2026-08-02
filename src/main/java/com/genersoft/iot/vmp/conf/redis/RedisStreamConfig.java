package com.genersoft.iot.vmp.conf.redis;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Runtime controls for Redis Stream consumers. */
@Data
@Component
@ConfigurationProperties(prefix = "redis.stream")
public class RedisStreamConfig {

    private boolean enabled = true;
    /** Keep publishing Pub/Sub while external producers are migrated. */
    private boolean pubSubCompatibility = false;
    private int batchSize = 100;
    private long blockMillis = 1000L;
    private long claimIdleSeconds = 30L;
    private int claimBatchSize = 100;
    private int maxDeliveryAttempts = 5;
    private int gpsLatestTtlSeconds = 60;
}
