# YAML Backup And Restore Design

## Overview

Add a new repository-based backup and restore path that exports application data to a compressed YAML package and restores it through Spring Data repositories instead of SQL scripts. Keep the existing SQL backup and restore behavior unchanged.

Goals:

- Keep SQL backup and restore for H2-oriented disaster recovery.
- Add database-independent backup and restore based on entities and repositories.
- Export YAML as a compressed package and allow uploading the compressed package for restore.
- Make startup-time YAML restore run before normal initialization creates new records.
- Support both overwrite restore and merge restore, with overwrite as the default behavior.

Non-goals:

- Replacing SQL backup or SQL startup restore.
- Restoring excluded cache or index data.
- Preserving database-specific schema details.

## Current Problems

The current backup path in [SettingService.java](/home/harold/workspace/alist-tvbox/src/main/java/cn/har01d/alist_tvbox/service/SettingService.java:164) exports SQL using H2 `SCRIPT TO`. That is not database-independent and requires handling H2/MySQL differences and H2 upgrade compatibility.

The application also has many startup `@PostConstruct` initializers that create or mutate persisted data, including:

- [UserService.java](/home/harold/workspace/alist-tvbox/src/main/java/cn/har01d/alist_tvbox/service/UserService.java:43)
- [SettingService.java](/home/harold/workspace/alist-tvbox/src/main/java/cn/har01d/alist_tvbox/service/SettingService.java:83)
- `SiteService`
- `NavigationService`
- `SubscriptionService`
- `AccountService`
- `DriverAccountService`

If repository restore happens after those initializers, the database can already contain new default data or migration side effects.

## Solution Summary

Introduce a new YAML backup system with these properties:

- Export application data from repositories, not from raw SQL.
- Store the exported YAML inside a zip archive.
- Keep SQL restore on `/data/database.zip`.
- Use a separate startup restore package path for YAML: `/data/database-yaml.zip`.
- When `/data/database-yaml.zip` exists, run repository restore before normal application initialization writes default data.
- During startup restore, initialization code must also see a restore-state flag and skip default record creation where necessary.

## File Format

The downloadable artifact is a zip file:

- Download name example: `database-yaml-2026-06-19.zip`
- Startup restore file: `/data/database-yaml.zip`

The zip archive contains:

- `database.yaml`

The first version does not require additional manifest files. If metadata expansion is needed later, new files may be added while keeping `database.yaml` as the primary source.

## YAML Structure

`database.yaml` stores application-level backup data instead of database table definitions.

Example structure:

```yaml
formatVersion: 1
appVersion: "2026.06.19"
exportedAt: "2026-06-19T10:20:30Z"
mode: "repository"
modules:
  settings:
    entity: "Setting"
    items:
      - name: "api_key"
        value: "..."
  users:
    entity: "User"
    items:
      - id: 1
        username: "admin"
        password: "..."
        role: "ADMIN"
        createdTime: "2026-01-01T00:00:00Z"
  sites:
    entity: "Site"
    items:
      - id: 2
        name: "..."
        url: "..."
```

Rules:

- `formatVersion` controls backward compatibility of the YAML structure.
- `appVersion` is metadata only and does not block restore by itself.
- `modules` groups exported entities by backup module name rather than database table name.
- Each module contains `items`, which are entity payloads serialized by Jackson YAML support.
- Entity `id` values are preserved when present so overwrite restore can reconstruct the original identity space.

## Architecture

Add a new backup subsystem separate from the existing sync subsystem.

Primary components:

- `DatabaseBackupService`
  - Export zip package.
  - Parse uploaded zip package.
  - Drive overwrite and merge restore.
- `BackupManifestDto`
  - Top-level representation of `database.yaml`.
- `BackupModuleRegistry`
  - Static registration of included modules, repository bindings, export order, restore order, and merge rules.
- `BackupModuleHandler<T>`
  - Per-entity handler abstraction for export, overwrite restore, merge restore, and cleanup.
- `StartupYamlRestore`
  - Early startup component that checks `/data/database-yaml.zip`, restores it, and marks restore state.

This must stay separate from `SyncService` because `SyncService` is scoped to remote sync modules, not full local backup and restore.

