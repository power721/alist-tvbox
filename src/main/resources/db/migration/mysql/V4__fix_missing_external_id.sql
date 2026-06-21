SET @column_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'plugin'
      AND column_name = 'external_id'
);
SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE plugin ADD COLUMN external_id VARCHAR(255)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS feiniu
(
    id         INTEGER NOT NULL PRIMARY KEY,
    name       VARCHAR(255),
    password   VARCHAR(255),
    sort_order INTEGER,
    token      VARCHAR(255),
    url        VARCHAR(255),
    user_agent VARCHAR(255),
    username   VARCHAR(255)
);
