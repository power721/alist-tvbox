# AGENTS.md

This is the single source of truth for AI agents (Codex / Claude Code).

---

# 1. Project Overview

AList-TvBox is a Spring Boot 4 (Java 21) + Vue 3 system:

- TVBox VOD API backend
- Cloud storage aggregation (AList)
- Telegram media search engine
- Live streaming aggregator
- Plugin + filter system
- Embedded AList runtime

Base package: cn.har01d.alist_tvbox

---

# 2. Architecture Overview

Controller → Service → Repository → Database

---

## Core Modules

### Storage (storage/)
- 30+ cloud drive adapters
- Strategy pattern
- AList-compatible config generator

### Live (live/)
Multi-platform streaming:
Huya / Douyu / Bilibili / CC / Kuaishou / Douyin

### Telegram Search
- Jsoup scraping
- Caffeine cache
- Media extraction

APIs:
- /tg-search
- /tgsc
- /tgs

### Plugin System
- Spider plugins (.txt)
- Filter plugins (.py)

### Offline Download
- 115 / Guangya / Thunder handlers

### Embedded AList
- subprocess runtime
- WebDAV proxy (/dav/)

---

# 3. Security Model

- X-API-KEY → CLIENT
- Basic Auth → legacy APIs
- Authorization → session tokens

Roles:
- ADMIN
- USER
- CLIENT

---

# 4. Scheduling Jobs

- Index build (22:00 + hourly)
- Douban sync (20:00 / 22:00)
- Share validation (:00 / :30)
- Proxy cleanup (:45)
- Daily cleanup (06:00)

---

# 5. Development Guide

Backend:
mvn clean package
mvn spring-boot:run
mvn test

Frontend:
cd web-ui
npm install
npm run dev
npm run build
npm run lint

---

# 6. Code Rules

## Java
- 4-space indent
- Lombok required
- Service-first design

## DTO Rules
- Must be top-level class
- Must be in dto/

## Frontend
- Vue 3 + TypeScript
- PascalCase components

## Database
- use Flyway migration

## Runtime Packaging
- JVM jar builds are the primary supported deployment target.
- Native Image configuration is retained as an optional build path.
- Native Image support must be validated separately with `mvn clean package -Pnative`.

## Native Image
- All resource files MUST be registered in `META-INF/native-image/resource-config.json`
    - GraalVM only includes explicitly declared resources
    - Missing resources cause runtime "unsupported protocol: resource" errors
- Common patterns:
    - Text/config files: `{"pattern": "filename.txt"}`
    - Directories: `{"pattern": "path/to/dir/.*"}`
    - SQL migrations: `{"pattern": "db/migration/.*"}`, `{"pattern": "db/migration/current/.*\\.sql"}`
- Reflection configuration:
    - **New DTO/entity packages MUST be added to Main.java scan list first**
    - After adding packages or classes, regenerate `reflect-config.json`:
      ```bash
      mvn compile
      java -cp target/classes cn.har01d.alist_tvbox.Main
      ```
    - Main.java scans configured packages (dto/entity/model/domain/storage/etc)
    - Individual classes can be added to `CUSTOM_REFLECTION_CLASSES` list
- Flyway Java migration file must add to src/main/resources/META-INF/services/org.flywaydb.core.api.migration.JavaMigration
- Flyway Java migration must ALSO be registered in src/main/java/cn/har01d/alist_tvbox/config/NativeFlywayMigrationConfig.java (native image cannot classpath-scan Java migrations)

---

# 8. AI CODING RULES

## Codex Mode
- Minimal patch only
- No refactor unless requested
- Must compile
- No API breaking changes

## Claude Mode
- Analyze before changes
- Prefer simple solutions
- Avoid over-engineering

## Shared Rules
- No cross-layer logic
- No API break
- Keep diffs small

## Documents
- Save all documents to folder docs/
- Save security related documents to folder docs/security/

# 9. 关联代码仓库
- PowerList（AList）： /home/harold/GolandProjects/PowerList
- TG-search： /home/harold/workspace/telegram-search
- TvBox爬虫（spring.jar）： /home/harold/workspace/CatVodTVSpider
- 桌面客户端： /home/harold/workspace/atv-player
- TvBox插件源码： /home/harold/workspace/atv-spiders
- TvBox插件发布： /home/harold/workspace/tvbox-resources
- 猫源插件： /home/harold/workspace/CatVodOpen/open/nodejs
- 115分享索引： /home/harold/workspace/five
- 豆瓣电影数据： /home/harold/workspace/xiaoya-douban
- PG、真心本地包： /home/harold/workspace/pg
- 斗鱼抖音签名： /home/harold/WebstormProjects/douyu-sign
- 版本同步、包同步： /home/harold/GolandProjects/atv-sync
- 阿里云盘Open Token： /home/harold/GolandProjects/AliToken
- 115云盘驱动： /home/harold/GolandProjects/115driver

# 10. Docker mounts
host to docker container
- /opt/alist-tvbox to /data
- /opt/alist-tvbox/www-static to /www/static

## database
/opt/alist-tvbox/atv.mv.db
