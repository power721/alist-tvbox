# PostgreSQL Support Design

## Goal

Make PostgreSQL a first-class supported database for AList-TvBox, alongside the existing H2 (default) and MySQL (profile). A user currently deployed on MySQL wants to run the application against PostgreSQL. This requires: main-app DB support, embedded AList runtime DB support, a data-migration path from H2/MySQL into PostgreSQL, GraalVM native-image support, and verification.

## Scope

**In scope**

- Main application database: boot, Flyway schema, Hibernate `validate`, full CRUD on PostgreSQL.
- Embedded AList runtime database (`DatabaseConfig.alistDataSource()`): accept `type: "postgres"` in AList's `config.json`.
- Data migration tool: move an existing H2/MySQL deployment's data into a fresh PostgreSQL instance.
- GraalVM native image: PostgreSQL must work under `-Pnative`, not only JVM mode.
- Tests (Testcontainers) + documentation.

**Out of scope**

- Dropping or altering H2/MySQL support. Existing deployments must be byte-for-byte unaffected.
- Renaming any reserved-word column (`year`, `key`, `value`, `extend`, `version`). Column names stay identical across all three databases.
- A single "portable ANSI SQL" migration file. Proven infeasible: reserved-word identifier quoting differs per dialect (backticks vs double quotes) and `AUTO_INCREMENT` vs `IDENTITY` cannot be expressed in one SQL file. See "Approaches Considered".
- New schema/tables. No entity changes.

## Background / Current State

- DB selection today: Spring profile + `spring.datasource.*` override. `application.yaml` (H2) and `application-mysql.yaml`. Hibernate dialect is hardcoded per profile (`H2Dialect` / `MySQL8Dialect`).
- `pom.xml` has `h2`, `mysql-connector-j`, `sqlite-jdbc`, `flyway-core`, `flyway-mysql`. **No PostgreSQL driver.**
- Migrations live in a single shared folder `db/migration/current/`: SQL `V1`, `V4`, `V5` + Java `V2`, `V3`. `flyway.locations: classpath:db/migration/current`.
- ID strategy: the vast majority of entities use `@TableGenerator` against the `id_generator` table (dialect-agnostic). Only `account`, `session`, `user` use `GenerationType.IDENTITY` (rely on DB auto-increment).
- `V1__Create_current_schema.sql` is PostgreSQL-incompatible: `AUTO_INCREMENT` (3 tables), `TINYINT` (4 columns), `LONGTEXT` (1 column), backtick-quoted reserved words (5 columns).
- `V2__Normalize_reserved_columns.java` **already branches on PostgreSQL** (prior partial work).
- `V4` / `V5` SQL are cross-dialect portable (`ADD COLUMN IF NOT EXISTS`, derived-table `DELETE`, `ADD CONSTRAINT ... UNIQUE`).
- Backup/restore is a modular JSON system (`DatabaseBackupService`, `BackupModuleRegistry`, `BackupModuleHandler`, `StartupJsonRestoreRunner`) that persists via JPA — inherently cross-database. A SQL (H2 `SCRIPT`) path exists as an H2-only fallback.
- `DatabaseConfig` already contains an unused `generatePostgresJdbcUrl()` helper.
- Native image: published (`--static --libc=musl`). Drivers register via SPI. `resource-config.json` already includes wildcard pattern `db/migration/.*` (covers new subfolders).

## Architecture

Five sequential phases. Phases 1–2 share the driver foundation; Phase 3 depends on Phase 1; Phase 4 verifies Phases 1–2 under native.

```
Phase 1 Main-app DB (driver, Flyway reorg, PG V1, profile) ─┬─► Phase 3 Migration tool
Phase 2 Embedded AList DB (alistDataSource branch)         ─┤
Phase 4 Native image verification ◄── (covers 1 & 2)
Phase 5 Testcontainers tests + docs
```

No new layers. Changes touch: `pom.xml`, Flyway resource layout, `application*.yaml`, `DatabaseConfig`, a thin migration CLI, tests, docs.

## Phase 1 — Main Application Database

### 1.1 Dependencies (`pom.xml`)

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

`flyway-database-postgresql` is required because Flyway 10+ splits database support into modules (mirrors the existing `flyway-mysql`). Versions are managed by the Spring Boot 3.5 BOM.

### 1.2 Migration Folder Reorganization (Approach A)

Switch to Flyway's built-in `{vendor}` resolution plus a shared `common` folder:

```
src/main/resources/db/migration/
├── h2/          V1__Create_current_schema.sql   (today's current/V1, byte-identical)
├── mysql/       V1__Create_current_schema.sql   (same bytes as h2/V1)
├── postgresql/  V1__Create_current_schema.sql   (NEW — see 1.3)
└── common/      V4__fix_missing_external_id.sql
                 V5__dedup_users_unique_username.sql   (cross-dialect portable)
```

