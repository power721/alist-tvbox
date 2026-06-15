# Database Sort Order Flyway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move reserved database columns away from `order` and ambiguous `site.version`, keep public JSON compatibility, and introduce a real Flyway-controlled schema path instead of the unused legacy migration directory.

**Architecture:** Java entities use safe internal names (`sortOrder`, `storageVersion`) mapped to safe database names (`sort_order`, `storage_version`). DTOs and serialized entity JSON keep legacy API names via Jackson annotations. Flyway scans a new clean migration location; the old duplicate-version migration files are not used.

**Tech Stack:** Spring Boot 3.5, Java 21, Spring Data JPA, Hibernate, Flyway, H2/MySQL runtime databases, JUnit 5, Mockito, Jackson.

---

## File Map

- Modify `pom.xml`: add Flyway dependencies.
- Modify `src/main/resources/application.yaml`: enable Flyway, point to clean migration location, change Hibernate to `validate`.
- Modify `src/main/resources/application-mysql.yaml`: keep MySQL dialect but do not re-enable `ddl-auto=update`.
- Move existing `src/main/resources/db/migration/*.sql` to `src/main/resources/db/migration-legacy/`.
- Create `src/main/resources/db/migration/current/V1__Create_current_schema.sql`: fresh schema baseline with `sort_order` and `storage_version`.
- Create `src/main/java/db/migration/V2__Normalize_reserved_columns.java`: compatibility migration for existing Hibernate-created databases.
- Modify entities: `Site`, `Navigation`, `Emby`, `Jellyfin`, `TelegramChannel`, `Feiniu`.
- Modify DTOs: `SiteDto`, `NavigationDto` only where needed to preserve JSON field names.
- Modify services: `SiteService`, `AListService`, `ShareService`, `Storage`, `AList`, `NavigationService`, `TelegramService`, `RemoteSearchService`, `EmbyService`, `JellyfinService`, `FeiniuService`, `SyncService`.
- Modify tests: `SiteServiceTest`.
- Create tests: `src/test/java/cn/har01d/alist_tvbox/entity/ReservedColumnMappingTest.java`, `src/test/java/cn/har01d/alist_tvbox/dto/SiteDtoJsonTest.java`.

## Task 1: Entity And DTO Naming Tests

**Files:**
- Create: `src/test/java/cn/har01d/alist_tvbox/entity/ReservedColumnMappingTest.java`
- Create: `src/test/java/cn/har01d/alist_tvbox/dto/SiteDtoJsonTest.java`

- [ ] **Step 1: Write the failing entity mapping test**

Create `src/test/java/cn/har01d/alist_tvbox/entity/ReservedColumnMappingTest.java`:

```java
package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservedColumnMappingTest {

    @Test
    void siteUsesSafeDatabaseColumnNames() throws Exception {
        Field sortOrder = Site.class.getDeclaredField("sortOrder");
        assertThat(sortOrder.getAnnotation(Column.class).name()).isEqualTo("sort_order");

        Field storageVersion = Site.class.getDeclaredField("storageVersion");
        assertThat(storageVersion.getAnnotation(Column.class).name()).isEqualTo("storage_version");
        assertThat(storageVersion.getAnnotation(Version.class)).isNull();

        assertThatThrownBy(() -> Site.class.getDeclaredField("order"))
                .isInstanceOf(NoSuchFieldException.class);
        assertThatThrownBy(() -> Site.class.getDeclaredField("version"))
                .isInstanceOf(NoSuchFieldException.class);
    }

    @Test
    void orderedEntitiesUseSortOrderColumn() throws Exception {
        assertSortOrderColumn(Navigation.class);
        assertSortOrderColumn(Emby.class);
        assertSortOrderColumn(Jellyfin.class);
        assertSortOrderColumn(TelegramChannel.class);
        assertSortOrderColumn(Feiniu.class);
    }

    private void assertSortOrderColumn(Class<?> type) throws Exception {
        Field sortOrder = type.getDeclaredField("sortOrder");
        assertThat(sortOrder.getAnnotation(Column.class).name()).isEqualTo("sort_order");
        assertThatThrownBy(() -> type.getDeclaredField("order"))
                .isInstanceOf(NoSuchFieldException.class);
    }
}
```

- [ ] **Step 2: Write the failing JSON compatibility test**

Create `src/test/java/cn/har01d/alist_tvbox/dto/SiteDtoJsonTest.java`:

