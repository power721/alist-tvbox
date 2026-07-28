# 盘搜分组 (Pan-sou Grouped Source) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add a `盘搜|分组` builtin source whose search returns one `vod_tag="folder"` per disk type (with count); clicking a folder lists that type's resources.

**Architecture:** New spider `FishPanSouGroup` (path hook → `/pansou-group`) + new backend endpoint `/pansou-group/{token}` that groups the existing `res=merge` search results and serves the folder drill-down via `categoryContent`. Flat `鱼佬盘搜` / `FishPanSou` / `/pansou` untouched.

**Tech Stack:** Java 21 / Spring Boot 4 (backend, repo root); Android spider DEX (sibling `/home/harold/workspace/CatVodTVSpider`).

## Global Constraints

- 4-space indent, Lombok, service-first; no API breaking changes; keep diffs small.
- Two repos: backend edits in `alist-tvbox`; spider edits in `/home/harold/workspace/CatVodTVSpider`.
- **Commit/push only when the user explicitly asks** (user policy). Commit steps below are batch points to run on request. Work on a feature branch, not `master` (repo git-workflow §7).
- No new DTO packages (reuses `Message`, `MovieDetail`, `MovieList`) → no reflect-config changes; verify at build.
- Spec: `docs/superpowers/specs/2026-07-28-pansou-grouping-design.md`.

---

