# Plugin Local Cache Design

**Date:** 2026-05-11

**Goal:** Change plugin management so plugins are downloaded from remote URLs into local files under `/www/plugins`, refreshed by re-downloading, and emitted in subscription `ext` as local URLs built from `readHostAddress("") + "/plugins/<plugin-id>.txt"`.

## Scope

This change applies to:
- Global plugin persistence and refresh behavior
- Local plugin file storage under `/www/plugins`
- Plugin `ext` generation in `SubscriptionService`
- Plugin add/refresh/delete backend flows
- Targeted backend tests for local file generation and `ext` behavior

Out of scope:
- Reverting the dedicated `plugin` table design
- Per-subscription plugin storage
- Executing plugin bodies on the server
- Parsing plugin contents for metadata beyond filename-derived defaults

## Current Context

The current plugin implementation:
- Stores the remote source URL in the `plugin` table
- Validates add/refresh by reading the remote URL
- Emits `ext` directly from the remote URL or `url@@extend`

This causes two practical problems:
- Remote URLs remain part of runtime plugin loading
- Already-encoded URLs with Chinese filenames can fail during add/refresh if handled incorrectly by HTTP clients

The new design removes remote URLs from runtime `ext` usage:
- Remote URL is kept only as the source of truth for downloading
- Subscription output uses a local plugin file URL

## Functional Requirements

### Storage Model

Plugins remain globally shared.

Each plugin row must store:
- `id`
- `name`
- `url`
- `enabled`
- `sortOrder`
- `extend`
- `sourceName`
- `localPath`
- `lastCheckedAt`
- `lastError`

`localPath` stores the filesystem path of the downloaded plugin file, for example:
- `/www/plugins/12.txt`

### Add Plugin

Add flow:
1. User submits remote URL and optional name.
2. Backend downloads the plugin text from the remote URL.
3. If download fails, do not save the plugin row.
4. If name is blank, derive default name from the decoded filename without extension.
5. Save the plugin row.
6. Use the saved plugin id to generate local file path:
   - `/www/plugins/<plugin-id>.txt`
7. Write downloaded content into that local file.
8. Persist `localPath`, `lastCheckedAt`, and cleared `lastError`.

The local file naming strategy is fixed:
- `/www/plugins/<plugin-id>.txt`

This is intentionally stable:
- it does not depend on remote filename
- it does not change when the plugin display name changes
- it avoids filename conflicts completely

### Refresh Plugin

Refresh behavior:
1. Re-download the remote URL.
2. If download succeeds:
   - overwrite the existing local file
   - refresh `sourceName`
   - update `lastCheckedAt`
   - clear `lastError`
   - update `name` only if the current name is blank or still equal to previous `sourceName`
3. If download fails:
   - keep the existing local file untouched
   - update `lastCheckedAt`
   - persist `lastError`

Refresh does not create a new local filename.
Refresh always targets the existing plugin id path.

### Delete Plugin

Delete behavior:
- remove the plugin row
- delete the corresponding local file if it exists

Delete should not fail just because the file is already missing.

### Update Plugin

Editable fields remain:
- name
- enabled
- extend
- url

If the remote URL changes:
- re-download from the new URL before saving
- overwrite the same local file path after successful download
- reject the update if download fails

### Subscription Site Generation

For each enabled plugin, generate a site with:

```json
{
  "filterable": 1,
  "quickSearch": 1,
  "name": "4K指南",
  "changeable": 0,
  "api": "readHostAddress(\"\") + \"/Atvp.py\"",
  "type": 3,
  "key": "4K指南",
  "searchable": 1,
  "ext": "readHostAddress(\"\") + \"/plugins/12.txt\""
}
```

Rules:
- `api` = runtime value of `readHostAddress("") + "/Atvp.py"`
- `ext` = runtime value of `readHostAddress("") + "/plugins/<plugin-id>.txt"`
- if `extend` is non-blank, `ext` becomes:
  - `readHostAddress("") + "/plugins/<plugin-id>.txt@@<extend>"`