```java
package cn.har01d.alist_tvbox.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SiteDtoJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsLegacyOrderAndVersionJsonNames() throws Exception {
        SiteDto dto = objectMapper.readValue("""
                {
                  "name": "本地",
                  "url": "http://localhost",
                  "order": 7,
                  "version": 4
                }
                """, SiteDto.class);

        assertThat(dto.getSortOrder()).isEqualTo(7);
        assertThat(dto.getStorageVersion()).isEqualTo(4);
    }

    @Test
    void writesLegacyOrderAndVersionJsonNames() throws Exception {
        SiteDto dto = new SiteDto();
        dto.setName("本地");
        dto.setUrl("http://localhost");
        dto.setSortOrder(7);
        dto.setStorageVersion(4);

        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).contains("\"order\":7");
        assertThat(json).contains("\"version\":4");
        assertThat(json).doesNotContain("sortOrder");
        assertThat(json).doesNotContain("storageVersion");
    }
}
```

- [ ] **Step 3: Run tests to verify RED**

Run:

```bash
mvn -Dtest=ReservedColumnMappingTest,SiteDtoJsonTest test
```

Expected: compilation fails or tests fail because `sortOrder` and `storageVersion` fields do not exist yet.

- [ ] **Step 4: Commit RED tests**

```bash
git add src/test/java/cn/har01d/alist_tvbox/entity/ReservedColumnMappingTest.java src/test/java/cn/har01d/alist_tvbox/dto/SiteDtoJsonTest.java
git commit -m "test: cover reserved column mappings"
```

## Task 2: Rename Entity Fields And Preserve JSON API

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/Site.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/Navigation.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/Emby.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/Jellyfin.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/TelegramChannel.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/entity/Feiniu.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/dto/SiteDto.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/dto/NavigationDto.java`

- [ ] **Step 1: Update `Site`**

Replace the reserved fields in `Site` with:

```java
    @Column(name = "sort_order")
    @JsonProperty("order")
    private int sortOrder;
    @Column(name = "storage_version")
    @JsonProperty("version")
    private Integer storageVersion;
```

Add import:

```java
import com.fasterxml.jackson.annotation.JsonProperty;
```

- [ ] **Step 2: Update ordered entities**

For `Navigation`, `Emby`, `Jellyfin`, `TelegramChannel`, and `Feiniu`, replace:

```java
    @Column(name = "`order`")
    private Integer order;
```

or:

```java
    @Column(name = "`order`")
    private int order;
```

with the matching type:

```java
    @Column(name = "sort_order")
    @JsonProperty("order")
    private Integer sortOrder;
```

or:

```java
    @Column(name = "sort_order")
    @JsonProperty("order")
    private int sortOrder;
```

Add `com.fasterxml.jackson.annotation.JsonProperty` imports.

- [ ] **Step 3: Update `Navigation` constructors**

Inside `Navigation`, update constructor assignments from:

```java
this.order = order;
```

to:

```java
this.sortOrder = order;
```

- [ ] **Step 4: Update `SiteDto`**

Replace the final two fields with:

```java
    @JsonProperty("order")
    private int sortOrder;
    @JsonProperty("version")
    private Integer storageVersion;
```

Add import:

```java
import com.fasterxml.jackson.annotation.JsonProperty;
```

- [ ] **Step 5: Update `NavigationDto` construction**

Replace:

```java
setOrder(navigation.getOrder());
```

with:

```java
setOrder(navigation.getSortOrder());
```

Keep the DTO property named `order`.

- [ ] **Step 6: Run focused tests to verify GREEN**

Run:

```bash
mvn -Dtest=ReservedColumnMappingTest,SiteDtoJsonTest test
```

Expected: both tests pass.

- [ ] **Step 7: Commit entity and DTO rename**

```bash
git add src/main/java/cn/har01d/alist_tvbox/entity src/main/java/cn/har01d/alist_tvbox/dto src/test/java/cn/har01d/alist_tvbox/entity/ReservedColumnMappingTest.java src/test/java/cn/har01d/alist_tvbox/dto/SiteDtoJsonTest.java
git commit -m "refactor: use safe sort and storage version fields"
```

## Task 3: Update Services To New Java Properties

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SiteService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/AListService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/ShareService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/storage/Storage.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/storage/AList.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/NavigationService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/TelegramService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/RemoteSearchService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/EmbyService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/JellyfinService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/FeiniuService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/sync/SyncService.java`

- [ ] **Step 1: Replace site property calls**

