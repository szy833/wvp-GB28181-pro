package com.genersoft.iot.vmp.service.redisMsg;

import com.alibaba.fastjson2.JSON;
import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.redis.RedisStreamConsumer;
import com.genersoft.iot.vmp.conf.redis.RedisStreamMessageService;
import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import com.genersoft.iot.vmp.gb28181.bean.MobilePosition;
import com.genersoft.iot.vmp.gb28181.dao.CommonGBChannelMapper;
import com.genersoft.iot.vmp.gb28181.dao.MobilePositionMapper;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelService;
import com.genersoft.iot.vmp.service.bean.GPSMsgInfo;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.utils.DateUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Receives GPS Pub/Sub compatibility messages and processes them through a durable Stream. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisGpsMsgListener implements MessageListener {

    private final IRedisCatchStorage redisCatchStorage;
    private final IGbChannelService channelService;
    private final RedisStreamConsumer streamConsumer;
    private final RedisStreamMessageService streamMessageService;
    private final CommonGBChannelMapper channelMapper;
    private final MobilePositionMapper mobilePositionMapper;
    private final UserSetting userSetting;

    private final String consumerName = "gps-" + UUID.randomUUID();

    @PostConstruct
    public void startStreamConsumer() {
        streamConsumer.start(VideoManagerConstants.WVP_REDIS_STREAM_GPS_PREFIX,
                VideoManagerConstants.WVP_REDIS_STREAM_GROUP, consumerName, this::handleRecord);
    }

    @PreDestroy
    public void stopStreamConsumer() {
        streamConsumer.stop(VideoManagerConstants.WVP_REDIS_STREAM_GPS_PREFIX,
                VideoManagerConstants.WVP_REDIS_STREAM_GROUP, consumerName);
    }

    @Override
    public void onMessage(@NotNull Message message, byte[] bytes) {
        log.debug("[REDIS: GPS]： {}", new String(message.getBody()));
        try {
            streamMessageService.append(VideoManagerConstants.WVP_REDIS_STREAM_GPS_PREFIX,
                    new String(message.getBody()), "pubsub");
        } catch (Exception e) {
            log.error("[REDIS的位置变化通知] 写入Stream失败：{}", e.getMessage(), e);
        }
    }

    /** Processes one Stream record. Returning false keeps it in Pending for retry. */
    public boolean handleRecord(MapRecord<String, String, String> record) {
        String body = record.getValue().get("body");
        if (body == null) {
            log.warn("[REDIS的位置变化通知] Stream消息缺少body字段，id={}", record.getId());
            return true;
        }
        try {
            GPSMsgInfo gpsMsgInfo = JSON.parseObject(body, GPSMsgInfo.class);
            if (gpsMsgInfo != null && gpsMsgInfo.getId() == null) {
                gpsMsgInfo.setId(JSON.parseObject(body).getString("gbDeviceId"));
            }
            if (gpsMsgInfo == null || gpsMsgInfo.getId() == null) {
                log.warn("[REDIS的位置变化通知] 消息解析失败，id={}", record.getId());
                return false;
            }
            if (gpsMsgInfo.getTime() != null) {
                gpsMsgInfo.setTime(DateUtil.ISO8601Toyyyy_MM_dd_HH_mm_ss(gpsMsgInfo.getTime()));
            }
            log.debug("[REDIS的位置变化通知] {}", JSON.toJSONString(gpsMsgInfo));

            // Latest position is independently TTL'd by RedisCatchStorageImpl.
            redisCatchStorage.updateGpsMsgInfo(gpsMsgInfo);
            channelService.updateGPSFromGPSMsgInfo(List.of(gpsMsgInfo));
            if (Boolean.TRUE.equals(userSetting.getSavePositionHistory())) {
                saveHistory(gpsMsgInfo);
            }
            return true;
        } catch (Exception e) {
            log.warn("[REDIS的位置变化通知] 处理失败，保留Pending：id={}, error={}", record.getId(), e.getMessage(), e);
            return false;
        }
    }

    private void saveHistory(GPSMsgInfo gpsMsgInfo) {
        List<CommonGBChannel> channels = channelMapper.queryByGbDeviceIds(List.of(gpsMsgInfo.getId()));
        if (channels == null || channels.isEmpty()) {
            log.debug("[REDIS的位置变化通知] 未找到轨迹通道：{}", gpsMsgInfo.getId());
            return;
        }
        List<MobilePosition> positions = channels.stream().map(channel -> {
            MobilePosition position = new MobilePosition();
            position.setChannelId(channel.getGbId());
            position.setChannelDeviceId(channel.getGbDeviceId());
            position.setTimestamp(DateUtil.yyyy_MM_dd_HH_mm_ssToTimestampMs(gpsMsgInfo.getTime()));
            position.setLongitude(gpsMsgInfo.getLng());
            position.setLatitude(gpsMsgInfo.getLat());
            position.setAltitude(gpsMsgInfo.getAltitude() == null ? 0 : gpsMsgInfo.getAltitude());
            position.setSpeed(gpsMsgInfo.getSpeed() == null ? 0 : gpsMsgInfo.getSpeed());
            position.setDirection(gpsMsgInfo.getDirection() == null ? 0 : gpsMsgInfo.getDirection());
            position.setCreateTime(DateUtil.getNow());
            return position;
        }).toList();
        mobilePositionMapper.batchAdd(positions);
    }
}
