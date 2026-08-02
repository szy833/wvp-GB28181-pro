package com.genersoft.iot.vmp.gb28181.task;

import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.service.bean.GPSMsgInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/** Copies legacy GPS hash entries to per-channel keys without deleting the source. */
@Slf4j
@Component
@RequiredArgsConstructor
public class GpsLegacyHashMigrationTask {

    private final RedisTemplate<String, Object> redisTemplate;
    private final UserSetting userSetting;

    @Scheduled(fixedDelayString = "${redis.stream.legacy-migration-delay-ms:60000}")
    public void migrate() {
        migrate(200);
    }

    public int migrate(int limit) {
        String legacyKey = VideoManagerConstants.WVP_STREAM_GPS_MSG_PREFIX + userSetting.getServerId();
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(legacyKey);
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        int copied = 0;
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            if (copied >= Math.max(1, limit)) {
                break;
            }
            if (!(entry.getValue() instanceof GPSMsgInfo gps) || gps.getId() == null) {
                continue;
            }
            String latestKey = VideoManagerConstants.WVP_STREAM_GPS_MSG_LATEST_PREFIX
                    + userSetting.getServerId() + ":" + gps.getId();
            if (redisTemplate.opsForValue().get(latestKey) != null) {
                continue;
            }
            redisTemplate.opsForValue().set(latestKey, gps, Duration.ofSeconds(60));
            copied++;
        }
        if (copied > 0) {
            log.info("[GPS迁移] legacyHash={}, copied={}", legacyKey, copied);
        }
        return copied;
    }
}
