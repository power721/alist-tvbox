# PostgreSQL Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make PostgreSQL a first-class database (main app DB + embedded AList runtime DB), ship a script-driven DB migration tool and a script-driven `config-db` switcher, and verify it under the GraalVM native image.

**Architecture:** Flyway `{vendor}` + `common` migration folders give each DB its own DDL while sharing portable migrations; a new `postgresql` Spring profile selects the driver/dialect; the embedded AList `alistDataSource()` gains a `postgres` branch reusing the existing `generatePostgresJdbcUrl()`; user-facing DB switching and H2/MySQL→PG migration are exposed as `config-db` / `migrate-db` subcommands of `scripts/alist-tvbox.sh`, which already manages the Docker container and persists config. Existing H2/MySQL deployments are byte-for-byte unaffected.

**Tech Stack:** Spring Boot 3.5 / Java 21, Hibernate 6, Flyway 10+, PostgreSQL JDBC driver, Testcontainers, GraalVM native-image, Bash (management script).

---

## File Structure

**Create:**
- `src/main/resources/db/migration/h2/V1__Create_current_schema.sql` — copy of today's `current/V1` (H2-valid).
- `src/main/resources/db/migration/mysql/V1__Create_current_schema.sql` — identical copy (MySQL-valid).
- `src/main/resources/db/migration/postgresql/V1__Create_current_schema.sql` — NEW, ported schema (Task 2).
- `src/main/resources/db/migration/common/V4__fix_missing_external_id.sql` — moved from `current/`.
- `src/main/resources/db/migration/common/V5__dedup_users_unique_username.sql` — moved from `current/`.
- `src/main/resources/application-postgresql.yaml` — profile (Task 4).
- `src/test/java/cn/har01d/alist_tvbox/PostgreSqlIntegrationTest.java` — Testcontainers boot test (Task 6).
- `docs/postgresql-migration.md` — migration guide (Task 9).

**Modify:**
- `pom.xml` — add `postgresql` driver, `flyway-database-postgresql`, testcontainers test deps.
- `src/main/resources/application.yaml` — `flyway.locations` → `{vendor},common`; remove SQL from `current/`.
- `src/main/java/cn/har01d/alist_tvbox/config/DatabaseConfig.java` — `postgres` branch in `alistDataSource()`.
- `scripts/alist-tvbox.sh` — `config-db` + `migrate-db` subcommands; inject DB env into `start_container`.
- `scripts/alist-tvbox.sh` help text — document the two new subcommands.

**Delete:** `src/main/resources/db/migration/current/V1__Create_current_schema.sql`, `V4__fix_missing_external_id.sql`, `V5__dedup_users_unique_username.sql` (after copying to new locations; Java `V2`/`V3` stay).

**Unchanged (by design):** entity classes, DTOs, `Main.java` reflection list, `NativeFlywayMigrationConfig`, `META-INF/services/...JavaMigration`, `resource-config.json`.

---

## Task 1: Add PostgreSQL + Flyway dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add runtime driver and Flyway PostgreSQL module**

Insert into `pom.xml` `<dependencies>`, right after the existing `flyway-mysql` block (around line 56) and after the `mysql-connector-j` block (around line 74):

```xml
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
```

and after the `mysql-connector-j` dependency:

```xml
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
```

(Versions are managed by the Spring Boot 3.5 BOM — do not pin.)

- [ ] **Step 2: Add Testcontainers test dependencies**

In `pom.xml`, after the existing `spring-boot-starter-test` test dependency, add:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 3: Verify it compiles and resolves**

Run: `mvn -q compile && mvn -q dependency:tree | grep -E 'postgresql|flyway-database-postgresql|testcontainers'`
Expected: lines for `org.postgresql:postgresql`, `org.flywaydb:flyway-database-postgresql`, and `org.testcontainers:*`.

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "build: add PostgreSQL driver, flyway-database-postgresql, testcontainers"
```

---

## Task 2: Write the PostgreSQL V1 schema migration

**Files:**
- Create: `src/main/resources/db/migration/postgresql/V1__Create_current_schema.sql`

This is the ported schema. Differences from the H2/MySQL `V1`: `AUTO_INCREMENT` → `GENERATED BY DEFAULT AS IDENTITY` (3 tables), `TINYINT` → `SMALLINT` (4 columns), `LONGTEXT` → `TEXT` (1 column), backtick-quoted reserved words → double-quoted. Column **names** are unchanged.

- [ ] **Step 1: Create the file with the full ported schema**

Create `src/main/resources/db/migration/postgresql/V1__Create_current_schema.sql` with exactly:

```sql
CREATE TABLE IF NOT EXISTS id_generator
(
    entity_name VARCHAR(255) NOT NULL PRIMARY KEY,
    next_id     BIGINT
);

