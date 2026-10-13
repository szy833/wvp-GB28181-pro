package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.gb28181.dao.InvitePlayProjectionMapper;
import org.apache.ibatis.annotations.Update;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Exercises the actual mapper SQL, including late writes after the newer owner has stopped. */
public final class InvitePlayProjectionVerifier {
    private static int checks;
    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); checks++; }
    private static int update(Connection db, String operation, long epoch, String owner) throws Exception {
        var method = Arrays.stream(InvitePlayProjectionMapper.class.getMethods()).filter(value -> value.getName().equals(operation)).findFirst().orElseThrow();
        String sql = String.join(" ", method.getAnnotation(Update.class).value());
        Matcher matcher = Pattern.compile("#\\{(\\w+)\\}").matcher(sql);
        List<Object> parameters = new ArrayList<>();
        Map<String, Object> values = Map.of("epoch", epoch, "owner", owner, "channel", 1, "stream", "stream-" + owner);
        while (matcher.find()) parameters.add(values.get(matcher.group(1)));
        try (PreparedStatement statement = db.prepareStatement(sql.replaceAll("#\\{\\w+\\}", "?"))) {
            for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
            return statement.executeUpdate();
        }
    }
    public static void main(String[] args) throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:h2:mem:invite-projection;DB_CLOSE_DELAY=-1")) {
            db.createStatement().execute("CREATE TABLE wvp_device_channel(id INT PRIMARY KEY,stream_id VARCHAR(128))");
            String migration = java.nio.file.Files.readString(java.nio.file.Path.of("数据库/2.7.4-h2/更新-Invite生命周期.sql"));
            for (String sql : migration.replaceAll("(?m)^--.*$", "").split(";")) if (!sql.isBlank()) db.createStatement().execute(sql);
            db.createStatement().execute("INSERT INTO wvp_device_channel(id) VALUES(1)");
            check(update(db, "reserve", 1, "A") == 1, "First reserve");
            check(update(db, "activate", 1, "A") == 1, "First activate");
            check(update(db, "close", 1, "A") == 1, "First close");
            check(update(db, "reserve", 2, "B") == 1, "Next reserve");
            check(update(db, "activate", 2, "B") == 1, "Next activate");
            check(update(db, "close", 1, "A") == 0, "Old close blocked");
            check(update(db, "close", 2, "B") == 1, "Next close");
            check(update(db, "activate", 1, "A") == 0, "Old start after next close blocked");
            check(update(db, "reserve", 1, "A") == 0, "Old reserve blocked");
            check(update(db, "close", 3, "C") == 1, "Close before reserve advances watermark");
            check(update(db, "reserve", 3, "C") == 0, "Late reserve after own close blocked");
            check(update(db, "activate", 3, "C") == 0, "Late own start blocked");
            System.out.println("INVITE_PROJECTION_ASSERTIONS=" + checks);
        }
    }
}
