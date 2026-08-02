package com.genersoft.iot.vmp.gb28181.service;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;

import java.util.List;

/**
 * 记录国标点播的状态，包括实时预览，下载，录像回放
 */
public interface IInviteStreamService {

    /**
     * 更新点播的状态信息
     */
    void updateInviteInfo(InviteInfo inviteInfo);

    void updateInviteInfo(InviteInfo inviteInfo, Long time);

    InviteInfo updateInviteInfoForStream(InviteInfo inviteInfo, String stream);

    /**
     * 获取点播的状态信息
     */
    InviteInfo getInviteInfo(InviteSessionType type, Integer channelId, String stream);

    /**
     * 移除点播的状态信息
     */
    void removeInviteInfo(InviteSessionType type, Integer channelId, String stream);
    /**
     * 移除点播的状态信息
     */
    void removeInviteInfo(InviteInfo inviteInfo);

    /**
     * Removes an InviteInfo only when the Redis hash still contains the expected owner.
     */
    boolean removeInviteInfoIfSame(InviteInfo expected);

    /**
     * Restores an owner snapshot only when its Redis hash field is still absent.
     */
    boolean restoreInviteInfoIfAbsent(InviteInfo inviteInfo);
    /**
     * 移除点播的状态信息
     */
    void removeInviteInfoByDeviceAndChannel(InviteSessionType inviteSessionType, Integer channelId);

    List<InviteInfo> getAllInviteInfo();

    /**
     * Returns active InviteInfo records whose current primary record belongs to the specified media node.
     */
    List<InviteInfo> getActiveInviteInfoByMediaServer(String mediaServerId);

    boolean inviteIndexesReady();

    boolean inviteIndexesBackfilled();

    /**
     * Builds derived indexes without enabling index-only reads.
     */
    boolean rebuildInviteIndexes();

    /**
     * Enables index-only reads after all legacy writers have been removed from service.
     */
    boolean activateInviteIndexes();

    /**
     * 获取点播的状态信息
     */
    InviteInfo getInviteInfoByDeviceAndChannel(InviteSessionType type, Integer channelId);

    /**
     * 获取点播的状态信息
     */
    InviteInfo getInviteInfoByStream(InviteSessionType type, String stream);

    /**
     * Finds the InviteInfo owned by a specific media node and stream.
     */
    InviteInfo getInviteInfoByStreamAndMediaServer(String mediaServerId, String stream);


    /**
     * 添加一个invite回调
     */
    void once(InviteSessionType type, Integer channelId, String stream,  ErrorCallback<StreamInfo> callback);

    /**
     * 调用一个invite回调
     */
    void call(InviteSessionType type,  Integer channelId, String stream,  int code, String msg, StreamInfo data);

    /**
     * 清空一个设备的所有invite信息
     */
    void clearInviteInfo(String deviceId);

    /**
     * 清理设备离线时仍占用资源的 InviteInfo，保留已完成下载的保留记录。
     */
    int clearActiveInviteInfoByDeviceId(String deviceId);

    /**
     * 统计同一个zlm下的国标收流个数
     */
    int getStreamInfoCount(String mediaServerId);


    /**
     * 获取MediaServer下的流信息
     */
    InviteInfo getInviteInfoBySSRC(String ssrc);

    /**
     * 更新ssrc
     */
    InviteInfo updateInviteInfoForSSRC(InviteInfo inviteInfo, String ssrcInResponse);
}
