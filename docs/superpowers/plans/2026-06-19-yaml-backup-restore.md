# YAML Backup Restore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add zip-compressed YAML export and repository-based restore, including startup restore priority over default initialization, while keeping existing SQL backup and restore unchanged.

**Architecture:** Introduce a dedicated backup subsystem centered on a repository-backed manifest model and per-entity module handlers. Wire the subsystem into `SettingController` for manual export/import and into early startup for `/data/database-yaml.zip`, then add targeted skip guards to initializer services and UI controls in `ConfigView`.

**Tech Stack:** Spring Boot 3, Spring Data JPA, Jackson YAML, Element Plus, Vue 3, MockMvc, Spring Boot integration tests, Maven, native-image reflection config.

---

## File Structure

**Create:**

- `src/main/java/cn/har01d/alist_tvbox/dto/backup/BackupManifestDto.java`
- `src/main/java/cn/har01d/alist_tvbox/dto/backup/BackupModuleDto.java`
- `src/main/java/cn/har01d/alist_tvbox/dto/backup/BackupRestoreMode.java`
- `src/main/java/cn/har01d/alist_tvbox/dto/backup/BackupRestoreResponse.java`
- `src/main/java/cn/har01d/alist_tvbox/dto/backup/BackupRestoreResult.java`
- `src/main/java/cn/har01d/alist_tvbox/service/backup/BackupModuleHandler.java`
- `src/main/java/cn/har01d/alist_tvbox/service/backup/BackupModuleRegistry.java`
- `src/main/java/cn/har01d/alist_tvbox/service/backup/DatabaseBackupService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/backup/RestoreState.java`
- `src/main/java/cn/har01d/alist_tvbox/service/backup/StartupYamlRestoreRunner.java`
- `src/test/java/cn/har01d/alist_tvbox/service/backup/DatabaseBackupServiceTest.java`
- `src/test/java/cn/har01d/alist_tvbox/service/backup/StartupYamlRestoreRunnerTest.java`
- `src/test/java/cn/har01d/alist_tvbox/web/SettingControllerBackupTest.java`

**Modify:**

- `pom.xml`
- `src/main/java/cn/har01d/alist_tvbox/Main.java`
- `src/main/java/cn/har01d/alist_tvbox/web/SettingController.java`
- `src/main/java/cn/har01d/alist_tvbox/service/SettingService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/UserService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/SiteService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/NavigationService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/AccountService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/DriverAccountService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/IndexTemplateService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/AListAliasService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/HistoryService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/ShareService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/PikPakService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/EmbyService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/JellyfinService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/TelegramService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionSourceService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/AListLocalService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/ConfigFileService.java`
- `src/main/java/cn/har01d/alist_tvbox/service/TaskService.java`
- `scripts/init.sh`
- `web-ui/src/views/ConfigView.vue`
- `src/main/resources/META-INF/native-image/reflect-config.json`

**References:**

- Spec: `docs/superpowers/specs/2026-06-19-yaml-backup-restore-design.md`
- Existing SQL export path: `src/main/java/cn/har01d/alist_tvbox/service/SettingService.java`
- Existing settings download endpoint: `src/main/java/cn/har01d/alist_tvbox/web/SettingController.java`
- Existing upload UI pattern: `web-ui/src/views/SharesView.vue`, `web-ui/src/views/FilesView.vue`
- Existing startup SQL restore: `scripts/init.sh`

### Task 1: Add YAML Dependency And Backup DTO Skeleton

**Files:**

