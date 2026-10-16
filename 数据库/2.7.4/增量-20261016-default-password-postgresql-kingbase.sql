-- Existing database only. Back up before running; do not rerun initialization scripts.
BEGIN;
ALTER TABLE wvp_user ADD COLUMN IF NOT EXISTS default_password BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE wvp_user SET default_password = TRUE
 WHERE username = 'admin' AND LOWER(password) = '21232f297a57a5a743894a0e4a801fc3';
COMMIT;
