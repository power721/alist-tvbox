# Offline Download Task Index Plan

**Goal:** Add a database-backed index for 115 offline download tasks so repeated URLs reuse existing tasks/results instead of re-submitting and consuming quota again.

**Architecture:** Keep the current `OfflineDownloadService` as the single orchestration point. Add a small JPA entity/repository for local task indexing. On each request, resolve by local index first; if not found, query 115 task list with `page_size=1000`; only when the add-task API reports duplicate and page 1 misses do a page 2 fallback.

**Files:**
- Add: `src/main/java/cn/har01d/alist_tvbox/entity/OfflineDownloadTask.java`
- Add: `src/main/java/cn/har01d/alist_tvbox/entity/OfflineDownloadTaskRepository.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/OfflineDownloadService.java`
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/OfflineDownloadServiceTest.java`

**Task 1: Add failing tests for local-index reuse**
- Add a test that a completed local record returns `targetPath` without calling 115 APIs.
- Add a test that duplicate-add-task response triggers a `page=1&page_size=1000` task-list lookup and reuses the existing remote task.
- Add a test that page 1 miss plus duplicate-add-task response triggers a page 2 lookup.
- Add a test that successful completion persists a local record and does not delete the remote task.

**Task 2: Add persistence model**
- Create a small entity with fields:
  - `id`
  - `accountId`
  - `urlHash`
  - `infoHash`
  - `targetPath`
  - `taskName`
  - `status`
  - `createdTime`
  - `updatedTime`
- Add repository methods for:
  - `findFirstByAccountIdAndUrlHashOrderByUpdatedTimeDesc`
  - `findFirstByAccountIdAndInfoHashOrderByUpdatedTimeDesc`

**Task 3: Refactor OfflineDownloadService**
- Inject the new repository.
- Normalize/hash URL once per request.
- Before calling 115, try local lookup and return completed path immediately.
- Replace the fixed `task_lists` URL with page-aware builders using `page_size=1000`.
- On duplicate-add-task response:
  - query remote page 1
  - if not found, query remote page 2
  - if found, reuse that task
- On successful completion:
  - build `targetPath`
  - persist/update local record
  - do not call `task_del`

**Task 4: Verify**
- Run: `./mvnw -Dtest=OfflineDownloadServiceTest test`
- If green, optionally run: `./mvnw -Dtest=ShareLinkTest,OfflineDownloadServiceTest test`