## Included And Excluded Data

### Included entities

The first version includes repository-backed entities that represent application data and configuration:

- `Setting`
- `User`
- `Site`
- `Share`
- `Account`
- `DriverAccount`
- `PanAccount`
- `PikPakAccount`
- `Subscription`
- `Plugin`
- `PluginFilter`
- `Jellyfin`
- `Emby`
- `Feiniu`
- `Navigation`
- `TelegramChannel`
- `IndexTemplate`
- `ConfigFile`
- `Device`
- `Tenant`
- `AListAlias`
- `OfflineDownloadTask`
- `PlayUrl`
- `History`
- `Task`
- `Tmdb`
- `TmdbMeta`

### Excluded entities and database objects

The first version excludes:

- `Movie`
- `Meta`
- `Alias`
- `Session`
- `id_generator`
- `FLYWAY_SCHEMA_HISTORY`
- `INFORMATION_SCHEMA`
- other database vendor system objects

Rationale:

- `Movie`, `Meta`, and `Alias` are intentionally excluded to match the existing backup intent around non-essential indexed or derived content.
- `Session` is runtime state and should not be restored.
- `id_generator` must be rebuilt after restore instead of imported.
- Flyway and database system objects are not application backup data.

## Export Rules

Export behavior:

- Read included data only through repositories.
- Serialize modules in a deterministic order defined by `BackupModuleRegistry`.
- Write `database.yaml` using Jackson YAML support.
- Compress `database.yaml` into the download zip package.

The existing SQL export endpoint remains unchanged. YAML export is an additional endpoint.

## Restore Modes

Two restore modes are supported:

- `OVERWRITE`
- `MERGE`

Default mode:

- `OVERWRITE`

### OVERWRITE

Behavior:

- Clear only the included entities.
- Restore the included entities in dependency-safe order.
- Rebuild `id_generator` after data restore.
- Leave excluded entities untouched.

This mode is intended to act as the main full restore workflow.

### MERGE

Behavior:

- For each module, attempt update-or-insert instead of full deletion.
- Preserve local records that are not present in the backup package.

Matching rules:

- Prefer `id` when it is stable and present.
- Use business keys where required:
  - `Setting.name`
  - `User.username`
  - `Share.path`
  - `ConfigFile.path`
  - `AListAlias.path`
  - `TelegramChannel.id`
- For entities without a practical business key override, fall back to `id`.

`MERGE` is a secondary option for advanced use cases and should not replace overwrite as the default restore path.

## Restore Order

Restore must respect entity dependencies.

Recommended restore order:

1. `Setting`
2. `User`
3. `Tmdb`
4. `TmdbMeta`
5. `Site`
6. `Share`
7. `Account`
8. `DriverAccount`
9. `PanAccount`
10. `PikPakAccount`
11. `Subscription`
12. `Plugin`
13. `PluginFilter`
14. `Jellyfin`
15. `Emby`
16. `Feiniu`
17. `Navigation`
18. `TelegramChannel`
19. `IndexTemplate`
20. `ConfigFile`
21. `Device`
22. `Tenant`
23. `AListAlias`
24. `OfflineDownloadTask`
25. `PlayUrl`
26. `History`
27. `Task`

Deletion order for overwrite should use the reverse of the restore order to avoid referential issues if future entities add stricter relationships.

## Primary Key Strategy

Repository restore must preserve entity identity without restoring raw database tables.

Rules:

- Export entity `id` values when present.
- Overwrite restore writes entities back with their original `id`.
- This applies to both `IDENTITY` and `TABLE` generated entities.
- `id_generator` is not backed up.
- After restore, recompute `id_generator` by scanning the maximum `id` of every entity using table-based ID generation and writing `max(id) + 1`.

This avoids future insert collisions while keeping the restore database-independent.

## Startup Restore Priority

Startup restore must have higher priority than default initialization that creates data.

Rules:

- SQL startup restore keeps using `/data/database.zip`.
- YAML startup restore uses `/data/database-yaml.zip`.
- If both files exist, YAML restore takes precedence.
- YAML startup restore must run before normal initialization logic writes default records.

