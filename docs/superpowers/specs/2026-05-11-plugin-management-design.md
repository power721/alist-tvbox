# Plugin Management Design

**Date:** 2026-05-11

**Goal:** Add a global plugin management dialog on the subscription page, persist plugins in a dedicated database table, allow drag-sort and refresh, and append enabled plugins as TvBox sites at the end of `SubscriptionService#addSite`.

## Scope

This change applies to:
- The subscription page in `web-ui/src/views/SubscriptionsView.vue`
- New backend plugin persistence, validation, and refresh APIs
- New database table for globally shared plugins
- Plugin site injection in `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`
- Targeted backend tests for plugin parsing and site generation

Out of scope:
- Per-subscription plugin lists
- Downloading plugin files to local storage
- Caching remote plugin file contents
- Executing or parsing plugin file bodies on the server
- Refactoring unrelated subscription or settings flows

## Current Context

The project already has:
- A subscription page with toolbar actions and modal dialogs in `SubscriptionsView.vue`
- Existing Spring MVC controller and service patterns for small CRUD features
- `SubscriptionService#addSite`, which appends built-in sites into the outgoing TvBox config
- Existing URL-based site generation patterns for TvBox site objects

The new plugin feature fits best as:
- A standalone global model with its own table and CRUD endpoints
- A subscription-page dialog because plugins affect generated subscription configs
- A late append step in `addSite`, after existing built-in sites are inserted

## Functional Requirements

### Global Plugin Model

Plugins are globally shared across all subscriptions.

Each plugin stores:
- `id`
- `name`
- `url`
- `enabled`
- `sortOrder`
- `extend`
- `sourceName`
- `lastCheckedAt`
- `lastError`

Semantics:
- `name` is the display name used for generated TvBox `name` and `key`
- `url` is the plugin source URL
- `enabled` controls whether the plugin is appended into generated subscription configs
- `sortOrder` controls plugin ordering in the management list and append order in generated configs
- `extend` is an arbitrary string appended to `ext` as `@@<extend>` when non-blank
- `sourceName` stores the default name derived from the source URL filename after decode
- `lastCheckedAt` stores the last successful or failed refresh check time
- `lastError` stores the latest validation or refresh error message; blank means the last check succeeded

### Add Plugin

The dialog must allow adding a plugin by:
- required URL
- optional name

Add flow:
1. User enters a URL and optional name.
2. Backend validates that the URL is reachable by issuing a remote read/check request.
3. If the URL is not reachable, the plugin is not saved.
4. If the name is blank, derive the default display name from the URL filename:
   - use the final path segment
   - URL decode it
   - remove the last extension
   - example: `4K%E6%8C%87%E5%8D%97.txt` -> `4K指南`
5. Save the plugin with `enabled = true`.
6. Save it at the end of the current order unless the caller explicitly supplies a position.

### Edit Plugin

Each plugin can be edited for:
- name
- enabled/disabled state
- extend string

Editing must not require re-validating the URL unless the URL itself changes.

If the URL changes, the backend must re-run the same validation as add:
- invalid/unreachable URL => reject update and keep existing row unchanged
- valid URL => update row and refresh derived metadata

### Refresh Plugin

Each plugin can be refreshed individually.

Refresh behavior:
- re-read/re-check the plugin URL
- update `sourceName`
- update `lastCheckedAt`
- clear `lastError` on success
- write `lastError` on failure

Refresh does not save local files.

Name update rule on refresh:
- if the current plugin `name` is blank, replace it with refreshed `sourceName`
- if the current plugin `name` equals the previous `sourceName`, replace it with refreshed `sourceName`
- otherwise keep the user-customized name unchanged

### Delete Plugin

Each plugin can be deleted from the management dialog.

Delete behavior:
- remove the row permanently
- do not re-normalize user-visible IDs
- compact ordering only through `sortOrder` updates

### Drag Sorting

The management dialog must support drag sorting for multiple plugins.

Sorting behavior:
- drag order updates the in-memory list immediately
- saving order persists `sortOrder` for all affected rows
- generated subscription configs must use ascending `sortOrder`
- ties should fall back to ascending `id` for deterministic output

### Plugin Site Generation

At the end of `SubscriptionService#addSite`, append one TvBox site per enabled plugin in plugin order.

Each plugin site must have this shape:

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
  "ext": "https://github.com/har01d5/tvbox/raw/refs/heads/master/py/4K%E6%8C%87%E5%8D%97.txt"
}
```

Generation rules:
- `name` = plugin display name
- `key` = plugin display name
- `api` = runtime value of `readHostAddress("") + "/Atvp.py"`
- `type` = `3`
- `filterable`, `quickSearch`, `searchable` = `1`
- `changeable` = `0`
- `ext` = plugin URL when `extend` is blank
- `ext` = `<url>@@<extend>` when `extend` is non-blank

Examples:
- `https://example.com/demo.txt`
- `https://example.com/demo.txt@@foo=bar`

