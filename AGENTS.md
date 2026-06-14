# AGENTS.md

This is the single source of truth for AI agents (Codex / Claude Code).

---

# 1. Project Overview

AList-TvBox is a Spring Boot 3 (Java 21) + Vue 3 system:

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

---

# 7. Git Workflow

1. worktree per task
2. feature branch
3. no direct main commits
4. PR-based merge
5. cleanup after merge

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
