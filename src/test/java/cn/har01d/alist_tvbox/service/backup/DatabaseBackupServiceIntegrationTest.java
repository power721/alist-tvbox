package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.Role;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreMode;
import cn.har01d.alist_tvbox.entity.ConfigFile;
import cn.har01d.alist_tvbox.entity.ConfigFileRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.entity.UserRepository;
import cn.har01d.alist_tvbox.service.AListService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "app.backup.yaml-restore-path=/tmp/atv-test-nonexistent-database-yaml.zip")
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
    void exportZipContainsDatabaseYamlAndExcludesDerivedEntities() throws Exception {
        File file = databaseBackupService.exportBackupZip();
        assertThat(file).exists();

        try (ZipFile zipFile = new ZipFile(file)) {
            ZipEntry entry = zipFile.getEntry("database.yaml");
            assertThat(entry).isNotNull();
            String yaml = new String(zipFile.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml).contains("formatVersion: 1");
            assertThat(yaml).contains("modules:");
            assertThat(yaml).contains("settings:");
            assertThat(yaml).contains("users:");
            // Derived / runtime entities must not be present as modules.
            assertThat(yaml).doesNotContain("\"Movie\"");
            assertThat(yaml).doesNotContain("\"Meta\"");
            assertThat(yaml).doesNotContain("\"Session\"");
        }
    }

    @Test
    @Transactional
    void overwriteRestorePreservesIdsAndRebuildsIdGenerator() throws Exception {
        // Admin user is created at startup with id 1.
        assertThat(userRepository.findById(1)).isPresent();

        String yaml = """
            formatVersion: 1
            mode: "repository"
            modules:
              settings:
                entity: "Setting"
                items:
                  - name: "ow_key"
                    value: "ow_value"
              sites:
                entity: "Site"
                items:
                  - id: 7
                    name: "restored-site"
                    url: "http://restored"
                    order: 1
                    searchable: false
                    xiaoya: false
            """;

        databaseBackupService.restoreBackupZip(createZip(yaml), BackupRestoreMode.OVERWRITE);

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
        settingRepository.save(new Setting("yaml_merge_key", "old"));

        String yaml = """
            formatVersion: 1
            mode: "repository"
            modules:
              settings:
                entity: "Setting"
                items:
                  - name: "yaml_merge_key"
                    value: "new"
                  - name: "yaml_merge_added"
                    value: "value"
              sites:
                entity: "Site"
                items:
                  - id: 4242
                    name: "merge-new-site"
                    url: "http://merge-new"
                    order: 1
                    searchable: false
                    xiaoya: false
            """;

        databaseBackupService.restoreBackupZip(createZip(yaml), BackupRestoreMode.MERGE);

        assertThat(settingRepository.findById("yaml_merge_key")).get()
            .extracting(Setting::getValue).isEqualTo("new");
        assertThat(settingRepository.findById("yaml_merge_added")).isPresent();
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

        // Fix A: the @JsonIgnore password must actually be present in the exported YAML.
        try (ZipFile zf = new ZipFile(zip)) {
            String yaml = new String(zf.getInputStream(zf.getEntry("database.yaml")).readAllBytes(),
                StandardCharsets.UTF_8);
            assertThat(yaml).contains("known-hash");
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
    void overwriteRestoreHandlesContentExceedingSnakeYamlDefaultLimit() throws Exception {
        // A 4 MB @JsonIgnore content blob makes the exported YAML exceed SnakeYAML's 3 MB default
        // code-point limit; restore must not throw "exceeds the limit: 3145728 code points".
        char[] chars = new char[4 * 1024 * 1024];
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

    private File createZip(String yaml) throws Exception {
        File file = File.createTempFile("backup-test-", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            out.putNextEntry(new ZipEntry("database.yaml"));
            out.write(yaml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file;
    }
}