## Data Model Design

Use a dedicated `plugin` table instead of `settings`.

Recommended columns:
- `id` integer primary key
- `name` text not null
- `url` text not null
- `enabled` boolean not null
- `sort_order` integer not null
- `extend` text nullable
- `source_name` text not null
- `last_checked_at` timestamp nullable
- `last_error` text nullable

Recommended constraints:
- `url` unique
- `sort_order` indexed
- `enabled` indexed if convenient

Rationale:
- ordering and row-level updates are first-class feature requirements
- refresh status belongs to the plugin row itself
- a dedicated table keeps plugin logic isolated from generic settings storage

## Backend Design

### Entity and Repository

Add a dedicated plugin entity and repository.

Repository capabilities should support:
- list all ordered by `sortOrder`, then `id`
- list enabled ordered by `sortOrder`, then `id`
- detect duplicate URLs

### Service

Add a dedicated plugin service responsible for:
- URL validation during create and update
- default/source name derivation from URL
- refresh behavior and status persistence
- sort-order persistence
- list/query methods for UI and subscription generation

The service should centralize name derivation and `ext` assembly rules so they do not drift between controller code and subscription generation.

### Controller API

Add plugin endpoints such as:
- `GET /api/plugins`
- `POST /api/plugins`
- `PUT /api/plugins/{id}`
- `DELETE /api/plugins/{id}`
- `POST /api/plugins/{id}/refresh`
- `POST /api/plugins/reorder`

Behavior requirements:
- create/update should return the saved row
- refresh should return the refreshed row
- reorder should persist the whole ordered list atomically enough for normal single-user admin usage

### Subscription Integration

`SubscriptionService#addSite` should query enabled plugins from the plugin service or repository and append site maps after the current built-in additions.

Site generation should be isolated in a small helper method so tests can assert:
- order
- `api`
- `name`
- `key`
- `ext`

## Frontend Design

Extend `SubscriptionsView.vue` with a new toolbar button:
- `插件管理`

The button opens a dialog containing:
- add form with URL and optional name
- plugin table/list
- drag handle or drag-enabled rows
- editable name field
- enabled switch
- extend input
- refresh action
- delete action

Recommended list columns:
- sort handle
- name
- URL
- enabled
- extend
- last checked time
- last error
- actions

Frontend behavior:
- adding uses backend validation; failed validation shows error and does not insert a local row
- editing name/enabled/extend persists through update API
- refresh updates row state from backend response
- drag sorting persists order through reorder API
- list loads when opening the dialog and can also be refreshed manually

## Error Handling

- Unreachable URL on create: reject save with explicit error
- Unreachable URL on update when URL changed: reject update with explicit error
- Refresh failure: keep row, update `lastError`, update `lastCheckedAt`
- Duplicate URL: reject save/update with explicit error
- Missing filename in URL path: fall back to a safe default source name such as the raw last segment or a fixed placeholder if truly absent
- Blank custom name after edit: normalize back to current `sourceName`

## Testing Strategy

### Backend

Add focused tests covering:
- default name derivation from encoded URL filename
- create rejects unreachable URL
- update rejects unreachable changed URL
- refresh updates `sourceName`, `lastCheckedAt`, and `lastError`
- refresh preserves custom renamed plugin name
- refresh updates name when the current name still matches previous source name
- `ext` is just URL when `extend` is blank
- `ext` is `url@@extend` when `extend` is non-blank
- enabled plugins are appended in `sortOrder` order
- disabled plugins are not appended
- generated plugin `api` uses `readHostAddress("") + "/Atvp.py"` at runtime

### Frontend

Verify through type-check/build that:
- the subscription toolbar renders the new `插件管理` button
- the dialog state compiles cleanly
- drag-sort integration compiles cleanly with the existing dependency set

## Acceptance Criteria

- Subscription page has a `插件管理` button.
- Plugins are stored in a dedicated global table.
- Adding a plugin requires a reachable URL.
- Blank add-name defaults from decoded filename without extension.
- Plugins can be renamed, enabled/disabled, deleted, refreshed, and reordered.
- `extend` accepts arbitrary strings.
- Refresh does not write plugin files locally.
- Subscription generation appends enabled plugins in configured order.
- Plugin site `api` resolves from `readHostAddress("") + "/Atvp.py"`.
- Plugin site `ext` uses `<url>` or `<url>@@<extend>`.
- Targeted backend tests cover naming, refresh, ordering, and `ext` generation.
