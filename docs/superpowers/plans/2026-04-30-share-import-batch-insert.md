# Share Import Batch Insert Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Batch AList storage persistence only for `ShareService#loadAListShares` so large share imports perform fewer SQL round-trips without losing per-share fault tolerance.

**Architecture:** Split storage construction from storage persistence inside `ShareService`, accumulate successful `Storage` objects in a bounded batch, and flush them through a new `AListLocalService#saveStorages(List<Storage>)` method. Keep the existing single-item `saveStorage(Storage)` path untouched for all other callers, and make the batch method fall back to single-item saves when JDBC batch work fails.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, Spring `ReflectionTestUtils`, JDBC `JdbcTemplate`

---

### Task 1: Lock In `ShareService` Import Behavior With Failing Tests

**Files:**
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/ShareServiceTest.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/ShareServiceTest.java`

- [ ] **Step 1: Write the failing test for batched persistence and side effects**

```java
import cn.har01d.alist_tvbox.entity.Share;
import org.springframework.test.util.ReflectionTestUtils;

@Test
void loadAListShares_batchesStorages_and_updates_side_effects() {
    Share ali = new Share();
    ali.setId(20001);
    ali.setType(0);
    ali.setShareId("ali-share");
    ali.setPath("/ali");

    Share pikpak = new Share();
    pikpak.setId(20002);
    pikpak.setType(1);
    pikpak.setShareId("pikpak-share");
    pikpak.setPath("/pikpak");

    Share nullType = new Share();
    nullType.setId(99899);
    nullType.setType(null);
    nullType.setShareId("legacy-share");
    nullType.setPath("/legacy");

    ReflectionTestUtils.invokeMethod(shareService, "loadAListShares", java.util.List.of(ali, pikpak, nullType));

    ArgumentCaptor<java.util.List<cn.har01d.alist_tvbox.storage.Storage>> captor = ArgumentCaptor.forClass(java.util.List.class);
    verify(aListLocalService).saveStorages(captor.capture());
    assertEquals(3, captor.getValue().size());
assertEquals(0, nullType.getType());
verify(shareRepository).save(nullType);
verify(pikPakService).updateIndexFile();
verify(aListLocalService, never()).saveStorage(any());
}
```

- [ ] **Step 2: Add the failing test for per-share tolerance**

```java
@Test
void loadAListShares_skips_invalid_share_and_keeps_later_items() {
    Share invalid = new Share();
    invalid.setId(20003);
    invalid.setType(99);
    invalid.setShareId("bad");
    invalid.setPath("/bad");

    Share valid = new Share();
    valid.setId(20004);
    valid.setType(0);
    valid.setShareId("good");
    valid.setPath("/good");

    ReflectionTestUtils.invokeMethod(shareService, "loadAListShares", java.util.List.of(invalid, valid));

    ArgumentCaptor<java.util.List<cn.har01d.alist_tvbox.storage.Storage>> captor = ArgumentCaptor.forClass(java.util.List.class);
    verify(aListLocalService).saveStorages(captor.capture());
    assertEquals(1, captor.getValue().size());
}
```

- [ ] **Step 3: Run the targeted `ShareService` tests to verify they fail for the right reason**

Run: `./mvnw -Dtest=ShareServiceTest test`

Expected: FAIL because `AListLocalService#saveStorages(...)` does not exist yet and `loadAListShares` still calls `saveStorage(...)` per item.

- [ ] **Step 4: Commit the red tests**

```bash
git add src/test/java/cn/har01d/alist_tvbox/service/ShareServiceTest.java
git commit -m "test: cover batched share import persistence"
```

### Task 2: Lock In Batch Persistence and Fallback Behavior With Failing Tests

**Files:**
- Create: `src/test/java/cn/har01d/alist_tvbox/service/AListLocalServiceTest.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/AListLocalServiceTest.java`

- [ ] **Step 1: Write the new batch success test**

```java
@ExtendWith(MockitoExtension.class)
class AListLocalServiceTest {
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private AppProperties appProperties;
    @Mock
    private RestTemplateBuilder builder;
    @Mock
    private Environment environment;
    @Mock
    private JdbcTemplate alistJdbcTemplate;
    @Mock
    private RestTemplate restTemplate;

    private AListLocalService service;

    @BeforeEach
    void setUp() {
        when(builder.rootUri(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(restTemplate);
        when(environment.matchesProfiles(any(String[].class))).thenReturn(false);
        when(environment.getProperty(eq("ALIST_PORT"), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        service = spy(new AListLocalService(settingRepository, siteRepository, appProperties, builder, environment, new ObjectMapper(), alistJdbcTemplate));
    }

    @Test
    void saveStorages_uses_batch_update_for_non_empty_list() {
        Storage first = new AliyunShare(share(20001, 0, "/a", "sid-a"));
        Storage second = new AliyunShare(share(20002, 0, "/b", "sid-b"));

        service.saveStorages(java.util.List.of(first, second));

        verify(alistJdbcTemplate, times(2)).batchUpdate(anyString(), any(org.springframework.jdbc.core.BatchPreparedStatementSetter.class));
        verify(service, never()).saveStorage(any());
    }
}
```

