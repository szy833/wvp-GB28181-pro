package com.genersoft.iot.vmp.gb28181.dao;

import org.apache.ibatis.annotations.*;
import java.util.Map;

@Mapper
public interface InvitePlayProjectionMapper {
    @Select("SELECT play_generation_seq FROM wvp_device_channel WHERE id=#{channel} FOR UPDATE")
    Long lockSequence(@Param("channel") int channel);

    @Update("UPDATE wvp_device_channel SET play_generation_seq=play_generation_seq+1 WHERE id=#{channel}")
    int incrementSequence(@Param("channel") int channel);

    @Update("UPDATE wvp_device_channel SET play_owner_epoch=#{epoch},play_owner_id=#{owner},play_owner_state='PREPARING',stream_id=NULL "
            + "WHERE id=#{channel} AND play_owner_epoch<#{epoch}")
    int reserve(@Param("channel") int channel, @Param("epoch") long epoch, @Param("owner") String owner);

    @Update("UPDATE wvp_device_channel SET play_owner_state='PLAYING',stream_id=#{stream} "
            + "WHERE id=#{channel} AND play_owner_epoch=#{epoch} AND play_owner_id=#{owner} AND play_owner_state='PREPARING'")
    int activate(@Param("channel") int channel, @Param("epoch") long epoch, @Param("owner") String owner, @Param("stream") String stream);

    @Update("UPDATE wvp_device_channel SET play_owner_epoch=#{epoch},play_owner_id=#{owner},play_owner_state='CLOSED',stream_id=NULL "
            + "WHERE id=#{channel} AND (play_owner_epoch<#{epoch} OR (play_owner_epoch=#{epoch} AND play_owner_id=#{owner}))")
    int close(@Param("channel") int channel, @Param("epoch") long epoch, @Param("owner") String owner);

    @Select("SELECT play_owner_epoch AS epoch,play_owner_id AS owner,play_owner_state AS state,stream_id AS stream "
            + "FROM wvp_device_channel WHERE id=#{channel}")
    Map<String, Object> projection(@Param("channel") int channel);
}
