# Auto-Update Data Sources — Design

Date: 2026-07-22
Owner: Har01d
Status: decisions confirmed; structure refined 2026-07-22 (see bottom note)

## Goal

Turn 4 data sources that today are manual (or only auto on the `xiaoya` profile) into **auto check + auto download**, running each evening with a **0–30 min random jitter** so instances don't hammer the source servers at the same instant.

## Sources & scope

| Source | Service | Base time | Scope | Reused method |
|---|---|---|---|---|
| 3 本地包 (PG/ZX/XS) | `SubscriptionService` | 20:00 | **all deployments** | `syncCat()` |
| 索引数据 | `IndexService` | 22:00 | **xiaoya only** | `update()` → `getRemoteVersion()` |
| 豆瓣数据 | `DoubanService` | 20:00 + 22:00 | **xiaoya only** | `update()` → `getRemoteVersion(Versions)` |
| 115 分享本地索引 | `Index115Service` | 23:00 | **all deployments** | `check()` → `update()` |

- xiaoya-only for index/douban is **free**: `getRemoteVersion()` early-returns `""` when `!environment.matchesProfiles("xiaoya")` (`IndexService.java:201`, `DoubanService.java:221`). No new gate code.
- 3本地包 / 115 have no gate → run on every deployment.
- Download logic is reused unchanged: each path already compares remote vs local and skips when equal.

## Randomness mechanism

- Jitter: **0–29 min**, re-rolled each run — `ThreadLocalRandom.current().nextLong(0, 30)` minutes.
- **Must not block Spring's scheduler.** `@EnableScheduling` (`AListApplication.java:11`) uses Spring Boot's default `ThreadPoolTaskScheduler` with **pool size 1** — a single thread shared by *every* `@Scheduled` job. Sleeping inside a `@Scheduled` method would stall them all.
- Solution: a dedicated `ScheduledExecutorService` (2 threads) inside `AutoUpdateExecutor`. Each `@Scheduled` method just calls `autoUpdateExecutor.scheduleWithJitter(task)` and **returns immediately**. The jitter wait + the lightweight version-check run on the dedicated pool; the heavy downloads still run on each service's own executor.

## Changes

### New: `service/AutoUpdateExecutor.java` (jitter helper)

```java
@Slf4j
@Component
public class AutoUpdateExecutor {
    private final ScheduledExecutorService executor;

    public AutoUpdateExecutor() {
        this(Executors.newScheduledThreadPool(2));
    }

    AutoUpdateExecutor(ScheduledExecutorService executor) { // package-private, for unit tests
        this.executor = executor;
    }

    public void scheduleWithJitter(Runnable task) {
        long minutes = ThreadLocalRandom.current().nextLong(0, 30); // 0..29
        executor.schedule(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.warn("auto-update task failed", e);
            }
        }, minutes, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
```

### New: `service/AutoUpdateService.java` (centralized scheduler)

Owns all 4 evening triggers. Each defers the real work to `AutoUpdateExecutor` with jitter, then returns. No service constructors are touched.

```java
@Slf4j
@Service
public class AutoUpdateService {
    private final AutoUpdateExecutor executor;
    private final SubscriptionService subscriptionService;
    private final IndexService indexService;
    private final DoubanService doubanService;
    private final Index115Service index115Service;

    public AutoUpdateService(AutoUpdateExecutor executor,
                             SubscriptionService subscriptionService,
                             IndexService indexService,
                             DoubanService doubanService,
                             Index115Service index115Service) {
        this.executor = executor;
        this.subscriptionService = subscriptionService;
        this.indexService = indexService;
        this.doubanService = doubanService;
        this.index115Service = index115Service;
    }

    @Scheduled(cron = "0 0 20 * * ?")          // 3 本地包 — all deployments
    public void autoSyncCat() {
        executor.scheduleWithJitter(subscriptionService::syncCat);
    }

    @Scheduled(cron = "0 0 22 * * ?")          // 索引 — xiaoya gate lives in getRemoteVersion
    public void autoIndex() {
        executor.scheduleWithJitter(indexService::update);
    }

    @Scheduled(cron = "0 0 20,22 * * ?")       // 豆瓣 — keep 2×/day
    public void autoDouban() {
        executor.scheduleWithJitter(doubanService::update);
    }

    @Scheduled(cron = "0 0 23 * * ?")          // 115 — all deployments
    public void autoUpdate115() {
        executor.scheduleWithJitter(this::update115);
    }

    void update115() {                          // package-private for tests
        Index115CheckResult r = index115Service.check();   // record: hasUpdate() accessor
        if (r.hasUpdate()) {
            try {
                index115Service.update();
            } catch (BadRequestException e) {
                log.debug("index115 auto-update skipped: {}", e.getMessage()); // download task already running
            }
        }
    }
}
```

