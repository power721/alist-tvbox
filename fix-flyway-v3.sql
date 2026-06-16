-- Fix failed V3 migration in flyway_schema_history
-- Run this against your H2 database to allow V3 to be re-executed

-- Delete the failed V3 record
DELETE FROM flyway_schema_history WHERE version = '3';

-- Verify
SELECT * FROM flyway_schema_history ORDER BY installed_rank;