Implementation requirements:

- Do not implement startup YAML restore as an ordinary business `@PostConstruct`.
- Add an early startup component dedicated to restore detection and execution.
- During startup restore, expose a global restore-state flag that can be checked by initialization services.
- Services that create default persisted records must skip those writes when startup YAML restore is pending or running.

This is a two-layer protection model:

- Restore is executed as early as possible.
- Startup initializers also skip conflicting writes during restore scenarios.

## Restore Package Lifecycle

For startup restore packages:

- Read `/data/database-yaml.zip`
- Restore the package
- Mark restore success
- Remove or rename the package so the same restore is not replayed on the next boot

Failure handling:

- If restore fails, keep the package for diagnosis and retry.
- Log the failure clearly.
- Do not silently continue into a half-restored state.

The exact failure policy during startup should prefer explicit failure over running on partially inconsistent data.

## API Design

Keep the current SQL export endpoint:

- `GET /api/settings/export`

Add YAML endpoints:

- `GET /api/settings/export-yaml`
- `POST /api/settings/import-yaml`

Behavior:

- `export-yaml` returns a zip file containing `database.yaml`
- `import-yaml` accepts multipart upload of a zip file
- `import-yaml` accepts restore mode selection: `OVERWRITE` or `MERGE`
- The server validates that the uploaded file is a supported zip package before restore

The API does not need to accept raw YAML in the first version. Zip-only is sufficient because compression is now part of the required format.

## Frontend Design

Add new controls to the existing configuration page that already exposes SQL export.

Required actions:

- `导出 SQL`
- `导出 YAML`
- `导入 YAML`

Import flow:

- User selects a zip file
- User selects restore mode
- User confirms the operation
- UI submits multipart upload
- UI displays restore result summary and errors

Warnings:

- Overwrite restore should present a destructive-action warning
- UI should explain that YAML restore is repository-based and intended for cross-database recovery
- UI should explain that SQL backup remains available for low-level recovery

## Validation And Safety Checks

Before restore:

- Verify zip archive structure
- Verify `database.yaml` exists
- Verify `formatVersion` is supported
- Verify all required modules deserialize successfully

During restore:

- Use transactional boundaries per module or per phase to avoid silent partial writes
- Collect module-level success and failure information
- Fail fast on structural restore errors

After restore:

- Rebuild `id_generator`
- Return a restore summary
- Refresh any in-memory settings that depend on persisted values if required

## Native Image Considerations

Because this feature adds new DTO classes and YAML serialization support:

- New DTO packages or classes must be covered by the native-image reflection scan
- If new resource files or startup markers are introduced, add them to native-image resource configuration where required
- Regenerate `reflect-config.json` after introducing new DTO classes

## Testing Strategy

Minimum test coverage:

- Export writes a zip package that contains a valid `database.yaml`
- Import accepts the zip package and restores included entities
- Overwrite restore clears included entities and preserves excluded entities
- Merge restore updates existing entities and inserts missing ones
- `id_generator` is rebuilt correctly after overwrite restore
- Startup restore prefers `/data/database-yaml.zip` over default initialization
- Startup restore does not interfere with existing SQL restore when no YAML package is present
- Uploaded invalid zip packages fail with clear errors

Test environments:

- H2 integration tests
- MySQL-oriented integration path where practical

The purpose of testing against both is to verify that repository-based restore is not tied to SQL dialect behavior.

## Rollout Notes

This feature should be added as a minimal patch:

- keep existing SQL backup untouched
- add YAML backup alongside it
- avoid unrelated refactoring
- preserve current APIs and startup behavior outside restore scenarios

## Open Decisions Resolved In This Design

The following decisions are fixed by this document:

- Keep SQL backup and restore.
- Add YAML backup as zip-compressed repository data.
- Use repositories, not direct SQL, for YAML restore.
- Use `/data/database-yaml.zip` for startup YAML restore.
- Give startup YAML restore higher priority than normal initialization writes.
- Exclude `Movie`, `Meta`, `Alias`, `Session`, and `id_generator`.
- Support both overwrite and merge restore, with overwrite as default.
