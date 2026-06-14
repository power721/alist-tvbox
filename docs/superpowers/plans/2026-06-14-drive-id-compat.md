# Drive Identifier Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace public share type identifiers with drive strings such as `quark` and `baidu`, while continuing to accept legacy numeric IDs.

**Architecture:** Keep `Share.type` as the existing numeric database field. Add a focused mapping utility that converts request parameters, import/export prefixes, and temp-share path prefixes between public drive IDs and internal numeric IDs.

**Tech Stack:** Spring Boot 3, Java 21, JUnit 5, Mockito, Vue 3, TypeScript.

---

### Task 1: Drive Mapping Utility

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/domain/DriveId.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/domain/DriveIdTest.java`

- [ ] Write tests for numeric and drive parsing, canonical export names, and unknown values.
- [ ] Implement `DriveId` with mappings: `0 ali`, `1 pikpak`, `2 thunder`, `3 123`, `5 quark`, `6 139`, `7 uc`, `8 115`, `9 189`, `10 baidu`, `11 strm`, `12 duck`.
- [ ] Verify `mvn test -Dtest=DriveIdTest`.

### Task 2: Backend Request Compatibility

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/dto/SharesDto.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/web/ShareController.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/ShareService.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/ShareServiceDriveIdTest.java`

- [ ] Write failing service tests for import with `quark` DTO type, import with `baidu:...` inline prefix, import with legacy `10:...`, and export output using `quark:...`.
- [ ] Change DTO/controller/service public type parameters from `int` where needed to `String`, parsing through `DriveId`.
- [ ] Keep repository queries using numeric type after conversion.
- [ ] Verify focused tests pass.

### Task 3: Temp Link and Proxy Path Compatibility

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/ShareService.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/ShareServiceDriveIdTest.java`

- [ ] Add tests showing `getLinkByPath("/temp/quark@abc@pwd")` and legacy `getLinkByPath("/temp/5@abc@pwd")` both work.
- [ ] Change new temp share generation to use drive IDs, not numbers.
- [ ] Keep old temp paths readable by parsing either format.
- [ ] Verify focused tests pass.

### Task 4: Frontend Public Parameters

**Files:**
- Modify: `web-ui/src/views/SharesView.vue`

- [ ] Change share import/export option values to drive IDs for public API calls.
- [ ] Keep existing row display and stored share model numeric because backend entities still return numeric `type`.
- [ ] Verify `cd web-ui && npm run build` if dependencies are available.

### Task 5: Final Verification and Commit

**Files:**
- All modified files above.

- [ ] Run `mvn test`.
- [ ] Run `cd web-ui && npm run build`.
- [ ] Review `git diff`.
- [ ] Commit with `feat: use drive identifiers for share type APIs`.
