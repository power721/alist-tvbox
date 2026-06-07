# Telegram Private Channel Search Design

## Goal

Separate public Telegram channel search from private Telegram channel search.
Public channel search remains the existing configured/open channel flow. Private channel
search uses `tg-provider` accounts and channels, lets the user select which private
channels participate, and exposes a TvBox-compatible entry at `/tgsc`.

## Backend Design

`/api/telegram/search` becomes public/open channel search only. It should call the
existing `TelegramService` flow and no longer try `tg-provider` first.

Add browser-facing private channel endpoints:

- `GET /api/telegram/private/channels`: fetches `tg-provider` `/api/channels`, unwraps
  the `items` envelope, and returns top-level DTOs with provider channel fields plus a
  local `enabled` flag.
- `PUT /api/telegram/private/channels`: accepts selected provider channel IDs and saves
  them in the existing `Setting` table under a dedicated key.
- `POST /api/telegram/private/channels/sync`: proxies selected channel IDs to
  `tg-provider` `/api/channels/sync`.
- `GET /api/telegram/private/search?wd=...`: searches only the selected private channels
  through `tg-provider` and maps provider links to the existing `Message` response shape.

Add TvBox-compatible private search endpoints modeled after `/tg-search`:

- `GET /tgsc`
- `GET /tgsc/{token}`

The token variant checks `subscriptionService.checkToken(token)`. The routing shape
matches `/tg-search`: `id` returns detail, `wd` returns search results, empty requests
return category metadata. Search results come from the selected private channels. If no
private channels are selected, the private search returns an empty result rather than
searching every provider channel implicitly.

## Provider Client

Extend `TgProviderClient` to support:

- `channels()` and optional `channels(accountId)`, parsing both bare arrays and
  `{"items":[...]}` for resilience.
- `syncChannels(channelIds)`.
- `search(keyword, limit, channelId)` with `channel_id` query filtering so selected
  private channels can be searched explicitly.

When multiple private channels are enabled, AList calls provider search per channel and
merges mapped `Message` results. This avoids relying on unsupported multi-value
`channel_id` filtering in `tg-provider`.

All externally exposed provider DTOs must be top-level classes under `dto.tg`, including
new channel and private-channel request/response DTOs. Native reflection config and tests
must include these DTOs.

## Frontend Design

`SearchView.vue` adds a search source tab on the standalone search page:

- `盘搜`: calls public search.
- `电报频道`: calls the private TvBox-compatible `/tgsc` search endpoint.

`VodView.vue` keeps its existing playback-page public Telegram search box and does not
own the private channel search tab.

`PlayConfig.vue` changes its tabs to:

- `基本配置`: existing playback and PanSou settings.
- `公开频道`: current channel management UI, renamed from `频道管理`.
- `我的频道`: loads private provider channels, shows account/channel metadata, lets the
  user enable channels for private search, can trigger account channel-list sync, and
  can trigger content sync for enabled channels.
- `电报管理`: Telegram login, password step, current account display, and logout. This UI
  is moved out of `SubscriptionsView.vue`.

`SubscriptionsView.vue` removes the Telegram login button, dialog, and login-specific
state/actions.

## Persistence

Use the existing `Setting` table rather than a new table. Store enabled private provider
channel IDs as a normalized comma-separated list under `tg_private_channel_ids`. The
backend owns parsing, sorting, duplicate removal, and invalid ID removal.

Provider channel IDs are scoped by provider account in the provider database. The UI
should display account ID and channel title/username so the user can distinguish channels
when multiple accounts exist.

## Error Handling

If `tg-provider` is offline or returns invalid data:

- `/api/telegram/user` continues returning an empty user.
- Private channel listing/search endpoints return clear backend errors for the UI to show.
- Public search is unaffected.

If a selected channel no longer exists in provider output, keep the saved ID but omit it
from the visible channel list until provider returns it again. Search still uses saved
IDs, because provider search can validate or reject each `channel_id`.

## Testing

Backend tests:

- `TgProviderClientTest` covers provider channel envelope parsing, channel-filtered
  search, and sync request bodies.
- `TelegramControllerTest` covers public search no longer using provider, private channel
  list/save/search, and `/tgsc` token behavior.
- Native DTO tests verify new provider/private DTOs are top-level and present in
  `reflect-config.json`.

Frontend source tests:

- `SearchView.test.mjs` verifies the new `电报频道` tab and `/tgsc` endpoint.
- `VodView.test.mjs` verifies the playback page does not own the private search tab.
- `PlayConfig.test.mjs` verifies `公开频道`/`我的频道`/`电报管理` tabs and Telegram login APIs.
- `SubscriptionsView.test.mjs` verifies Telegram login UI and API calls were removed.
