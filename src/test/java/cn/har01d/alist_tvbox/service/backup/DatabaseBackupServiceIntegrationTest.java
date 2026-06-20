package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.Role;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreMode;
import cn.har01d.alist_tvbox.entity.ConfigFile;
import cn.har01d.alist_tvbox.entity.ConfigFileRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.entity.UserRepository;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.service.AListService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.backup.json-restore-path=/tmp/atv-test-nonexistent-database-json.zip")
class DatabaseBackupServiceIntegrationTest {
    @MockitoBean
    private AListService aListService;
    @MockitoBean
    private AppProperties appProperties;

    @Autowired
    private DatabaseBackupService databaseBackupService;
    @Autowired
    private SettingRepository settingRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SiteRepository siteRepository;
    @Autowired
    private ConfigFileRepository configFileRepository;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void exportZipHasPerModuleJsonAndExcludesDerivedEntities() throws Exception {
        File file = databaseBackupService.exportBackupZip();
        assertThat(file).exists();

        try (ZipFile zipFile = new ZipFile(file)) {
            assertThat(zipFile.getEntry("manifest.json")).isNotNull();
            assertThat(zipFile.getEntry("modules/users.json")).isNotNull();
            assertThat(zipFile.getEntry("modules/settings.json")).isNotNull();

            String manifest = new String(
                zipFile.getInputStream(zipFile.getEntry("manifest.json")).readAllBytes(),
                StandardCharsets.UTF_8);
            assertThat(manifest).contains("\"formatVersion\":1");
            assertThat(manifest).contains("\"users\"");
            // Derived / runtime entities must not be present as modules (quoted so "Meta" does not
            // match the legitimate "TmdbMeta" module).
            assertThat(manifest).doesNotContain("\"Movie\"");
            assertThat(manifest).doesNotContain("\"Meta\"");
            assertThat(manifest).doesNotContain("\"Session\"");
        }
    }

    @Test
    @Transactional
    void overwriteRestorePreservesIdsAndRebuildsIdGenerator() throws Exception {
        // Admin user is created at startup with id 1.
        assertThat(userRepository.findById(1)).isPresent();

        String manifest = "{\"formatVersion\":1,\"mode\":\"repository\",\"modules\":{\"settings\":\"Setting\",\"sites\":\"Site\"}}";
        Map<String, String> modules = Map.of(
            "settings", "{\"entity\":\"Setting\",\"items\":[{\"name\":\"ow_key\",\"value\":\"ow_value\"}]}",
            "sites", "{\"entity\":\"Site\",\"items\":[{\"id\":7,\"name\":\"restored-site\",\"url\":\"http://restored\",\"order\":1,\"searchable\":false,\"xiaoya\":false}]}");

        databaseBackupService.restoreBackupZip(createBackupZip(manifest, modules), BackupRestoreMode.OVERWRITE);

        // Preserved explicit id from the backup.
        assertThat(siteRepository.findById(7)).isPresent();
        assertThat(settingRepository.findById("ow_key")).isPresent();

        // id_generator was rebuilt to max(id)+1 (= 8) so the next generated Site id won't collide.
        Long nextId = jdbcTemplate.queryForObject(
            "select next_id from id_generator where entity_name = 'site'", Long.class);
        assertThat(nextId).isEqualTo(8L);
    }

    @Test
    @Transactional
    void mergeRestoreUpdatesByBusinessKeyAndInsertsNew() throws Exception {
        settingRepository.save(new Setting("merge_key", "old"));

        String manifest = "{\"formatVersion\":1,\"mode\":\"repository\",\"modules\":{\"settings\":\"Setting\",\"sites\":\"Site\"}}";
        Map<String, String> modules = Map.of(
            "settings", "{\"entity\":\"Setting\",\"items\":[{\"name\":\"merge_key\",\"value\":\"new\"},{\"name\":\"merge_added\",\"value\":\"value\"}]}",
            "sites", "{\"entity\":\"Site\",\"items\":[{\"id\":4242,\"name\":\"merge-new-site\",\"url\":\"http://merge-new\",\"order\":1,\"searchable\":false,\"xiaoya\":false}]}");

        databaseBackupService.restoreBackupZip(createBackupZip(manifest, modules), BackupRestoreMode.MERGE);

        assertThat(settingRepository.findById("merge_key")).get()
            .extracting(Setting::getValue).isEqualTo("new");
        assertThat(settingRepository.findById("merge_added")).isPresent();
        // New TABLE-entity row inserted via the persister with its preserved id.
        assertThat(siteRepository.findById(4242)).isPresent();
    }

    @Test
    @Transactional
    void overwriteRestorePreservesUserPasswordAndKeepsSingleAdminAtIdOne() throws Exception {
        // Startup seeds the admin at id=1 with a random password; pin a known hash to verify round-trip.
        User admin = userRepository.findById(1).orElseThrow();
        String username = admin.getUsername();
        admin.setPassword("known-hash");
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);
        userRepository.flush();

        File zip = databaseBackupService.exportBackupZip();

        // Fix A: the @JsonIgnore password must actually be present in the exported users module.
        try (ZipFile zf = new ZipFile(zip)) {
            String usersJson = new String(
                zf.getInputStream(zf.getEntry("modules/users.json")).readAllBytes(),
                StandardCharsets.UTF_8);
            assertThat(usersJson).contains("known-hash");
        }

        databaseBackupService.restoreBackupZip(zip, BackupRestoreMode.OVERWRITE);

        // Password round-tripped (Fix A) and exactly one admin survives at id=1 (Fix B).
        User restored = userRepository.findByUsername(username);
        assertThat(restored).isNotNull();
        assertThat(restored.getPassword()).isEqualTo("known-hash");
        assertThat(userRepository.findById(1)).isPresent();
        assertThat(userRepository.findById(1).get().getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    @Transactional
    void overwriteRestoreHandlesContentExceedingJacksonDefaultLimit() throws Exception {
        // A 6 MB @JsonIgnore content blob exceeds Jackson's 5 MB default maxStringLength; restore must
        // not throw. The backup mapper raises the string/document limits to handle large content.
        char[] chars = new char[6 * 1024 * 1024];
        java.util.Arrays.fill(chars, 'x');
        String largeContent = new String(chars);

        ConfigFile file = new ConfigFile();
        file.setPath("config/large.json");
        file.setName("large.json");
        file.setDir("config");
        file.setContent(largeContent);
        configFileRepository.save(file);
        configFileRepository.flush();

        File zip = databaseBackupService.exportBackupZip();
        databaseBackupService.restoreBackupZip(zip, BackupRestoreMode.OVERWRITE);

        ConfigFile restored = configFileRepository.findByPath("config/large.json");
        assertThat(restored).isNotNull();
        assertThat(restored.getContent()).isEqualTo(largeContent);
    }

    private File createBackupZip(String manifestJson, Map<String, String> moduleJsonByName) throws Exception {
        File file = File.createTempFile("backup-test-", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            out.putNextEntry(new ZipEntry("manifest.json"));
            out.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (Map.Entry<String, String> e : moduleJsonByName.entrySet()) {
                out.putNextEntry(new ZipEntry("modules/" + e.getKey() + ".json"));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }
}