`application.yaml`:
```yaml
spring.flyway.locations: classpath:db/migration/{vendor},classpath:db/migration/common
```

Rules:

- **Java `V2` / `V3` do not move.** They stay in package `db.migration.current` and are discovered via the existing `META-INF/services/org.flywaydb.core.api.migration.JavaMigration` SPI descriptor (works in both JVM and native). `NativeFlywayMigrationConfig` is unchanged. Once `current` is no longer a scanned location, the Java migrations are found solely via the SPI — no double discovery.
- **Existing H2/MySQL deployments are unaffected.** Flyway tracks `flyway_schema_history` by version + checksum + description + type, not by file path. Moving `V1`/`V4`/`V5` to new folders without changing bytes keeps checksums identical → validation passes. The `locations` change itself does not invalidate history. **Verification requirement: byte-compare moved SQL files against their `current/` originals.**
- The old `db/migration/current/` resource folder ends up holding no SQL (Java classes remain under `src/main/java/db/migration/current/`).

### 1.3 PostgreSQL V1 (`postgresql/V1__Create_current_schema.sql`)

Same 40+ tables, same column names. Only type/syntax changes:

| Change | From (H2/MySQL) | To (PostgreSQL) |
|---|---|---|
| IDENTITY tables `account`, `session`, `user` PK | `INTEGER AUTO_INCREMENT PRIMARY KEY` | `INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` |
| 4 `type`/`result`/`status` columns | `TINYINT` | `SMALLINT` |
| `device.config` | `LONGTEXT` | `TEXT` |
| 5 reserved-word columns (`year`, `key`, `value`, `extend`, `version`) | backticks `` `year` `` | double quotes `"year"` (**name unchanged**) |
| All other tables/columns | unchanged | unchanged (`@TableGenerator` / `id_generator` is dialect-agnostic) |

`BY DEFAULT` (not `ALWAYS`) is mandatory: it permits explicit id insertion so JSON restore can preserve ids (e.g. admin `user.id = 1` — the exact failure `V5` repaired).

All five reserved words are non-reserved in PostgreSQL, so double-quoting is defensive insurance (future-proofing `value`) rather than a functional requirement. Column-name bytes stay identical across H2/MySQL/PostgreSQL so JPA mappings and JSON field names are uniform.

