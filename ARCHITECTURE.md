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

### Live Streaming (live/)
Multiple platform aggregators implementing LivePlatform

### Telegram Search
Jsoup scraping + caching + media extraction

### Plugin System
Spider (.txt) + Filter (.py)

### Offline Download
Strategy-based provider handlers

### Embedded AList
Subprocess + WebDAV proxy

---

## Security

- X-API-KEY → CLIENT
- Basic Auth → legacy
- Session token auth

Roles: ADMIN / USER / CLIENT

---

## Scheduling
Multiple cron jobs for indexing, cleanup, sync tasks

---

## AI CODING RULES (IMPORTANT)

### Codex Mode (Execution)
- Only small, local changes
- No refactor without request
- Must not change public APIs
- Must preserve compilation
- Prefer patch-level edits

### Claude Mode (Architecture)
- Analyze before modifying
- Prefer reuse over new abstraction
- Avoid unnecessary layering
- Do not over-engineer

### Shared Rules
- Never modify controller/service boundaries incorrectly
- Never break external API contracts
- Never introduce cross-layer logic
- Keep changes minimal and reversible

---

## Design Principles

- Service-oriented architecture
- Adapter-based storage layer
- Plugin extensibility
- External system isolation