CREATE TABLE IF NOT EXISTS account
(
    id                     INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
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
    "year"      INTEGER
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
    config TEXT,
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
    type          SMALLINT,
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
    "key"        TEXT,
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
    "year"      INTEGER
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
    "year"   INTEGER
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
    "value"    VARCHAR(255)
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
    type      SMALLINT,
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
    "extend"        TEXT,
    external_id     VARCHAR(255),
    last_checked_at TIMESTAMP,
    last_error      TEXT,
    local_path      VARCHAR(255),
    name            VARCHAR(255),
    sort_order      INTEGER NOT NULL,
    source_name     VARCHAR(255),
    url             TEXT,
    "version"       INTEGER
);

CREATE TABLE IF NOT EXISTS plugin_filter
(
    id              INTEGER NOT NULL PRIMARY KEY,
    content         TEXT,
    enabled         BOOLEAN NOT NULL,
    error_strategy  VARCHAR(255),
    "extend"        TEXT,
    last_checked_at TIMESTAMP,
    last_error      TEXT,
    name            VARCHAR(255),
    plugin_ids      VARCHAR(255),
    plugin_scope    VARCHAR(255),
    sort_order      INTEGER NOT NULL,
    source_name     VARCHAR(255),
    stages          VARCHAR(255),
    url             TEXT,
    "version"       INTEGER
);

CREATE TABLE IF NOT EXISTS session
(
    id          INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
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
    result       SMALLINT,
    start_time   TIMESTAMP,
    status       SMALLINT,
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
    "year"  INTEGER
);