Replace `site.setOrder(...)` with `site.setSortOrder(...)`.
Replace `site.getOrder()` with `site.getSortOrder()`.
Replace `site.setVersion(...)` with `site.setStorageVersion(...)`.
Replace `site.getVersion()` with `site.getStorageVersion()`.
Replace `Sort.by("order")` with `Sort.by("sortOrder")`.

Required examples in `SiteService`:

```java
site.setStorageVersion(s.getVersion());
site.setSortOrder(order);
Sort sort = Sort.by("sortOrder");
Storage storage = site.getStorageVersion() == 4 ? new OpenList(site) : new AList(site);
site.setSortOrder(dto.getSortOrder());
site.setStorageVersion(dto.getStorageVersion());
```

- [ ] **Step 2: Replace media and navigation property calls**

Use these replacements:

```java
Emby::getOrder -> Emby::getSortOrder
Jellyfin::getOrder -> Jellyfin::getSortOrder
item.getOrder() -> item.getSortOrder()
setOrder(...) -> setSortOrder(...)
getOrder() -> getSortOrder()
Sort.by("order") -> Sort.by("sortOrder")
```

Only apply these replacements to the renamed entities. Do not change `Ordered.HIGHEST_PRECEDENCE`, subscription JSON maps, or unrelated DTOs that intentionally expose `order`.

- [ ] **Step 3: Update sync import copying**

In `SyncService`, use:

```java
local.setSortOrder(remote.getSortOrder());
local.setStorageVersion(remote.getStorageVersion());
```

For Jellyfin, Emby, and Feiniu use:

```java
local.setSortOrder(remote.getSortOrder());
```

- [ ] **Step 4: Run compile-focused tests**

Run:

```bash
mvn -Dtest=ReservedColumnMappingTest,SiteDtoJsonTest,SiteServiceTest test
```

Expected: compile succeeds; tests may fail only where `SiteServiceTest` still expects the old insert SQL.

- [ ] **Step 5: Commit service updates**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service src/main/java/cn/har01d/alist_tvbox/storage src/main/java/cn/har01d/alist_tvbox/dto src/main/java/cn/har01d/alist_tvbox/entity
git commit -m "refactor: update services for safe persistence fields"
```

## Task 4: SiteService SQL Regression Test

**Files:**
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/SiteServiceTest.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SiteService.java`

- [ ] **Step 1: Update failing test expectation**

In `SiteServiceTest`, update the insert SQL verification to expect `sort_order` and `storage_version`:

```java
verify(jdbcTemplate).update(
        startsWith("INSERT INTO site"),
        eq(1),
        eq("本地"),
        eq("http://localhost"),
        eq(""),
        startsWith("openlist-"),
        eq(null),
        eq(""),
        eq(true),
        eq(false),
        eq(false),
        eq(1),
        eq(3)
);
```

Also assert the SQL contains the new columns:

```java
verify(jdbcTemplate).update(
        argThat(sql -> sql.contains("sort_order") && sql.contains("storage_version")),
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any(),
        any()
);
```

- [ ] **Step 2: Run test to verify RED**

Run:

```bash
mvn -Dtest=SiteServiceTest test
```

Expected: fails because `insertDefaultSite` does not write `sort_order` and `storage_version`.

- [ ] **Step 3: Update `insertDefaultSite`**

Use this SQL in `SiteService`:

```java
jdbcTemplate.update("""
        INSERT INTO site
        (id, name, url, password, token, index_file, folder, searchable, disabled, xiaoya, sort_order, storage_version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        site.getId(),
        site.getName(),
        site.getUrl(),
        site.getPassword(),
        site.getToken(),
        site.getIndexFile(),
        site.getFolder(),
        site.isSearchable(),
        site.isDisabled(),
        site.isXiaoya(),
        site.getSortOrder(),
        site.getStorageVersion());
```

- [ ] **Step 4: Run test to verify GREEN**

Run:

```bash
mvn -Dtest=SiteServiceTest test
```

Expected: passes.

- [ ] **Step 5: Commit SiteService SQL fix**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/SiteService.java src/test/java/cn/har01d/alist_tvbox/service/SiteServiceTest.java
git commit -m "fix: write safe site column names"
```

## Task 5: Replace Invalid Flyway History With Clean Location

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/main/resources/application-mysql.yaml`
- Move: `src/main/resources/db/migration/*.sql` to `src/main/resources/db/migration-legacy/`
- Create: `src/main/resources/db/migration/current/V1__Create_current_schema.sql`

