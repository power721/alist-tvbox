# Auto-Update Data Sources Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the 3 local packages, index data, Douban data, and 115 share index auto-check + auto-download each evening with a 0–30 min random jitter.

**Architecture:** Add `AutoUpdateExecutor` (a `ScheduledExecutorService` wrapper that defers work by a random 0–29 min) and `AutoUpdateService` (4 evening `@Scheduled` triggers that delegate to existing service methods via the executor). Remove the 2 existing `@Scheduled` annotations from `IndexService.update()` / `DoubanService.update()` so their triggers live in `AutoUpdateService` (now jittered). No existing constructor or test changes.

**Tech Stack:** Java 21, Spring Boot 4 (`@Scheduled`, `@Component`, `@Service`, `jakarta.annotation.PreDestroy`), JUnit 5, Mockito (`MockitoExtension`, `@Mock`, `@InjectMocks`).

## Global Constraints

- Jitter = `ThreadLocalRandom.current().nextLong(0, 30)` minutes → range **0..29**, re-rolled each run.
- Evening crons (server default TZ, same as existing jobs): 本地包 `0 0 20 * * ?`, 索引 `0 0 22 * * ?`, 豆瓣 `0 0 20,22 * * ?` (keep 2×/day), 115 `0 0 23 * * ?`.
- **Never** `Thread.sleep` inside a `@Scheduled` method (Spring scheduler pool = 1). Always defer via `AutoUpdateExecutor`.
- Do **not** lift the `xiaoya` gate — index/douban self-gate inside `getRemoteVersion()`.
- Do **not** edit any existing service constructor or existing test.
- 4-space indent, Lombok `@Slf4j`, match surrounding style.

---

### Task 1: `AutoUpdateExecutor` (jittered-delay helper)

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/service/AutoUpdateExecutor.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/AutoUpdateExecutorTest.java`

**Interfaces:**
- Produces: `AutoUpdateExecutor()` default constructor (Spring); package-private `AutoUpdateExecutor(ScheduledExecutorService)` for tests; `void scheduleWithJitter(Runnable task)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/cn/har01d/alist_tvbox/service/AutoUpdateExecutorTest.java`:

```java
package cn.har01d.alist_tvbox.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.*;

class AutoUpdateExecutorTest {

    @Test
    void scheduleWithJitterDefersByZeroToTwentyNineMinutes() {
        ScheduledExecutorService pool = mock(ScheduledExecutorService.class);
        AutoUpdateExecutor executor = new AutoUpdateExecutor(pool);

        executor.scheduleWithJitter(() -> {});

        verify(pool).schedule(any(Runnable.class), longThat(m -> m >= 0 && m < 30), eq(TimeUnit.MINUTES));
    }

