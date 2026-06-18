ALTER TABLE plugin ADD COLUMN IF NOT EXISTS external_id VARCHAR(255);

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
