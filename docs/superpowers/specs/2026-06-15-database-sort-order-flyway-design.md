# Database Sort Order And Flyway Design

## Goal

Move schema ownership from Hibernate auto-update to Flyway migrations, and remove `order` as a physical database column from project-owned business tables. The runtime API remains compatible: frontend and external JSON payloads continue to use `order`.

The existing `src/main/resources/db/migration` files are not treated as a valid Flyway history. The current application has no Flyway dependency, uses Hibernate `ddl-auto=update`, and contains duplicate migration versions. Enabling Flyway on those files as-is would be unsafe.

## Scope

In scope:

- Add Flyway to the backend.
- Enable Flyway migrations with `baseline-on-migrate`.
- Change JPA schema handling from `update` to `validate`.
- Rename project-owned `order` columns to `sort_order`.
- Rename `site.version` to `site.storage_version`.
- Update Java entity fields to `sortOrder`.
- Update `Site.version` to `Site.storageVersion`.
- Keep DTO and JSON compatibility for the public `order` property.
- Keep DTO and JSON compatibility for the public `version` property.
- Add focused tests for site migration-sensitive behavior.

Tables in scope:

- `site`
- `navigation`
- `emby`
- `jellyfin`
- `telegram_channel`
- `feiniu`

Out of scope:

- AList embedded runtime tables, including SQL strings that target AList's own schema such as `x_storages.order`.
- Subscription JSON semantics that intentionally use an external `order` property.
- Broad refactoring of unrelated persistence code.
- Introducing JPA optimistic locking for `Site`.

## Architecture

Flyway becomes the only component responsible for schema changes. Hibernate validates the mapped schema at startup and no longer creates or alters tables.

The mapping rule is:

- Database columns use `snake_case`.
- Java entity fields use `camelCase`.
- SQL keywords are not used as physical column names.
- Public JSON fields may keep legacy names where required for compatibility.

For sort positions, database columns become `sort_order`, Java entity fields become `sortOrder`, and DTO JSON stays `order`.

For site storage protocol version, database column becomes `storage_version`, Java entity field becomes `storageVersion`, and DTO JSON stays `version`. This field is business data, not an optimistic-lock field, so it must not use JPA `@Version`.

## Migration Strategy

The current migration folder is invalid as a Flyway source because:

- `pom.xml` has no Flyway dependency, so these files have not been applied by the application.
- `application.yaml` and `application-mysql.yaml` rely on Hibernate `ddl-auto=update`.
- There are duplicate versions (`V3` and `V4`), which Flyway rejects.
- `V1__Create_tables.sql` uses H2-oriented schema syntax such as `PUBLIC`, which is not a safe MySQL/PostgreSQL baseline.

Do not enable Flyway against this directory unchanged.

Implementation should first replace or archive the invalid migration history, then create a real Flyway baseline for current schema ownership. The new baseline should be written for the databases this project supports. If one SQL file cannot be made portable across H2, MySQL, and PostgreSQL, use vendor-specific migration locations.

For existing installations, use `baseline-on-migrate=true` so Flyway can adopt non-empty schemas that were created by Hibernate. Then run a compatibility migration that:

- Adds `sort_order` columns where missing.
- Copies data from old `order` columns where present.
- Drops old `order` columns where the database supports safe conditional drop.
- Adds `site.storage_version` where missing.
- Copies data from old `site.version` where present.
- Drops old `site.version` where safe.

The compatibility migration must handle existing installations that may already have drifted because Hibernate `ddl-auto=update` was previously enabled. It should be idempotent where the target database supports conditional DDL.

Fresh databases must be created with `sort_order` and `storage_version` from the new baseline, not by relying on Hibernate auto-update.

## Java Changes

Update these entity fields and their column mappings:

- `Site.order` -> `Site.sortOrder`
- `Navigation.order` -> `Navigation.sortOrder`
- `Emby.order` -> `Emby.sortOrder`
- `Jellyfin.order` -> `Jellyfin.sortOrder`
- `TelegramChannel.order` -> `TelegramChannel.sortOrder`
- `Feiniu.order` -> `Feiniu.sortOrder`
- `Site.version` -> `Site.storageVersion`

Update service sorting from `Sort.by("order")` to `Sort.by("sortOrder")` for those entities. Update local comparisons and sync copy logic to use the new Java property.

`SiteDto` will expose JSON property `order` while internally using `sortOrder`, so existing frontend code and external callers do not need to change.

`SiteDto` will expose JSON property `version` while internally using `storageVersion`, so existing frontend code and external callers do not need to change.

## Configuration Changes

Add `org.flywaydb:flyway-core`.

Configure:

- `spring.flyway.enabled=true`
- `spring.flyway.baseline-on-migrate=true`
- `spring.jpa.hibernate.ddl-auto=validate`

Remove the duplicate Hibernate `hbm2ddl.auto=update` setting.

Database profiles should not re-enable Hibernate schema updates.

## Testing

Use TDD for implementation:

1. Add a failing test for `SiteService` showing that default site insert SQL writes `sort_order`.
2. Add a failing test showing that default site insert SQL writes `storage_version`.
3. Add or adjust tests that prove site ordering uses the `sortOrder` Java property while JSON compatibility still accepts and emits `order`.
4. Add or adjust tests that prove site version uses the `storageVersion` Java property while JSON compatibility still accepts and emits `version`.
5. Implement the minimal production changes.
6. Run focused tests first, then `mvn test`.

## Compatibility

Existing API clients keep sending and receiving `order`.

Existing databases migrate data from `order` to `sort_order`.

Existing databases migrate `site.version` to `site.storage_version`.

New databases get `sort_order` and `storage_version` from the Flyway baseline and pass Hibernate validation without relying on auto-update.