    @Test
    void wrapsTaskSoExceptionsAreSwallowed() {
        ScheduledExecutorService pool = mock(ScheduledExecutorService.class);
        AutoUpdateExecutor executor = new AutoUpdateExecutor(pool);

        executor.scheduleWithJitter(() -> { throw new RuntimeException("boom"); });

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(pool).schedule(captor.capture(), anyLong(), eq(TimeUnit.MINUTES));
        assertDoesNotThrow(() -> captor.getValue().run());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=AutoUpdateExecutorTest test`
Expected: compile error — `AutoUpdateExecutor` does not exist.

- [ ] **Step 3: Write the implementation**

`src/main/java/cn/har01d/alist_tvbox/service/AutoUpdateExecutor.java`:

```java
package cn.har01d.alist_tvbox.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class AutoUpdateExecutor {
    private final ScheduledExecutorService executor;

    public AutoUpdateExecutor() {
        this(Executors.newScheduledThreadPool(2));
    }

    AutoUpdateExecutor(ScheduledExecutorService executor) {
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

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=AutoUpdateExecutorTest test`
Expected: `Tests run: 2, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/AutoUpdateExecutor.java \
        src/test/java/cn/har01d/alist_tvbox/service/AutoUpdateExecutorTest.java
git commit -m "feat: add AutoUpdateExecutor for jittered task scheduling"
```

---

### Task 2: `AutoUpdateService` + relocate index/douban triggers

**Files:**
- Create: `src/main/java/cn/har01d/alist_tvbox/service/AutoUpdateService.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/AutoUpdateServiceTest.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/IndexService.java` (line 195 — remove `@Scheduled`)
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/DoubanService.java` (line 215 — remove `@Scheduled`)

**Interfaces:**
- Consumes: `AutoUpdateExecutor.scheduleWithJitter(Runnable)` (Task 1); existing public methods `SubscriptionService.syncCat()`, `IndexService.update()`, `DoubanService.update()`, `Index115Service.check()`, `Index115Service.update()`.
- Produces: 4 `@Scheduled` triggers on `AutoUpdateService`; package-private `update115()`.

- [ ] **Step 1: Write the failing test**

`src/test/java/cn/har01d/alist_tvbox/service/AutoUpdateServiceTest.java`:

```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.Index115CheckResult;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutoUpdateServiceTest {
    @Mock AutoUpdateExecutor executor;
    @Mock SubscriptionService subscriptionService;
    @Mock IndexService indexService;
    @Mock DoubanService doubanService;
    @Mock Index115Service index115Service;
    @InjectMocks AutoUpdateService service;

    @Test
    void autoSyncCatDelegatesWithJitter() {
        service.autoSyncCat();
        verify(executor).scheduleWithJitter(any(Runnable.class));
    }

    @Test
    void autoIndexDelegatesWithJitter() {
        service.autoIndex();
        verify(executor).scheduleWithJitter(any(Runnable.class));
    }

    @Test
    void autoDoubanDelegatesWithJitter() {
        service.autoDouban();
        verify(executor).scheduleWithJitter(any(Runnable.class));
    }

    @Test
    void update115RunsUpdateWhenHasUpdate() {
        when(index115Service.check()).thenReturn(new Index115CheckResult(true, true, "a", "b", null));

        service.update115();

        verify(index115Service).update();
    }

    @Test
    void update115SkipsWhenNoUpdate() {
        when(index115Service.check()).thenReturn(new Index115CheckResult(true, false, "a", "a", null));

        service.update115();

        verify(index115Service, never()).update();
    }

    @Test
    void update115SwallowsBadRequest() {
        when(index115Service.check()).thenReturn(new Index115CheckResult(true, true, "a", "b", null));
        doThrow(new BadRequestException("running")).when(index115Service).update();

        assertDoesNotThrow(() -> service.update115());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=AutoUpdateServiceTest test`
Expected: compile error — `AutoUpdateService` does not exist.

- [ ] **Step 3: Write `AutoUpdateService`**

`src/main/java/cn/har01d/alist_tvbox/service/AutoUpdateService.java`:

```java
package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.Index115CheckResult;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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

    @Scheduled(cron = "0 0 20 * * ?")
    public void autoSyncCat() {
        executor.scheduleWithJitter(subscriptionService::syncCat);
    }

    @Scheduled(cron = "0 0 22 * * ?")
    public void autoIndex() {
        executor.scheduleWithJitter(indexService::update);
    }

    @Scheduled(cron = "0 0 20,22 * * ?")
    public void autoDouban() {
        executor.scheduleWithJitter(doubanService::update);
    }

    @Scheduled(cron = "0 0 23 * * ?")
    public void autoUpdate115() {
        executor.scheduleWithJitter(this::update115);
    }

    void update115() {
        Index115CheckResult result = index115Service.check();
        if (result.hasUpdate()) {
            try {
                index115Service.update();
            } catch (BadRequestException e) {
                log.debug("index115 auto-update skipped: {}", e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 4: Remove the relocated `@Scheduled` annotations**

In `src/main/java/cn/har01d/alist_tvbox/service/IndexService.java`, delete the annotation line above `update()` (currently line 195) so it becomes:

```java
    public void update() {
        getRemoteVersion();
    }
```

In `src/main/java/cn/har01d/alist_tvbox/service/DoubanService.java`, delete the annotation line above `update()` (currently line 215) so it becomes:

```java
    public void update() {
        getRemoteVersion(new Versions());
    }
```

Leave both method bodies untouched. If the `org.springframework.scheduling.annotation.Scheduled` import becomes unused in either file, remove it.

- [ ] **Step 5: Run the new test to verify it passes**

Run: `mvn -q -Dtest=AutoUpdateServiceTest test`
Expected: `Tests run: 6, Failures: 0`.

- [ ] **Step 6: Run the affected existing tests to confirm no regression**

Run: `mvn -q -Dtest=Index115ServiceTest,DoubanServiceTest,IndexServiceTest,SubscriptionServiceTest test`
Expected: all pass (no constructor/test changes were made to those services).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/AutoUpdateService.java \
        src/test/java/cn/har01d/alist_tvbox/service/AutoUpdateServiceTest.java \
        src/main/java/cn/har01d/alist_tvbox/service/IndexService.java \
        src/main/java/cn/har01d/alist_tvbox/service/DoubanService.java
git commit -m "feat: auto check+download packages/index/douban/115 with nightly jitter"
```

---

### Task 3: Full build, verify scheduling wiring

**Files:** none (verification only).

- [ ] **Step 1: Confirm exactly 4 evening triggers, no leftover double-scheduling**

Run:
```bash
grep -rn "@Scheduled(cron" src/main/java/cn/har01d/alist_tvbox/service/AutoUpdateService.java | wc -l
grep -n "@Scheduled" src/main/java/cn/har01d/alist_tvbox/service/IndexService.java src/main/java/cn/har01d/alist_tvbox/service/DoubanService.java
```
Expected: first command prints `4`; second prints nothing (index/douban no longer carry `@Scheduled`).

- [ ] **Step 2: Compile + run the full test suite**

Run: `mvn -q clean test`
Expected: BUILD SUCCESS, no test failures.

- [ ] **Step 3: Confirm the new beans load in the Spring context**

Run: `mvn -q -Dtest=IndexServiceTest test`
Expected: PASS (this is a `@SpringBootTest` context-load test; `AutoUpdateExecutor` + `AutoUpdateService` must wire without errors).

- [ ] **Step 4: Manual smoke (optional, requires a running app)**

- Open the config page → 索引/豆瓣/115 cards still show local/remote versions as before.
- `POST /api/cat/sync` and `POST /api/index115/update` still work immediately (manual fallback unchanged).
- At 20:00/22:00/23:00 server time, logs show `auto-update task` activity only when a remote version differs from local.

- [ ] **Step 5: Commit (docs only, if the spec/plan were not already committed)**

The spec and plan live under `docs/superpowers/`. If they are uncommitted, stage and commit them with the feature or in a docs commit per the repo's branch/PR workflow.