### Task 1: Backend grouping service (TDD)

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/RemoteSearchService.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/RemoteSearchServiceTest.java`

**Interfaces:**
- Produces: `MovieList pansouGroup(String keyword)`, `MovieList pansouGroupList(String tid, int pg)` on `RemoteSearchService`.

- [ ] **Step 1: Write failing tests** — append to `RemoteSearchServiceTest`:

```java
@Test
void pansouGroupReturnsFolderPerDiskType() {
    RestTemplate restTemplate = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    AppProperties appProperties = new AppProperties();
    appProperties.setPanSouUrl("http://pansou.example");
    appProperties.setPanSouSource("all");
    TelegramChannelRepository telegramChannelRepository = mock(TelegramChannelRepository.class);
    when(telegramChannelRepository.findByEnabledTrue(any())).thenReturn(List.of());
    OfflineDownloadService offlineDownloadService = mock(OfflineDownloadService.class);
    when(offlineDownloadService.getConfig()).thenReturn(new OfflineDownloadConfigDto(false, "", null, ""));

    RemoteSearchService service = new RemoteSearchService(
            appProperties, restTemplateBuilder(restTemplate), objectMapper,
            telegramChannelRepository, mock(ShareService.class),
            mock(TvBoxService.class), offlineDownloadService);

    server.expect(once(), requestTo("http://pansou.example/api/search"))
            .andRespond(withSuccess("""
                    {"code":0,"data":{"total":2,"results":[],"merged_by_type":{
                      "quark":[{"note":"电影A","url":"https://pan.quark.cn/s/a","work_title":"电影A"}],
                      "uc":[{"note":"电影B","url":"https://pan.uc.cn/s/b","work_title":"电影B"}]}}}
                    """, org.springframework.http.MediaType.APPLICATION_JSON));

    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    try {
        cn.har01d.alist_tvbox.tvbox.MovieList result = service.pansouGroup("电影");
        org.assertj.core.api.Assertions.assertThat(result.getList()).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(result.getList())
                .extracting(cn.har01d.alist_tvbox.tvbox.MovieDetail::getVod_tag)
                .containsOnly("folder");
        org.assertj.core.api.Assertions.assertThat(result.getList())
                .extracting(cn.har01d.alist_tvbox.tvbox.MovieDetail::getVod_name)
                .containsExactlyInAnyOrder("夸克网盘", "UC网盘");
        org.assertj.core.api.Assertions.assertThat(result.getList())
                .extracting(cn.har01d.alist_tvbox.tvbox.MovieDetail::getVod_remarks)
                .containsOnly("1条结果");
        // cache id present in vod_id; capture quark folder id for Task 1 step 4
        String quarkId = result.getList().stream()
                .filter(m -> "夸克网盘".equals(m.getVod_name())).findFirst().orElseThrow().getVod_id();

        // folder click → list resources of that type
        cn.har01d.alist_tvbox.tvbox.MovieList list = service.pansouGroupList(quarkId, 1);
        org.assertj.core.api.Assertions.assertThat(list.getList()).hasSize(1);
        // vod_id is encodeUrl(link) → starts with the scheme; NOT a pgroup: id
        org.assertj.core.api.Assertions.assertThat(list.getList().get(0).getVod_id()).startsWith("https");
        org.assertj.core.api.Assertions.assertThat(list.getTotal()).isEqualTo(1);
        server.verify();
    } finally {
        RequestContextHolder.resetRequestAttributes();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=RemoteSearchServiceTest#pansouGroupReturnsFolderPerDiskType`
Expected: FAIL — `method pansouGroup(String)` not found / compile error.

- [ ] **Step 3: Implement** — in `RemoteSearchService.java`:

Add imports: `import java.util.LinkedHashMap;` and `import java.util.UUID;`.

Add field next to `shareTitle`:
```java
private final Cache<String, List<Message>> groupCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(15))
        .maximumSize(20)
        .build();
```

Extract a per-message builder and refactor `pansou()` to use it (replaces the inline `for` body):
```java
private MovieDetail toMovieDetail(Message message) {
    var movieDetail = new MovieDetail();
    movieDetail.setVod_id(encodeUrl(message.getLink()));
    movieDetail.setVod_name(message.getName());
    if (StringUtils.isNotBlank(message.getLink()) && StringUtils.isNotBlank(movieDetail.getVod_name())) {
        shareTitle.put(message.getLink(), movieDetail.getVod_name());
    }
    if (StringUtils.isBlank(message.getCover())) {
        movieDetail.setVod_pic(getPic(message.getType()));
    } else {
        movieDetail.setVod_pic(message.getCover());
    }
    movieDetail.setVod_remarks(getTypeName(message.getType()));
    movieDetail.setVod_play_from(message.getChannel());
    if (message.getTime() != null) {
        movieDetail.setVod_time(message.getTime().toString());
    }
    movieDetail.setValidity_state(message.getValidityState());
    movieDetail.setValidity_summary(message.getValiditySummary());
    return movieDetail;
}
```
In `pansou(keyword)`, replace the inline `movieDetail` block inside the `for (var message : messages)` loop with `list.add(toMovieDetail(message));`.

Add the two public methods + helpers (after `pansou(...)`):
```java
public MovieList pansouGroup(String keyword) {
    long start = System.currentTimeMillis();
    List<String> channels = telegramChannelRepository.findByEnabledTrue(Sort.by("sortOrder")).stream()
            .filter(TelegramChannel::isValid)
            .map(TelegramChannel::getUsername)
            .toList();
    List<Message> messages = search(keyword, channels);
    String cacheId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    groupCache.put(cacheId, messages);

    Map<String, List<Message>> byType = new LinkedHashMap<>();
    for (String type : appProperties.getTgDriverOrder()) {
        byType.put(type, new ArrayList<>());
    }
    for (Message m : messages) {
        byType.computeIfAbsent(m.getType(), k -> new ArrayList<>()).add(m);
    }

    List<MovieDetail> folders = new ArrayList<>();
    for (var entry : byType.entrySet()) {
        if (entry.getValue().isEmpty()) {
            continue;
        }
        String type = entry.getKey();
        String typeName = getTypeName(type);
        var folder = new MovieDetail();
        folder.setVod_id("pgroup:" + cacheId + ":" + type);
        folder.setVod_name((typeName != null ? typeName : type) + "网盘");
        folder.setVod_pic(getPic(type));
        folder.setVod_remarks(entry.getValue().size() + "条结果");
        folder.setVod_tag("folder");
        folders.add(folder);
    }

    var result = new MovieList();
    result.setList(folders);
    result.setTotal(folders.size());
    result.setLimit(folders.size());
    log.info("Grouped search {} -> {} disk types, elapsed {} ms", keyword, folders.size(), System.currentTimeMillis() - start);
    return result;
}

public MovieList pansouGroupList(String tid, int pg) {
    int page = Math.max(1, pg);
    String rest = tid.startsWith("pgroup:") ? tid.substring("pgroup:".length()) : "";
    int sep = rest.indexOf(':');
    if (sep < 0) {
        return emptyGroupList(page);
    }
    String cacheId = rest.substring(0, sep);
    String type = rest.substring(sep + 1);
    List<Message> messages = groupCache.getIfPresent(cacheId);
    if (messages == null) {
        return emptyGroupList(page);
    }
    List<MovieDetail> all = messages.stream()
            .filter(m -> type.equals(m.getType()))
            .map(this::toMovieDetail)
            .toList();
    int size = 20;
    int from = Math.min((page - 1) * size, all.size());
    int to = Math.min(from + size, all.size());
    List<MovieDetail> pageItems = new ArrayList<>(all.subList(from, to));

    var result = new MovieList();
    result.setList(pageItems);
    result.setPage(page);
    result.setPagecount((int) Math.ceil(all.size() / (double) size));
    result.setLimit(pageItems.size());
    result.setTotal(all.size());
    return result;
}

private MovieList emptyGroupList(int page) {
    var result = new MovieList();
    result.setList(new ArrayList<>());
    result.setPage(page);
    result.setPagecount(1);
    result.setLimit(0);
    result.setTotal(0);
    return result;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=RemoteSearchServiceTest`
Expected: PASS (all tests incl. existing `detailBackfillsSearchResultTitleForPanSou` — the `pansou()` refactor must not regress it).

- [ ] **Step 5: Commit (on request)**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/RemoteSearchService.java \
        src/test/java/cn/har01d/alist_tvbox/service/RemoteSearchServiceTest.java
git commit -m "feat: add pansou grouping service (folders per disk type)"
```

---

### Task 2: Backend controller endpoint

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/web/RemoteSearchController.java`

**Interfaces:**
- Consumes: `RemoteSearchService.pansouGroup(String)`, `pansouGroupList(String,int)`, `detail(String)`.

- [ ] **Step 1: Add the `/pansou-group` mappings** — alongside the existing `/pansou` methods:

```java
@GetMapping("/pansou-group")
public Object pansouGroup(String id, String t, String wd, @RequestParam(required = false, defaultValue = "1") int pg) {
    return pansouGroup("", id, t, wd, pg);
}

@GetMapping("/pansou-group/{token}")
public Object pansouGroup(@PathVariable String token, String id, String t, String wd, @RequestParam(required = false, defaultValue = "1") int pg) {
    subscriptionService.checkToken(token);
    if (StringUtils.isNotBlank(id)) {
        return remoteSearchService.detail(id);
    } else if (StringUtils.isNotBlank(wd)) {
        return remoteSearchService.pansouGroup(wd);
    } else if (StringUtils.isNotBlank(t) && !"0".equals(t)) {
        return remoteSearchService.pansouGroupList(t, pg);
    }
    return null;
}
```

- [ ] **Step 2: Verify compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit (on request)**

```bash
git add src/main/java/cn/har01d/alist_tvbox/web/RemoteSearchController.java
git commit -m "feat: add /pansou-group endpoint for grouped pansou source"
```

---

### Task 3: Register the builtin source

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionSourceService.java:280-282`

- [ ] **Step 1: Add the definition** — in `builtinDefinitions()`, replace the pansou block:

```java
if (StringUtils.isNotBlank(appProperties.getPanSouUrl())) {
    definitions.add(new BuiltinDefinition("csp_FishPanSou", "鱼佬盘搜", order));
    definitions.add(new BuiltinDefinition("csp_FishPanSouGroup", "盘搜|分组", order++));
}
```
(`FishPanSou` keeps its existing `order`; the new source takes the next slot. `buildSite` needs no change — key==api==class.)

- [ ] **Step 2: Verify compile + test**

Run: `mvn -q test -Dtest=RemoteSearchServiceTest && mvn -q compile`
Expected: BUILD SUCCESS, tests pass.

- [ ] **Step 3: Commit (on request)**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/SubscriptionSourceService.java
git commit -m "feat: register 盘搜|分组 builtin source"
```

---

### Task 4: Spider — `FishPanSouGroup` + rebuild `spring.jar`

**Files (sibling repo `/home/harold/workspace/CatVodTVSpider`):**
- Modify: `app/src/main/java/com/github/catvod/spider/FishPanSou.java`
- Create: `app/src/main/java/com/github/catvod/spider/FishPanSouGroup.java`
- Produced: `app/build/.../classes.dex` → `jar/custom_spider.jar` → copied to `alist-tvbox/src/main/resources/static/spring.jar`

- [ ] **Step 1: Add the path hook to `FishPanSou`**

In `FishPanSou.java`, replace the 5 occurrences of `"/pansou"` (in `homeContent`, `homeVideoContent`, `categoryContent`, `detailContent`, `searchContent`) with `pansouPath()`. Example for `homeContent`:

```java
String api = baseUrl + pansouPath() + (token.isEmpty() ? "" : "/" + token);
```
Leave `requestPlayerContent`'s `/play` unchanged. Add the hook method:

```java
protected String pansouPath() {
    return "/pansou";
}
```

- [ ] **Step 2: Create `FishPanSouGroup.java`**

```java
package com.github.catvod.spider;

public class FishPanSouGroup extends FishPanSou {
    @Override
    protected String pansouPath() {
        return "/pansou-group";
    }
}
```

- [ ] **Step 3: Rebuild and copy** (requires Android SDK + the repo's `local.properties`)

```bash
cd /home/harold/workspace/CatVodTVSpider
./build.sh
```
Expected: prints two `date` lines, ends with `custom_spider.jar` + `.md5` copied to `alist-tvbox/src/main/resources/static/spring.{jar,md5}`.

- [ ] **Step 4: Verify the jar changed & contains the new class**

```bash
cd /home/harold/workspace/alist-tvbox
unzip -p src/main/resources/static/spring.jar classes.dex | strings | grep -c "FishPanSouGroup"
```
Expected: `1` (or more). If `0`, the class was stripped — confirm proguard `-keep class com.github.catvod.spider.**` is present (it is) and rebuild.

- [ ] **Step 5: Commit both repos (on request)**

```bash
# CatVodTVSpider
cd /home/harold/workspace/CatVodTVSpider
git add app/src/main/java/com/github/catvod/spider/FishPanSou.java \
        app/src/main/java/com/github/catvod/spider/FishPanSouGroup.java
git commit -m "feat: add FishPanSouGroup spider for grouped pansou"
# alist-tvbox
cd /home/harold/workspace/alist-tvbox
git add src/main/resources/static/spring.jar src/main/resources/static/spring.md5
git commit -m "chore: rebuild spring.jar with FishPanSouGroup"
```

---

### Task 5: End-to-end build + smoke

- [ ] **Step 1: Full backend build**

Run: `mvn -q clean package`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Smoke (manual, after run)**

- `GET /sub/{token}/<profile>` JSON now contains a `csp_FishPanSouGroup` site whose `api` == `csp_FishPanSouGroup` and `jar` == `<url>/spring.jar`.
- `GET /pansou-group/{token}?wd=<kw>` → `list[]` of folders: `vod_tag=="folder"`, names `<type>网盘`, `vod_remarks=="<n>条结果"`, `vod_id=="pgroup:<id>:<type>"`.
- `GET /pansou-group/{token}?t=pgroup:<id>:<type>&pg=1` → resource list of that type.
- `GET /pansou-group/{token}?id=<encodedShareLink>` → same playlist as flat `/pansou`.
- Re-search after 15 min → folder click returns empty (cache expired) — re-search repopulates.

- [ ] **Step 3: Native-image note**

If building native (`-Pnative`): no new DTO packages were added, so no reflect/resource-config edits expected. Run `mvn clean package -Pnative` to confirm per repo policy.

---

## Self-Review notes

- **Spec coverage:** all spec sections covered (spider hook+subclass T4, builtin T3, controller T2, service T1, cache/encoding in T1, build note T4). ✓
- **Type consistency:** `pansouGroup(String)→MovieList`, `pansouGroupList(String,int)→MovieList`, `pansouPath()→String` consistent across tasks. `toMovieDetail(Message)→MovieDetail` shared by `pansou()` and `pansouGroupList`. ✓
- **Placeholder scan:** none — the resource `vod_id` assertion uses `startsWith("https")` (id is `encodeUrl(link)`). ✓

## Unresolved questions (for the user)

1. The spider rebuild (Task 4 Step 3) needs the Android SDK configured in `CatVodTVSpider/local.properties` — is that environment available, or should I (a) leave the spring.jar rebuild to you, or (b) attempt it?
2. Folder display name `<typeName>网盘` (e.g. "夸克网盘", "UC网盘") — keep, or drop the "网盘" suffix for types that already read as drives (e.g. "115网盘")?
