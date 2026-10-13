package com.genersoft.iot.vmp.gb28181.service.impl;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class InviteStreamServiceRedisIntegrationTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.0")).withExposedPorts(6379);
    @Test void actualLuaConcurrentClaimsIndexesAndTerminalRetention() throws Exception {
        InviteLifecycleRedisVerifier.main(new String[]{String.valueOf(REDIS.getMappedPort(6379))});
    }
}
