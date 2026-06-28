package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.auth.TokenFilter;
import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.service.backup.DatabaseBackupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettingServiceBasicAuthTest {
    @Mock JdbcTemplate jdbcTemplate;
    @Mock Environment environment;
    @Mock AppProperties appProperties;
    @Mock TmdbService tmdbService;
    @Mock AListLocalService aListLocalService;
    @Mock TokenFilter tokenFilter;
    @Mock SettingRepository settingRepository;
    @Mock DriverAccountRepository driverAccountRepository;
    @Mock ObjectMapper objectMapper;
    @Mock GitHubProxyService gitHubProxyService;
    @Mock DatabaseBackupService databaseBackupService;

    private SettingService service;
    private final Map<String, String> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new SettingService(jdbcTemplate, environment, appProperties, tmdbService,
                aListLocalService, tokenFilter, settingRepository, driverAccountRepository,
                objectMapper, gitHubProxyService, databaseBackupService);
        store.clear();
        lenient().when(settingRepository.findById(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            return store.containsKey(k) ? Optional.of(new Setting(k, store.get(k))) : Optional.empty();
        });
        lenient().when(settingRepository.save(any(Setting.class))).thenAnswer(inv -> {
            Setting s = inv.getArgument(0);
            store.put(s.getName(), s.getValue());
            return s;
        });
    }

    @Test
    void shouldGenerateAndPersistWhenMissing() {
        service.initBasicAuthCredentials();
        assertEquals(8, store.get(Constants.BASIC_AUTH_USERNAME).length());
        assertEquals(16, store.get(Constants.BASIC_AUTH_PASSWORD).length());
        verify(tokenFilter).setBasicAuthCredentials(contains("Basic "));
    }

    @Test
    void shouldBeIdempotentWhenPresent() {
        store.put(Constants.BASIC_AUTH_USERNAME, "existinguser");
        store.put(Constants.BASIC_AUTH_PASSWORD, "existingpass");
        service.initBasicAuthCredentials();
        assertEquals("existinguser", store.get(Constants.BASIC_AUTH_USERNAME));
        verify(settingRepository, never()).save(any(Setting.class));
        verify(tokenFilter).setBasicAuthCredentials("Basic ZXhpc3Rpbmd1c2VyOmV4aXN0aW5ncGFzcw==");
    }

    @Test
    void regenerateShouldProduceNewPair() {
        store.put(Constants.BASIC_AUTH_USERNAME, "olduser");
        store.put(Constants.BASIC_AUTH_PASSWORD, "oldpass");
        Map<String, String> result = service.regenerateBasicAuthCredentials();
        assertNotEquals("olduser", result.get("username"));
        assertEquals(result.get("username"), store.get(Constants.BASIC_AUTH_USERNAME));
        verify(tokenFilter).setBasicAuthCredentials(contains("Basic "));
    }

    @Test
    void getShouldReturnPersistedPair() {
        store.put(Constants.BASIC_AUTH_USERNAME, "u1");
        store.put(Constants.BASIC_AUTH_PASSWORD, "p1");
        Map<String, String> r = service.getBasicAuthCredentials();
        assertEquals("u1", r.get("username"));
        assertEquals("p1", r.get("password"));
    }
}
