package com.genersoft.iot.vmp.storager.dao;

import com.genersoft.iot.vmp.service.impl.UserServiceImpl;
import com.genersoft.iot.vmp.storager.dao.dto.User;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UserPasswordMapperTest {
    private static final String DEFAULT_HASH = "21232f297a57a5a743894a0e4a801fc3";

    @Test void partialProfileUpdateCannotClearFlagOrChangePassword() throws Exception {
        try (Fixture f = new Fixture()) {
            User partial = new User();
            partial.setId(1);
            partial.setUsername("admin");
            partial.setPassword("must-not-write-through-profile");
            partial.setDefaultPassword(false);
            assertEquals(1, f.mapper.update(partial));
            User stored = f.mapper.selectById(1);
            assertTrue(stored.isDefaultPassword());
            assertEquals(DEFAULT_HASH, stored.getPassword());
        }
    }

    @Test void passwordAndFlagChangeAtomicallyWithoutRewritingProfile() throws Exception {
        try (Fixture f = new Fixture()) {
            UserServiceImpl service = new UserServiceImpl();
            ReflectionTestUtils.setField(service, "userMapper", f.mapper);
            assertTrue(service.changePassword(1, "new-password-hash"));
            User stored = f.mapper.selectById(1);
            assertFalse(stored.isDefaultPassword());
            assertEquals("new-password-hash", stored.getPassword());
            assertEquals("admin", stored.getUsername());
            assertEquals("push-secret", stored.getPushKey());
            assertEquals(1, stored.getRole().getId());
            assertFalse(service.changePassword(999, "new-password-hash"));
        }
    }

    @Test void resubmittingSamePasswordCannotClearFlag() throws Exception {
        try (Fixture f = new Fixture()) {
            UserServiceImpl service = new UserServiceImpl();
            ReflectionTestUtils.setField(service, "userMapper", f.mapper);
            assertFalse(service.changePassword(1, DEFAULT_HASH));
            assertTrue(f.mapper.selectById(1).isDefaultPassword());
        }
    }

    @Test void h2MigrationIsRepeatableAndPreservesExistingUsers() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.connection.createStatement().executeUpdate("INSERT INTO wvp_user (id,username,password,role_id,push_key) VALUES "
                    + "(2,'changed-admin','changed',1,'keep-2'),(3,'operator','21232f297a57a5a743894a0e4a801fc3',1,'keep-3')");
            var migration = new FileSystemResource("数据库/2.7.4/增量-20261016-default-password-h2.sql");
            ScriptUtils.executeSqlScript(f.connection, migration);
            ScriptUtils.executeSqlScript(f.connection, migration);
            assertTrue(f.mapper.selectById(1).isDefaultPassword());
            assertFalse(f.mapper.selectById(2).isDefaultPassword());
            assertFalse(f.mapper.selectById(3).isDefaultPassword());
            assertEquals(DEFAULT_HASH, f.mapper.selectById(1).getPassword());
            assertEquals("keep-3", f.mapper.selectById(3).getPushKey());
            f.mapper.changePassword(1, "changed", "now");
            ScriptUtils.executeSqlScript(f.connection, migration);
            assertFalse(f.mapper.selectById(1).isDefaultPassword());
        }
    }

    @Test void migrationDoesNotFlagAdminWhoAlreadyChangedPassword() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.connection.createStatement().executeUpdate("UPDATE wvp_user SET password='already-changed' WHERE id=1");
            var migration = new FileSystemResource("数据库/2.7.4/增量-20261016-default-password-h2.sql");
            ScriptUtils.executeSqlScript(f.connection, migration);
            ScriptUtils.executeSqlScript(f.connection, migration);
            assertFalse(f.mapper.selectById(1).isDefaultPassword());
            assertEquals("already-changed", f.mapper.selectById(1).getPassword());
        }
    }

    @Test void freshH2InitializationFlagsDefaultAdmin() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:fresh_password_" + UUID.randomUUID());
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("数据库/2.7.4-h2/h2-schema.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("数据库/2.7.4-h2/h2-data.sql"));
            try (var rows = connection.createStatement().executeQuery("SELECT password, default_password FROM wvp_user WHERE username='admin'")) {
                assertTrue(rows.next());
                assertEquals(DEFAULT_HASH, rows.getString(1));
                assertTrue(rows.getBoolean(2));
            }
        }
    }

    private static class Fixture implements AutoCloseable {
        final Connection connection;
        final SqlSession session;
        final UserMapper mapper;
        Fixture() throws Exception { this(true); }
        Fixture(boolean withFlag) throws Exception {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:password_" + UUID.randomUUID());
            connection = source.getConnection();
            connection.createStatement().execute("CREATE TABLE wvp_user (id INT PRIMARY KEY, username VARCHAR(255), "
                    + "password VARCHAR(255), role_id INT, push_key VARCHAR(50), create_time VARCHAR(50), update_time VARCHAR(50)"
                    + (withFlag ? ",default_password BOOLEAN NOT NULL DEFAULT FALSE" : "") + ")");
            connection.createStatement().execute("CREATE TABLE wvp_user_role (id INT, name VARCHAR(50), authority VARCHAR(50), "
                    + "create_time VARCHAR(50), update_time VARCHAR(50))");
            connection.createStatement().execute("INSERT INTO wvp_user_role VALUES(1,'admin','0',null,null)");
            connection.createStatement().execute("INSERT INTO wvp_user (id,username,password,role_id,push_key"
                    + (withFlag ? ",default_password" : "") + ") VALUES (1,'admin','" + DEFAULT_HASH
                    + "',1,'push-secret'" + (withFlag ? ",true" : "") + ")");
            Configuration configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(UserMapper.class);
            session = new SqlSessionFactoryBuilder().build(configuration).openSession(connection);
            mapper = session.getMapper(UserMapper.class);
        }
        @Override public void close() throws Exception { session.close(); connection.close(); }
    }
}
