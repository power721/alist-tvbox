# Restore `/open`, `/node`, `/cat` + random one-time basic auth

**Date:** 2026-06-28
**Branch:** `fix/security-fixes`
**Related commits:** `8b6c748b` (backend removal), `18f300e2` (frontend removal)

## Problem

Commit `8b6c748b` (security hardening) removed the legacy cat endpoints `/open`, `/open/{token}`, `/node/{token}/{file}`, the `/cat/**` static resource mapping, the `open()` / `node()` service methods, and the basic-auth branch in `TokenFilter` (which had a hardcoded `alist:alist` default). Commit `18f300e2` removed the two SubscriptionsView link rows that exposed those URLs.

We want the endpoints and the UI rows back, but the basic-auth credential must **not** be the hardcoded `alist:alist` — it must be a randomly generated pair initialized once and persisted.

## Scope

Restore the removed surface across backend + frontend, changing only the credential model. No behavior change to the security hardening itself (constant-time compare, SSRF guards, etc. remain).

## Decisions

- **Credential model:** random username + password, generated **once** on first init, persisted to settings, reused across restarts. Regenerable on demand. (Mirrors the existing `api_key` and `ATV_PASSWORD` lifecycle.)
- **Credential access:** ADMIN-only(ish) REST endpoints. **CLIENT is treated as ADMIN** for these endpoints (explicitly accepted) — so we reuse the existing `/api/**` → `ADMIN + CLIENT` matcher and add **no** new `WebSecurityConfiguration` rule.
- **Auth gate:** restore the original semantics — URIs starting `/open`, `/node`, `/cat` require a valid Basic `Authorization` header (constant-time compared). The `{token}` path variants *additionally* call `checkToken` in the controller (defense-in-depth, as before).
- **`/cat` restoration:** restore the `/cat/**` static resource mapping and the basic-auth gate. Do **not** restore the dead `// TODO:` `cat.zip` extraction line in `syncCat` (it was already commented-out dead code at removal time).
- **No native-image changes:** endpoints return `Map` / `String`; no new DTO/entity packages → no `reflect-config.json` / `resource-config.json` edits.

## Backend changes

### 1. `SubscriptionService.java` — restore methods
- `public Map<String,Object> open()` — reads `cat/config_open.json`, merges `cat/my.json` if present (`mergeOpen`), `addCatSites`, then `replaceOpen`.
- `public String node(String file)` — serves `cat/index.config.js` with token/host/address placeholders substituted (or its md5 for `index.config.js.md5`), else the raw `cat/{file}`.
- Verbatim from `8b6c748b^-`. All helpers (`replaceOpen`, `mergeOpen`, `addCatSites`, `getSites`, `readHostAddress`) still present.

### 2. `TvBoxController.java` — restore endpoints
- `GET /open` → `open()`
- `GET /open/{token}` → `checkToken(token)`; `open()`
- `GET /node/{token}/{file}` → `checkToken(token)`; `node(file)`

### 3. `MvcConfig.java` — restore static handler
- `registry.addResourceHandler("/cat/**").addResourceLocations("file:" + Utils.getWebPath("cat") + "/");`

### 4. `TokenFilter.java` — restore basic-auth branch
- Add `volatile String basicAuthCredentials`; setter `setBasicAuthCredentials(String)`.
- Constructor: read `basic_auth_username` + `basic_auth_password` settings, compute `"Basic " + base64(user + ":" + pass)`, store as read-only fallback (mirrors `apiKey`).
- In `doFilterInternal`, before token handling: if URI starts with `/open`, `/node`, or `/cat` — require `Authorization` header; if blank or not **constant-time**-equal to `basicAuthCredentials`, set `Www-Authenticate: Basic realm="alist"` + `sendError(401)` + return. Otherwise fall through to `filterChain.doFilter`.
- All other paths unchanged.

### 5. `SettingService.java` — credential lifecycle (mirror `api_key`)
- `@PostConstruct init()`: if either setting missing → generate `username = IdUtils.generate(8)`, `password = IdUtils.generate(16)`, persist both, then `tokenFilter.setBasicAuthCredentials(computeBasic(username, password))`.
- `getBasicAuthCredentials()` → `Map<String,String>` `{username, password}` (plain, not masked; avoids adding a new DTO + native-image reflect config).
- `regenerateBasicAuthCredentials()` → new random pair, persist, push to filter, return.
- Setting names: `basic_auth_username`, `basic_auth_password` (add to `Constants`, following `ATV_PASSWORD`).

### 6. Admin API (in `TvBoxController`, co-located with `/api/cat/sync`)
- `GET /api/basic-auth-credentials` → `{username, password}`.
- `POST /api/basic-auth-credentials/regenerate` → `{username, password}`.
- Covered by existing `/api/**` matcher (ADMIN + CLIENT). No security-config edit.

## Frontend changes

### 7. `web-ui/src/views/SubscriptionsView.vue`
- On mount, fetch `GET /api/basic-auth-credentials` → `{username, password}`.
- Restore the two removed `el-row` blocks (猫影视配置接口 / node配置接口) from `18f300e2`, replacing the hardcoded `alist:alist@` with `{username}:{password}@`:
  - `/open{token}` link
  - `/node{token ? token : '-'}/index.config.js` link
- `currentUrl` (`window.location.origin`) and `token` refs already exist in the file.

## Tests (follow `UtilsSecurityTest` / `RateLimiterTest` patterns)

- `TokenFilter`: correct Basic header passes; missing/wrong → 401 with `Www-Authenticate`; constant-time path exercised.
- `SettingService`: first init generates + persists a pair; second init (settings present) is idempotent (no regenerate); `regenerateBasicAuthCredentials()` yields a different pair and updates the filter.
- Manual: `curl -u user:pass http://host/open` returns JSON; without creds → 401; `/cat/<file>` served; SubscriptionsView shows working embedded-cred links.

## Risks / notes

- Embedding credentials in a URL (`http://user:pass@host/...`) is a browser convenience; the password is recoverable from the URL/logs. Accepted here — creds are low-sensitivity (gate only the token-protected cat subsystem) and ADMIN/CLIENT is a trusted audience.
- `CLIENT` can read/regenerate basic-auth creds per the explicit decision; consistent with the codebase's existing CLIENT privilege level.
