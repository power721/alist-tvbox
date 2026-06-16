CREATE TABLE IF NOT EXISTS id_generator
(
    entity_name VARCHAR(255) NOT NULL PRIMARY KEY,
    next_id     BIGINT
);

CREATE TABLE IF NOT EXISTS account
(
    id                     INTEGER AUTO_INCREMENT PRIMARY KEY,
    access_token           TEXT,
    access_token_time      TIMESTAMP,
    auto_checkin           BOOLEAN NOT NULL,
    checkin_days           INTEGER NOT NULL,
    checkin_time           TIMESTAMP,
    chunk_size             INTEGER,
    clean                  BOOLEAN DEFAULT FALSE,
    concurrency            INTEGER,
    master                 BOOLEAN DEFAULT FALSE,
    nickname               VARCHAR(255),
    open_access_token      TEXT,
    open_access_token_time TIMESTAMP,
    open_token             TEXT,
    open_token_time        TIMESTAMP,
    refresh_token          VARCHAR(255),
    refresh_token_time     TIMESTAMP,
    show_my_ali            BOOLEAN NOT NULL,
    use_proxy              BOOLEAN DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS movie
(
    id          INTEGER NOT NULL PRIMARY KEY,
    actors      VARCHAR(255),
    country     VARCHAR(255),
    cover       VARCHAR(255),
    db_score    VARCHAR(255),
    description VARCHAR(255),
    directors   VARCHAR(255),
    editors     VARCHAR(255),
    genre       VARCHAR(255),
    language    VARCHAR(255),
    name        VARCHAR(255),
    `year`      INTEGER
);

CREATE TABLE IF NOT EXISTS alias
(
    name     VARCHAR(255) NOT NULL PRIMARY KEY,
    alias    VARCHAR(255),
    movie_id INTEGER
);

CREATE TABLE IF NOT EXISTS alist_alias
(
    id      INTEGER NOT NULL PRIMARY KEY,
    content TEXT,
    path    VARCHAR(255) UNIQUE
);

CREATE TABLE IF NOT EXISTS config_file
(
    id      INTEGER NOT NULL PRIMARY KEY,
    content TEXT,
    dir     VARCHAR(255),
    name    VARCHAR(255),
    path    VARCHAR(255) UNIQUE
);

CREATE TABLE IF NOT EXISTS device
(
    id     INTEGER NOT NULL PRIMARY KEY,
    config LONGTEXT,
    ip     VARCHAR(255),
    name   VARCHAR(255),
    type   INTEGER NOT NULL,
    uuid   VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS driver_account
(
    id            INTEGER NOT NULL PRIMARY KEY,
    addition      TEXT,
    concurrency   INTEGER,
    cookie        TEXT,
    disabled      BOOLEAN DEFAULT FALSE,
    folder        VARCHAR(255),
    master        BOOLEAN NOT NULL,
    name          VARCHAR(255),
    password      VARCHAR(255),
    safe_password VARCHAR(255),
    token         TEXT,
    type          TINYINT,
    use_proxy     BOOLEAN DEFAULT FALSE,
    username      VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS emby
(
    id                 INTEGER NOT NULL PRIMARY KEY,
    client_name        VARCHAR(255),
    client_version     VARCHAR(255),
    device_id          VARCHAR(255),
    device_name        VARCHAR(255),
    enable_image_proxy BOOLEAN DEFAULT FALSE,
    name               VARCHAR(255),
    password           VARCHAR(255),
    sort_order         INTEGER,
    url                VARCHAR(255),
    user_agent         VARCHAR(255),
    username           VARCHAR(255)
);

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

CREATE TABLE IF NOT EXISTS history
(
    id           INTEGER NOT NULL PRIMARY KEY,
    cid          INTEGER NOT NULL,
    create_time  BIGINT NOT NULL,
    duration     BIGINT NOT NULL,
    ending       BIGINT NOT NULL,
    episode      INTEGER NOT NULL,
    episode_url  TEXT,
    `key`        TEXT,
    opening      BIGINT NOT NULL,
    position     BIGINT NOT NULL,
    rev_play     BOOLEAN NOT NULL,
    rev_sort     BOOLEAN NOT NULL,
    scale        INTEGER NOT NULL,
    speed        FLOAT NOT NULL,
    uid          INTEGER,
    vod_flag     VARCHAR(255),
    vod_name     VARCHAR(255),
    vod_pic      VARCHAR(255),
    vod_remarks  VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS index_template
(
    id            INTEGER NOT NULL PRIMARY KEY,
    created_time  TIMESTAMP,
    data          TEXT,
    name          VARCHAR(255),
    schedule_time VARCHAR(255),
    scheduled     BOOLEAN DEFAULT FALSE,
    scrape        BOOLEAN DEFAULT FALSE,
    site_id       INTEGER,
    sleep         INTEGER
);

CREATE TABLE IF NOT EXISTS jellyfin
(
    id             INTEGER NOT NULL PRIMARY KEY,
    client_name    VARCHAR(255),
    client_version VARCHAR(255),
    device_id      VARCHAR(255),
    device_name    VARCHAR(255),
    name           VARCHAR(255),
    password       VARCHAR(255),
    sort_order     INTEGER,
    url            VARCHAR(255),
    user_agent     VARCHAR(255),
    username       VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS tmdb
(
    id          INTEGER NOT NULL PRIMARY KEY,
    actors      VARCHAR(255),
    country     VARCHAR(255),
    cover       VARCHAR(255),
    description VARCHAR(255),
    directors   VARCHAR(255),
    editors     VARCHAR(255),
    genre       VARCHAR(255),
    language    VARCHAR(255),
    name        VARCHAR(255),
    score       VARCHAR(255),
    tmdb_id     INTEGER,
    type        VARCHAR(255),
    `year`      INTEGER
);

CREATE TABLE IF NOT EXISTS meta
(
    id       INTEGER NOT NULL PRIMARY KEY,
    disabled BOOLEAN DEFAULT FALSE,
    movie_id INTEGER,
    name     VARCHAR(255),
    path     VARCHAR(255) UNIQUE,
    score    INTEGER,
    site_id  INTEGER,
    time     TIMESTAMP,
    tid      INTEGER,
    tm_id    INTEGER,
    tmdb_id  INTEGER,
    type     VARCHAR(255),
    `year`   INTEGER
);

CREATE TABLE IF NOT EXISTS navigation
(
    id         INTEGER NOT NULL PRIMARY KEY,
    name       VARCHAR(255),
    parent_id  INTEGER NOT NULL,
    reserved   BOOLEAN DEFAULT FALSE,
    visible    BOOLEAN DEFAULT TRUE,
    sort_order INTEGER NOT NULL,
    type       INTEGER NOT NULL,
    `value`    VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS offline_download_task
(
    id           INTEGER NOT NULL PRIMARY KEY,
    account_id   INTEGER,
    created_time TIMESTAMP,
    folder       BOOLEAN DEFAULT FALSE,
    info_hash    VARCHAR(255),
    status       VARCHAR(255),
    target_path  TEXT,
    task_name    VARCHAR(255),
    updated_time TIMESTAMP,
    url_hash     VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS pan_account
(
    id        INTEGER NOT NULL PRIMARY KEY,
    cookie    TEXT,
    folder    VARCHAR(255),
    master    BOOLEAN NOT NULL,
    name      VARCHAR(255),
    token     VARCHAR(255),
    type      TINYINT,
    use_proxy BOOLEAN DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS pik_pak_account
(
    id                   INTEGER NOT NULL PRIMARY KEY,
    master               BOOLEAN NOT NULL,
    nickname             VARCHAR(255),
    password             VARCHAR(255),
    platform             VARCHAR(255),
    refresh_token_method VARCHAR(255),
    username             VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS play_url
(
    id      INTEGER NOT NULL PRIMARY KEY,
    path    TEXT NOT NULL,
    rating  INTEGER,
    referer VARCHAR(255),
    site    INTEGER NOT NULL,
    time    TIMESTAMP
);

CREATE TABLE IF NOT EXISTS plugin
(
    id              INTEGER NOT NULL PRIMARY KEY,
    content         TEXT,
    enabled         BOOLEAN NOT NULL,
    `extend`        TEXT,
    external_id     VARCHAR(255),
    last_checked_at TIMESTAMP,
    last_error      TEXT,
    local_path      VARCHAR(255),
    name            VARCHAR(255),
    sort_order      INTEGER NOT NULL,
    source_name     VARCHAR(255),
    url             TEXT,
    `version`       INTEGER
);

CREATE TABLE IF NOT EXISTS plugin_filter
(
    id              INTEGER NOT NULL PRIMARY KEY,
    content         TEXT,
    enabled         BOOLEAN NOT NULL,
    error_strategy  VARCHAR(255),
    `extend`        TEXT,
    last_checked_at TIMESTAMP,
    last_error      TEXT,
    name            VARCHAR(255),
    plugin_ids      VARCHAR(255),
    plugin_scope    VARCHAR(255),
    sort_order      INTEGER NOT NULL,
    source_name     VARCHAR(255),
    stages          VARCHAR(255),
    url             TEXT,
    `version`       INTEGER
);

CREATE TABLE IF NOT EXISTS session
(
    id          INTEGER AUTO_INCREMENT PRIMARY KEY,
    create_time TIMESTAMP,
    expire_time TIMESTAMP,
    role        VARCHAR(255),
    token       VARCHAR(255) UNIQUE,
    user_id     INTEGER,
    username    VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS setting
(
    name   VARCHAR(255) NOT NULL PRIMARY KEY,
    svalue TEXT
);

CREATE TABLE IF NOT EXISTS share
(
    id        INTEGER NOT NULL PRIMARY KEY,
    cookie    TEXT,
    folder_id VARCHAR(255),
    password  VARCHAR(255),
    path      VARCHAR(255) UNIQUE,
    share_id  VARCHAR(255),
    temp      BOOLEAN DEFAULT FALSE,
    time      TIMESTAMP,
    type      INTEGER
);

CREATE TABLE IF NOT EXISTS site
(
    id              INTEGER NOT NULL PRIMARY KEY,
    disabled        BOOLEAN NOT NULL,
    folder          VARCHAR(255),
    index_file      VARCHAR(255),
    name            VARCHAR(255),
    password        VARCHAR(255),
    searchable      BOOLEAN NOT NULL,
    sort_order      INTEGER NOT NULL,
    storage_version INTEGER,
    token           VARCHAR(255),
    url             VARCHAR(255),
    xiaoya          BOOLEAN DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS subscription
(
    id       INTEGER NOT NULL PRIMARY KEY,
    name     VARCHAR(255),
    override TEXT,
    sid      VARCHAR(255),
    sort     VARCHAR(255),
    url      VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS task
(
    id           INTEGER NOT NULL PRIMARY KEY,
    created_time TIMESTAMP,
    data         TEXT,
    end_time     TIMESTAMP,
    error        VARCHAR(255),
    name         VARCHAR(255),
    result       TINYINT,
    start_time   TIMESTAMP,
    status       TINYINT,
    summary      TEXT,
    task_type    VARCHAR(255),
    updated_time TIMESTAMP
);

CREATE TABLE IF NOT EXISTS telegram_channel
(
    id          BIGINT NOT NULL PRIMARY KEY,
    access_hash BIGINT NOT NULL,
    enabled     BOOLEAN NOT NULL,
    sort_order  INTEGER NOT NULL,
    title       VARCHAR(255),
    type        INTEGER NOT NULL,
    username    VARCHAR(255),
    valid       BOOLEAN NOT NULL,
    web_access  BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS tenant
(
    id      INTEGER NOT NULL PRIMARY KEY,
    exclude TEXT,
    include TEXT,
    name    VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS tmdb_meta
(
    id      INTEGER NOT NULL PRIMARY KEY,
    name    VARCHAR(255),
    path    VARCHAR(255) UNIQUE,
    score   INTEGER,
    site_id INTEGER,
    tid     INTEGER,
    time    TIMESTAMP,
    tm_id   INTEGER,
    tmdb_id INTEGER,
    type    VARCHAR(255),
    `year`  INTEGER
);

CREATE TABLE IF NOT EXISTS x_user
(
    id           INTEGER AUTO_INCREMENT PRIMARY KEY,
    created_time TIMESTAMP,
    password     VARCHAR(255),
    role         VARCHAR(255),
    username     VARCHAR(255)
);

CREATE INDEX idx_account_nickname ON account (nickname);
CREATE INDEX idx_driver_account_type_username ON driver_account (type, username);
CREATE INDEX idx_driver_account_type_name ON driver_account (type, name);
CREATE INDEX idx_share_type_shareid ON share (type, share_id);
CREATE INDEX idx_subscription_url ON subscription (url);
CREATE INDEX idx_plugin_external_id ON plugin (external_id);
CREATE INDEX idx_plugin_url ON plugin (url);
CREATE INDEX idx_plugin_filter_url ON plugin_filter (url);
CREATE INDEX idx_pikpak_account_username ON pik_pak_account (username);
CREATE INDEX idx_site_url ON site (url);
