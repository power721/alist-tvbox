# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AList-TvBox is a Spring Boot application that serves as a proxy/management server for TvBox clients. It aggregates multiple cloud storage platforms into a unified VOD API, with a Vue 3 web UI for administration. Base package: `cn.har01d.alist_tvbox`.

## Build and Run Commands

```bash
# Full build (web UI + Java)
mvn clean package

# Build web UI only
cd web-ui && npm run build    # output → src/main/resources/static/

# Run
java -jar target/alist-tvbox-1.0.jar

# Run tests
mvn test

# Run single test class
mvn test -Dtest=TvBoxServiceTest

# Run single test method
mvn test -Dtest=TvBoxServiceTest#testMethodName

# Native build (requires Java 21 + GraalVM + musl)
./build-native.sh

# Docker build + run
./build-docker.sh
docker run -d -p 4567:4567 -p 5344:80 -e ALIST_PORT=5344 -v /etc/xiaoya:/data --restart=always --name=xiaoya-tvbox haroldli/xiaoya-tvbox:latest
```

Other build scripts: `build-java.sh`, `build-xiaoya.sh`, `build-hostmode.sh`, `build-app.sh`, `build-musl.sh`, `build-service.sh`.

## Key Technologies

- **Backend**: Spring Boot 3.5.9, Java 21, Spring Data JPA, Spring Security, Lombok
- **Databases**: H2 (default), MySQL, SQLite — configurable via Spring profiles
- **Frontend**: Vue 3 + TypeScript + Vite + Element Plus (in `web-ui/`)
- **HTTP Client**: OkHttp 4.12.0
- **Video Proxy**: Pre-compiled Go binary in `atv-proxy/` (source not in repo)
- **Caching**: Caffeine
- **Auth**: Custom UUID token-based auth with BCrypt passwords (not JWT)

## Architecture

### Layered Pattern
Controller → Service → Repository (Spring Data JPA) → H2/MySQL.

### Dual DataSource
`DatabaseConfig` sets up two DataSources:
1. **Primary**: App's own database (H2 file at `/opt/atv/data/data`, or MySQL)
2. **Secondary**: Reads AList's own database (SQLite or MySQL) for cross-querying storage data

### Key Subsystems

**Storage providers** (`storage/`) — 32 classes, one per cloud drive type. Each builds AList-compatible storage configs (driver name, mount path, addition JSON). Supported: Aliyun, Baidu, Quark, UC, 115, 123, 139, 189, PikPak, Thunder, GuangYaPan, Local, AList, Alias, STRM, UrlTree. Strategy pattern with `Storage` base class.

**Live streaming** (`live/`) — Independent module aggregating 6 platforms (Huya, Douyu, Bilibili, CC, Kuaishou, Douyin). All implement `LivePlatform` interface. Exposed via `LiveController` at `/live` and `/live-play`.

**Plugin system** — Two types:
- **Plugins** (spider scripts): served as `.txt` at `/plugins/{token}/{id}.txt`, imported from GitHub registry
- **Plugin Filters** (Python scripts): served as `.py` at `/plugin-filters/{token}/{id}.py`, with configurable scope and config schemas

**Telegram search** (`TelegramService`, `TelegramController`) — Scrapes Telegram channels for media share links, parses with Jsoup, Caffeine-cached. Admin APIs for channel CRUD, public APIs for search at `/tg-search`, `/tgsc`, `/tg-db`, `/tgs`.

**Offline download** (`service/offline/`) — Strategy pattern with handlers per provider: `Pan115OfflineDownloadHandler`, `GuangyapanOfflineDownloadHandler`, `ThunderOfflineDownloadHandler`.

**Embedded AList** (`AListLocalService`) — Manages AList as a child process. `WebDavProxyFilter` proxies `/dav/` requests to AList's internal port.

### Security (`auth/`)
- `TokenFilter` checks: (1) `X-API-KEY` header → CLIENT role, (2) Basic auth for `/open`, `/node`, `/cat`, (3) `Authorization` header → session-based auth
- `SessionTokenService`: UUID tokens in DB, 30-day expiry, max 5 sessions/user
- Roles: `ADMIN`, `USER`, `CLIENT` (in `domain/Role`)
- CSRF disabled, stateless sessions

