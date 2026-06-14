# AList Share Import Batch Insert Design

**Date:** 2026-04-30

**Goal:** Reduce SQL round-trips during `ShareService#loadAListShares` imports by batching AList storage persistence while preserving per-share fault tolerance.

## Scope

This change applies only to the share import path rooted at `cn.har01d.alist_tvbox.service.ShareService#loadAListShares`.

Out of scope:
- Changing other callers of `AListLocalService#saveStorage`
- Refactoring unrelated storage creation logic
- Changing share parsing, ID allocation, or PikPak index update semantics

## Current Behavior

`loadAListShares` iterates imported shares and calls `saveStorage(share, false)` for each item.

`saveStorage` currently:
- Builds a `Storage` implementation from the share type
- Sets the disabled flag
- Persists immediately through `AListLocalService#saveStorage`

`AListLocalService#saveStorage` currently:
- Deletes by storage ID
- Inserts the new row into `x_storages`
- Executes both statements per storage

This causes import performance to degrade linearly with the number of imported shares because every successful share performs its own SQL write.

## Requirements

### Functional

- Keep the existing `loadAListShares` share loop semantics.
- Preserve per-share fault tolerance:
  - If one share fails to parse into a `Storage` or fails to persist, other shares must still be imported.
- Preserve existing side effects inside `loadAListShares`:
  - PikPak detection for post-import index refresh
  - `shareId` offset bookkeeping
  - Normalization of `share.type == null` to `0` with repository save
- Preserve the final `pikPakService.updateIndexFile()` call behavior.

### Performance

- Reduce the number of database round-trips for successful imported storages by using batch persistence.
- Avoid unbounded accumulation in memory during large imports by flushing in chunks instead of waiting for the full list.

## Design

### ShareService Changes

`loadAListShares` will stop persisting storage rows one-by-one.

Instead it will:
- Build `Storage` objects per share using a method that only maps share data to a storage instance
- Continue handling per-share exceptions in the existing inner `try/catch`
- Accumulate successful storages in a local batch
- Flush the batch to `AListLocalService` once it reaches a fixed chunk size
- Flush any remainder after the loop completes

The storage-construction step and the storage-persistence step will be separated so import-time batching only affects this one path.

### AListLocalService Changes

Add a new batch-oriented persistence method dedicated to this use case, for example `saveStorages(List<Storage> storages)`.

Behavior:
- No-op for empty input
- Delete existing `x_storages` rows by ID for the incoming batch
- Insert the new storage rows using JDBC batch updates
- If batch execution fails, fall back to single-item persistence so a bad record does not fail the whole chunk

This preserves the caller's per-item tolerance while still giving the fast path to the common case.

### Batch Failure Handling

Failure handling must stay local to the chunk:
- A batch failure must not abort `loadAListShares`
- During fallback, one bad storage may fail, but subsequent storages in the same chunk must still be attempted
- Logging should identify batch fallback and preserve existing warning visibility for individual failures

## Data Flow

1. `loadAListShares` receives imported shares.
2. Each share is parsed into a `Storage` instance when possible.
3. Existing share-side effects are applied immediately in the loop.
4. Successful storages are appended to the current batch.
5. When batch size is reached, `AListLocalService#saveStorages` persists that chunk.
6. If the batch write fails, `AListLocalService` retries storage-by-storage.
7. After all shares are processed, the last partial batch is flushed.
8. If any successful storage used `PikPakShare`, refresh the PikPak index once.

## Error Handling

- Storage construction failures remain handled at the share level in `ShareService`.
- Batch SQL failures are handled inside `AListLocalService`.
- Single-record SQL failures during fallback are logged and skipped.
- The outer import method should continue to protect the whole import from unexpected exceptions.

## Testing

### ShareService

Add tests covering:
- Multiple import shares resulting in a single batch persistence call
- Shares that fail storage construction are skipped without blocking later shares
- Null share type still gets normalized and persisted through `shareRepository.save`
- PikPak share detection still triggers exactly one index refresh after import

### AListLocalService

Add tests covering:
- Empty batch is ignored
- Successful batch path issues batched delete/insert behavior
- Batch failure falls back to per-item persistence
- Fallback continues past a failing item instead of aborting the rest

## Acceptance Criteria

- Importing shares through `loadAListShares` no longer calls the single-record storage persistence path for every share.
- Other services that use `saveStorage` continue to behave exactly as before.
- A single bad share or bad storage row does not abort the rest of the import.
- Existing tests remain green and new targeted tests pass.
