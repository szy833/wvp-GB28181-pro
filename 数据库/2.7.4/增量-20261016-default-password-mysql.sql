-- Existing database only. Back up before running; DDL implicitly commits in MySQL.
-- Use a single connection for this complete script. No stored procedure privileges required.
SET @wvp_default_password_ddl = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE wvp_user ADD COLUMN default_password BOOLEAN NOT NULL DEFAULT FALSE',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'wvp_user' AND column_name = 'default_password'
);
PREPARE wvp_default_password_statement FROM @wvp_default_password_ddl;
EXECUTE wvp_default_password_statement;
DEALLOCATE PREPARE wvp_default_password_statement;
UPDATE wvp_user SET default_password = TRUE
 WHERE username = 'admin' AND LOWER(password) = '21232f297a57a5a743894a0e4a801fc3';
