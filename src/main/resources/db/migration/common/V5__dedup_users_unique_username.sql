-- Repair and enforce username uniqueness on x_user.
--
-- Background: the IDENTITY-backed user id cannot be preserved across a YAML restore, so a restored admin
-- could land at id!=1; UserService.initializeAdminUser() then re-created an "admin" on every boot. With
-- no unique constraint on username, duplicate admin rows accumulated until findByUsername() threw
-- IncorrectResultSizeDataAccessException and login crashed.
--
-- Step 1: collapse duplicate usernames to the lowest-id row each (keeps the original admin).
--         The extra derived table avoids MySQL error 1093 (target table in the DELETE subquery) and is
--         also valid on H2.
DELETE FROM x_user WHERE id NOT IN
  (SELECT min_id FROM (SELECT MIN(id) AS min_id FROM x_user GROUP BY username) AS keep);

-- Step 2: prevent the duplicates from ever recurring at the database level.
ALTER TABLE x_user ADD CONSTRAINT uk_x_user_username UNIQUE (username);
