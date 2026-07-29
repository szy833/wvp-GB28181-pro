package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.common.VideoManagerConstants;

import java.util.Optional;
import java.util.OptionalLong;

final class InviteInfoRedisIndex {

    private InviteInfoRedisIndex() {
    }

    static Optional<String> primaryField(InviteSessionType type, Integer channelId, String stream) {
        if (type == null || channelId == null || stream == null || stream.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(type + ":" + channelId + ":" + stream);
    }

    static String primaryField(InviteInfo inviteInfo) {
        return primaryField(inviteInfo.getType(), inviteInfo.getChannelId(), inviteInfo.getStream())
                .orElseThrow(() -> new IllegalArgumentException("InviteInfo primary field is incomplete"));
    }

    static boolean matchesPrimaryField(InviteInfo inviteInfo, String field) {
        if (inviteInfo == null || field == null) {
            return false;
        }
        try {
            return field.equals(primaryField(inviteInfo));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String channelIndexKey(InviteInfo inviteInfo) {
        if (inviteInfo == null || inviteInfo.getType() == null || inviteInfo.getChannelId() == null) {
            return null;
        }
        return VideoManagerConstants.INVITE_INDEX_CHANNEL_PREFIX + inviteInfo.getType() + ":" + inviteInfo.getChannelId();
    }

    static String streamIndexKey(InviteInfo inviteInfo) {
        if (inviteInfo == null || inviteInfo.getStream() == null || inviteInfo.getStream().isEmpty()) {
            return null;
        }
        return VideoManagerConstants.INVITE_INDEX_STREAM_PREFIX + inviteInfo.getStream();
    }

    static String ssrcIndexKey(InviteInfo inviteInfo) {
        if (inviteInfo == null || inviteInfo.getSsrcInfo() == null || inviteInfo.getSsrcInfo().getSsrc() == null
                || inviteInfo.getSsrcInfo().getSsrc().isEmpty()) {
            return null;
        }
        return VideoManagerConstants.INVITE_INDEX_SSRC_PREFIX + inviteInfo.getSsrcInfo().getSsrc();
    }

    static String deviceIndexKey(InviteInfo inviteInfo) {
        if (inviteInfo == null || inviteInfo.getDeviceId() == null || inviteInfo.getDeviceId().isEmpty()) {
            return null;
        }
        return VideoManagerConstants.INVITE_INDEX_DEVICE_PREFIX + inviteInfo.getDeviceId();
    }

    static String activeMediaKey(InviteInfo inviteInfo) {
        String mediaServerId = mediaServerId(inviteInfo);
        return mediaServerId == null ? null : activeMediaKey(mediaServerId);
    }

    static String activeMediaKey(String mediaServerId) {
        if (mediaServerId == null || mediaServerId.isEmpty()) {
            return null;
        }
        return VideoManagerConstants.INVITE_ACTIVE_MEDIA_PREFIX + mediaServerId;
    }

    static boolean countsAsActive(InviteInfo inviteInfo) {
        if (inviteInfo == null || inviteInfo.getStreamInfo() == null || mediaServerId(inviteInfo) == null) {
            return false;
        }
        return inviteInfo.getType() != InviteSessionType.DOWNLOAD
                || inviteInfo.getStreamInfo().getProgress() < 1;
    }

    static OptionalLong deadline(InviteInfo inviteInfo) {
        if (inviteInfo == null) {
            return OptionalLong.empty();
        }
        if (inviteInfo.getType() == InviteSessionType.DOWNLOAD && inviteInfo.getStreamInfo() != null
                && inviteInfo.getStreamInfo().getProgress() >= 1 && inviteInfo.getCleanupAt() != null) {
            return OptionalLong.of(inviteInfo.getCleanupAt());
        }
        if (inviteInfo.getStreamInfo() == null && inviteInfo.getCreateTime() != null
                && inviteInfo.getExpirationTime() != null) {
            return OptionalLong.of(inviteInfo.getCreateTime() + inviteInfo.getExpirationTime());
        }
        return OptionalLong.empty();
    }

    static String mediaServerId(InviteInfo inviteInfo) {
        if (inviteInfo == null) {
            return null;
        }
        if (inviteInfo.getStreamInfo() != null && inviteInfo.getStreamInfo().getMediaServer() != null
                && inviteInfo.getStreamInfo().getMediaServer().getId() != null
                && !inviteInfo.getStreamInfo().getMediaServer().getId().isEmpty()) {
            return inviteInfo.getStreamInfo().getMediaServer().getId();
        }
        return inviteInfo.getMediaServerId();
    }

    static boolean representsIndexKey(InviteInfo inviteInfo, String indexKey) {
        if (inviteInfo == null || indexKey == null) {
            return false;
        }
        return indexKey.equals(channelIndexKey(inviteInfo))
                || indexKey.equals(streamIndexKey(inviteInfo))
                || indexKey.equals(ssrcIndexKey(inviteInfo))
                || indexKey.equals(deviceIndexKey(inviteInfo))
                || (countsAsActive(inviteInfo) && indexKey.equals(activeMediaKey(inviteInfo)));
    }
}
