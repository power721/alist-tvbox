# Plugin Database Content Design

**Date:** 2026-05-11

**Goal:** Make plugin content survive restarts by storing the downloaded plugin text in the database and serving it through `/plugins/{vod_token}/{id}.txt` instead of relying on files under `/www/plugins`.

## Scope

This change applies to:
- Plugin persistence model
- Plugin add, refresh, update, and delete backend flows
- Public plugin content delivery for TvBox
- Subscription plugin `ext` generation
- Targeted backend tests for database-backed plugin content

Out of scope:
- Per-subscription plugin ownership
- Multiple files per plugin
- Executing or parsing plugin content on the server
- Keeping disk files under `/www/plugins` as a second source of truth

## Current Context

The current plugin implementation downloads remote plugin text and writes it to a local file path like:
- `/www/plugins/<plugin-id>.txt`

Subscription output then points plugin `ext` at a public URL derived from that local file path.

This solves remote URL instability, but it introduces a new durability problem:
- plugin files are lost after restart if the runtime filesystem is not persistent

The new design changes the persistence boundary:
- database becomes the only durable store for plugin content
- public plugin URLs are served dynamically from database content

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
- `content`
- `lastCheckedAt`
- `lastError`

`content` stores the downloaded plugin text body.

`localPath` is no longer part of the runtime design and should not be used for subscription generation or content delivery.

### Add Plugin

Add flow:
1. User submits remote URL and optional name.
2. Backend downloads the plugin text from the remote URL.
3. If download fails, do not save the plugin row.
4. If name is blank, derive default name from the decoded filename without extension.
5. Save the plugin row with downloaded text in `content`.
6. Persist `sourceName`, `lastCheckedAt`, and cleared `lastError`.

Successful add means:
- remote content is reachable
- plugin text is already stored in database

### Refresh Plugin

Refresh behavior:
1. Re-download the remote URL.
2. If download succeeds:
   - overwrite `content`
   - refresh `sourceName`
   - update `lastCheckedAt`
   - clear `lastError`
   - update `name` only if the current name is blank or still equal to previous `sourceName`
3. If download fails:
   - keep the existing `content`
   - update `lastCheckedAt`
   - persist `lastError`

### Update Plugin

Editable fields remain:
- name
- enabled
- extend
- url

If the remote URL changes:
- re-download from the new URL before saving
- overwrite `content` after successful download
- reject the update if download fails

### Delete Plugin

Delete behavior:
- remove the plugin row

No local file deletion is required because plugin files are no longer stored on disk.

### Public Plugin Content Endpoint

Expose plugin content through:
- `/plugins/{vod_token}/{id}.txt`

Rules:
- `{vod_token}` reuses the existing subscription access token mechanism
- token validation must match the same logical rules used for current subscription access
- `{id}` is the plugin id
- response body is the stored plugin `content`
- response content type is `text/plain; charset=UTF-8`

If the token is invalid:
- reject access using the same failure style already used by token-protected subscription endpoints

If the plugin does not exist:
- return not found

If the plugin exists but `content` is blank:
- treat that as invalid plugin state and fail clearly rather than returning an empty body silently

### Subscription Site Generation

For each enabled plugin, generate a site with:
- `api = readHostAddress("") + "/Atvp.py"`
- `ext = readHostAddress("") + "/plugins/" + vodToken + "/" + plugin.getId() + ".txt"`

If `extend` is non-blank:
- append `@@<extend>`

Examples:
- `http://host/plugins/abc123/12.txt`
- `http://host/plugins/abc123/12.txt@@foo=bar`

The remote source URL must not appear in emitted `ext`.

### Ordering

Plugin ordering remains:
- global
- drag-sort controlled
- emitted before built-in sites in `SubscriptionService#addSite`
- ascending `sortOrder`, then `id`

## Backend Design

### Table Change

Extend the `plugin` table with:
- `content`

Recommended column:
- `CONTENT CHARACTER VARYING`

If the in-progress `local_path` change already exists in some environments:
- migrate away from using it
- do not depend on it for runtime behavior

This design does not require dropping `local_path` immediately if that complicates migration safety, but the application must stop reading it.

### Service Responsibilities

`PluginService` should own:
- remote download
- source name derivation
- storing downloaded plugin text into `content`
- replacing `content` on refresh and URL change
- preserving old `content` when refresh fails

`PluginService` should no longer:
- write plugin text to `/www/plugins`
- delete plugin files from disk
- derive runtime plugin URLs from filesystem paths

### Download Contract

Remote plugin fetch must:
- accept both ASCII and already-percent-encoded URLs
- treat non-200 or empty-body responses as failure
- return the raw text body for database persistence

Use URI-safe HTTP access so encoded Chinese filenames are not encoded again.

### Public Delivery Controller

Add a controller dedicated to public plugin text delivery, for example:
- `GET /plugins/{vod_token}/{id}.txt`

Responsibilities:
- validate `vod_token`
- load plugin by id
- return plugin `content` as plain text

This controller should not:
- refresh plugins on read
- read from the filesystem
- mutate plugin metadata

### Subscription Integration

`SubscriptionService#buildPluginSite` should no longer use:
- remote plugin URL
- `localPath`

Instead it should build:
- `readHostAddress("") + "/plugins/" + currentVodToken + "/" + plugin.getId() + ".txt"`

The current token source used to build `/sub/...` style URLs should be reused so plugin URLs remain accessible to the same consumer session.

## Error Handling

- Remote download failure on create: reject save
- Remote download failure on update with changed URL: reject update
- Remote download failure on refresh: keep old `content`, persist `lastError`
- Missing plugin on public read: return not found
- Invalid token on public read: reject access
- Blank stored `content` on public read: fail explicitly rather than returning an empty file silently

## Testing Strategy

### Backend

Add focused tests covering:
- create stores downloaded plugin text into `content`
- create derives default display name from encoded source filename
- create rejects remote download failure
- refresh overwrites `content` on success
- refresh preserves old `content` on failure
- update with changed URL overwrites `content`
- public `/plugins/{vod_token}/{id}.txt` returns stored text
- public endpoint rejects invalid token
- generated `ext` points to `/plugins/{vod_token}/{id}.txt`
- generated `ext` appends `@@extend` when needed
- enabled plugins still emit before built-in sites

### Frontend

At minimum verify:
- the plugin dialog still type-checks
- plugin CRUD requests remain compatible with backend response fields after replacing local-path-based runtime behavior

## Acceptance Criteria

- Restarting the application does not lose plugin content
- Adding a plugin stores downloaded text in database
- Refresh stores the new text in database and keeps old text on failure
- Subscription `ext` uses `/plugins/{vod_token}/{id}.txt`, not a remote URL or local file path
- `ext` format is `<plugin-url>` or `<plugin-url>@@<extend>`
- Encoded Chinese filenames in plugin URLs still add successfully
- Plugin ordering remains configurable and emitted before built-in sites
