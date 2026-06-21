package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.dto.backup.BackupManifestDto;
import cn.har01d.alist_tvbox.dto.backup.BackupModuleDto;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreMode;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreResponse;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreResult;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.UserService;
import cn.har01d.alist_tvbox.service.backup.BackupModuleHandler.IdStrategy;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Repository-based, database-independent backup and restore. Each included entity is exported as its own
 * JSON file inside a zip: a lightweight {@code manifest.json} (formatVersion, appVersion, module list)
 * plus one {@code modules/<name>.json} per table holding {@code {entity, items}}. Restore streams the
 * zip one module at a time, so peak memory is one table, not the whole backup, and no single document
 * has to carry the entire dump. The existing SQL backup ({@code SettingService.exportDatabase}) is left
 * untouched.
 */
@Service
public class DatabaseBackupService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupService.class);

    static final String MANIFEST_ENTRY = "manifest.json";
    static final String MODULES_DIR = "modules/";
    private static final int SUPPORTED_FORMAT_VERSION = 1;

    // Jackson caps individual strings at 5 MB and (since 2.16) the whole document at a modest default.
    // Large @JsonIgnore TEXT/LONGTEXT fields (Device.config, ConfigFile.content, ...) exceed those, so
    // raise both generously. Per-module files keep each document small, but a single content blob can
    // still be large.
    private static final int JSON_MAX_STRING_LEN = 256 * 1024 * 1024;
    private static final long JSON_MAX_DOCUMENT_LEN = 512L * 1024 * 1024;

    private final BackupModuleRegistry registry;
    private final SettingRepository settingRepository;
    private final JdbcTemplate jdbcTemplate;
    private final UserService userService;

    @PersistenceContext
    private EntityManager entityManager;

    // JavaTimeModule is mandatory – Instant fields (exportedAt, TmdbMeta.time, ...) fail to
    // serialize without it. Disable timestamps so output matches ISO-8601.
    private final ObjectMapper jsonMapper = createJsonMapper();

    private static ObjectMapper createJsonMapper() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
            .maxStringLength(JSON_MAX_STRING_LEN)
            .maxDocumentLength(JSON_MAX_DOCUMENT_LEN)
            .build();
        JsonFactory jsonFactory = JsonFactory.builder()
            .streamReadConstraints(constraints)
            .build();
        return new ObjectMapper(jsonFactory)
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public DatabaseBackupService(BackupModuleRegistry registry,
                                 SettingRepository settingRepository,
                                 JdbcTemplate jdbcTemplate,
                                 UserService userService) {
        this.registry = registry;
        this.settingRepository = settingRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.userService = userService;
    }

    public File exportBackupZip() throws Exception {
        return exportBackupZip(false);
    }

    /**
     * Export the backup zip. When {@code includeDouban} is true the large douban modules
     * (movie/meta/alias) are included — used by the migration path so the target DB receives the
     * douban data; daily backups pass false to stay small.
     */
    public File exportBackupZip(boolean includeDouban) throws Exception {
        BackupManifestDto manifest = new BackupManifestDto();
        manifest.setAppVersion(settingRepository.findById("app_version").map(Setting::getValue).orElse("unknown"));

        File out = File.createTempFile("database-backup-" + LocalDate.now() + "-", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            // One JSON file per table keeps each document small and lets restore stream module by module.
            for (BackupModuleHandler<?> handler : registry.exportHandlers(includeDouban)) {
                BackupModuleDto module = new BackupModuleDto();
                module.setEntity(handler.entityName());
                module.setItems(handler.exportItems());
                zip.putNextEntry(new ZipEntry(MODULES_DIR + handler.moduleName() + ".json"));
                zip.write(jsonMapper.writeValueAsBytes(module));
                zip.closeEntry();
                manifest.getModules().put(handler.moduleName(), handler.entityName());
            }
            zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
            zip.write(jsonMapper.writeValueAsBytes(manifest));
            zip.closeEntry();
        }
        return out;
    }

    @Transactional
    public BackupRestoreResponse restoreBackupZip(File zipFile, BackupRestoreMode mode) throws Exception {
        try (ZipFile zip = new ZipFile(zipFile)) {
            BackupManifestDto manifest = readManifest(zip);
            if (manifest.getFormatVersion() > SUPPORTED_FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported backup formatVersion: " + manifest.getFormatVersion());
            }

            BackupRestoreResponse response = new BackupRestoreResponse();
            response.setSuccess(true);

            List<BackupModuleHandler<?>> handlers = registry.orderedHandlers();

            if (mode == BackupRestoreMode.OVERWRITE) {
                // Delete non-IDENTITY entities in reverse dependency order via raw SQL so the rows are
                // gone from the DB immediately (within the transaction). IDENTITY entities are NOT
                // deleted here: their handler upserts by id and removes missing rows, preserving
                // existing ids (e.g. admin user = 1) without DDL that would break this transaction.
                // Only clear tables actually present in this backup: a base-only backup (no douban)
                // must not wipe movie/meta/alias rows that aren't being restored.
                Set<String> manifestModules = manifest.getModules().keySet();
                for (int i = handlers.size() - 1; i >= 0; i--) {
                    BackupModuleHandler<?> handler = handlers.get(i);
                    if (manifestModules.contains(handler.moduleName())
                            && handler.idStrategy() != IdStrategy.IDENTITY) {
                        jdbcTemplate.update("delete from " + handler.tableName());
                    }
                }
                // Drop any managed (pre-loaded) instances so the subsequent inserts, which reuse the
                // same ids, do not resolve to stale UPDATEs against the now-deleted rows.
                entityManager.clear();
            }

            // Stream one module file at a time: read it, restore it, let it be GC'd before the next.
            for (BackupModuleHandler<?> handler : handlers) {
                ZipEntry entry = zip.getEntry(MODULES_DIR + handler.moduleName() + ".json");
                if (entry == null) {
                    continue;
                }
                BackupModuleDto module;
                try (InputStream in = zip.getInputStream(entry)) {
                    module = jsonMapper.readValue(in, BackupModuleDto.class);
                }
                BackupRestoreResult result = handler.restore(module.getItems(), mode);
                response.getResults().put(handler.moduleName(), result);
            }

            // The IDENTITY-backed User id cannot be preserved, so a restored admin may not land on id=1.
            // Re-establish the id=1 invariant inside this transaction before restart so init() does not
            // re-create the admin on every boot.
            userService.ensureAdminOccupiesIdOne();

            // Flush inserts so the JDBC max(id) scan in rebuildIdGenerator sees the restored rows.
            entityManager.flush();
            rebuildIdGenerator();
            return response;
        }
    }

    private BackupManifestDto readManifest(ZipFile zip) throws Exception {
        ZipEntry entry = zip.getEntry(MANIFEST_ENTRY);
        if (entry == null) {
            throw new IllegalArgumentException(MANIFEST_ENTRY + " not found in backup zip");
        }
        try (InputStream in = zip.getInputStream(entry)) {
            BackupManifestDto manifest = jsonMapper.readValue(in, BackupManifestDto.class);
            if (manifest == null) {
                throw new IllegalArgumentException("Invalid backup manifest");
            }
            return manifest;
        }
    }

    /** Rebuild id_generator rows for TABLE-strategy entities so future inserts do not collide. */
    private void rebuildIdGenerator() {
        for (BackupModuleHandler<?> handler : registry.orderedHandlers()) {
            if (handler.idStrategy() != IdStrategy.TABLE) {
                continue;
            }
            String table = handler.tableName();
            Long nextId = jdbcTemplate.queryForObject(
                "select coalesce(max(id), 0) + 1 from " + table, Long.class);
            // Cross-database upsert (no vendor-specific MERGE). Segment value is the snake_case
            // table name – the default Hibernate enhanced TableGenerator segment for these entities.
            jdbcTemplate.update("delete from id_generator where entity_name = ?", table);
            jdbcTemplate.update("insert into id_generator(entity_name, next_id) values (?, ?)", table, nextId);
        }
    }
}
