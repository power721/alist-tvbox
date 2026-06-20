package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.domain.Role;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreMode;
import cn.har01d.alist_tvbox.dto.backup.BackupRestoreResult;
import cn.har01d.alist_tvbox.entity.ConfigFile;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.User;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the {@code @JsonIgnore} round-trip: backup must export and restore fields the
 * API hides (User.password, ConfigFile.content, ...). Before the fix, {@code objectMapper.convertValue}
 * silently dropped them, leaving restored users with a null password.
 */
@ExtendWith(MockitoExtension.class)
class BackupModuleHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Mock
    private JpaRepository<User, Integer> userRepository;
    @Mock
    private JpaRepository<ConfigFile, Integer> configFileRepository;
    @Mock
    private JpaRepository<Plugin, Integer> pluginRepository;
    @Mock
    private EntityManager entityManager;

    @Test
    void exportIncludesJsonIgnoreUserPassword() {
        User user = new User();
        user.setId(1);
        user.setUsername("harold");
        user.setRole(Role.ADMIN);
        user.setPassword("secret-hash");
        when(userRepository.findAll()).thenReturn(List.of(user));

        BackupModuleHandler<User> handler = new BackupModuleHandler<>(
            "users", "x_user", User.class, userRepository, mapper, entityManager,
            BackupModuleHandler.IdStrategy.IDENTITY, "username");

        List<Map<String, Object>> items = handler.exportItems();

        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsKey("password");
        assertThat(items.get(0).get("password")).isEqualTo("secret-hash");
    }

    @Test
    void restorePreservesJsonIgnoreUserPassword() {
        User user = new User();
        user.setId(1);
        user.setUsername("harold");
        user.setRole(Role.ADMIN);
        user.setPassword("secret-hash");
        when(userRepository.findAll()).thenReturn(List.of(user));

        BackupModuleHandler<User> handler = new BackupModuleHandler<>(
            "users", "x_user", User.class, userRepository, mapper, entityManager,
            BackupModuleHandler.IdStrategy.IDENTITY, "username");

        List<Map<String, Object>> exported = handler.exportItems();

        // Restore into an "empty" target table (id not matched -> new IDENTITY insert via save()).
        when(userRepository.findAll()).thenReturn(List.of());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        BackupRestoreResult result = handler.restore(new ArrayList<>(exported), BackupRestoreMode.OVERWRITE);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(result.getCreated()).isEqualTo(1);
        assertThat(captor.getValue().getPassword()).isEqualTo("secret-hash");
        assertThat(captor.getValue().getUsername()).isEqualTo("harold");
    }

    @Test
    void exportIncludesJsonIgnoreConfigFileContent() {
        ConfigFile file = new ConfigFile();
        file.setId(1);
        file.setPath("config/iptv.json");
        file.setName("iptv.json");
        file.setDir("config");
        file.setContent("{\"channels\":[]}");
        when(configFileRepository.findAll()).thenReturn(List.of(file));

        BackupModuleHandler<ConfigFile> handler = new BackupModuleHandler<>(
            "configFiles", "config_file", ConfigFile.class, configFileRepository, mapper, entityManager,
            BackupModuleHandler.IdStrategy.TABLE, "path");

        List<Map<String, Object>> items = handler.exportItems();

        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsKey("content");
        assertThat(items.get(0).get("content")).isEqualTo("{\"channels\":[]}");
    }

    @Test
    void exportExcludesPluginContentWhenConfigured() {
        Plugin plugin = new Plugin();
        plugin.setId(1);
        plugin.setName("spider");
        plugin.setContent("huge-public-downloaded-spider-source");
        when(pluginRepository.findAll()).thenReturn(List.of(plugin));

        // Plugin.content is public-internet content excluded from the backup (Set.of("content")).
        BackupModuleHandler<Plugin> handler = new BackupModuleHandler<>(
            "plugins", "plugin", Plugin.class, pluginRepository, mapper, entityManager,
            BackupModuleHandler.IdStrategy.TABLE, "id", java.util.Set.of("content"));

        List<Map<String, Object>> items = handler.exportItems();

        assertThat(items).hasSize(1);
        assertThat(items.get(0)).doesNotContainKey("content");
        assertThat(items.get(0).get("name")).isEqualTo("spider");
    }
}