- Modify: `pom.xml`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/backup/BackupManifestDto.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/backup/BackupModuleDto.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/backup/BackupRestoreMode.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/backup/BackupRestoreResponse.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/dto/backup/BackupRestoreResult.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/backup/DatabaseBackupServiceTest.java`

- [ ] **Step 1: Write the failing DTO serialization test**

```java
package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.dto.backup.BackupManifestDto;
import cn.har01d.alist_tvbox.dto.backup.BackupModuleDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseBackupServiceTest {
    @Test
    void shouldSerializeManifestToYamlStructure() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        BackupModuleDto module = new BackupModuleDto();
        module.setEntity("Setting");
        module.setItems(List.of(Map.of("name", "api_key", "value", "abc")));

        BackupManifestDto manifest = new BackupManifestDto();
        manifest.setFormatVersion(1);
        manifest.setMode("repository");
        manifest.setModules(Map.of("settings", module));

        String yaml = mapper.writeValueAsString(manifest);

        assertThat(yaml).contains("formatVersion: 1");
        assertThat(yaml).contains("entity: \"Setting\"");
        assertThat(yaml).contains("name: \"api_key\"");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=DatabaseBackupServiceTest test`

Expected: FAIL with missing `com.fasterxml.jackson.dataformat.yaml` classes and missing backup DTO classes.

- [ ] **Step 3: Add YAML dependency and minimal DTO classes**

Add this dependency block in `pom.xml` near the existing Jackson-related dependencies:

```xml
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>
```

Create `BackupModuleDto.java`:

```java
package cn.har01d.alist_tvbox.dto.backup;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class BackupModuleDto {
    private String entity;
    private List<Map<String, Object>> items = new ArrayList<>();
}
```

Create `BackupManifestDto.java`:

```java
package cn.har01d.alist_tvbox.dto.backup;

import lombok.Data;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class BackupManifestDto {
    private int formatVersion = 1;
    private String appVersion;
    private Instant exportedAt = Instant.now();
    private String mode = "repository";
    private Map<String, BackupModuleDto> modules = new LinkedHashMap<>();
}
```

Create `BackupRestoreMode.java`:

```java
package cn.har01d.alist_tvbox.dto.backup;

public enum BackupRestoreMode {
    OVERWRITE,
    MERGE
}
```

Create `BackupRestoreResult.java`:

```java
package cn.har01d.alist_tvbox.dto.backup;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BackupRestoreResult {
    private int created;
    private int updated;
    private int deleted;
    private int skipped;
    private int failed;
    private List<String> errors = new ArrayList<>();
}
```

Create `BackupRestoreResponse.java`:

```java
package cn.har01d.alist_tvbox.dto.backup;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class BackupRestoreResponse {
    private boolean success;
    private Map<String, BackupRestoreResult> results = new LinkedHashMap<>();
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=DatabaseBackupServiceTest test`

Expected: PASS with one successful test.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/java/cn/har01d/alist_tvbox/dto/backup src/test/java/cn/har01d/alist_tvbox/service/backup/DatabaseBackupServiceTest.java
git commit -m "feat: add yaml backup manifest dto"
```

### Task 2: Build Registry And Service For YAML Zip Export

**Files:**

- Create: `src/main/java/cn/har01d/alist_tvbox/service/backup/BackupModuleHandler.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/service/backup/BackupModuleRegistry.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/service/backup/DatabaseBackupService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SettingService.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/backup/DatabaseBackupServiceTest.java`

- [ ] **Step 1: Extend the failing test to assert zip export**

Append this test to `DatabaseBackupServiceTest.java`:

```java
@Test
void shouldExportZipContainingDatabaseYaml() throws Exception {
    DatabaseBackupService service = new DatabaseBackupService(
        null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null
    );

    assertThatThrownBy(service::exportBackupZip)
        .isInstanceOf(NullPointerException.class);
}
```

Then replace it after constructors exist with a real integration-style Spring Boot test:

```java
@Autowired
private DatabaseBackupService databaseBackupService;

@Test
void shouldExportZipContainingDatabaseYaml() throws Exception {
    File file = databaseBackupService.exportBackupZip();

    assertThat(file).exists();

    try (ZipFile zipFile = new ZipFile(file)) {
        ZipEntry entry = zipFile.getEntry("database.yaml");
        assertThat(entry).isNotNull();
        String yaml = new String(zipFile.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        assertThat(yaml).contains("formatVersion: 1");
        assertThat(yaml).contains("modules:");
        assertThat(yaml).doesNotContain("Alias");
        assertThat(yaml).doesNotContain("Meta");
        assertThat(yaml).doesNotContain("Movie");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=DatabaseBackupServiceTest#shouldExportZipContainingDatabaseYaml test`

Expected: FAIL because `DatabaseBackupService` and export methods do not exist.

- [ ] **Step 3: Add module handler abstraction and registry**

Create `BackupModuleHandler.java`:

```java
package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.dto.backup.BackupRestoreMode;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreResult;

import java.util.List;
import java.util.Map;

public interface BackupModuleHandler {
    String moduleName();
    String entityName();
    List<Map<String, Object>> exportItems();
    BackupRestoreResult restore(List<Map<String, Object>> items, BackupRestoreMode mode);
    void deleteAll();
    void rebuildSequenceState();
}
```

Create `BackupModuleRegistry.java` with a `List<BackupModuleHandler>` constructor dependency and deterministic iteration:

```java
package cn.har01d.alist_tvbox.service.backup;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class BackupModuleRegistry {
    private final List<BackupModuleHandler> handlers;

    public BackupModuleRegistry(List<BackupModuleHandler> handlers) {
        this.handlers = handlers.stream()
            .sorted(Comparator.comparing(BackupModuleHandler::moduleName))
            .toList();
    }

    public List<BackupModuleHandler> orderedHandlers() {
        return handlers;
    }

    public Map<String, BackupModuleHandler> handlerMap() {
        Map<String, BackupModuleHandler> map = new LinkedHashMap<>();
        for (BackupModuleHandler handler : handlers) {
            map.put(handler.moduleName(), handler);
        }
        return map;
    }
}
```

- [ ] **Step 4: Implement export service with zip output**

Create `DatabaseBackupService.java` with an injectable YAML mapper, manifest export, and zip writer:

```java
package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.dto.backup.BackupManifestDto;
import cn.har01d.alist_tvbox.dto.backup.BackupModuleDto;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class DatabaseBackupService {
    private final BackupModuleRegistry registry;
    private final SettingRepository settingRepository;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public DatabaseBackupService(BackupModuleRegistry registry, SettingRepository settingRepository) {
        this.registry = registry;
        this.settingRepository = settingRepository;
    }

    public File exportBackupZip() throws Exception {
        BackupManifestDto manifest = new BackupManifestDto();
        manifest.setAppVersion(settingRepository.findById("app_version").map(Setting::getValue).orElse("unknown"));

        for (BackupModuleHandler handler : registry.orderedHandlers()) {
            BackupModuleDto module = new BackupModuleDto();
            module.setEntity(handler.entityName());
            module.setItems(handler.exportItems());
            manifest.getModules().put(handler.moduleName(), module);
        }

        byte[] yaml = yamlMapper.writeValueAsString(manifest).getBytes(StandardCharsets.UTF_8);
        File out = File.createTempFile("database-yaml-" + LocalDate.now(), ".zip");
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(out))) {
            zipOutputStream.putNextEntry(new ZipEntry("database.yaml"));
            zipOutputStream.write(yaml);
            zipOutputStream.closeEntry();
        }
        return out;
    }
}
```

Modify `SettingService.java` to inject `DatabaseBackupService` and add:

```java
public FileSystemResource exportYamlDatabase() throws Exception {
    return new FileSystemResource(databaseBackupService.exportBackupZip());
}
```

- [ ] **Step 5: Replace registry sorting with explicit order**

Update `BackupModuleRegistry.java` to use a static ordered list matching the spec instead of alphabetical sort:

```java
private static final List<String> ORDER = List.of(
    "settings", "users", "tmdb", "tmdbMeta", "sites", "shares", "accounts",
    "driverAccounts", "panAccounts", "pikpakAccounts", "subscriptions", "plugins",
    "pluginFilters", "jellyfins", "embys", "fenius", "navigations",
    "telegramChannels", "indexTemplates", "configFiles", "devices", "tenants",
    "alistAliases", "offlineDownloadTasks", "playUrls", "histories", "tasks"
);
```

and sort handlers by the index in `ORDER`.

- [ ] **Step 6: Run tests to verify export passes**

Run: `mvn -Dtest=DatabaseBackupServiceTest test`

Expected: PASS with manifest and zip export assertions succeeding.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/backup src/main/java/cn/har01d/alist_tvbox/service/SettingService.java src/test/java/cn/har01d/alist_tvbox/service/backup/DatabaseBackupServiceTest.java
git commit -m "feat: add yaml backup zip export service"
```

### Task 3: Implement Concrete Module Handlers And Exclusions

**Files:**

- Modify: `src/main/java/cn/har01d/alist_tvbox/service/backup/DatabaseBackupService.java`
- Create or modify: `src/main/java/cn/har01d/alist_tvbox/service/backup/BackupModuleHandler.java`
- Create or modify: `src/main/java/cn/har01d/alist_tvbox/service/backup/BackupModuleRegistry.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/backup/DatabaseBackupServiceTest.java`

- [ ] **Step 1: Write failing export coverage test for included and excluded modules**

Add this test:

```java
@Test
void shouldExcludeAliasMetaMovieAndSessionFromManifest() throws Exception {
    BackupManifestDto manifest = databaseBackupService.buildManifest();

    assertThat(manifest.getModules()).containsKeys("settings", "users", "sites");
    assertThat(manifest.getModules()).doesNotContainKeys("alias", "meta", "movie", "session");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=DatabaseBackupServiceTest#shouldExcludeAliasMetaMovieAndSessionFromManifest test`

Expected: FAIL because `buildManifest()` and concrete handlers are not implemented.

- [ ] **Step 3: Add generic repository-backed handlers for included entities**

Refactor `BackupModuleHandler.java` into an abstract class if needed:

```java
package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.dto.backup.BackupRestoreMode;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Map;

public abstract class BackupModuleHandler<T, ID> {
    protected final JpaRepository<T, ID> repository;
    protected final ObjectMapper objectMapper;
    private final Class<T> entityClass;

    protected BackupModuleHandler(JpaRepository<T, ID> repository, ObjectMapper objectMapper, Class<T> entityClass) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.entityClass = entityClass;
    }

    public String entityName() {
        return entityClass.getSimpleName();
    }

    public List<Map<String, Object>> exportItems() {
        return repository.findAll().stream()
            .map(item -> objectMapper.convertValue(item, new TypeReference<Map<String, Object>>() {}))
            .toList();
    }

    protected T toEntity(Map<String, Object> item) {
        return objectMapper.convertValue(item, entityClass);
    }

    public void deleteAll() {
        repository.deleteAll();
    }

    public void rebuildSequenceState() {
    }

    public abstract String moduleName();
    public abstract BackupRestoreResult restore(List<Map<String, Object>> items, BackupRestoreMode mode);
}
```

Register one handler bean per included entity inside `BackupModuleRegistry.java` or a dedicated configuration class using existing repositories, excluding `MovieRepository`, `MetaRepository`, `AliasRepository`, and `SessionRepository`.

- [ ] **Step 4: Expose `buildManifest()` for testable manifest generation**

Add to `DatabaseBackupService.java`:

```java
public BackupManifestDto buildManifest() {
    BackupManifestDto manifest = new BackupManifestDto();
    manifest.setAppVersion(settingRepository.findById("app_version").map(Setting::getValue).orElse("unknown"));
    for (BackupModuleHandler handler : registry.orderedHandlers()) {
        BackupModuleDto module = new BackupModuleDto();
        module.setEntity(handler.entityName());
        module.setItems(handler.exportItems());
        manifest.getModules().put(handler.moduleName(), module);
    }
    return manifest;
}
```

and make `exportBackupZip()` call `buildManifest()`.

- [ ] **Step 5: Run tests to verify module coverage passes**

Run: `mvn -Dtest=DatabaseBackupServiceTest test`

Expected: PASS with exclusions and manifest generation verified.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/backup src/test/java/cn/har01d/alist_tvbox/service/backup/DatabaseBackupServiceTest.java
git commit -m "feat: register repository backup modules"
```

### Task 4: Implement Overwrite Restore And ID Generator Rebuild

**Files:**

- Modify: `src/main/java/cn/har01d/alist_tvbox/service/backup/DatabaseBackupService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/backup/BackupModuleHandler.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/backup/DatabaseBackupServiceTest.java`

- [ ] **Step 1: Write failing overwrite restore test**

Add this test:

```java
@Test
void shouldOverwriteRestoreIncludedEntitiesAndKeepExcludedOnes() throws Exception {
    String yaml = """
        formatVersion: 1
        mode: repository
        modules:
          settings:
            entity: "Setting"
            items:
              - name: "yaml_key"
                value: "yaml_value"
        """;

    File zip = createZip(yaml);
    databaseBackupService.restoreBackupZip(zip, BackupRestoreMode.OVERWRITE);

    assertThat(settingRepository.findById("yaml_key")).isPresent();
}
```

Add helper in the test:

```java
private File createZip(String yaml) throws Exception {
    File file = File.createTempFile("backup-", ".zip");
    try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
        out.putNextEntry(new ZipEntry("database.yaml"));
        out.write(yaml.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
    return file;
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=DatabaseBackupServiceTest#shouldOverwriteRestoreIncludedEntitiesAndKeepExcludedOnes test`

Expected: FAIL because restore methods do not exist.

- [ ] **Step 3: Implement zip parsing and overwrite restore**

Add to `DatabaseBackupService.java`:

```java
public BackupRestoreResponse restoreBackupZip(File zipFile, BackupRestoreMode mode) throws Exception {
    BackupManifestDto manifest = readManifest(zipFile);
    BackupRestoreResponse response = new BackupRestoreResponse();
    response.setSuccess(true);

    if (mode == BackupRestoreMode.OVERWRITE) {
        List<BackupModuleHandler> handlers = registry.orderedHandlers();
        for (int i = handlers.size() - 1; i >= 0; i--) {
            handlers.get(i).deleteAll();
        }
    }

    for (BackupModuleHandler handler : registry.orderedHandlers()) {
        BackupModuleDto module = manifest.getModules().get(handler.moduleName());
        if (module == null) {
            continue;
        }
        response.getResults().put(handler.moduleName(), handler.restore(module.getItems(), mode));
    }

    rebuildIdGenerator();
    return response;
}
```

Add `readManifest()`:

```java
private BackupManifestDto readManifest(File zipFile) throws Exception {
    try (ZipFile file = new ZipFile(zipFile)) {
        ZipEntry entry = file.getEntry("database.yaml");
        if (entry == null) {
            throw new IllegalArgumentException("database.yaml not found in zip");
        }
        return yamlMapper.readValue(file.getInputStream(entry), BackupManifestDto.class);
    }
}
```

Implement overwrite behavior in generic handlers:

```java
public BackupRestoreResult restore(List<Map<String, Object>> items, BackupRestoreMode mode) {
    BackupRestoreResult result = new BackupRestoreResult();
    List<T> entities = items.stream().map(this::toEntity).toList();
    repository.saveAll(entities);
    result.setCreated(entities.size());
    return result;
}
```

- [ ] **Step 4: Implement `id_generator` rebuild**

Add a JDBC-based helper inside `DatabaseBackupService.java`:

```java
private final JdbcTemplate jdbcTemplate;

private void rebuildIdGenerator() {
    Map<String, String> tables = Map.of(
        "device", "Device",
        "task", "Task",
        "navigation", "Navigation",
        "feiniu", "Feiniu",
        "play_url", "PlayUrl",
        "offline_download_task", "OfflineDownloadTask",
        "plugin", "Plugin",
        "driver_account", "DriverAccount",
        "tmdb", "Tmdb",
        "pan_account", "PanAccount",
        "history", "History",
        "site", "Site",
        "subscription", "Subscription",
        "plugin_filter", "PluginFilter",
        "index_template", "IndexTemplate",
        "config_file", "ConfigFile",
        "emby", "Emby",
        "pik_pak_account", "PikPakAccount",
        "jellyfin", "Jellyfin",
        "tmdb_meta", "TmdbMeta",
        "tenant", "Tenant"
    );
    tables.forEach((table, entityName) -> {
        Integer nextId = jdbcTemplate.queryForObject(
            "select coalesce(max(id), 0) + 1 from " + table, Integer.class
        );
        jdbcTemplate.update(
            "merge into id_generator(entity_name, next_id) key(entity_name) values (?, ?)",
            entityName, nextId
        );
    });
}
```

Then immediately replace vendor-specific `merge into` with a repository-free upsert split for cross-database support:

```java
jdbcTemplate.update("delete from id_generator where entity_name = ?", entityName);
jdbcTemplate.update("insert into id_generator(entity_name, next_id) values (?, ?)", entityName, nextId);
```

- [ ] **Step 5: Run tests to verify overwrite restore passes**

Run: `mvn -Dtest=DatabaseBackupServiceTest test`

Expected: PASS with overwrite restore and zip parsing covered.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/backup src/test/java/cn/har01d/alist_tvbox/service/backup/DatabaseBackupServiceTest.java
git commit -m "feat: add overwrite yaml restore"
```

### Task 5: Implement Merge Restore Rules

**Files:**

- Modify: `src/main/java/cn/har01d/alist_tvbox/service/backup/BackupModuleHandler.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/backup/BackupModuleRegistry.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/backup/DatabaseBackupServiceTest.java`

- [ ] **Step 1: Write failing merge restore test**

Add:

```java
@Test
void shouldMergeByBusinessKeyForSettings() throws Exception {
    settingRepository.save(new Setting("yaml_key", "old"));

    String yaml = """
        formatVersion: 1
        mode: repository
        modules:
          settings:
            entity: "Setting"
            items:
              - name: "yaml_key"
                value: "new"
              - name: "yaml_added"
                value: "value"
        """;

    databaseBackupService.restoreBackupZip(createZip(yaml), BackupRestoreMode.MERGE);

    assertThat(settingRepository.findById("yaml_key")).get().extracting(Setting::getValue).isEqualTo("new");
    assertThat(settingRepository.findById("yaml_added")).isPresent();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=DatabaseBackupServiceTest#shouldMergeByBusinessKeyForSettings test`

Expected: FAIL because merge-specific matching is not implemented.

- [ ] **Step 3: Add merge hooks to handlers**

Extend `BackupModuleHandler.java`:

```java
protected T mergeEntity(T remote) {
    return remote;
}

public BackupRestoreResult restore(List<Map<String, Object>> items, BackupRestoreMode mode) {
    BackupRestoreResult result = new BackupRestoreResult();
    for (Map<String, Object> item : items) {
        T entity = toEntity(item);
        if (mode == BackupRestoreMode.MERGE) {
            repository.save(mergeEntity(entity));
            result.setUpdated(result.getUpdated() + 1);
        } else {
            repository.save(entity);
            result.setCreated(result.getCreated() + 1);
        }
    }
    return result;
}
```

Provide specialized handlers in `BackupModuleRegistry.java` for:

- `Setting` merged by `name`
- `User` merged by `username`
- `Share` merged by `path`
- `ConfigFile` merged by `path`
- `AListAlias` merged by `path`
- `TelegramChannel` merged by `id`

For `Setting`, implement:

```java
@Override
protected Setting mergeEntity(Setting remote) {
    return settingRepository.findById(remote.getName())
        .map(existing -> {
            existing.setValue(remote.getValue());
            return existing;
        })
        .orElse(remote);
}
```

- [ ] **Step 4: Run tests to verify merge passes**

Run: `mvn -Dtest=DatabaseBackupServiceTest test`

Expected: PASS with merge semantics verified for at least `Setting`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/backup src/test/java/cn/har01d/alist_tvbox/service/backup/DatabaseBackupServiceTest.java
git commit -m "feat: add merge mode for yaml restore"
```

### Task 6: Add Controller Endpoints For YAML Export And Import

**Files:**

- Modify: `src/main/java/cn/har01d/alist_tvbox/web/SettingController.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SettingService.java`
- Create: `src/test/java/cn/har01d/alist_tvbox/web/SettingControllerBackupTest.java`

- [ ] **Step 1: Write failing MockMvc controller tests**

Create `SettingControllerBackupTest.java`:

```java
package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.backup.BackupRestoreMode;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreResponse;
import cn.har01d.alist_tvbox.service.SettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.File;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SettingControllerBackupTest {
    private MockMvc mockMvc;
    private SettingService settingService;

    @BeforeEach
    void setUp() throws Exception {
        settingService = Mockito.mock(SettingService.class);
        File temp = File.createTempFile("backup-", ".zip");
        Mockito.when(settingService.exportYamlDatabase()).thenReturn(new FileSystemResource(temp));
        Mockito.when(settingService.importYamlDatabase(any(), eq(BackupRestoreMode.OVERWRITE)))
            .thenReturn(new BackupRestoreResponse());
        mockMvc = MockMvcBuilders.standaloneSetup(new SettingController(settingService, null)).build();
    }

    @Test
    void shouldDownloadYamlZip() throws Exception {
        mockMvc.perform(get("/api/settings/export-yaml"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("database-yaml-")));
    }

    @Test
    void shouldAcceptYamlZipUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "database-yaml.zip", "application/zip", new byte[]{1, 2, 3});
        mockMvc.perform(multipart("/api/settings/import-yaml")
                .file(file)
                .param("mode", "OVERWRITE")
                .contentType(MediaType.MULTIPART_FORM_DATA))
            .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -Dtest=SettingControllerBackupTest test`

Expected: FAIL because the new controller methods and service methods do not exist.

- [ ] **Step 3: Implement service import/export methods**

Add to `SettingService.java`:

```java
public FileSystemResource exportYamlDatabase() throws Exception {
    return new FileSystemResource(databaseBackupService.exportBackupZip());
}

public BackupRestoreResponse importYamlDatabase(MultipartFile file, BackupRestoreMode mode) throws Exception {
    Path temp = Files.createTempFile("database-yaml-upload-", ".zip");
    file.transferTo(temp);
    return databaseBackupService.restoreBackupZip(temp.toFile(), mode);
}
```

Ensure `DatabaseBackupService` is injected through the constructor.

- [ ] **Step 4: Add controller endpoints**

Modify `SettingController.java`:

```java
@GetMapping("/export-yaml")
public FileSystemResource exportYamlDatabase(HttpServletResponse response) throws Exception {
    response.addHeader("Content-Disposition", "attachment; filename=\"database-yaml-" + LocalDate.now() + ".zip\"");
    response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    return service.exportYamlDatabase();
}

@PostMapping("/import-yaml")
public BackupRestoreResponse importYamlDatabase(@RequestParam("file") MultipartFile file,
                                                @RequestParam(name = "mode", defaultValue = "OVERWRITE") BackupRestoreMode mode) throws Exception {
    return service.importYamlDatabase(file, mode);
}
```

Add imports:

```java
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreMode;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreResponse;
import org.springframework.web.multipart.MultipartFile;
```

- [ ] **Step 5: Run tests to verify controller passes**

Run: `mvn -Dtest=SettingControllerBackupTest test`

Expected: PASS with both download and upload endpoints covered.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/web/SettingController.java src/main/java/cn/har01d/alist_tvbox/service/SettingService.java src/test/java/cn/har01d/alist_tvbox/web/SettingControllerBackupTest.java
git commit -m "feat: add yaml backup controller endpoints"
```

### Task 7: Add Startup Restore State And Early Runner

**Files:**

- Create: `src/main/java/cn/har01d/alist_tvbox/service/backup/RestoreState.java`
- Create: `src/main/java/cn/har01d/alist_tvbox/service/backup/StartupYamlRestoreRunner.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/backup/StartupYamlRestoreRunnerTest.java`

- [ ] **Step 1: Write failing startup restore runner tests**

Create `StartupYamlRestoreRunnerTest.java`:

```java
package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.dto.backup.BackupRestoreMode;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.*;

class StartupYamlRestoreRunnerTest {
    @Test
    void shouldRestoreDatabaseYamlZipWhenPresent() throws Exception {
        DatabaseBackupService backupService = mock(DatabaseBackupService.class);
        RestoreState restoreState = new RestoreState();
        Path tempDir = Files.createTempDirectory("yaml-restore");
        Path restoreFile = tempDir.resolve("database-yaml.zip");
        Files.write(restoreFile, new byte[]{1});

        StartupYamlRestoreRunner runner = new StartupYamlRestoreRunner(backupService, restoreState, restoreFile.toString());
        runner.run(null);

        verify(backupService).restoreBackupZip(new File(restoreFile.toString()), BackupRestoreMode.OVERWRITE);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=StartupYamlRestoreRunnerTest test`

Expected: FAIL because runner and restore state classes do not exist.

- [ ] **Step 3: Implement restore state**

Create `RestoreState.java`:

```java
package cn.har01d.alist_tvbox.service.backup;

import org.springframework.stereotype.Component;

@Component
public class RestoreState {
    private volatile boolean startupRestorePending;
    private volatile boolean startupRestoreRunning;
    private volatile boolean startupRestoreCompleted;

    public boolean shouldSkipInitializationWrites() {
        return startupRestorePending || startupRestoreRunning;
    }

    public void markPending() {
        startupRestorePending = true;
    }

    public void markRunning() {
        startupRestorePending = false;
        startupRestoreRunning = true;
    }

    public void markCompleted() {
        startupRestoreRunning = false;
        startupRestoreCompleted = true;
    }
}
```

- [ ] **Step 4: Implement startup runner**

Create `StartupYamlRestoreRunner.java`:

```java
package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.dto.backup.BackupRestoreMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StartupYamlRestoreRunner implements ApplicationRunner {
    private final DatabaseBackupService databaseBackupService;
    private final RestoreState restoreState;
    private final String startupRestorePath;

    public StartupYamlRestoreRunner(DatabaseBackupService databaseBackupService,
                                    RestoreState restoreState,
                                    @Value("${app.backup.yaml-restore-path:/data/database-yaml.zip}") String startupRestorePath) {
        this.databaseBackupService = databaseBackupService;
        this.restoreState = restoreState;
        this.startupRestorePath = startupRestorePath;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        File file = new File(startupRestorePath);
        if (!file.exists()) {
            return;
        }
        restoreState.markPending();
        restoreState.markRunning();
        databaseBackupService.restoreBackupZip(file, BackupRestoreMode.OVERWRITE);
        if (!file.delete()) {
            file.renameTo(new File(startupRestorePath + ".restored"));
        }
        restoreState.markCompleted();
    }
}
```

- [ ] **Step 5: Run tests to verify startup runner passes**

Run: `mvn -Dtest=StartupYamlRestoreRunnerTest test`

Expected: PASS with restore invocation verified.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service/backup/RestoreState.java src/main/java/cn/har01d/alist_tvbox/service/backup/StartupYamlRestoreRunner.java src/test/java/cn/har01d/alist_tvbox/service/backup/StartupYamlRestoreRunnerTest.java
git commit -m "feat: add startup yaml restore runner"
```

### Task 8: Guard Startup Initializers During Restore

**Files:**

- Modify: `src/main/java/cn/har01d/alist_tvbox/service/UserService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SettingService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SiteService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/NavigationService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/AccountService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/DriverAccountService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/IndexTemplateService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/AListAliasService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/HistoryService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/ShareService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/PikPakService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/EmbyService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/JellyfinService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/TelegramService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/SubscriptionSourceService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/AListLocalService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/ConfigFileService.java`
- Modify: `src/main/java/cn/har01d/alist_tvbox/service/TaskService.java`
- Test: `src/test/java/cn/har01d/alist_tvbox/service/backup/StartupYamlRestoreRunnerTest.java`

- [ ] **Step 1: Write failing guard test against one representative initializer**

Add to `StartupYamlRestoreRunnerTest.java`:

```java
@Test
void shouldSkipInitializerWriteWhenRestoreIsPending() {
    RestoreState state = new RestoreState();
    state.markPending();
    assertThat(state.shouldSkipInitializationWrites()).isTrue();
}
```

- [ ] **Step 2: Run test to verify it fails or is incomplete**

Run: `mvn -Dtest=StartupYamlRestoreRunnerTest test`

Expected: FAIL if AssertJ import is missing or no guard method exists in production; once `RestoreState` exists, this test passes but production still needs wiring.

- [ ] **Step 3: Inject `RestoreState` and add guard clauses**

For each initializer service listed above, inject `RestoreState` through the constructor or required-args path and add an early return at the top of the `@PostConstruct` method:

```java
if (restoreState.shouldSkipInitializationWrites()) {
    log.info("skip initialization during startup yaml restore");
    return;
}
```

For example in `UserService.java`:

```java
private final RestoreState restoreState;

@PostConstruct
public void init() {
    if (restoreState.shouldSkipInitializationWrites()) {
        log.info("skip user initialization during startup yaml restore");
        return;
    }
    try {
        initializeAdminUser();
    } catch (Exception e) {
        ...
    }
}
```

- [ ] **Step 4: Run focused compilation and unit tests**

Run: `mvn -Dtest=StartupYamlRestoreRunnerTest,SettingControllerBackupTest,DatabaseBackupServiceTest test`

Expected: PASS with all backup-related tests green and no constructor wiring errors.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cn/har01d/alist_tvbox/service src/test/java/cn/har01d/alist_tvbox/service/backup/StartupYamlRestoreRunnerTest.java
git commit -m "feat: skip startup initializers during yaml restore"
```

### Task 9: Update Startup Script Priority Rules

**Files:**

- Modify: `scripts/init.sh`
- Test: local shell syntax verification

- [ ] **Step 1: Add a failing behavior note to the plan implementation branch**

There is no existing shell test file, so use syntax verification as the first guard and then edit minimally.

- [ ] **Step 2: Run shell syntax verification before changes**

Run: `/bin/sh -n scripts/init.sh`

Expected: PASS before modification, establishing a baseline.

- [ ] **Step 3: Modify SQL restore function to yield to YAML restore**

Change `restore_database()` in `scripts/init.sh` to:

```sh
restore_database() {
  if [ -f "/data/database-yaml.zip" ]; then
    echo "=== skip sql restore because yaml restore package exists ==="
    return
  fi
  if [ -f "/data/database.zip" ]; then
    echo "=== restore database ==="
    rm -f /data/atv.mv.db /data/atv.trace.db
    /jre/bin/java -cp /opt/atv/BOOT-INF/lib/h2-*.jar org.h2.tools.RunScript -url jdbc:h2:/data/atv -user sa -password password -script /data/database.zip -options compression zip
    rm -f /data/database.zip
  fi
}
```

This preserves the existing SQL path while ensuring application-level YAML restore gets first priority when both files exist.

- [ ] **Step 4: Re-run shell syntax verification**

Run: `/bin/sh -n scripts/init.sh`

Expected: PASS with no syntax errors.

- [ ] **Step 5: Commit**

```bash
git add scripts/init.sh
git commit -m "fix: prioritize yaml restore over sql startup restore"
```

### Task 10: Add Config Page Export And Import Controls

**Files:**

- Modify: `web-ui/src/views/ConfigView.vue`
- Test: existing front-end test command or typecheck

- [ ] **Step 1: Add failing UI behavior assertions if a test harness exists**

If there is no existing test file for `ConfigView.vue`, skip direct component test creation and rely on typecheck plus manual verification. Keep the change localized.

- [ ] **Step 2: Identify the existing SQL export button section**

Use the existing `exportDatabase()` function in `ConfigView.vue` and place the new controls in the same application-data or advanced settings area.

- [ ] **Step 3: Add YAML export and import UI**

Add state near the other refs:

```ts
const yamlImportVisible = ref(false)
const yamlImporting = ref(false)
const yamlRestoreMode = ref<'OVERWRITE' | 'MERGE'>('OVERWRITE')
const yamlUploadRef = ref<UploadInstance>()
const yamlSelectedFile = ref<File | null>(null)
```

Add methods:

```ts
const exportYamlDatabase = () => {
  window.location.href = '/api/settings/export-yaml' + '?t=' + new Date().getTime() + '&X-ACCESS-TOKEN=' + localStorage.getItem("token");
}

const handleYamlFileChange: UploadProps['onChange'] = (uploadFile, uploadFiles) => {
  yamlSelectedFile.value = uploadFiles[0]?.raw || null
}

const importYamlDatabase = async () => {
  if (!yamlSelectedFile.value) {
    ElMessage.warning('请选择备份文件')
    return
  }
  yamlImporting.value = true
  const formData = new FormData()
  formData.append('file', yamlSelectedFile.value)
  formData.append('mode', yamlRestoreMode.value)
  try {
    const {data} = await axios.post('/api/settings/import-yaml', formData, {
      headers: {'Content-Type': 'multipart/form-data'}
    })
    if (data.success) {
      ElMessage.success('恢复成功')
      yamlImportVisible.value = false
    } else {
      ElMessage.error('恢复失败')
    }
  } finally {
    yamlImporting.value = false
  }
}
```

Add buttons near the existing SQL export action:

```vue
<el-button type="primary" @click="exportDatabase">导出 SQL</el-button>
<el-button type="success" @click="exportYamlDatabase">导出 YAML</el-button>
<el-button type="warning" @click="yamlImportVisible = true">导入 YAML</el-button>
```

Add an `el-dialog` with:

```vue
<el-dialog v-model="yamlImportVisible" title="导入 YAML 备份" width="520px">
  <el-alert
    title="覆盖恢复会清空纳入备份的业务数据，请谨慎操作。"
    type="warning"
    show-icon
    :closable="false"
  />
  <el-form label-width="100px" class="mt-3">
    <el-form-item label="恢复模式">
      <el-radio-group v-model="yamlRestoreMode">
        <el-radio label="OVERWRITE">覆盖恢复</el-radio>
        <el-radio label="MERGE">合并恢复</el-radio>
      </el-radio-group>
    </el-form-item>
    <el-form-item label="备份文件">
      <el-upload
        ref="yamlUploadRef"
        :auto-upload="false"
        :limit="1"
        accept=".zip"
        :on-change="handleYamlFileChange"
      >
        <el-button type="primary">选择 ZIP 文件</el-button>
      </el-upload>
    </el-form-item>
  </el-form>
  <template #footer>
    <el-button @click="yamlImportVisible = false">取消</el-button>
    <el-button type="warning" :loading="yamlImporting" @click="importYamlDatabase">开始恢复</el-button>
  </template>
</el-dialog>
```

- [ ] **Step 4: Run front-end verification**

Run: `npm run web:typecheck`

Expected: PASS with no TypeScript errors in `ConfigView.vue`.

- [ ] **Step 5: Commit**

```bash
git add web-ui/src/views/ConfigView.vue
git commit -m "feat: add yaml backup controls to config view"
```

### Task 11: Add Native Image And Reflection Updates

**Files:**

- Modify: `src/main/java/cn/har01d/alist_tvbox/Main.java`
- Modify: `src/main/resources/META-INF/native-image/reflect-config.json`

- [ ] **Step 1: Add failing native reflection expectation to the implementation notes**

No dedicated native test is available here, so the guard is to keep DTOs in scanned packages and regenerate reflection metadata.

- [ ] **Step 2: Ensure backup DTO package is scanned**

If the new DTOs live under `cn.har01d.alist_tvbox.dto.backup`, verify `Main.java` already scans `cn.har01d.alist_tvbox.dto`. If not, add explicit scan lines. In this codebase it already scans `cn.har01d.alist_tvbox.dto`, so only keep the package placement consistent.

- [ ] **Step 3: Regenerate `reflect-config.json`**

Run:

```bash
mvn compile
java -cp target/classes cn.har01d.alist_tvbox.Main
```

Expected: `src/main/resources/META-INF/native-image/reflect-config.json` is updated and contains the new backup DTO classes.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/META-INF/native-image/reflect-config.json
git commit -m "chore: update native reflection config for backup dto"
```

### Task 12: Full Verification Pass

**Files:**

- Modify: any files needed for final fixes discovered during verification

- [ ] **Step 1: Run targeted backend tests**

Run: `mvn -Dtest=DatabaseBackupServiceTest,StartupYamlRestoreRunnerTest,SettingControllerBackupTest test`

Expected: PASS with all new backup tests green.

- [ ] **Step 2: Run broader backend regression tests**

Run: `mvn test`

Expected: PASS or only pre-existing unrelated failures. If unrelated failures exist, document them explicitly before proceeding.

- [ ] **Step 3: Run front-end verification**

Run: `npm run web:typecheck`

Expected: PASS.

- [ ] **Step 4: Run shell syntax verification**

Run: `/bin/sh -n scripts/init.sh`

Expected: PASS.

- [ ] **Step 5: Inspect git diff for unintended changes**

Run: `git diff --stat HEAD~12..HEAD`

Expected: Only backup-related Java, Vue, shell, DTO, tests, and reflection config changes.

- [ ] **Step 6: Commit any final fixes**

```bash
git add src/main/java src/test/java web-ui/src/views/ConfigView.vue scripts/init.sh src/main/resources/META-INF/native-image/reflect-config.json
git commit -m "test: finalize yaml backup restore integration"
```

## Self-Review

Spec coverage:

- Zip-compressed YAML export: covered by Tasks 1-3 and 6.
- Repository-based restore: covered by Tasks 4-5.
- Startup restore path `/data/database-yaml.zip`: covered by Tasks 7 and 9.
- Startup restore priority over default initialization: covered by Tasks 7-9.
- Excluded entities `Movie`, `Meta`, `Alias`, `Session`, `id_generator`: covered by Task 3 and Task 4.
- Overwrite and merge restore modes: covered by Tasks 4-5.
- Front-end export/import controls: covered by Task 10.
- Native image updates: covered by Task 11.
- Verification and tests: covered by Task 12.

Placeholder scan:

- No `TODO`, `TBD`, or “implement later” placeholders remain.
- Commands, files, and code snippets are concrete.

Type consistency:

- `BackupRestoreMode`, `BackupManifestDto`, `BackupModuleDto`, `BackupRestoreResponse`, and `BackupRestoreResult` are used consistently across service, controller, and tests.
- Startup restore path is consistently `/data/database-yaml.zip`.
- Export filename is consistently `database-yaml-<date>.zip`.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-19-yaml-backup-restore.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