- [ ] **Step 2: Add the test helper used by the new tests**

```java
private Share share(int id, int type, String path, String shareId) {
    Share share = new Share();
    share.setId(id);
    share.setType(type);
    share.setPath(path);
    share.setShareId(shareId);
    return share;
}
```

- [ ] **Step 3: Add the failing fallback test**

```java
@Test
void saveStorages_falls_back_to_single_saves_when_batch_insert_fails() {
    Storage first = new AliyunShare(share(20001, 0, "/a", "sid-a"));
    Storage second = new AliyunShare(share(20002, 0, "/b", "sid-b"));

    doThrow(new RuntimeException("batch insert failed"))
            .when(alistJdbcTemplate)
            .batchUpdate(contains("INSERT INTO x_storages"), any(org.springframework.jdbc.core.BatchPreparedStatementSetter.class));

    service.saveStorages(java.util.List.of(first, second));

    verify(service, times(2)).saveStorage(any());
}
```

- [ ] **Step 4: Add the failing empty-input test**

```java
@Test
void saveStorages_ignores_empty_input() {
    service.saveStorages(java.util.Collections.emptyList());

    verifyNoInteractions(alistJdbcTemplate);
}
```

- [ ] **Step 5: Run the new `AListLocalService` tests to verify they fail**

Run: `./mvnw -Dtest=AListLocalServiceTest test`

Expected: FAIL because `saveStorages(...)` does not exist yet.

- [ ] **Step 6: Commit the red tests**

```bash
git add src/test/java/cn/har01d/alist_tvbox/service/AListLocalServiceTest.java
git commit -m "test: cover alist storage batch persistence"
```

### Task 3: Implement Batched Persistence in `AListLocalService`

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/AListLocalService.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/AListLocalServiceTest.java`

- [ ] **Step 1: Add reusable SQL builders for single-row insert values**

```java
private String storageModifiedTime(Storage storage) {
    return storage.getTime()
            .truncatedTo(ChronoUnit.SECONDS)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .toString();
}

private static final String INSERT_STORAGE_SQL = "INSERT INTO x_storages " +
        "(id,mount_path,`order`,driver,cache_expiration,status,addition,modified,disabled,order_by,order_direction,extract_folder,web_proxy,webdav_policy) " +
        "VALUES (?,?,?,?,?,'work',?,?,?,'name','asc','front',?,?)";
```

- [ ] **Step 2: Add the new batch persistence method**

```java
public void saveStorages(List<Storage> storages) {
    if (storages == null || storages.isEmpty()) {
        return;
    }

    try {
        batchDeleteStorages(storages);
        batchInsertStorages(storages);
        log.info("batch insert {} storages", storages.size());
    } catch (Exception e) {
        log.warn("batch save storages failed, falling back to single-row persistence", e);
        for (Storage storage : storages) {
            try {
                saveStorage(storage);
            } catch (Exception ex) {
                log.warn("save storage failed: {}", storage.getPath(), ex);
            }
        }
    }
}
```

- [ ] **Step 3: Add the JDBC batch helpers used by `saveStorages`**

```java
private void batchDeleteStorages(List<Storage> storages) {
    alistJdbcTemplate.batchUpdate(
            "DELETE FROM x_storages WHERE id = ?",
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    ps.setInt(1, storages.get(i).getId());
                }

                @Override
                public int getBatchSize() {
                    return storages.size();
                }
            });
}