### Scheduled Tasks
8 `@Scheduled` cron jobs across: `IndexService` (index building at 22:00 + hourly prime-time), `DoubanService` (metadata scrape 20:00/22:00), `ShareService` (validation at :00/:30), `ProxyService` (cleanup at :45), `SettingService` (daily cleanup 06:00), `BiliBiliService`, `AccountService`.

### Controllers (46) → Main API Surface
- `TvBoxController` — Core TVBox VOD API: `/vod`, `/vod1`, `/m3u8`, `/sub`, `/open`, `/node`, `/cat`, `/repo`
- `PlayController` — `/p/{token}/{id}`, `/play`, `/play-urls`
- `SubscriptionController` / `SubscriptionSourceController` — TVBox subscription management
- `ShareController` — Share links, storage CRUD, import/export, cookie proxies
- `SiteController` — Site CRUD and browsing
- `AListController` / `AListAliasController` — Embedded AList management
- `EmbyController` / `JellyfinController` / `FeiniuController` — Media server integrations
- `BiliBiliController` — BiliBili videos/seasons/collections
- `TelegramController` — Channel management + search
- `PluginController` / `PluginFilterController` — Plugin management
- `IndexController` / `IndexFileController` / `IndexTemplateController` — Index management
- `DriverAccountController` / `PikPakController` / `Pan115Controller` — Cloud drive accounts
- `RemoteSearchController` — PanSou + TG search proxy
- `DoubanController` / `TmdbController` — Metadata management
- `OfflineDownloadController` — Offline download config
- `LiveController` — Live streaming aggregation

### Services (46)
Key services: `TvBoxService` (core VOD logic), `AListService` (AList API client), `IndexService` (media indexing), `SubscriptionService`, `BiliBiliService`, `EmbyService`, `JellyfinService`, `TelegramService`, `PluginService`, `ShareService`, `SiteService`, `OfflineDownloadService`, `ProxyService`, `TmdbService`, `DoubanService`, `RemoteSearchService`.

### Entities (31)
`Site`, `Share`, `Movie`, `History`, `Device`, `User`, `Session`, `Setting`, `Tenant`, `Subscription`, `Account`, `DriverAccount`, `PikPakAccount`, `Plugin`, `PluginFilter`, `Meta`, `Tmdb`, `TmdbMeta`, `Emby`, `Jellyfin`, `Feiniu`, `Task`, `PlayUrl`, `Alias`, `AListAlias`, `ConfigFile`, `IndexTemplate`, `Navigation`, `TelegramChannel`, `OfflineDownloadTask`, `PanAccount`. Each has a matching `JpaRepository`.

## Configuration

### Spring Profiles
`application.yaml` (default/H2), `application-dev.yaml`, `application-docker.yaml`, `application-host.yaml`, `application-mysql.yaml`, `application-xiaoya.yaml`, `application-production.yaml`.

### Key Config Sections
- `app.sites` — Default AList site URL
- `app.subtitles` / `app.formats` — Supported media extensions
- `app.tgSearch` / `app.panSearch` — External search API config
- `app.localProxyConfig` — Per-driver proxy settings
- `spring.datasource` — DB config (H2/MySQL)
- `spring.jpa.hibernate.ddl-auto: update` — Auto DDL (Flyway migrations also exist in `db/migration/`)

### TvBox Configuration URL
`http://ip:4567/sub/0`

### Docker Architecture
Dockerfile in `docker/`: base `haroldli/alist-base` (busybox httpd + nginx + JRE + AList binary). Entrypoint starts httpd(81) → nginx → Java app. Ports: 4567 (API), 5244 (AList). Data volume: `/data`. Memory via `MEM_OPT` env (default `-Xmx1024M`).

## Testing

JUnit 5 + Spring Boot Test + Mockito. Tests mirror source package structure under `src/test/java/cn/har01d/alist_tvbox/`:
- `web/` — Controller tests
- `service/` — Service tests
- `storage/` — Storage config tests
- `auth/` — Auth filter tests
- `live/service/` — Live streaming tests
- `util/` — Utility tests

## Code Conventions

- Lombok `@Data`, `@Slf4j` on most classes — no manual getters/setters/logging
- DTOs in `dto/` sub-packages (`bili/`, `emby/`, `tg/`, `pansou/`, `driver/`)
- AList API models in `model/`
- TVBox API models in `tvbox/`
- Domain enums in `domain/` (`DriverType`, `Role`, `TaskType`, `TaskStatus`)
- Custom exceptions in `exception/` (`BadRequestException`, `NotFoundException`, `UserUnauthorizedException`)
