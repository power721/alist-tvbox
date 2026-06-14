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