The remote source URL must not appear in emitted `ext`.

### Ordering

Plugin ordering remains:
- global
- drag-sort controlled
- emitted before built-in sites in `SubscriptionService#addSite`
- ascending `sortOrder`, then `id`

## Filesystem Design

### Local Directory

Downloaded plugin files live under:
- `/www/plugins`

The application already exposes `/www/...` content as public files, so using `/www/plugins` fits existing file-serving behavior.

### File Content

Plugin files are stored as plain text.
The application does not transform, decode, or rewrite plugin content before saving.

### File Naming

Use plugin id only:
- `/www/plugins/<plugin-id>.txt`

Examples:
- `/www/plugins/1.txt`
- `/www/plugins/12.txt`

This deliberately avoids:
- conflicts from repeated filenames like `index.txt`
- unstable URLs tied to source filename changes
- filesystem issues with non-ASCII names

## Backend Design

### Table Change

Extend the `plugin` table with:
- `local_path`

Recommended column:
- `LOCAL_PATH CHARACTER VARYING(255) not null`

### Service Responsibilities

`PluginService` should own:
- remote download
- local file path generation
- writing plugin files
- overwriting plugin files on refresh/update
- deleting local files on plugin delete
- source name derivation from remote URL

The remote download step should use a URI-safe API so already-encoded URLs are not encoded again.

### Download Contract

Remote plugin fetch must:
- accept both ASCII and already-percent-encoded URLs
- treat non-200 or empty-body responses as failure
- return the raw text body for local persistence

### Local Path Generation

After the database row has a stable id:
- `localPath = "/www/plugins/" + id + ".txt"`

This means create flow is naturally two-step:
1. save row to obtain id
2. write local file and save `localPath`

If writing the local file fails after row creation:
- delete the just-created row, or fail the transaction so the row is not kept half-created

### Subscription Integration

`SubscriptionService#buildPluginSite` should no longer use `plugin.getUrl()`.

Instead it should derive:
- `String ext = readHostAddress(\"\") + plugin.getLocalPath().substring(4)`

Because:
- stored filesystem path is `/www/plugins/<id>.txt`
- public URL should become `/plugins/<id>.txt`

If `extend` is non-blank:
- append `@@<extend>`

## Frontend Design

The plugin management dialog can stay mostly unchanged:
- user still inputs remote URL
- refresh still means re-fetch remote source

Visible behavior change:
- add success now means “downloaded and cached locally”
- refresh means “re-downloaded and overwrote local file”

Optional UI enhancement:
- show local file path or public plugin URL in the table

This is optional and not required for the behavior change.

## Error Handling

- Remote download failure on create: reject save
- Remote download failure on update with changed URL: reject update
- Remote download failure on refresh: keep old local file, persist `lastError`
- Local file write failure on create: fail create, do not keep partial plugin row
- Local file write failure on refresh: keep old file, persist `lastError`
- Missing local file on delete: ignore and continue deleting DB row

## Testing Strategy

### Backend

Add focused tests covering:
- create writes plugin content to `/www/plugins/<id>.txt`
- create rejects remote download failure
- create derives default display name from encoded source filename
- refresh overwrites existing local file on success
- refresh preserves current local file on download failure
- update with changed URL overwrites the same local file path
- delete removes local file
- generated `ext` points to local `/plugins/<id>.txt`
- generated `ext` appends `@@extend` when needed
- enabled plugins still emit before built-in sites

### Frontend

At minimum verify:
- the plugin dialog still type-checks
- add/update/refresh/delete API calls still match backend endpoints

## Acceptance Criteria

- Adding a plugin downloads the remote text into `/www/plugins/<plugin-id>.txt`
- Refresh re-downloads and overwrites the local file
- Subscription `ext` uses local plugin URL, not remote source URL
- `ext` format is `<local-plugin-url>` or `<local-plugin-url>@@<extend>`
- Chinese filenames in remote URLs no longer break add because runtime `ext` no longer depends on remote URL and download uses URI-safe access
- Plugin ordering remains configurable and emitted before built-in sites