`check()` returns `hasUpdate=false` when there is no 115 account → no-op; the gate also avoids creating a daily Task record when already up-to-date.

### `IndexService.update()` (line 195) — drop the `@Scheduled` annotation

The method body stays (`public void update() { getRemoteVersion(); }`); only the `@Scheduled(cron = "0 0 22 * * ?")` line is removed. It is now triggered (with jitter) by `AutoUpdateService.autoIndex()`. No constructor change.

### `DoubanService.update()` (line 215) — drop the `@Scheduled` annotation

Same: remove only `@Scheduled(cron = "0 0 20,22 * * ?")`; keep the method. Triggered by `AutoUpdateService.autoDouban()`. No constructor change.

### Nothing else changes

- `SubscriptionService.syncCat()`, `Index115Service.check()`, `Index115Service.update()` are called as-is. Manual buttons (`POST /api/cat/sync`, `POST /api/index115/update`) keep their immediate behavior.
- Controllers, DTOs, frontend, DB: untouched.

## Concurrency & error handling (all reused, not new)

- **3本地包**: `FileDownloader` single-thread executor serializes; each `downloadPg/Zx/Xs` skips when `local == remote`.
- **索引**: `IndexService.executor`; `getRemoteVersion` only fires `updateXiaoyaIndexFile` when `remote != local`.
- **豆瓣**: `DoubanService.executor` + `downloading` flag + triple-compare guard.
- **115**: `TaskService.isTaskRunning(DOWNLOAD)` guard inside `update()`.
- `AutoUpdateExecutor` swallows exceptions per-task so one source failing doesn't affect the others or the scheduler.

## Out of scope

- No new UI toggle, no frontend changes. Existing version cards continue to reflect the auto-updated state; manual buttons remain as a fallback.
- Do **not** lift the `xiaoya` gate; `scripts/index.sh` stays bash (only runs on xiaoya where these execute).
- No native-image config changes (`@Component` + `@Scheduled` are handled by Spring's native support; existing jobs already work in native).

## Testing

- `AutoUpdateExecutorTest`: with a mocked `ScheduledExecutorService`, `scheduleWithJitter` calls `schedule(task, delay, MINUTES)` with `delay` in `[0, 30)` and swallows a thrown exception.
- `AutoUpdateServiceTest` (Mockito): `update115()` calls `index115Service.update()` when `check()` reports `hasUpdate`; does not call it otherwise; swallows `BadRequestException`. The 4 `@Scheduled` methods delegate to `executor.scheduleWithJitter(any)`.
- Existing `Index115ServiceTest` / `SubscriptionServiceTest` / `IndexServiceTest` / `DoubanServiceTest` are unchanged (no constructor signatures changed).

---

## Refinement note (2026-07-22)

The originally-approved structure injected `AutoUpdateExecutor` into each of the 4 services and added/modified `@Scheduled` methods inline. Exploration found all 4 services use **explicit constructors** (not Lombok), so that would have edited 4 multi-line constructors and forced mock additions in existing tests. Centralizing the 4 triggers in one new `AutoUpdateService` achieves identical behavior with **zero constructor edits and zero existing-test edits** — only two `@Scheduled` annotations are removed from `IndexService`/`DoubanService` (relocated into `AutoUpdateService`, now with jitter). Net behavior is unchanged from the approved design.