private void batchInsertStorages(List<Storage> storages) {
    alistJdbcTemplate.batchUpdate(
            INSERT_STORAGE_SQL,
            new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    Storage storage = storages.get(i);
                    ps.setInt(1, storage.getId());
                    ps.setString(2, storage.getPath());
                    ps.setString(3, storage.getDriver());
                    ps.setInt(4, storage.getCacheExpiration());
                    ps.setString(5, storage.getAddition());
                    ps.setString(6, storageModifiedTime(storage));
                    ps.setInt(7, storage.isDisabled() ? 1 : 0);
                    ps.setInt(8, storage.isWebProxy() ? 1 : 0);
                    ps.setString(9, storage.getWebdavPolicy());
                }

                @Override
                public int getBatchSize() {
                    return storages.size();
                }
            });
}
```

- [ ] **Step 4: Keep the existing single-item path intact while deduplicating shared formatting**

```java
public void saveStorage(Storage storage) {
    executeUpdate("DELETE FROM x_storages WHERE id = " + storage.getId());
    String sql = "INSERT INTO x_storages " +
            "(id,mount_path,`order`,driver,cache_expiration,status,addition,modified,disabled,order_by,order_direction,extract_folder,web_proxy,webdav_policy) " +
            "VALUES (%d,'%s',0,'%s',%d,'work','%s','%s',%d,'name','asc','front',%d,'%s');";
    executeUpdate(String.format(sql, storage.getId(), storage.getPath(), storage.getDriver(),
            storage.getCacheExpiration(), storage.getAddition(), storageModifiedTime(storage),
            storage.isDisabled() ? 1 : 0, storage.isWebProxy() ? 1 : 0, storage.getWebdavPolicy()));
}
```

- [ ] **Step 5: Run the `AListLocalService` tests and make them pass**

Run: `./mvnw -Dtest=AListLocalServiceTest test`

Expected: PASS

- [ ] **Step 6: Commit the batch persistence implementation**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/AListLocalService.java src/test/java/cn/har01d/alist_tvbox/service/AListLocalServiceTest.java
git commit -m "feat: batch alist storage persistence"
```

### Task 4: Switch `loadAListShares` to the Batched Path

**Files:**
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/ShareService.java`
- Modify: `src/test/java/cn/har01d/alist_tvbox/service/ShareServiceTest.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/ShareServiceTest.java`

- [ ] **Step 1: Add a storage-construction helper that does not persist**

```java
private Storage createStorage(Share share, boolean disabled) {
    Storage storage = null;
    if (share.getType() == null || share.getType() == 0) {
        storage = new AliyunShare(share);
    } else if (share.getType() == 1) {
        storage = new PikPakShare(share);
    } else if (share.getType() == 8) {
        storage = new Pan115Share(share);
    } else if (share.getType() == 4) {
        storage = new Local(share);
    } else if (share.getType() == 5) {
        storage = new QuarkShare(share);
    } else if (share.getType() == 7) {
        storage = new UCShare(share);
    } else if (share.getType() == 9) {
        storage = new Pan189Share(share);
    } else if (share.getType() == 2) {
        storage = new ThunderShare(share);
    } else if (share.getType() == 3) {
        storage = new Pan123Share(share);
    } else if (share.getType() == 6) {
        storage = new Pan139Share(share);
    } else if (share.getType() == 10) {
        storage = new BaiduShare(share);
    } else if (share.getType() == 11) {
        storage = new StrmStorage(share);
    }

    if (storage != null) {
        storage.setDisabled(disabled);
    }
    return storage;
}
```

- [ ] **Step 2: Rewire `saveStorage(Share, boolean)` to preserve existing callers**

```java
private Storage saveStorage(Share share, boolean disabled) {
    Storage storage = createStorage(share, disabled);
    if (storage != null) {
        aListLocalService.saveStorage(storage);
    }
    return storage;
}
```

- [ ] **Step 3: Update `loadAListShares` to batch and flush in chunks**

```java
private static final int IMPORT_STORAGE_BATCH_SIZE = 100;

private void loadAListShares(List<Share> list) {
    if (list.isEmpty()) {
        return;
    }

    boolean pikpak = false;
    List<Storage> storages = new ArrayList<>();
    try {
        for (Share share : list) {
            try {
                Storage storage = createStorage(share, false);
                if (storage != null) {
                    storages.add(storage);
                    if ("PikPakShare".equals(storage.getDriver())) {
                        pikpak = true;
                    }
                    if (storages.size() >= IMPORT_STORAGE_BATCH_SIZE) {
                        aListLocalService.saveStorages(storages);
                        storages.clear();
                    }
                }
                if (share.getId() < offset) {
                    shareId = Math.max(shareId, share.getId() + 1);
                }
                if (share.getType() == null) {
                    share.setType(0);
                    shareRepository.save(share);
                }
            } catch (Exception e) {
                log.warn("{}", e.getMessage());
            }
        }
        if (!storages.isEmpty()) {
            aListLocalService.saveStorages(storages);
        }
    } catch (Exception e) {
        log.warn("", e);
    }

    if (pikpak) {
        pikPakService.updateIndexFile();
    }
}
```

- [ ] **Step 4: Run the `ShareService` tests and make them pass**

Run: `./mvnw -Dtest=ShareServiceTest test`

Expected: PASS

- [ ] **Step 5: Run the focused regression suite**

Run: `./mvnw -Dtest=ShareServiceTest,AListLocalServiceTest test`

Expected: PASS

- [ ] **Step 6: Commit the share import batching change**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/ShareService.java src/test/java/cn/har01d/alist_tvbox/service/ShareServiceTest.java
git commit -m "feat: batch storage inserts during share import"
```
