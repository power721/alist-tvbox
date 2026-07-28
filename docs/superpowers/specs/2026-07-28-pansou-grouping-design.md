# 盘搜分组 (Pan-sou Grouped Source) — Design

Date: 2026-07-28
Status: Approved (pending spec review)

## Goal

Add a new built-in subscription source `盘搜|分组` that, on search, returns **one folder per network-disk type** (UC/115/百度/夸克/…) with a result count; clicking a folder lists the resources of that type. The existing flat `鱼佬盘搜` source stays unchanged. Implemented as a **separate spider class** + **separate backend endpoint** (chosen as the simplest of the options).

## Background facts (verified)

- CSP sources (`csp_*`) are TVBox type-3 sites; site-config `api` = spider **class name**, mapped by **naming convention** (`csp_<Class>` → `com.github.catvod.spider.<Class>`), loaded from `spring.jar` (a packaged DEX). No registry to edit. Proguard `-keep class com.github.catvod.spider.**` keeps every spider class.
- Spider source lives in sibling repo `/home/harold/workspace/CatVodTVSpider`; `build.sh` rebuilds and copies to `alist-tvbox/src/main/resources/static/spring.jar`.
- `FishPanSou` spider calls `{baseUrl}/pansou[/{token}]` for **all** of home/category/search/detail (passes `t=tid` for categoryContent, `wd` for search, `id` for detail). `playerContent` calls `/play`. Body passed through untouched.
- `vod_tag="folder"` on any vod item (search or category result) → TVBox client drills in via **categoryContent(tid=vod_id)**, NOT detail.
- PanSou `res` param: `all`/`results`/`merge`, default `merge`. `merge` returns `merged_by_type` = data **already grouped by disk type**. Backend `RemoteSearchService.search` already uses `res=merge` and processes `merged_by_type`.
- `RemoteSearchController./pansou/{token}` dispatch: `id`→detail, `wd`→flat search, `t=="0"`→home, other `t`→null.

## Mechanism

New spider `FishPanSouGroup` (key `csp_FishPanSouGroup`) routes to a new backend endpoint `/pansou-group/{token}`. That endpoint returns folder-typed search results (`vod_tag="folder"`) and serves the folder drill-down via categoryContent. Flat `/pansou` and `FishPanSou` behavior are untouched.

## Data flow (3 levels)

1. **Search** (`/pansou-group/{token}?wd=kw`) → `RemoteSearchService.pansouGroup(kw)`:
   - reuse `search(kw, channels)` (`res=merge` → filtered/sorted `List<Message>`, incl. invalid-link filtering when enabled),
   - generate a **short cache id**, store the list in the group cache,
   - group by `Message::getType`,
   - emit one folder per non-empty type: `vod_tag="folder"`, `vod_id="pgroup:<cacheId>:<type>"`, `vod_name=<typeName>网盘`, `vod_pic=getPic(type)`, `vod_remarks=<count>条结果`.
2. **Folder click** (`/pansou-group/{token}?t=pgroup:<cacheId>:<type>&pg=n`) → `pansouGroupList(cacheId, type, pg)`:
   - cache lookup → filter by type → paginate → resource `MovieList` (normal items, `vod_id=encodeUrl(link)`; no folder tag → click goes to detail).
   - cache miss (expired) → empty list.
3. **Resource click** (`/pansou-group/{token}?id=<link>`) → existing `detail(id)` → resolves share → playlist. Play via existing `/play`.

## Cache

`Cache<String, List<Message>> groupCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(15)).maximumSize(20).build();`
- Keyed by short cache id (≈8-char base36).
- `maximumSize 20` = at most 20 cached searches (NOT a result-count limit). Results are never capped.

## Encoding

- Folder `vod_id`: `pgroup:<cacheId>:<type>` — short, no keyword embedded.
- Parse on categoryContent: strip `pgroup:` prefix, split first `:` → `[cacheId, type]`.
- Resource `vod_id` unchanged (`encodeUrl(link)`), so existing detail/title-backfill still applies.

## Changes

| Layer | File | Change |
|---|---|---|
| Spider | `CatVodTVSpider/.../FishPanSou.java` | Extract `protected String pansouPath() { return "/pansou"; }`; replace the 5 `"/pansou"` literals (home/homeVideo/category/search/detail) with `pansouPath()`. `/play` untouched. Behavior-preserving. |
| Spider | `CatVodTVSpider/.../FishPanSouGroup.java` | NEW: `extends FishPanSou`, `@Override protected String pansouPath() { return "/pansou-group"; }`. ~5 lines. Rebuild `spring.jar` via `build.sh`. |
| Builtin registry | `SubscriptionSourceService.builtinDefinitions()` | `+1`: `new BuiltinDefinition("csp_FishPanSouGroup", "盘搜\|分组", order)` conditional on `panSouUrl` non-blank. `buildSite` unchanged (key==api==class). |
| Controller | `RemoteSearchController` | NEW `@GetMapping("/pansou-group")` + `@GetMapping("/pansou-group/{token}")`: `id`→`detail(id)`; `wd`→`pansouGroup(wd)`; `t=pgroup:…`→`pansouGroupList(t,pg)`; `t=="0"`/blank→empty. Flat `/pansou` untouched. |
| Service | `RemoteSearchService` | `pansouGroup(keyword)` + `pansouGroupList(tid,pg)`; new `groupCache`; short-id generator; reuse `search()`, `getTypeName()`, `getPic()`, `encodeUrl()`, per-message builder from `pansou()`. |

## Defaults / decisions

- Folder order follows `tgDriverOrder`; empty types hidden.
- List page size 20, paginated; folder `vod_remarks` shows the true total.
- New endpoint `/pansou-group` (chosen over query-param-on-`/pansou` for simplicity & isolation).
- Group source `homeContent` (no `wd`/`t`/`id`) → empty list (search-oriented).
- Frontend needs **no** change (builtin sources render generically).
- Detail/play paths reused unchanged.

## Build / deploy note

Editing the spider requires rebuilding `spring.jar` (Android `gradlew assembleRelease` + `jar/genJar.sh`) in the sibling repo and committing the regenerated `spring.jar` (+ `spring.md5`) here. The `FishPanSou` change is behavior-preserving (string → hook); flat behavior is unaffected. New class auto-registered by naming convention + kept by proguard.

## Out of scope

- Per-type filtering UI / sort within a folder.
- Re-search fallback on cache miss (folder expires → empty; user re-searches).
- Native-image: no new DTO packages (reuses `Message`, `MovieDetail`, `MovieList`) → no reflect-config changes; verify at build.
