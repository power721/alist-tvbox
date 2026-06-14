# ARCHITECTURE.md

## System Overview

AList-TvBox is a Spring Boot-based media aggregation system providing:
- TVBox VOD API
- Cloud storage aggregation (AList)
- Telegram media search
- Live streaming aggregation
- Plugin-based extensibility

Base package: cn.har01d.alist_tvbox

---

## High-Level Architecture

Controller → Service → Repository → Database

Two data sources:
1. Primary DB (app data: H2/MySQL/SQLite)
2. Secondary DB (external AList database)

---

## Core Modules

### Storage System (storage/)
- 30+ cloud drive adapters
- Strategy pattern
- AList-compatible config generator

Examples: Aliyun, Baidu, Quark, 115, PikPak

---

### Live Streaming (live/)
Platforms:
- Huya
- Douyu
- Bilibili
- CC
- Kuaishou
- Douyin

All implement LivePlatform

---

### Telegram Search
- TelegramService
- TelegramController
- Jsoup scraping + caching

APIs:
- /tg-search
- /tgsc
- /tgs

---

### Plugin System
Spider plugins (.txt) + Filter plugins (.py)

---

### Offline Download
- 115 Pan
- GuangyaPan
- Thunder

---

### Embedded AList
- subprocess runtime
- WebDAV proxy (/dav/)

---

## Security

- X-API-KEY → CLIENT
- Basic Auth → legacy
- Session token auth

Roles: ADMIN / USER / CLIENT

---

## Scheduling

- Index build
- Douban scrape
- Share validation
- Proxy cleanup
- Daily cleanup

---

## Core Design Principles

- Service-oriented architecture
- Adapter-based storage layer
- Plugin extensibility
- External system isolation


---

## AI Coding Rules (Codex / Claude)

### General Principles
- Prefer **small, incremental changes**
- Never perform large-scale refactors unless explicitly requested
- Maintain backward compatibility at all times
- Keep changes **local to the feature/module**

---

### Codex Mode (Patch Executor)
- Work in **minimal diff patches**
- Do NOT rename files, classes, or public APIs
- Do NOT reformat code or style-only changes
- One logical change per commit
- Ensure code compiles after each step
- Avoid cross-module modifications unless required
- Always respect existing architecture boundaries

---

### Claude Mode (Architecture Reasoning)
- Focus on system design and impact analysis
- Propose changes before implementation
- Highlight risks and dependency impact
- Prefer extending existing services over creating new layers
- Avoid unnecessary abstraction

---

### Forbidden Actions (All AI)
- No mass refactoring without explicit request
- No moving packages or reorganizing structure
- No silent API changes
- No mixing controller/service/storage responsibilities
- No deleting unused code unless confirmed

---

### Safe Refactor Rules
- Keep controller → service → repository flow intact
- Prefer additive changes over modifications
- Reuse existing DTOs and services
- Introduce new modules only when necessary
