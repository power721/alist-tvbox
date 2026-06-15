# Database Sort Order And Flyway Design

## Goal

Move schema ownership from Hibernate auto-update to Flyway migrations, and remove `order` as a physical database column from project-owned business tables. The runtime API remains compatible: frontend and external JSON payloads continue to use `order`.

## Scope

In scope:

- Add Flyway to the backend.
- Enable Flyway migrations with `baseline-on-migrate`.
- Change JPA schema handling from `update` to `validate`.
- Rename project-owned `order` columns to `sort_order`.
- Update Java entity fields to `sortOrder`.
- Keep DTO and JSON compatibility for the public `order` property.
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

## Architecture

Flyway becomes the only component responsible for schema changes. Hibernate validates the mapped schema at startup and no longer creates or alters tables.

The mapping rule is:

- Database columns use `snake_case`.
- Java entity fields use `camelCase`.
- SQL keywords are not used as physical column names.
- Public JSON fields may keep legacy names where required for compatibility.

For sort positions, database columns become `sort_order`, Java entity fields become `sortOrder`, and DTO JSON stays `order`.

## Migration Strategy

Add a new Flyway version after the current latest migration. The migration creates `sort_order`, copies values from the old `order` column where it exists, and then drops the old column.

The migration must handle existing installations that may already have drifted because Hibernate `ddl-auto=update` was previously enabled. It should be idempotent where the target database supports conditional DDL. If a fully portable single SQL script is not reliable across H2, MySQL, and PostgreSQL, the implementation will split migrations by vendor-specific locations.

The existing `V1__Create_tables.sql` should also be updated so fresh databases are created with `sort_order`, not `order`.

## Java Changes

Update these entity fields and their column mappings:

- `Site.order` -> `Site.sortOrder`
- `Navigation.order` -> `Navigation.sortOrder`
- `Emby.order` -> `Emby.sortOrder`
- `Jellyfin.order` -> `Jellyfin.sortOrder`
- `TelegramChannel.order` -> `TelegramChannel.sortOrder`
- `Feiniu.order` -> `Feiniu.sortOrder`

Update service sorting from `Sort.by("order")` to `Sort.by("sortOrder")` for those entities. Update local comparisons and sync copy logic to use the new Java property.

`SiteDto` will expose JSON property `order` while internally using `sortOrder`, so existing frontend code and external callers do not need to change.

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
2. Add or adjust tests that prove site ordering uses the `sortOrder` Java property while JSON compatibility still accepts and emits `order`.
3. Implement the minimal production changes.
4. Run focused tests first, then `mvn test`.

## Compatibility

Existing API clients keep sending and receiving `order`.

Existing databases migrate data from `order` to `sort_order`.

New databases get `sort_order` from the initial schema and pass Hibernate validation without relying on auto-update.