- [ ] **Step 1: Move legacy migration files**

Run:

```bash
mkdir -p src/main/resources/db/migration-legacy
git mv src/main/resources/db/migration/*.sql src/main/resources/db/migration-legacy/
mkdir -p src/main/resources/db/migration/current
```

Expected: `src/main/resources/db/migration` no longer contains duplicate `V3`/`V4` SQL files.

- [ ] **Step 2: Add Flyway dependencies**

In `pom.xml`, add after `spring-boot-starter-data-jpa`:

```xml
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
        </dependency>
```

- [ ] **Step 3: Configure Flyway and validation**

In `application.yaml`, replace JPA schema settings with:

```yaml
  jpa:
    hibernate:
      ddl-auto: validate
    database-platform: org.hibernate.dialect.H2Dialect
    open-in-view: false
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration/current
```

Remove:

```yaml
    defer-datasource-initialization: true
    properties:
      hibernate:
        hbm2ddl:
          auto: update
```

Keep `spring.sql.init.mode: always` only if it is still required for data scripts; do not use it for schema.

In `application-mysql.yaml`, change:

```yaml
    hibernate:
      ddl-auto: update
```

to:

```yaml
    hibernate:
      ddl-auto: validate
```

- [ ] **Step 4: Create fresh schema baseline**

Create `src/main/resources/db/migration/current/V1__Create_current_schema.sql` by normalizing the old `V1__Create_tables.sql` and merging the plugin-related legacy migrations into one current schema:

- Remove `PUBLIC.` schema prefixes.
- Use lowercase table and column names.
- Use `sort_order` instead of `order`.
- Use `storage_version` instead of `site.version`.
- Include the current `plugin` and `plugin_filter` columns from legacy `V2` through `V6`.
- Include indexes from legacy `V7`.
- Keep AList embedded runtime tables out of this schema.

The `site` table block must be:

```sql
CREATE TABLE IF NOT EXISTS site
(
    id              INTEGER NOT NULL PRIMARY KEY,
    disabled        BOOLEAN NOT NULL,
    folder          VARCHAR(255),
    index_file      VARCHAR(255),
    name            VARCHAR(255),
    sort_order      INTEGER,
    password        VARCHAR(255),
    searchable      BOOLEAN NOT NULL,
    token           VARCHAR(255),
    url             VARCHAR(255),
    storage_version INTEGER,
    xiaoya          BOOLEAN DEFAULT FALSE
);
```

The ordered table blocks must use:

```sql
sort_order INTEGER
```

for `navigation`, `emby`, `jellyfin`, `telegram_channel`, and `feiniu`.

- [ ] **Step 5: Run a fresh H2 startup test**

Run:

```bash
mvn -Dtest=ReservedColumnMappingTest,SiteDtoJsonTest,SiteServiceTest test
```

Expected: tests pass and Flyway does not reject duplicate migrations.

- [ ] **Step 6: Commit Flyway location and baseline**

```bash
git add pom.xml src/main/resources/application.yaml src/main/resources/application-mysql.yaml src/main/resources/db
git commit -m "feat: add clean flyway schema baseline"
```

## Task 6: Existing Database Compatibility Migration

**Files:**
- Create: `src/main/java/db/migration/V2__Normalize_reserved_columns.java`

- [ ] **Step 1: Create Java migration**

Create `src/main/java/db/migration/V2__Normalize_reserved_columns.java`:

```java
package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

public class V2__Normalize_reserved_columns extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        migrateSortOrder(connection, "site");
        migrateSortOrder(connection, "navigation");
        migrateSortOrder(connection, "emby");
        migrateSortOrder(connection, "jellyfin");
        migrateSortOrder(connection, "telegram_channel");
        migrateSortOrder(connection, "feiniu");
        migrateSiteVersion(connection);
    }

    private void migrateSortOrder(Connection connection, String table) throws SQLException {
        if (!tableExists(connection, table)) {
            return;
        }
        if (!columnExists(connection, table, "sort_order")) {
            execute(connection, "ALTER TABLE " + quote(connection, table) + " ADD COLUMN " + quote(connection, "sort_order") + " INTEGER DEFAULT 0");
        }
        if (columnExists(connection, table, "order")) {
            execute(connection, "UPDATE " + quote(connection, table)
                    + " SET " + quote(connection, "sort_order") + " = " + quote(connection, "order")
                    + " WHERE " + quote(connection, "sort_order") + " IS NULL OR " + quote(connection, "sort_order") + " = 0");
            execute(connection, "ALTER TABLE " + quote(connection, table) + " DROP COLUMN " + quote(connection, "order"));
        }
    }

    private void migrateSiteVersion(Connection connection) throws SQLException {
        String table = "site";
        if (!tableExists(connection, table)) {
            return;
        }
        if (!columnExists(connection, table, "storage_version")) {
            execute(connection, "ALTER TABLE " + quote(connection, table) + " ADD COLUMN " + quote(connection, "storage_version") + " INTEGER");
        }
        if (columnExists(connection, table, "version")) {
            execute(connection, "UPDATE " + quote(connection, table)
                    + " SET " + quote(connection, "storage_version") + " = " + quote(connection, "version")
                    + " WHERE " + quote(connection, "storage_version") + " IS NULL");
            execute(connection, "ALTER TABLE " + quote(connection, table) + " DROP COLUMN " + quote(connection, "version"));
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getTables(connection.getCatalog(), schemaPattern(connection), tablePattern(connection, table), null)) {
            while (resultSet.next()) {
                if (resultSet.getString("TABLE_NAME").equalsIgnoreCase(table)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(connection.getCatalog(), schemaPattern(connection), tablePattern(connection, table), columnPattern(connection, column))) {
            while (resultSet.next()) {
                if (resultSet.getString("COLUMN_NAME").equalsIgnoreCase(column)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String schemaPattern(Connection connection) throws SQLException {
        String schema = connection.getSchema();
        return schema == null || schema.isBlank() ? null : schema;
    }

    private String tablePattern(Connection connection, String table) throws SQLException {
        return storesUpperCaseIdentifiers(connection) ? table.toUpperCase(Locale.ROOT) : table;
    }

    private String columnPattern(Connection connection, String column) throws SQLException {
        return storesUpperCaseIdentifiers(connection) ? column.toUpperCase(Locale.ROOT) : column;
    }

    private boolean storesUpperCaseIdentifiers(Connection connection) throws SQLException {
        return connection.getMetaData().storesUpperCaseIdentifiers();
    }

    private String quote(Connection connection, String identifier) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            return identifier;
        }
        return quote + identifier + quote;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
```

- [ ] **Step 2: Run focused tests**

Run:

```bash
mvn -Dtest=ReservedColumnMappingTest,SiteDtoJsonTest,SiteServiceTest test
```

Expected: passes.

- [ ] **Step 3: Commit compatibility migration**

```bash
git add src/main/java/db/migration/V2__Normalize_reserved_columns.java
git commit -m "feat: migrate reserved database columns"
```

## Task 7: Full Verification

**Files:**
- All modified files.

- [ ] **Step 1: Search for forbidden mappings**

Run:

```bash
rg -n 'Column\(name = "`order`"\)|Sort\.by\("order"\)|getOrder\(\)|setOrder\(|getVersion\(\)|setVersion\(' src/main/java/cn/har01d/alist_tvbox/entity src/main/java/cn/har01d/alist_tvbox/service src/main/java/cn/har01d/alist_tvbox/storage src/main/java/cn/har01d/alist_tvbox/dto
```

Expected: no matches for renamed persistence fields. Matches in plugin version code or DTOs that intentionally expose `order` are acceptable only outside the listed target files or after manual review.

- [ ] **Step 2: Run backend tests**

Run:

```bash
mvn test
```

Expected: build success with 0 test failures.

- [ ] **Step 3: Inspect diff**

Run:

```bash
git diff --stat HEAD~6..HEAD
git status --short
```

Expected: status clean; diff contains only database governance, entity/service rename, tests, and migration changes.

- [ ] **Step 4: Commit any verification-only fixes**

If verification requires small fixes:

```bash
git add pom.xml src/main/resources/application.yaml src/main/resources/application-mysql.yaml src/main/resources/db src/main/java/db/migration src/main/java/cn/har01d/alist_tvbox src/test/java/cn/har01d/alist_tvbox
git commit -m "fix: complete database migration verification"
```

Do not commit unrelated formatting or refactors.

---

## Self-Review

- Spec coverage: the plan covers legacy migration invalidity, safe sort columns, `site.storage_version`, API compatibility, Flyway configuration, compatibility migration, and verification.
- Placeholder scan: no `TBD` or deferred steps remain.
- Type consistency: Java property names are `sortOrder` and `storageVersion`; JSON names remain `order` and `version`; database columns are `sort_order` and `storage_version`.
