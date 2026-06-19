package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.dto.backup.BackupManifestDto;
import cn.har01d.alist_tvbox.dto.backup.BackupModuleDto;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreMode;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreResponse;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreResult;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.backup.BackupModuleHandler.IdStrategy;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Repository-based, database-independent backup and restore. Exports included entities to a
 * zip-compressed {@code database.yaml} and restores them through Spring Data repositories. The
 * existing SQL backup ({@code SettingService.exportDatabase}) is left untouched.
 */
@Service
public class DatabaseBackupService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupService.class);

    static final String YAML_ENTRY = "database.yaml";
    private static final int SUPPORTED_FORMAT_VERSION = 1;

    private final BackupModuleRegistry registry;
    private final SettingRepository settingRepository;
    private final JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    // JavaTimeModule is mandatory – Instant fields (exportedAt, TmdbMeta.time, ...) fail to
    // serialize without it. Disable timestamps so output matches ISO-8601.
    private final ObjectMapper yamlMapper = new ObjectMapper(
        new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public DatabaseBackupService(BackupModuleRegistry registry,
                                 SettingRepository settingRepository,
                                 JdbcTemplate jdbcTemplate) {
        this.registry = registry;
        this.settingRepository = settingRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public BackupManifestDto buildManifest() {
        BackupManifestDto manifest = new BackupManifestDto();
        manifest.setAppVersion(settingRepository.findById("app_version").map(Setting::getValue).orElse("unknown"));
        for (BackupModuleHandler<?> handler : registry.orderedHandlers()) {
            BackupModuleDto module = new BackupModuleDto();
            module.setEntity(handler.entityName());
            module.setItems(handler.exportItems());
            manifest.getModules().put(handler.moduleName(), module);
        }
        return manifest;
    }

    public File exportBackupZip() throws Exception {
        BackupManifestDto manifest = buildManifest();
        byte[] yaml = yamlMapper.writeValueAsString(manifest).getBytes(StandardCharsets.UTF_8);
        File out = File.createTempFile("database-yaml-" + LocalDate.now() + "-", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))) {
            zip.putNextEntry(new ZipEntry(YAML_ENTRY));
            zip.write(yaml);
            zip.closeEntry();
        }
        return out;
    }

    @Transactional
    public BackupRestoreResponse restoreBackupZip(File zipFile, BackupRestoreMode mode) throws Exception {
        BackupManifestDto manifest = readManifest(zipFile);
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
            for (int i = handlers.size() - 1; i >= 0; i--) {
                BackupModuleHandler<?> handler = handlers.get(i);
                if (handler.idStrategy() != IdStrategy.IDENTITY) {
                    jdbcTemplate.update("delete from " + handler.tableName());
                }
            }
            // Drop any managed (pre-loaded) instances so the subsequent inserts, which reuse the
            // same ids, do not resolve to stale UPDATEs against the now-deleted rows.
            entityManager.clear();
        }

        for (BackupModuleHandler<?> handler : handlers) {
            BackupModuleDto module = manifest.getModules().get(handler.moduleName());
            if (module == null) {
                continue;
            }
            BackupRestoreResult result = handler.restore(module.getItems(), mode);
            response.getResults().put(handler.moduleName(), result);
        }

        // Flush inserts so the JDBC max(id) scan in rebuildIdGenerator sees the restored rows.
        entityManager.flush();
        rebuildIdGenerator();
        return response;
    }

    private BackupManifestDto readManifest(File zipFile) throws Exception {
        try (ZipFile file = new ZipFile(zipFile)) {
            ZipEntry entry = file.getEntry(YAML_ENTRY);
            if (entry == null) {
                throw new IllegalArgumentException(YAML_ENTRY + " not found in backup zip");
            }
            try (InputStream in = file.getInputStream(entry)) {
                BackupManifestDto manifest = yamlMapper.readValue(in, BackupManifestDto.class);
                if (manifest == null || manifest.getModules() == null) {
                    throw new IllegalArgumentException("Invalid backup manifest");
                }
                return manifest;
            }
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
