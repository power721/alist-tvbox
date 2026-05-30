# GuangYaPan Account And Share Support Design

**Date:** 2026-05-30

**Goal:** Add complete GuangYaPan support: QR-code account login, GuangYaPan account storage mounting, GuangYaPan share import, and frontend account/share management integration.

## Scope

- Add GuangYaPan as a first-class netdisk account type.
- Add GuangYaPan OAuth device-code QR login to the existing driver-account QR flow.
- Save GuangYaPan account tokens and device id in `DriverAccount` data and generate the matching AList/OpenList storage addition.
- Add GuangYaPan share-link parsing and share storage generation.
- Add frontend labels, mount-path previews, QR-login support, and share-link display for GuangYaPan.
- Use the downloaded GuangYaPan logo asset at `web-ui/public/guangya.webp` wherever the UI shows provider icons.
- Add focused backend tests for parsing and storage addition generation.

Out of scope:
- Implementing GuangYaPan file APIs directly in this Spring Boot application.
- Adding a dedicated GuangYaPan account page.
- Supporting GuangYaPan SMS login in this application.
- Implementing GuangYaPan offline download.

## Driver Contract

PowerList driver sources define the AList/OpenList contract used by this project.

Account driver:

- Driver name: `GuangYaPan`
- Addition keys:
  - `root_folder_id`
  - `access_token`
  - `refresh_token`
  - `device_id`
  - `page_size`
  - `order_by`
  - `sort_type`

Share driver:

- Driver name: `GuangYaPanShare`
- Addition keys:
  - `share_id`
  - `device_id`
  - `page_size`
  - `order_by`
  - `sort_type`

GuangYaPan share links use this format:

```text
https://www.guangyapan.com/s/<share_id>
```

The share id can also be stored directly. The expected id format is an alphanumeric prefix, an underscore, and an alphanumeric or dash/underscore suffix, for example:

```text
1894369771769081942_aeWVzywV3ZOZly47
```

## Backend Design

### Driver Type

Add `GUANGYA` to `DriverType`.

The account type represents GuangYaPan account storage. GuangYaPan share support remains driven by the existing numeric `Share.type` model.

### Account Storage

Add `cn.har01d.alist_tvbox.storage.GuangYaPan`.

The storage class will:

- Extend `Storage` with driver name `GuangYaPan`.
- Use `Storage.getMountPath(account)` for the mount path.
- Map `DriverAccount.folder` to `root_folder_id`.
- Map the active access token to `access_token`.
- Map refresh token and device id from account addition to `refresh_token` and `device_id`.
- Set conservative defaults:
  - `page_size = 100`
  - `order_by = 3`
  - `sort_type = 1`

Token storage convention:

- `DriverAccount.token` stores the current access token for display and generic token handling.
- `DriverAccount.addition` stores JSON containing `refresh_token` and `device_id`, and may also include `access_token` for AList addition compatibility.
- When building the AList storage, `access_token` uses `DriverAccount.token` first, then `addition.access_token` as fallback.

### Account Service Integration

Update `DriverAccountService` to support `GUANGYA`:

- `saveStorage` maps `GUANGYA` accounts to `GuangYaPan` storage.
- Validation accepts `GUANGYA` when token or addition contains usable token data.
- Empty folder defaults to `0`, matching root folder behavior used by similar providers.
- Mount path preview becomes `/我的光鸭网盘/<name>`.

Master token integration is not required for GuangYaPan unless the surrounding code explicitly consumes it later. The storage addition carries the token data needed by AList/OpenList.

### QR Login

Extend the existing `/api/pan/accounts/-/qr` and `/api/pan/accounts/-/token` flow for `type=GUANGYA`.

The QR flow follows the provided Node reference:

1. Ensure a 32-character hex device id exists.
2. `POST https://account.guangyapan.com/v1/auth/device/code` with:
   - `scope = user`
   - `client_id = aMe-8VSlkrbQXpUR`
3. Return QR payload compatible with the current frontend QR modal:
   - QR text or generated QR image data from `verification_uri_complete` or `verification_url`
   - `query_token` containing device code and device id with expiration
