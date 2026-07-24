package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Setting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.har01d.alist_tvbox.auth.TokenFilter;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.backup.DatabaseBackupService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettingServicePanSouConfigTest {
    @Mock JdbcTemplate jdbcTemplate;
    @Mock Environment environment;
    @Mock TmdbService tmdbService;
    @Mock AListLocalService aListLocalService;
    @Mock TokenFilter tokenFilter;
    @Mock SettingRepository settingRepository;
    @Mock DriverAccountRepository driverAccountRepository;
    @Mock ObjectMapper objectMapper;
    @Mock GitHubProxyService gitHubProxyService;
    @Mock DatabaseBackupService databaseBackupService;

    private SettingService service;
    private final AppProperties appProperties = new AppProperties();
    private final Map<String, String> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new SettingService(jdbcTemplate, environment, appProperties, tmdbService,
                aListLocalService, tokenFilter, settingRepository, driverAccountRepository,
                objectMapper, gitHubProxyService, databaseBackupService);
        lenient().when(settingRepository.findById(any())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            return store.containsKey(k) ? Optional.of(new Setting(k, store.get(k))) : Optional.empty();
        });
    }

    @Test
    void updateParsesAllFiveKeys() {
        when(settingRepository.save(any(Setting.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(new Setting("pan_sou_conc", "20"));
        assertEquals(20, appProperties.getPanSouConc());
        service.update(new Setting("pan_sou_refresh", "true"));
        assertTrue(appProperties.getPanSouRefresh());
        service.update(new Setting("pan_sou_res", "results"));
        assertEquals("results", appProperties.getPanSouRes());
        service.update(new Setting("pan_sou_filter_include", "1080, 4K"));
        assertEquals(java.util.List.of("1080", "4K"), appProperties.getPanSouFilterInclude());
        service.update(new Setting("pan_sou_filter_exclude", "枪版,广告"));
        assertEquals(java.util.List.of("枪版", "广告"), appProperties.getPanSouFilterExclude());
    }

    @Test
    void blankConcBecomesNull() {
        service.update(new Setting("pan_sou_conc", ""));
        assertNull(appProperties.getPanSouConc());
    }

    @Test
    void linkCheckTypesParsesCsv() {
        service.update(new Setting("pan_sou_link_check_types", "quark, baidu, 115"));
        assertEquals(java.util.List.of("quark", "baidu", "115"), appProperties.getPanSouLinkCheckTypes());
    }

    @Test
    void blankLinkCheckTypesBecomesNull() {
        service.update(new Setting("pan_sou_link_check_types", ""));
        assertNull(appProperties.getPanSouLinkCheckTypes());
    }
}
