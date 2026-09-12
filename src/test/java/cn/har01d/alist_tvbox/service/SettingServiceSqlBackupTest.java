package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.auth.TokenFilter;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.backup.DatabaseBackupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SQL dump must include FLYWAY_SCHEMA_HISTORY: a database restored from a dump without it
 * has no migration history, and Flyway then baselines the non-empty schema at version 1 and
 * replays V2+ on the already-evolved structure, crashing startup. (The zip entry itself must be
 * named {@code script.sql} for H2 RunScript COMPRESSION ZIP; that part is fixed in the export
 * code and verified by the container-level end-to-end check.)
 */
@ExtendWith(MockitoExtension.class)
class SettingServiceSqlBackupTest {
    @Mock JdbcTemplate jdbcTemplate;
    @Mock Environment environment;
    @Mock AppProperties appProperties;
    @Mock AListLocalService aListLocalService;
    @Mock TokenFilter tokenFilter;
    @Mock SettingRepository settingRepository;
    @Mock DriverAccountRepository driverAccountRepository;
    @Mock ObjectMapper objectMapper;
    @Mock GitHubProxyService gitHubProxyService;
    @Mock DatabaseBackupService databaseBackupService;

    private SettingService service;

    @BeforeEach
    void setUp() {
        service = new SettingService(jdbcTemplate, environment, appProperties,
                aListLocalService, tokenFilter, settingRepository, driverAccountRepository,
                objectMapper, gitHubProxyService, databaseBackupService);
        lenient().when(environment.matchesProfiles("mysql")).thenReturn(false);
    }

    @Test
    void sqlDumpStatementIncludesFlywayHistoryAndKeepsDoubanTablesExcluded() {
        when(jdbcTemplate.query(org.mockito.ArgumentMatchers.eq("SHOW TABLES"),
                org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class)))
            .thenReturn((List) List.of("FLYWAY_SCHEMA_HISTORY", "SETTING", "SITE", "MOVIE", "META", "ALIAS", "USER"));

        service.backupDatabase();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(sql.capture());
        String script = sql.getValue();
        assertTrue(script.startsWith("SCRIPT TO "), () -> "unexpected statement: " + script);
        assertTrue(script.contains("FLYWAY_SCHEMA_HISTORY"), () -> "history table must be dumped: " + script);
        assertTrue(script.contains("SETTING"), () -> script);
        assertFalse(script.matches("(?s).*\\bMOVIE\\b.*"), () -> "douban bulk tables must stay excluded: " + script);
        assertFalse(script.matches("(?s).*\\bMETA\\b.*"), () -> script);
        assertFalse(script.matches("(?s).*\\bALIAS\\b.*"), () -> script);
    }
}
