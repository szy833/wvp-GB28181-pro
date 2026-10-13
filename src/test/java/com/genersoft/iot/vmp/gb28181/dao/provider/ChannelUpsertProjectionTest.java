package com.genersoft.iot.vmp.gb28181.dao.provider;

import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ChannelUpsertProjectionTest {
    @Test
    void catalogProvidersNeverWriteRuntimeProjectionColumns() {
        for (String script : List.of(new H2ChannelUpsertProvider().batchUpsert(Map.of()),
                new MysqlChannelUpsertProvider().batchUpsert(Map.of()),
                new PostgresStyleUpsertProvider().batchUpsert(Map.of()))) {
            assertFalse(java.util.regex.Pattern.compile("\\bstream_id\\b").matcher(script).find());
            assertFalse(script.contains("play_owner"));
            assertFalse(script.contains("play_generation"));
        }
        for (var method : com.genersoft.iot.vmp.gb28181.dao.DeviceChannelMapper.class.getMethods()) {
            var annotation = method.getAnnotation(org.apache.ibatis.annotations.Update.class);
            if (annotation == null) continue;
            String sql = String.join(" ", annotation.value());
            assertFalse(sql.contains("stream_id="), method.getName() + " bypasses lifecycle fencing");
        }
    }

    @Test
    void actualH2MergePreservesPlayingOwnerAndStream() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:h2:mem:catalog-projection")) {
            String schema = Files.readString(Path.of("数据库/2.7.4-h2/h2-schema.sql"));
            int start = schema.indexOf("create table IF NOT EXISTS wvp_device_channel");
            db.createStatement().execute(schema.substring(start, schema.indexOf(";", start)));
            db.createStatement().execute("INSERT INTO wvp_device_channel(id,data_device_id,device_id,data_type,create_time,update_time,"
                    + "stream_id,play_generation_seq,play_owner_epoch,play_owner_id,play_owner_state) "
                    + "VALUES(1,1,'channel',1,'now','now','gb_owned',7,7,'owner','PLAYING')");
            DeviceChannel channel = new DeviceChannel();
            channel.setDataDeviceId(1); channel.setDeviceId("channel"); channel.setDataType(1);
            channel.setCreateTime("now"); channel.setUpdateTime("later"); channel.setName("updated catalog");
            Map<String, Object> params = Map.of("channels", List.of(channel));
            Configuration configuration = new Configuration();
            var source = new XMLLanguageDriver().createSqlSource(configuration,
                    new H2ChannelUpsertProvider().batchUpsert(params), Map.class);
            var mapped = new MappedStatement.Builder(configuration, "catalog-upsert", source, SqlCommandType.UPDATE).build();
            var bound = source.getBoundSql(params);
            try (var statement = db.prepareStatement(bound.getSql())) {
                new DefaultParameterHandler(mapped, params, bound).setParameters(statement);
                statement.executeUpdate();
            }
            try (var result = db.createStatement().executeQuery("SELECT * FROM wvp_device_channel WHERE id=1")) {
                assertTrue(result.next());
                assertEquals("updated catalog", result.getString("name"));
                assertEquals("gb_owned", result.getString("stream_id"));
                assertEquals(7, result.getLong("play_generation_seq"));
                assertEquals(7, result.getLong("play_owner_epoch"));
                assertEquals("owner", result.getString("play_owner_id"));
                assertEquals("PLAYING", result.getString("play_owner_state"));
            }
        }
    }
}