CREATE TABLE IF NOT EXISTS x_user
(
    id           INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
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
```

- [ ] **Step 2: Sanity-check the file is well-formed**

Run: `grep -c 'CREATE TABLE' src/main/resources/db/migration/postgresql/V1__Create_current_schema.sql`
Expected: `33` (32 entity tables + `id_generator`).

Run: `grep -c 'GENERATED BY DEFAULT AS IDENTITY' src/main/resources/db/migration/postgresql/V1__Create_current_schema.sql`
Expected: `3` (`account`, `session`, `x_user`).

Run: `grep -c 'AUTO_INCREMENT\|TINYINT\|LONGTEXT\|`' src/main/resources/db/migration/postgresql/V1__Create_current_schema.sql`
Expected: `0` (no MySQL/H2-isms, no backticks).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/postgresql/V1__Create_current_schema.sql
git commit -m "feat(db): add PostgreSQL V1 schema migration"
```

---

## Task 3: Reorganize Flyway folders to `{vendor}` + `common`

**Files:**
- Create: `src/main/resources/db/migration/h2/V1__Create_current_schema.sql` (copy)
- Create: `src/main/resources/db/migration/mysql/V1__Create_current_schema.sql` (copy)
- Create: `src/main/resources/db/migration/common/V4__fix_missing_external_id.sql` (move)
- Create: `src/main/resources/db/migration/common/V5__dedup_users_unique_username.sql` (move)
- Delete: the three SQL files under `current/`
- Modify: `src/main/resources/application.yaml` (flyway.locations)

- [ ] **Step 1: Copy current V1 into h2/ and mysql/ (byte-identical)**

```bash
mkdir -p src/main/resources/db/migration/h2 src/main/resources/db/migration/mysql src/main/resources/db/migration/common
cp src/main/resources/db/migration/current/V1__Create_current_schema.sql src/main/resources/db/migration/h2/V1__Create_current_schema.sql
cp src/main/resources/db/migration/current/V1__Create_current_schema.sql src/main/resources/db/migration/mysql/V1__Create_current_schema.sql
cp src/main/resources/db/migration/current/V4__fix_missing_external_id.sql src/main/resources/db/migration/common/V4__fix_missing_external_id.sql
cp src/main/resources/db/migration/current/V5__dedup_users_unique_username.sql src/main/resources/db/migration/common/V5__dedup_users_unique_username.sql
```

- [ ] **Step 2: Prove the copies are byte-identical to the originals**

```bash
diff src/main/resources/db/migration/current/V1__Create_current_schema.sql src/main/resources/db/migration/h2/V1__Create_current_schema.sql && echo V1-h2-ok
diff src/main/resources/db/migration/current/V1__Create_current_schema.sql src/main/resources/db/migration/mysql/V1__Create_current_schema.sql && echo V1-mysql-ok
diff src/main/resources/db/migration/current/V4__fix_missing_external_id.sql src/main/resources/db/migration/common/V4__fix_missing_external_id.sql && echo V4-ok
diff src/main/resources/db/migration/current/V5__dedup_users_unique_username.sql src/main/resources/db/migration/common/V5__dedup_users_unique_username.sql && echo V5-ok
```
Expected: four `*-ok` lines (checksums unchanged → existing H2/MySQL `flyway_schema_history` validates).

- [ ] **Step 3: Delete the SQL files from `current/` (Java V2/V3 stay)**

```bash
git rm src/main/resources/db/migration/current/V1__Create_current_schema.sql
git rm src/main/resources/db/migration/current/V4__fix_missing_external_id.sql
git rm src/main/resources/db/migration/current/V5__dedup_users_unique_username.sql
```

Leave `src/main/java/db/migration/current/V2__*.java` and `V3__*.java` untouched (still discovered via `META-INF/services/org.flywaydb.core.api.migration.JavaMigration`).

- [ ] **Step 4: Switch `flyway.locations` to vendor-aware**

In `src/main/resources/application.yaml`, replace:

```yaml
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration/current
```

with:

```yaml
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration/{vendor},classpath:db/migration/common
```

- [ ] **Step 5: Verify H2 still migrates (existing test suite must stay green)**

Run: `mvn -q -Dtest='ReservedColumnMappingTest,V2NormalizeReservedColumnsTest' test`
Expected: PASS (these exercise the H2 path; confirms the reorg did not break H2 discovery/checksums).

- [ ] **Step 6: Commit**

```bash
git add -A src/main/resources/db/migration src/main/resources/application.yaml
git commit -m "refactor(db): switch Flyway to {vendor}+common layout (H2/MySQL unchanged)"
```

---

## Task 4: Add the `postgresql` Spring profile

**Files:**
- Create: `src/main/resources/application-postgresql.yaml`

- [ ] **Step 1: Create the profile config**

Create `src/main/resources/application-postgresql.yaml`:

```yaml
spring:
  datasource:
    jdbc-url: jdbc:postgresql://localhost:5432/alist_tvbox
    username: atv
    password: AList_TvBox
    driver-class-name: org.postgresql.Driver
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: validate
    show-sql: false
```

- [ ] **Step 2: Register the profile resource for native image**

Check `src/main/resources/META-INF/native-image/resource-config.json` — it already contains `{"pattern": "db/migration/.*"}` (covers the new vendor folders) and Spring Boot auto-includes `application-*.yaml`. Verify the yaml is matched:

Run: `grep -n 'application-\|db/migration' src/main/resources/META-INF/native-image/resource-config.json`
Expected: a pattern covering `application-postgresql.yaml` (e.g. `application-.*` or the classpath default) and `db/migration/.*`. If `application-*.yaml` is not covered, add `{"pattern": "application-postgresql\\.yaml"}` to the resources array. (The wildcard `db/migration/.*` already covers the SQL; no change needed there.)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application-postgresql.yaml src/main/resources/META-INF/native-image/resource-config.json
git commit -m "feat(db): add postgresql Spring profile"
```

---

## Task 5: Embedded AList `postgres` support

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/config/DatabaseConfig.java:59-71`

- [ ] **Step 1: Add the `postgres` branch to `alistDataSource()`**

In `DatabaseConfig.java`, replace the closing of the `mysql` branch and the `else` throw (the block starting `} else if ("mysql".equals(type)) {` ... through `throw new IllegalArgumentException("unknown database type: " + type);`) so a new branch is inserted before the throw. The final structure:

```java
        if ("sqlite3".equals(type)) {
            String dbFile = Utils.getAListPath(database.get("db_file").asText());
            log.info("===> AList use sqlite3 database file: {}", dbFile);

            return DataSourceBuilder.create()
                    .url("jdbc:sqlite:" + dbFile)
                    .driverClassName("org.sqlite.JDBC")
                    .build();
        } else if ("mysql".equals(type)) {
            String url = generateMysqlJdbcUrl(database);
            log.info("===> AList use mysql database url: {}", url);
            return DataSourceBuilder.create()
                    .url(url)
                    .username(database.get("user").asText())
                    .password(database.get("password").asText())
                    .driverClassName("com.mysql.cj.jdbc.Driver")
                    .build();
        } else if ("postgres".equals(type) || "postgresql".equals(type)) {
            String url = generatePostgresJdbcUrl(database);
            log.info("===> AList use postgresql database url: {}", url);
            return DataSourceBuilder.create()
                    .url(url)
                    .username(database.get("user").asText())
                    .password(database.get("password").asText())
                    .driverClassName("org.postgresql.Driver")
                    .build();
        } else {
            throw new IllegalArgumentException("unknown database type: " + type);
        }
```

(`generatePostgresJdbcUrl` already exists in this file at line ~119; no other change needed.)

- [ ] **Step 2: Verify compilation**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/config/DatabaseConfig.java
git commit -m "feat(alist): support postgres in embedded AList datasource"
```

---

## Task 6: PostgreSQL integration test (Testcontainers)

This is the primary correctness gate: it boots the real app against a PostgreSQL container, so Flyway must run the ported schema and Hibernate `ddl-auto: validate` must confirm the schema matches every entity.

**Files:**
- Create: `src/test/java/cn/har01d/alist_tvbox/PostgreSqlIntegrationTest.java`
- Create: `src/test/resources/alist/config.json` (minimal, so `alistDataSource()` initializes)

- [ ] **Step 1: Provision a minimal embedded-AList `config.json` for the test**

The full Spring context creates `alistDataSource()`, which reads `Utils.getAListPath("data/config.json")`. Inspect `Utils.getAListPath` (around `src/main/java/cn/har01d/alist_tvbox/util/Utils.java:301`) to confirm the resolved path relative to `atv.data.dir`. Create `src/test/resources/alist/config.json` (sqlite3, so it needs no external DB; sqlite opens/creates the file lazily):

```json
{
  "database": {
    "type": "sqlite3",
    "db_file": "data/data.db"
  }
}
```

(The test copies this into the temp `atv.data.dir` in Step 3; adjust the copy path to match what `getAListPath("data/config.json")` resolves to — typically `<atv.data.dir>/alist/data/config.json`.)

- [ ] **Step 2: Write the failing test**

Create `src/test/java/cn/har01d/alist_tvbox/PostgreSqlIntegrationTest.java`:

```java
package cn.har01d.alist_tvbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole application against a real PostgreSQL container. If this context loads, then:
 * (1) Flyway ran the postgresql + common migrations cleanly, and
 * (2) Hibernate ddl-auto=validate confirmed the migrated schema matches every @Entity.
 * That is the end-to-end proof of PostgreSQL schema correctness.
 */
@Testcontainers
@SpringBootTest
class PostgreSqlIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withReuse(true);

    @DynamicPropertySource
    static void postgresProps(DynamicPropertyRegistry registry) {
        // @ServiceConnection-style wiring done manually so we also pin driver + dialect,
        // which the default H2 profile would otherwise override.
        registry.add("spring.datasource.jdbc-url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Test
    void contextLoadsOnPostgresql(@Autowired DataSource dataSource) throws Exception {
        DatabaseMetaData md = dataSource.getConnection().getMetaData();
        assertThat(md.getDatabaseProductName()).contains("PostgreSQL");
    }
}
```

- [ ] **Step 3: Handle the `atv.data.dir` / alist config in the test JVM**

Because `@SpringBootTest` runs in a shared JVM, set `atv.data.dir` once. Add a `@BeforeAll` that creates a temp dir and copies the test `config.json` into it (path per Step 1), plus a system property:

```java
    @org.junit.jupiter.api.BeforeAll
    static void prepareDataDir() throws Exception {
        java.nio.file.Path dir = java.nio.Files.createTempDirectory("atv-pg-test");
        System.setProperty("atv.data.dir", dir.toString());
        // Copy the minimal alist config.json to the path getAListPath("data/config.json") resolves to.
        java.nio.file.Path alistConfig = dir.resolve("alist/data/config.json"); // adjust if getAListPath differs
        java.nio.file.Files.createDirectories(alistConfig.getParent());
        try (var in = PostgreSqlIntegrationTest.class.getResourceAsStream("/alist/config.json")) {
            java.nio.file.Files.copy(in, alistConfig);
        }
    }

    @org.junit.jupiter.api.AfterAll
    static void clearDataDir() {
        System.clearProperty("atv.data.dir");
    }
```

(Confirm the exact relative path by reading `Utils.getAListPath` before finalizing; if it is `<atv.data.dir>/alist/data/config.json` the above is correct.)

- [ ] **Step 4: Run the test (requires Docker)**

Run: `mvn -q -Dtest=PostgreSqlIntegrationTest test`
Expected: PASS. If Flyway or Hibernate `validate` fails, the failure message names the offending table/column — fix it in `postgresql/V1` (Task 2) and re-run. Common causes: a `TINYINT`/`AUTO_INCREMENT` leftover, or a reserved-word column not double-quoted.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/cn/har01d/alist_tvbox/PostgreSqlIntegrationTest.java src/test/resources/alist/config.json
git commit -m "test(db): add Testcontainers PostgreSQL boot test"
```

---

## Task 7: `config-db` subcommand (switch main app DB)

Let the user point the running container at a different main-app database (H2/MySQL/PostgreSQL) by setting JDBC URL / credentials, persisted in the script's existing `CONFIG_FILE` and applied on container (re)creation.

**Files:**
- Modify: `scripts/alist-tvbox.sh` (top-level `cli_mode` dispatch ~line 2907; `start_container` run_args ~line 500; help text)

- [ ] **Step 1: Add DB env injection to `start_container`**

In `start_container()` (around line 500, inside the `local -a run_args=( ... )` block), after the existing `-e MEM_OPT=...` line, add a conditional block that injects datasource env + the DB profile when `DB_TYPE` is configured:

```bash
  local -a run_args=(
    -d
    --name "$container_name"
    -e ALIST_PORT="${CONFIG[PORT2]}"
    -e MEM_OPT="-Xmx512M"
    -v "${CONFIG[BASE_DIR]}":/data
    -v "${CONFIG[BASE_DIR]}/www-static":/www/static
    --restart="${CONFIG[RESTART]}"
  )

  # 主应用数据库覆盖（config-db 子命令写入）。未配置时走镜像内置的 H2 默认。
  if [[ -n "${CONFIG[DB_TYPE]:-}" ]]; then
    run_args+=("-e" "SPRING_DATASOURCE_JDBC-URL=${CONFIG[DB_JDBC_URL]}")
    run_args+=("-e" "SPRING_DATASOURCE_USERNAME=${CONFIG[DB_USERNAME]}")
    run_args+=("-e" "SPRING_DATASOURCE_PASSWORD=${CONFIG[DB_PASSWORD]}")
    run_args+=("-e" "SPRING_DATASOURCE_DRIVER-CLASS-NAME=${CONFIG[DB_DRIVER]}")
    run_args+=("-e" "SPRING_JPA_DATABASE-PLATFORM=${CONFIG[DB_DIALECT]}")
    run_args+=("-e" "SPRING_PROFILES_ACTIVE=${CONFIG[DB_TYPE]}")
  fi
```

(Spring relaxed binding maps `SPRING_DATASOURCE_JDBC-URL`/`SPRING_DATASOURCE_DRIVER-CLASS-NAME` to `jdbc-url`/`driver-class-name`. `SPRING_PROFILES_ACTIVE` *adds* the `postgresql`/`mysql` profile to the image CMD's `production,docker`.)

- [ ] **Step 2: Add the `config_db` interactive function**

Add this function near the other config helpers (e.g. after `save_config` ~line 366):

```bash
# 配置主应用数据库（H2/MySQL/PostgreSQL）。写入 CONFIG_FILE，下次安装/重建容器时生效。
config_db() {
  echo -e "${CYAN}=== 配置主应用数据库 ===${NC}"
  echo "当前: ${CONFIG[DB_TYPE]:-H2（默认）}"
  echo "1) H2（内置默认，清空外部数据库配置）"
  echo "2) MySQL"
  echo "3) PostgreSQL"
  read -rp "请选择 [1-3]: " db_choice

  case "$db_choice" in
    1)
      unset 'CONFIG[DB_TYPE]';      unset 'CONFIG[DB_JDBC_URL]'
      unset 'CONFIG[DB_USERNAME]';  unset 'CONFIG[DB_PASSWORD]'
      unset 'CONFIG[DB_DRIVER]';    unset 'CONFIG[DB_DIALECT]'
      save_config
      echo -e "${GREEN}已切换回 H2 默认。重新安装/更新容器后生效。${NC}"
      ;;
    2|3)
      if [[ "$db_choice" == "2" ]]; then
        CONFIG[DB_TYPE]="mysql"
        CONFIG[DB_DRIVER]="com.mysql.cj.jdbc.Driver"
        CONFIG[DB_DIALECT]="org.hibernate.dialect.MySQL8Dialect"
        local default_url="jdbc:mysql://localhost:3306/alist_tvbox"
      else
        CONFIG[DB_TYPE]="postgresql"
        CONFIG[DB_DRIVER]="org.postgresql.Driver"
        CONFIG[DB_DIALECT]="org.hibernate.dialect.PostgreSQLDialect"
        local default_url="jdbc:postgresql://localhost:5432/alist_tvbox"
      fi
      read -rp "JDBC URL [$default_url]: " jdbc_url
      read -rp "用户名 [atv]: " username
      read -rsp "密码: " password; echo
      CONFIG[DB_JDBC_URL]="${jdbc_url:-$default_url}"
      CONFIG[DB_USERNAME]="${username:-atv}"
      CONFIG[DB_PASSWORD]="$password"
      save_config
      echo -e "${GREEN}已保存 ${CONFIG[DB_TYPE]} 配置。执行安装/更新（菜单 1）或 'config-db apply' 重建容器后生效。${NC}"
      ;;
    *)
      echo -e "${RED}无效选择${NC}"; return 1 ;;
  esac
}

# 用当前 CONFIG 中的数据库配置重建容器（停止+删除+start_container）。
config_db_apply() {
  local container_name=$(get_container_name)
  echo -e "${CYAN}使用新数据库配置重建容器 $container_name ...${NC}"
  docker stop "$container_name" 2>/dev/null || true
  docker rm "$container_name" 2>/dev/null || true
  start_container
  show_access_info
}
```

- [ ] **Step 3: Wire `config-db` into `cli_mode` dispatch**

In `cli_mode()`'s `case "$1"` (around line 2907), add cases (next to the existing `restart)`, etc.):

```bash
    config-db)
      case "${2:-}" in
        apply) config_db_apply ;;
        *)     config_db ;;
      esac
      ;;
```

- [ ] **Step 4: Document it in help**

In `show_help` (the `-h|--help|help` handler), add a line:

```bash
  echo "  config-db [apply]   配置/切换主应用数据库 (H2/MySQL/PostgreSQL)；apply 用新配置重建容器"
```

- [ ] **Step 5: Verify the script still parses**

Run: `bash -n scripts/alist-tvbox.sh && bash scripts/alist-tvbox.sh help | grep config-db`
Expected: no syntax errors; the help line prints.

- [ ] **Step 6: Commit**

```bash
git add scripts/alist-tvbox.sh
git commit -m "feat(script): add config-db subcommand to switch main app database"
```

---

## Task 8: `migrate-db` subcommand (H2/MySQL → PostgreSQL)

User-facing migration: export a JSON backup from the source instance, then import it into the target PostgreSQL instance via the existing startup-restore path.

**Files:**
- Modify: `scripts/alist-tvbox.sh`

- [ ] **Step 1: Add the `migrate_db` functions**

Add near `config_db` (Task 7):

```bash
# 从当前容器导出 JSON 备份（需要先写 backup_token）。复用 /api/local/backup。
migrate_db_export() {
  local out="${2:-$(pwd)/database-json.zip}"
  local container_name=$(get_container_name)
  local base="${CONFIG[BASE_DIR]}"
  local token_file="$base/backup_token"

  if [[ ! -f "$token_file" ]]; then
    echo -e "${RED}未找到 $token_file。请先通过管理界面或重置流程生成 backup_token。${NC}" >&2
    return 1
  fi
  local token; token=$(<"$token_file")

  echo -e "${CYAN}从容器 $container_name 导出 JSON 备份到 $out ...${NC}"
  curl -fsS -X POST \
    -H "X-BACKUP-TOKEN: $token" \
    "http://127.0.0.1:${CONFIG[PORT1]}/api/local/backup?format=json" \
    -o "$out" || { echo -e "${RED}导出失败${NC}" >&2; return 1; }
  echo -e "${GREEN}已导出: $out${NC}"
  echo -e "${YELLOW}下一步：在目标 PostgreSQL 实例上执行 '$(basename "$0") migrate-db import $out'${NC}"
}

# 把 JSON 备份放进容器启动恢复路径并重启；StartupJsonRestoreRunner 会在下次启动恢复。
migrate_db_import() {
  local zip="${2:-}"
  if [[ -z "$zip" || ! -f "$zip" ]]; then
    echo -e "${RED}用法: migrate-db import <database-json.zip>${NC}" >&2; return 1
  fi
  local container_name=$(get_container_name)
  local base="${CONFIG[BASE_DIR]}"
  echo -e "${CYAN}将 $zip 放入启动恢复路径并重启容器 $container_name ...${NC}"
  mkdir -p "$base"
  cp "$zip" "$base/database-json.zip"
  docker restart "$container_name" || { echo -e "${RED}重启失败${NC}" >&2; return 1; }
  echo -e "${GREEN}已触发恢复。观察 'docker logs -f $container_name'，应用会恢复后自动重启(exit 85)。${NC}"
}
```

- [ ] **Step 2: Wire `migrate-db` into `cli_mode` dispatch**

In `cli_mode()`'s `case "$1"`, add:

```bash
    migrate-db)
      case "${2:-}" in
        export) migrate_db_export "$@" ;;
        import) migrate_db_import "$@" ;;
        *) echo "用法: migrate-db <export|import> [zip]" ;;
      esac
      ;;
```

- [ ] **Step 3: Document it in help**

In `show_help`:

```bash
  echo "  migrate-db export [zip]   从当前实例导出 JSON 备份（H2/MySQL→PG 迁移第一步）"
  echo "  migrate-db import <zip>   将备份导入当前实例（恢复后自动重启；迁移第二步，目标为 PG 实例）"
```

- [ ] **Step 4: Verify parse + help**

Run: `bash -n scripts/alist-tvbox.sh && bash scripts/alist-tvbox.sh help | grep migrate-db`
Expected: no syntax errors; both help lines print.

- [ ] **Step 5: Commit**

```bash
git add scripts/alist-tvbox.sh
git commit -m "feat(script): add migrate-db subcommand for H2/MySQL->PostgreSQL migration"
```

---

## Task 9: Documentation

**Files:**
- Create: `docs/postgresql-migration.md`
- Modify: `docs/` deployment notes (add PostgreSQL profile usage)

- [ ] **Step 1: Write the migration guide**

Create `docs/postgresql-migration.md` covering:

1. **New PostgreSQL deployment** — set `SPRING_PROFILES_ACTIVE=postgresql` + `SPRING_DATASOURCE_JDBC-URL`/`USERNAME`/`PASSWORD` env, or run `./alist-tvbox.sh config-db`, choose PostgreSQL, enter URL/credentials, then `config-db apply`.
2. **Migrate from H2/MySQL to PostgreSQL**:
   - On the source instance: `./alist-tvbox.sh migrate-db export` → produces `database-json.zip`.
   - Stand up the target PostgreSQL instance (empty DB; Flyway creates the schema on first boot).
   - On the target: `./alist-tvbox.sh migrate-db import database-json.zip` → startup restore → auto-restart (exit 85).
3. **Caveats** — IDENTITY-table ids (`account`/`session`/`user`) are upserted by business key; admin is re-pinned to id=1 by `ensureAdminOccupiesIdOne`. The H2-only SQL backup path is not used; JSON restore is cross-database.
4. **Embedded AList DB** — set `type: "postgres"` in AList's `config.json` (alongside `sqlite3` default / `mysql`).

- [ ] **Step 2: Commit**

```bash
git add docs/postgresql-migration.md
git commit -m "docs: PostgreSQL deployment and migration guide"
```

---

## Task 10: Native image verification

**Files:** possibly `src/main/resources/META-INF/native-image/reflect-config.json` or `resource-config.json` (only if the native build fails).

- [ ] **Step 1: Build the native image**

Run: `mvn clean package -Pnative`
Expected: BUILD SUCCESS; produces `target/atv` (or `target/atv` per `imageName`).

- [ ] **Step 2: Run the native binary against a real PostgreSQL instance**

Start a local PG (e.g. `docker run -e POSTGRES_PASSWORD=... -p 5432:5432 postgres:16`), create the `alist_tvbox` DB, then:

```bash
SPRING_PROFILES_ACTIVE=postgresql \
SPRING_DATASOURCE_JDBC-URL='jdbc:postgresql://localhost:5432/alist_tvbox' \
SPRING_DATASOURCE_USERNAME=atv SPRING_DATASOURCE_PASSWORD='...' \
./target/atv
```

Expected: app boots, Flyway runs (watch logs for `Migrating schema ... to version "1" - create current schema`), no `unsupported protocol` / reflection errors.

- [ ] **Step 3: If Flyway's PostgreSQL DatabaseType is missing natively, register it**

If startup fails with a Flyway error about no database type for PostgreSQL, add the Flyway SPI service descriptor to native resources. Mirror how `flyway-mysql` is handled: add to `resource-config.json` the pattern `{"pattern": "META-INF/services/org.flywaydb.database.DatabaseType"}` (verify the exact SPI file name inside `flyway-database-postgresql.jar` via `unzip -l`) and, if needed, a reflect entry for the PostgreSQL `DatabaseType` implementation class. Re-build and re-run Step 2.

- [ ] **Step 4: Smoke-test CRUD through the native binary**

Via the admin UI or API, create/read a site and a user against PostgreSQL; confirm rows persist. Then trigger a JSON backup export (`/api/local/backup?format=json`) and confirm the zip is non-empty.

- [ ] **Step 5: Commit any native-image config additions**

```bash
git add src/main/resources/META-INF/native-image/
git commit -m "build(native): register PostgreSQL Flyway DatabaseType for native image"
```
(Skip if Step 2 passed with no config changes.)

---

## Self-Review (completed during authoring)

- **Spec coverage:** Phase 1 → Tasks 1–4,6; Phase 2 → Task 5; Phase 3 (migration tool, script per maintainer) → Task 8; `config-db` (JDBC-URL change, script, main DB only) → Task 7; Phase 4 (native) → Task 10; Phase 5 (tests + docs) → Tasks 6,9. All spec sections covered.
- **Checksum safety:** Task 3 Step 2 byte-diff-proves H2/MySQL files unchanged; `locations` change does not touch `flyway_schema_history` content.
- **id-preservation:** `GENERATED BY DEFAULT AS IDENTITY` (Task 2) + existing `restoreIdentity` upsert + `ensureAdminOccupiesIdOne` (Task 8 caveats) — no new restore code needed.
- **Type consistency:** `DB_TYPE`/`DB_JDBC_URL`/`DB_USERNAME`/`DB_PASSWORD`/`DB_DRIVER`/`DB_DIALECT` CONFIG keys used identically in `config_db` (write) and `start_container` (read). Profile value `postgresql` matches the `application-postgresql.yaml` filename and Flyway `{vendor}` folder.
