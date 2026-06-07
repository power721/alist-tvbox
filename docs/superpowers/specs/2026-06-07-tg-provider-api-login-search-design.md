# tg-provider API Login and Search Design

## Scope

Integrate the local same-container `tg-provider` HTTP API into AList-TVBox for Telegram login and search.

This phase assumes the container already packages `/usr/local/bin/tg-provider`, starts it through `/tg-provider-runtime.sh`, and keeps it bound to `127.0.0.1:6000`.

## Goals

- Let the existing AList-TVBox web UI log in to Telegram through `tg-provider`.
- Let existing Telegram search calls prefer `tg-provider` indexed results.
- Preserve existing Telegram search fallback behavior when `tg-provider` is unavailable or returns no usable results.
- Keep `tg-provider` private to the container; AList-TVBox remains the only browser-facing API surface.

## Non-Goals

- No direct Java access to the provider SQLite database.
- No public exposure of provider port `6000`.
- No QR-code Telegram login; `tg-provider` currently supports SMS code and optional 2FA password login.
- No channel management UI overhaul.
- No change to the old remote `tg_search` setting semantics except provider search preference inside `/api/telegram/search`.

## Backend Client

Add a Spring service named `TgProviderClient`.

Default base URL:

```text
http://127.0.0.1:6000
```

The client uses `RestTemplateBuilder` and a short timeout derived from `app.tgTimeout`. It provides typed methods for:

- `status()`: `GET /api/status`
- `accounts()`: `GET /api/accounts`
- `deleteAccount(id)`: `DELETE /api/accounts/{id}`
- `sendCode(phone)`: `POST /api/login/send-code`
- `signIn(phone, code)`: `POST /api/login/sign-in`
- `password(phone, password)`: `POST /api/login/password`
- `search(keyword, limit)`: `GET /api/search?q={keyword}&limit={limit}`

Provider failures are wrapped in a small runtime exception or return an empty optional/list where the caller has a defined fallback path. Login calls return errors to the caller because silent fallback would hide user action failures.

## Backend API

Extend `TelegramController` with provider-backed endpoints:

- `GET /api/telegram/provider/status`
- `GET /api/telegram/user`
- `POST /api/telegram/login/send-code`
- `POST /api/telegram/login/sign-in`
- `POST /api/telegram/login/password`
- `POST /api/telegram/logout`

`GET /api/telegram/user` returns the first provider account when available. If no account exists or the provider is unreachable, it returns the existing empty user shape expected by `SubscriptionsView.vue`:

```json
{
  "id": 0,
  "username": "",
  "first_name": "",
  "last_name": "",
  "phone": ""
}
```

`POST /api/telegram/logout` deletes the first provider account when present. It returns success even if there is no account, making the UI action idempotent.

The existing `GET /api/telegram/search?wd=` changes behavior:

1. Try provider search with `limit=100`.
2. Map provider results into existing `cn.har01d.alist_tvbox.dto.tg.Message`.
3. If provider is unavailable or mapped results are empty, fall back to the existing `telegramService.search(wd, 100, false, false)` path.

This keeps existing `VodView.vue` usage intact.

## Search Mapping

Provider search response shape:

```json
{
  "items": [
    {
      "id": 1,
      "telegram_message_id": 123,
      "text": "message text",
      "date": "2026-06-07T12:00:00Z",
      "channel_title": "Channel",
      "channel_username": "channel_name",
      "links": [
        {"type": "quark", "url": "https://pan.quark.cn/s/abc", "password": ""}
      ]
    }
  ]
}
```

Mapping rules:

- One provider link becomes one AList-TVBox `Message`.
- `Message.id` uses `telegram_message_id` when it fits in `int`, otherwise provider `id`.
- `Message.content` uses provider `text`.
- `Message.time` uses provider `date`.
- `Message.channel` prefers `channel_username`, then `channel_title`.
- `Message.link` uses the provider link URL.
- `Message.type` is derived by existing URL parsing rules.
- `Message.name` is derived by existing message parsing rules from `content`.

To avoid duplicating parsing logic, add a package-visible or public factory on `dto.tg.Message`, for example `Message.fromProvider(...)`, rather than copying private parsing logic into the client.

## Frontend Login

Update `web-ui/src/views/SubscriptionsView.vue`.

Existing Telegram login dialog is reused, but the QR path is removed from the active workflow.

UI behavior:

- Add a visible admin button named `登录 Telegram` near the existing subscription toolbar.
- Opening the dialog calls `GET /api/telegram/user`.
- The dialog shows SMS-code login only:
  - phone input calls `POST /api/telegram/login/send-code`
  - code input calls `POST /api/telegram/login/sign-in`
  - if response has `password_required=true`, show the password input
  - password input calls `POST /api/telegram/login/password`
- Successful sign-in refreshes `GET /api/telegram/user` and displays account info.
- Logout calls `POST /api/telegram/logout` and resets the local user model.

No visible in-app text should describe implementation details or ports.

## Configuration

Initial implementation uses the default provider URL `http://127.0.0.1:6000`.

If a setting is needed later, add it as a separate change. This phase does not add UI configuration for provider URL because the packaged runtime always starts the provider on localhost.

## Error Handling

- Search provider failure logs a warning and falls back to existing search.
- Login provider failure returns a non-2xx response to the UI.
- Frontend shows a compact error message through `ElMessage.error`.
- Provider 202 response with `password_required=true` moves the UI into password phase instead of treating the login as complete.

## Testing

Backend tests:

- `TgProviderClientTest`
  - verifies login request paths and JSON bodies.
  - verifies account response parsing.
  - verifies provider search response maps into usable DTOs.
- `TelegramControllerTest`
  - verifies `/api/telegram/user` returns empty shape when no provider account exists.
  - verifies login endpoints proxy to the client.
  - verifies `/api/telegram/search` uses provider results first.
  - verifies `/api/telegram/search` falls back to `TelegramService.search` when provider search fails or returns empty results.

Frontend tests:

- `SubscriptionsView` source test verifies the Telegram login button is present.
- Verify QR login controls are no longer part of the active Telegram login workflow.
- Verify phone/code/password functions call the new `/api/telegram/login/*` endpoints.

Verification commands:

```bash
mvn test
npm test -- --run
```

Run the frontend test command from `web-ui` if that is the existing project convention.