4. Poll `POST https://account.guangyapan.com/v1/auth/token` with grant type `urn:ietf:params:oauth:grant-type:device_code`.
5. Map OAuth statuses:
   - token response with access or refresh token -> success
   - `authorization_pending` or `slow_down` -> pending
   - `access_denied` -> canceled
   - `expired` or `invalid_grant` -> expired

On success, return account info with:

- `token` or equivalent current access token field
- `refresh_token`
- `device_id`

The frontend will place access token in `form.token` and write refresh token/device id into `form.addition`.

### Share Storage

Add `cn.har01d.alist_tvbox.storage.GuangYaPanShare`.

The storage class will:

- Extend `Storage` with driver name `GuangYaPanShare`.
- Add `share_id` from `Share.shareId`.
- Add `device_id` when `Share.cookie` contains one; otherwise allow the driver to auto-generate.
- Set defaults:
  - `page_size = 200`
  - `order_by = 0`
  - `sort_type = 0`

The PowerList share driver resolves playable links by using an existing `GuangYaPan` account, so users must configure a GuangYaPan account for share playback.

### Share Parsing

Add GuangYaPan share parsing to `ShareService`.

Supported input forms:

- Full link: `https://www.guangyapan.com/s/<share_id>`
- Full link without `www`: `https://guangyapan.com/s/<share_id>`
- Raw share id matching the GuangYaPan share id format

Assign a new share type value that does not collide with existing types. The next available value is `12`.

Mount path:

```text
/我的光鸭分享/<path>
```

`getShareLink` in the frontend should rebuild links as:

```text
https://www.guangyapan.com/s/<share_id>
```

No password handling is required for GuangYaPan share links based on the referenced driver.

## Frontend Design

### Driver Account UI

Update `DriverAccountView.vue`:

- Add `GUANGYA` option label `光鸭网盘`.
- Use `/guangya.webp` as the GuangYaPan provider icon when the account UI renders provider logos.
- Show mount path preview `/我的光鸭网盘/<name>`.
- Enable the existing QR-code login button for `GUANGYA`.
- On QR polling success:
  - set `form.token` from access token
  - set `form.addition.access_token`
  - set `form.addition.refresh_token`
  - set `form.addition.device_id`
- Keep the existing generic account dialog instead of adding a GuangYaPan-specific page.

### Share UI

Update `SharesView.vue`:

- Add type label `光鸭分享` for type `12`.
- Use `/guangya.webp` as the GuangYaPan share icon when the share UI renders provider logos.
- Add mount-path prefix `/我的光鸭分享/`.
- Add share-link rendering for type `12`.
- Add driver display label for `GuangYaPanShare`.

## Error Handling

- QR creation failures return a clear backend error such as `二维码生成失败: <reason>`.
- QR polling treats transient request failures as pending, matching existing QR-login behavior for other providers.
- Expired or canceled QR sessions should not create or mutate an account.
- Saving a GuangYaPan account without token data should fail validation.
- GuangYaPan share parsing should reject unrecognized links with the existing `无法识别的分享链接` error.
- AList/OpenList share playback errors should surface through the existing storage validation path.

## Testing

Backend tests should cover:

- GuangYaPan account storage addition contains `root_folder_id`, `access_token`, `refresh_token`, `device_id`, `page_size`, `order_by`, and `sort_type`.
- GuangYaPan share storage addition contains `share_id`, `page_size`, `order_by`, and `sort_type`.
- GuangYaPan share parser accepts full links and raw share ids.
- GuangYaPan share parser rejects malformed ids.
- `DriverAccountService` accepts `GUANGYA` accounts with token data and defaults empty folder to `0`.

Manual verification should include:

- Open the netdisk account page and generate a GuangYaPan QR code.
- Poll after confirming login and ensure account fields are populated.
- Save a GuangYaPan account and confirm AList storage uses driver `GuangYaPan`.
- Add a GuangYaPan share link and confirm AList storage uses driver `GuangYaPanShare`.

## Acceptance Criteria

- A GuangYaPan account can be added through QR login in the existing netdisk account UI.
- Saved GuangYaPan accounts create AList/OpenList storage with driver `GuangYaPan` and the required addition keys.
- GuangYaPan shares can be imported from `guangyapan.com/s/...` links.
- Imported GuangYaPan shares create AList/OpenList storage with driver `GuangYaPanShare` and the required addition keys.
- Existing providers continue to behave as before.
- Targeted tests pass.