### 1.4 Configuration (`application-postgresql.yaml`)

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
```

Explicit dialect, consistent with the existing `application-mysql.yaml` pattern; no change to H2/MySQL. Users select PostgreSQL via `--spring.profiles.active=postgresql` plus datasource overrides (env vars / CLI).

## Phase 2 — Embedded AList Runtime Database

Add a `postgresql` branch to `DatabaseConfig.alistDataSource()`, wiring the existing-but-unused `generatePostgresJdbcUrl()`:

```java
} else if ("postgres".equals(type) || "postgresql".equals(type)) {
    String url = generatePostgresJdbcUrl(database);
    log.info("===> AList use postgresql database url: {}", url);
    return DataSourceBuilder.create()
            .url(url)
            .username(database.get("user").asText())
            .password(database.get("password").asText())
            .driverClassName("org.postgresql.Driver")
            .build();
}
```

Shares the Phase 1 PostgreSQL driver; no additional native cost.

**Confirmed:** the embedded AList runtime accepts `type: "postgres"` in `config.json` alongside the `sqlite3` default and `mysql`. The branch matches on `"postgres"` (AList's actual config value) and also accepts `"postgresql"` as an alias.

## Phase 3 — Data Migration Tool (H2/MySQL → PostgreSQL)

Mechanism: reuse the existing modular JSON backup/restore (JPA-backed, cross-database by construction).

1. Run the app against the **source** DB (H2/MySQL); export full JSON via `DatabaseBackupService`.
2. Provision a fresh **PostgreSQL** instance; Flyway creates the empty schema.
3. Run the app against PostgreSQL; import the JSON via `StartupJsonRestoreRunner`.

Deliverables (lightweight, no new dump format):

- A user-facing migration tool exposed as a `migrate-db` subcommand of `scripts/alist-tvbox.sh` (per maintainer preference: script-based, not a new in-app CLI). Two operations, both reusing existing infrastructure:
  - `migrate-db export` — reads `/data/atv/backup_token`, calls the existing token-guarded `POST /api/local/backup?format=json` endpoint, saves `database-json.zip`. Run against the **source** (H2/MySQL) instance.
  - `migrate-db import <zip>` — copies the zip to the startup-restore path (`/data/database-json.zip`, i.e. `app.backup.json-restore-path`), restarts the service; `StartupJsonRestoreRunner` restores on the next boot. Run on the **target** PostgreSQL instance.
- A migration guide under `docs/` covering the end-to-end procedure and caveats.

A UI wizard is a possible future enhancement but is intentionally out of scope here: the script approach fits the existing single-DB-per-instance + startup-restore architecture with far less risk than a dual-DataSource in-app feature.

**Key verification:** explicit-id preservation for the three IDENTITY tables (`account`, `session`, `user`) under PostgreSQL. `BY DEFAULT AS IDENTITY` permits explicit-id inserts at the DDL level; the existing restore path (`BackupModuleHandler.restoreIdentity`) already upserts by business key and `DatabaseBackupService` calls `userService.ensureAdminOccupiesIdOne()` to re-establish the admin=1 invariant. This is confirmed by code inspection — the migration tool reuses it unchanged.

## Phase 3b — DB Switching Tool (`config-db`)

Per maintainer direction, users must be able to change the main app DB JDBC URL without redeploying. Delivered as a `config-db` subcommand of `scripts/alist-tvbox.sh` (script-based, not UI; main app DB only — the embedded AList DB keeps its `config.json` mechanism).

- `config-db` (interactive) — choose H2 / MySQL / PostgreSQL, enter JDBC URL + username + password. Persists `DB_TYPE`, `DB_JDBC_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_DRIVER`, `DB_DIALECT` into the script's existing `CONFIG_FILE`.
- `config-db apply` — stops/removes/recreates the container so `start_container` injects the saved values as env (`SPRING_DATASOURCE_JDBC-URL`, `...USERNAME`, `...PASSWORD`, `...DRIVER-CLASS-NAME`, `SPRING_JPA_DATABASE-PLATFORM`, and `SPRING_PROFILES_ACTIVE=<type>`, which adds the `postgresql`/`mysql` profile to the image CMD's defaults).
- Choosing H2 unsets the keys → the container reverts to the image's built-in H2 default.

The `start_container` `run_args` array is extended to emit these `-e` entries only when `DB_TYPE` is set, so existing H2 deployments that never run `config-db` are untouched.

## Phase 4 — Native Image Verification

- The PostgreSQL JDBC driver and `flyway-database-postgresql` register via SPI. Spring Boot AOT handles known JDBC drivers; `flyway-mysql` already works natively via the same path — follow that precedent.
- `resource-config.json` already carries `db/migration/.*` (covers `postgresql/` and `common/`). **Likely no change**; verify the new SQL files are embedded.
- **Verification step:** `mvn clean package -Pnative` → run `./target/atv` against a real PostgreSQL instance → Flyway runs → app boots → basic read/write. If Flyway's `DatabaseType` SPI for PostgreSQL is missing at native runtime, add the needed reflect/resource hints (mirror the current `flyway-mysql` setup).

## Phase 5 — Testing and Documentation

- **Testcontainers integration test:** spin up a PostgreSQL container, assert Flyway applies `V1`–`V5` cleanly, Hibernate `ddl-auto: validate` passes (schema matches entities), and a basic CRUD round-trip works. Optional: a MySQL container test to guard regression.
- `application-test.yaml` stays H2 for fast unit tests; the PostgreSQL test is a separate integration test/profile.
- Documentation: deployment notes covering the `postgresql` profile, and the migration guide from Phase 3.

## Compatibility

- **Existing H2 users:** zero impact. Same default profile; `V1`/`V4`/`V5` bytes preserved (checksums stable); Java migrations unchanged.
- **Existing MySQL users:** zero impact. `application-mysql.yaml` untouched; `mysql/V1` is byte-identical to today's `current/V1`.
- **PostgreSQL users (new):** fully supported in JVM and native; data importable from H2/MySQL via JSON restore.
- No API changes. No DTO/entity changes. No breaking config changes.

## Approaches Considered

- **(Chosen) A — Flyway `{vendor}` folders + `common`.** Per-dialect DDL where it must differ, shared SQL/Java elsewhere. Existing deployments validate cleanly. Matches the `V2` vendor-branching style.
- **(Rejected) B — Single portable ANSI SQL `V1`.** Infeasible: identifier quoting differs per dialect (backticks vs double quotes) and `AUTO_INCREMENT` vs `IDENTITY` cannot be unified in one SQL file.
- **(Rejected) C — Rewrite `V1` as a Java migration.** Verbose (40+ tables), changes checksums (breaks existing deployments), adds native-registration surface. Low value vs. A.

## Open Questions / Verification Items

These do not block the design; they are tracked into the implementation plan:

1. **(Resolved)** Embedded AList accepts `type: "postgres"` — confirmed.
2. **Phase 4:** Does `flyway-database-postgresql`'s `DatabaseType` SPI need manual reflect/resource hints for native, or does it auto-register like `flyway-mysql`?
3. **Phase 3:** Does `StartupJsonRestoreRunner` preserve explicit ids for IDENTITY tables under PostgreSQL? (Confirm against prior id-preservation findings.)
4. **Phase 1:** Byte-compare moved SQL files against originals to prove existing H2/MySQL checksums are unchanged.
