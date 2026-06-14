# 115 Offline Download Design

**Goal**

Add a synchronous `POST /api/offline_download` API that accepts a single offline-download URL, submits it to AList's 115 offline-download capability, waits up to 10 seconds for completion, and returns the resulting TvBox playlist. Add frontend configuration in the netdisk account configuration dialog for enabling offline download and selecting the 115 account used by this feature.

**Scope**

- Backend API for saving offline-download configuration.
- Backend API for triggering a synchronous offline download and returning a playlist.
- Frontend configuration UI in the existing netdisk account configuration dialog.
- Automatic AList `set_115` synchronization when the offline-download configuration is saved.
- Automatic `set_115` re-sync when the selected 115 account's mount folder changes.

**Out of Scope**

- Supporting any cloud drive type other than 115.
- Async task APIs, push notifications, or WebSocket/SSE status streaming.
- Persisting task history in this application.
- Adding a dedicated UI page for triggering downloads.

## Current Context

The project already has:

- `DriverAccount` management for cloud-drive accounts, including 115 accounts with a `folder` field used as the mount directory.
- `TvBoxService.getDetail(ac, ids)` that can build a playable playlist for an AList folder path when called with `ids = "1$" + path + "/~playlist"`.
- `AListLocalService` for local AList integration, including admin setting mutation using the AList admin token stored in site `1`.
- `DriverAccountView.vue` with an existing "网盘账号配置" dialog currently used for local proxy settings.

These existing pieces allow this feature to stay narrow:

- The frontend only needs to manage offline-download configuration.
- The backend can reuse AList for task creation/status lookup.
- The backend can reuse `TvBoxService` for the final playlist response.

## Functional Requirements

### Configuration

The netdisk account configuration dialog must add an "离线下载" section with:

- Enable offline download.
- Drive type selector.
- 115 account selector, filtered to `DriverType.PAN115`.
- Read-only display of the currently selected account mount directory.

Only 115 is supported now, so the drive-type selector should present only `115云盘`.

Saving configuration must:

1. Persist the offline-download configuration in application settings.
2. If enabled, resolve the selected 115 account's current mount folder.
3. Call AList `POST /api/admin/setting/set_115` with:
   - `temp_dir = "<current 115 folder>/alist-tvbox-offline"`

The persisted configuration should only store:

- `enabled`
- `driverType`
- `accountId`

The mount folder must not be persisted inside the offline-download setting. The current folder is always derived from the selected account.

### Offline Download API

`POST /api/offline_download` must:

1. Accept a request body containing one URL string.
2. Allow only these schemes:
   - `magnet:`
   - `ed2k:`
   - `http:`
   - `https:`
3. Reject other schemes with a `400 Bad Request`.
4. Load offline-download configuration.
5. Require:
   - feature enabled
   - selected driver type is `PAN115`
   - selected account exists
   - selected account has a non-blank folder
6. Resolve the selected account's current folder at request time.
7. Call AList `POST /api/fs/add_offline_download` with:
   - `urls = [request.url]`
   - `path = "<current 115 folder>/alist-tvbox-offline"`
   - `tool = "115 Cloud"`
   - `delete_policy = "delete_never"`
8. Read the first returned task id.
9. Poll AList `POST /api/task/offline_download/info?tid=<taskId>` once per second.
10. Stop polling after 10 seconds.
11. Handle task states:
   - `2`: success
   - `5`, `6`, `7`: failure
   - others: keep polling until timeout
12. On success:
   - read `name` from the task info response
   - build the final folder path as `"<current 115 folder>/alist-tvbox-offline/<name>"`
   - return `tvBoxService.getDetail(ac, "1$" + path + "/~playlist")`

The API is synchronous. It returns either:

- a TvBox playlist response on success
- a business error when configuration is invalid, task creation fails, task fails, or the task does not complete within 10 seconds

### Account Update Consistency

If the currently configured offline-download 115 account is edited and its `folder` changes, the backend must automatically re-apply:

- AList `set_115`
- `temp_dir = "<new folder>/alist-tvbox-offline"`

This keeps AList's temp directory aligned with the selected account after account edits.

## Backend Design

### New Setting Payload

Store a JSON object in a single application setting, for example:

```json
{
  "enabled": true,
  "driverType": "PAN115",
  "accountId": 12
}
```

This should be handled centrally by a small service layer rather than scattered JSON parsing in controllers.

### New Service

Add a dedicated `OfflineDownloadService` responsible for:

- reading and validating configuration
- syncing AList `set_115`
- submitting AList offline-download tasks
- polling AList task state
- converting a completed task into a TvBox playlist response

This keeps the feature isolated from `TvBoxService` and avoids bloating `DriverAccountService`.

### New Controller

Add a dedicated controller for this feature with endpoints such as:

- `GET /api/offline_download/config`
- `POST /api/offline_download/config`
- `POST /api/offline_download`

The config endpoints support the frontend dialog. The download endpoint serves callers that want the final playlist directly.

### AList Calls

Use the existing local AList root URI and AList admin token pattern already used by `AListLocalService`.

Two token contexts are required:

- admin token for `set_115`
- regular AList token for `add_offline_download` and task info

The implementation should reuse existing login/token-fetching flow where possible instead of duplicating token storage logic.

### Polling and Errors

Polling contract:

- interval: 1 second
- max wait: 10 seconds

Error messages should be explicit:

- feature disabled
- only 115 is supported
- 115 account not found
- 115 account mount directory is empty
- invalid offline-download URL
- AList did not return any task
- task failed: `<error or status>`
- task did not complete within 10 seconds

## Frontend Design

Extend the existing `DriverAccountView.vue` config dialog rather than creating a new page.

Add a second configuration section below the local proxy config:

- 离线下载开关
- 网盘类型
- 115账号选择
- 当前挂载目录展示

Behavior:

- When disabled, account/type inputs can be disabled or ignored.
- When enabled, selecting a 115 account should populate the read-only current mount directory immediately from the already loaded account list.
- Saving should call the new config endpoint.
- The page should also fetch existing offline-download config on load/open and hydrate the dialog state.

## Testing Strategy

### Backend

Add controller/service tests covering:

- config save success
- config save rejects missing account when enabled
- download rejects unsupported URL scheme
- download rejects disabled config
- download rejects missing folder
- download handles AList task creation without tasks
- download handles failed task states
- download times out after 10 seconds
- download success returns the value produced by `tvBoxService.getDetail(...)`
- updating the selected 115 account folder triggers `set_115` re-sync

Mock AList HTTP interactions at the service boundary.

### Frontend

At minimum, verify:

- config dialog loads saved offline-download config
- PAN115 account options are filtered correctly
- selected account folder is displayed correctly
- save payload uses `enabled`, `driverType`, and `accountId`

If the repo's current frontend test coverage is minimal, keep this to focused component logic and rely on a successful build for regression coverage.

## Risks and Constraints

- The API is intentionally synchronous with a 10-second cap. Many real offline-download tasks may exceed that cap. This is expected behavior, and the API should fail fast with a clear timeout message.
- `name` returned by the task is assumed to map directly to the created folder under `<mount>/alist-tvbox-offline`. This matches the requested behavior.
- `http` and `https` URLs are passed through directly to AList without additional validation beyond scheme checking.
- The feature depends on a healthy local AList instance and valid AList tokens.
