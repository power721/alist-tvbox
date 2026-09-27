package cn.har01d.alist_tvbox.service.diagnostics;

import cn.har01d.alist_tvbox.dto.diagnostics.DiagnosticsReportDto;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionNotifyTaskRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.AListLocalService;
import cn.har01d.alist_tvbox.service.sitesearch.SearchSourceThrottle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiagnosticsServiceTest {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------ 脱敏

    @Test
    void sanitizeMasksCredentialQueryParams() {
        String input = "fetch https://example.com/api?token=abc123&sign=XYZ&foo=1 failed";
        String output = DiagnosticsService.sanitize(input);
        assertFalse(output.contains("abc123"));
        assertFalse(output.contains("XYZ"));
        assertTrue(output.contains("token=***"));
        assertTrue(output.contains("sign=***"));
        assertTrue(output.contains("foo=1"));
    }

    @Test
    void sanitizeMasksPasswordCookieAndBearer() {
        assertEquals("login password=*** ok", DiagnosticsService.sanitize("login password=hunter2 ok"));
        assertEquals("cookie=***", DiagnosticsService.sanitize("cookie=s%3Asession"));
        assertEquals("Authorization: Bearer ***", DiagnosticsService.sanitize("Authorization: Bearer eyJhbGciOi.abc.def"));
    }

    @Test
    void sanitizeKeepsOrdinaryText() {
        assertEquals("订阅 12 巡检完成", DiagnosticsService.sanitize("订阅 12 巡检完成"));
    }

    @Test
    void sanitizeStripsAnsiCodes() {
        assertEquals("ERROR something", DiagnosticsService.sanitize("\u001b[31mERROR\u001b[0m something"));
    }

    // ------------------------------------------------------------------ 等级/字段解析

    @Test
    void parseLevelSpringBootFormat() {
        assertEquals("ERROR", DiagnosticsService.parseLevel(
                "2026-09-13 20:01:02,123 ERROR 123 --- [scheduling-1] c.h.a.Service : boom"));
        assertEquals("INFO", DiagnosticsService.parseLevel(
                "2026-09-13 20:01:02,123  INFO 123 --- [http-nio-5244-exec-1] c.h.a.Other : fine"));
    }

    @Test
    void loggerRangeFindsLoggerAndMessage() {
        String line = "2026-09-13 20:01:02,123 ERROR 1 --- [task-1] cn.har01d.alist_tvbox.service.Foo : failed to check sub 12";
        int[] range = DiagnosticsService.loggerRange(line);
        assertEquals("cn.har01d.alist_tvbox.service.Foo", line.substring(range[0], range[1]));
        assertEquals("failed to check sub 12", line.substring(range[2]));
    }

    // ------------------------------------------------------------------ 聚合

    @Test
    void aggregateErrorsGroupsByNormalizedMessage() throws IOException {
        Path log = tempDir.resolve("app.log");
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            content.append("2026-09-13 20:01:0").append(i % 10).append(",123 INFO 1 --- [t] c.h.a.A : ok\n");
        }
        content.append("2026-09-13 20:02:00,123 ERROR 1 --- [t] cn.har01d.alist_tvbox.service.SubService : 检查订阅 12 失败\n");
        content.append("java.lang.RuntimeException: boom\n\tat cn.har01d.Foo.bar(Foo.java:10)\n");
        content.append("2026-09-13 20:03:00,123 ERROR 1 --- [t] cn.har01d.alist_tvbox.service.SubService : 检查订阅 34 失败\n");
        content.append("2026-09-13 20:04:00,123 ERROR 1 --- [t] cn.har01d.alist_tvbox.service.OtherService : 连接超时 https://pan.example.com/api?token=secret1\n");
        Files.write(log, content.toString().getBytes(StandardCharsets.UTF_8));

        List<String> top = DiagnosticsService.aggregateErrors(log, true, 5);
        assertEquals(2, top.size());
        assertTrue(top.get(0).startsWith("cn.har01d.alist_tvbox.service.SubService"));
        assertTrue(top.get(0).contains("(2次)"));
        // 数字折叠后两条同 key;URL 整体折叠成 URL(参数里的 token 随之消失)
        assertTrue(top.get(1).contains("URL"));
        assertFalse(top.get(1).contains("secret1"));
        assertFalse(top.get(1).contains("pan.example.com"));
    }

    @Test
    void aggregateErrorsAlistFormatMatchesAnyErrorLine() throws IOException {
        Path log = tempDir.resolve("log.log");
        Files.write(log, ("INFO[2026-09-13 20:00:00] ok\n"
                + "ERRO[2026-09-13 20:01:00] failed to list /drv1: context deadline exceeded\n"
                + "ERRO[2026-09-13 20:02:00] failed to list /drv2: context deadline exceeded\n").getBytes(StandardCharsets.UTF_8));
        List<String> top = DiagnosticsService.aggregateErrors(log, false, 3);
        assertEquals(1, top.size());
        // /drv1 与 /drv2 数字折叠后同 key 聚类
        assertTrue(top.get(0).contains("(2次)"));
        assertTrue(top.get(0).contains("context deadline exceeded"));
    }

    @Test
    void aggregateErrorsMissingFileReturnsEmpty() throws IOException {
        assertEquals(List.of(), DiagnosticsService.aggregateErrors(tempDir.resolve("nope.log"), true, 5));
    }

    @Test
    void tailLinesSkipsPartialFirstLine() throws IOException {
        Path file = tempDir.resolve("big.log");
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            content.append("line-").append(i).append("-0123456789\n");
        }
        Files.write(file, content.toString().getBytes(StandardCharsets.UTF_8));
        List<String> lines = DiagnosticsService.tailLines(file, 2000);
        // 尾部窗口必然丢弃首行(可能被截断),最后一条完整保留
        assertEquals("line-999-0123456789", lines.get(lines.size() - 1));
        assertFalse(lines.get(0).startsWith("line-0-"));
    }

    // ------------------------------------------------------------------ 杂项

    @Test
    void truthyHandlesMultipleColumnShapes() {
        assertTrue(DiagnosticsService.truthy(true));
        assertTrue(DiagnosticsService.truthy(Boolean.TRUE));
        assertTrue(DiagnosticsService.truthy(1));
        assertTrue(DiagnosticsService.truthy("true"));
        assertFalse(DiagnosticsService.truthy(false));
        assertFalse(DiagnosticsService.truthy(0));
        assertFalse(DiagnosticsService.truthy(null));
        assertFalse(DiagnosticsService.truthy("work"));
    }

    @Test
    void normalizeMessageFoldsNumbersUrlsAndUuids() {
        assertEquals("第N集 URL 失败", DiagnosticsService.normalizeMessage("第5集 https://a.b/c?x=1 失败"));
        assertTrue(DiagnosticsService.normalizeMessage("id 550e8800-e29b-41d4-a716-446655440000 gone")
                .contains("UUID"));
    }

    @Test
    void formatDurationRendersDaysHoursMinutes() {
        assertEquals("2天3小时", DiagnosticsService.formatDuration(((2 * 24 + 3) * 3600 + 30 * 60) * 1000L));
        assertEquals("5小时10分", DiagnosticsService.formatDuration((5 * 3600 + 10 * 60) * 1000L));
        assertEquals("42分", DiagnosticsService.formatDuration(42 * 60 * 1000L));
    }

    // ------------------------------------------------------------------ 整报告烟囱

    /**
     * 用真实 Flyway + 内存 H2 锁住数据库区块的 API 契约:Flyway 11 在 H2 建的历史表是带引号
     * 小写标识符(裸 SQL 查询即 BadSqlGrammar,线上实测);current() 停在最后成功版本,
     * applied() 含 FAILED 行。塞失败行走带引号小写 INSERT,同时验证该存储形态。
     */
    @Test
    void flywayInfoApiCountsFailedMigrationsOnH2() throws Exception {
        org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:diag-flyway-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(dataSource)
                // 与 application.yaml 同约束:只扫单 vendor 目录,避免 mysql/postgresql 两套 V1 冲突
                .locations("classpath:db/migration/h2")
                .baselineVersion("2").load();
        flyway.baseline();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("insert into \"flyway_schema_history\" (\"installed_rank\",\"version\","
                    + "\"description\",\"type\",\"script\",\"checksum\",\"installed_by\",\"installed_on\","
                    + "\"execution_time\",\"success\") values (2,'3','fake-failed','JDBC','x',0,'sa',"
                    + "CURRENT_TIMESTAMP,10,false)");
        }

        var info = flyway.info();
        // 锁住两个口径:info.current() 会把失败行(版本 3)也算进去;
        // 服务展示口径=最后成功应用的版本(=baseline 2,失败行不改变 schema 实际形态)
        assertEquals("3", info.current().getVersion().getVersion());
        assertEquals("2", DiagnosticsService.latestSuccessfulVersion(info.applied()));
        long failed = java.util.Arrays.stream(info.applied())
                .filter(m -> m.getState().isFailed()).count();
        assertEquals(1, failed);
    }

    /**
     * mock 全依赖走 buildReport:数据库区块 getDataSource() 为 null 必炸 → 验证区块容错
     * (降级为 finding 不拖垮报告)、网盘/搜索源配置区块渲染,以及凭证值不进报告。
     */
    @Test
    void buildReportDegradesFailedSectionsAndRendersText() {
        SettingRepository settingRepository = mock(SettingRepository.class);
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.springframework.jdbc.core.JdbcTemplate alistJdbcTemplate = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        AListLocalService aListLocalService = mock(AListLocalService.class);
        MediaSubscriptionRepository subscriptionRepository = mock(MediaSubscriptionRepository.class);
        MediaSubscriptionNotifyTaskRepository notifyTaskRepository = mock(MediaSubscriptionNotifyTaskRepository.class);
        DriverAccountRepository driverAccountRepository = mock(DriverAccountRepository.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<org.flywaydb.core.Flyway> flywayProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        java.util.Map<String, String> settings = new java.util.HashMap<>();
        settings.put("quark_to_123", "true");
        settings.put("use_quark_tv", "true");
        settings.put("quark_multi_account_proxy", "true");
        settings.put("delete_delay_time", "300");
        settings.put("local_proxy_config", "{\"QUARK\":{\"enabled\":true,\"concurrency\":5},\"UC\":{\"enabled\":false}}");
        settings.put("offline_download_config", "{\"enabled\":true,\"driverType\":\"THUNDER\",\"accountId\":3}");
        settings.put("panlian_accounts", "[{\"username\":\"SECRETUSER\",\"password\":\"SECRETPASS\"},{\"username\":\"b\"}]");
        settings.put("enabled_token", "true");
        settings.put("tmdb_api_key", "SECRETTMDBKEY");
        settings.put("tmdb_api_host", "tmdb.example.worker.dev");
        settings.put("movie_version", "1341");
        settings.put("open_token_url", "https://ali.har01d.cn/access_token");
        settings.put("index115.share_code", "abc123DEF");
        settings.put("msub_pool_filter", "{\"minEpisodeSizeMb\":2000,\"maxEpisodeSizeMb\":0,"
                + "\"includeKeywords\":[\"国语\",\"4K\"],\"excludeKeywords\":[]}");
        when(settingRepository.findById(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = settings.get(key);
            return value == null ? Optional.<cn.har01d.alist_tvbox.entity.Setting>empty()
                    : Optional.of(new cn.har01d.alist_tvbox.entity.Setting(key, value));
        });
        when(settingRepository.findAll()).thenReturn(List.of());
        when(alistJdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        when(driverAccountRepository.findAll()).thenReturn(List.of());
        when(subscriptionRepository.countByStatus(anyString())).thenReturn(0L);
        cn.har01d.alist_tvbox.entity.MediaSubscription sized =
                new cn.har01d.alist_tvbox.entity.MediaSubscription();
        sized.setFilterConfig("{\"maxEpisodeSizeMb\":3000}");
        when(subscriptionRepository.findAll()).thenReturn(List.of(sized));
        when(notifyTaskRepository.countByStatus(anyString())).thenReturn(0L);

        DiagnosticsService service = new DiagnosticsService(settingRepository, jdbcTemplate, alistJdbcTemplate,
                aListLocalService, subscriptionRepository, notifyTaskRepository, driverAccountRepository,
                new SearchSourceThrottle(), flywayProvider, new cn.har01d.alist_tvbox.config.AppProperties());
        DiagnosticsReportDto report = service.buildReport();

        assertEquals(8, report.getSections().size());
        String text = report.getText();
        for (String name : new String[]{"[系统]", "[数据库]", "[存储]", "[网盘]", "[配置]", "[追剧]", "[搜索源]", "[日志]"}) {
            assertTrue(text.contains(name), "missing section " + name);
        }
        // 配置区块:安全/订阅/调试开关 + TMDB 状态 + 数据版本(Key 只报已配置)
        assertTrue(text.contains("安全订阅: 开"));
        assertTrue(text.contains("TMDB Key: 已配置"));
        assertTrue(text.contains("TMDB 代理: tmdb.example.worker.dev"));
        assertTrue(text.contains("豆瓣数据版本: 1341"));
        assertTrue(text.contains("开放Token认证URL: https://ali.har01d.cn/access_token"));
        assertTrue(text.contains("115索引版本: abc123DEF"));
        assertFalse(text.contains("SECRETTMDBKEY"), "TMDB Key 值绝不进报告");
        // 网盘区块:开关/延时/离线下载/代理驱动名(UI 原名口径)
        assertTrue(text.contains("夸克→123"));
        assertTrue(text.contains("夸克/UC分享用TV帐号: 开"));
        assertTrue(text.contains("夸父逐日: 开"));
        assertTrue(text.contains("文件删除延时: 300 秒"));
        assertTrue(text.contains("离线下载: 开(迅雷)"));
        assertTrue(text.contains("本地代理: QUARK"));
        assertFalse(text.contains("\"UC\""), "禁用驱动不应出现在代理摘要");
        // 搜索源区块:源开关 + 凭证计数
        assertTrue(text.contains("夸父 开"));
        assertTrue(text.contains("盘链 2 个账号"));
        assertTrue(text.contains("观影 未配置"));
        // 追剧区块:资源筛选口径(全局下限/关键词计数/订阅级覆盖;关键词具体值不进报告)
        assertTrue(text.contains("资源筛选"));
        assertTrue(text.contains("下限 2000 MB"));
        assertTrue(text.contains("包含词 2/排除词 0"));
        assertTrue(text.contains("订阅级体积覆盖 1 个"));
        assertFalse(text.contains("国语"), "包含词的具体值不进报告");
        assertTrue(text.contains("单集体积下限 2000 MB"), "激进下限要有可操作告警");
        // 凭证值绝不进报告
        assertFalse(text.contains("SECRETUSER"));
        assertFalse(text.contains("SECRETPASS"));
        // 数据库区块炸了 → finding 出现,但报告其余部分照常
        assertTrue(report.getErrorCount() >= 1);
        assertTrue(text.contains("[数据库] 状态: 查询失败:"));
        assertTrue(text.contains("告警"));
    }


    /** 离线清理链路状态入报告:自动清理参数 + 行分布 + 上次清理时间(候选恒空/调度错过的排障锚点)。 */
    @Test
    void buildReportRendersOfflineCleanupStatus() {
        SettingRepository settingRepository = mock(SettingRepository.class);
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.springframework.jdbc.core.JdbcTemplate alistJdbcTemplate = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        AListLocalService aListLocalService = mock(AListLocalService.class);
        MediaSubscriptionRepository subscriptionRepository = mock(MediaSubscriptionRepository.class);
        MediaSubscriptionNotifyTaskRepository notifyTaskRepository = mock(MediaSubscriptionNotifyTaskRepository.class);
        DriverAccountRepository driverAccountRepository = mock(DriverAccountRepository.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<org.flywaydb.core.Flyway> flywayProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        java.util.Map<String, String> settings = new java.util.HashMap<>();
        settings.put("offline_download_config",
                "{\"enabled\":true,\"driverType\":\"PAN115\",\"accountId\":9,\"autoDelete\":true,\"ttlHours\":1}");
        when(settingRepository.findById(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = settings.get(key);
            return value == null ? Optional.<cn.har01d.alist_tvbox.entity.Setting>empty()
                    : Optional.of(new cn.har01d.alist_tvbox.entity.Setting(key, value));
        });
        when(settingRepository.findAll()).thenReturn(List.of());
        when(subscriptionRepository.countByStatus(anyString())).thenReturn(0L);
        when(subscriptionRepository.findAll()).thenReturn(List.of());
        when(notifyTaskRepository.countByStatus(anyString())).thenReturn(0L);
        when(driverAccountRepository.findAll()).thenReturn(List.of());

        // jdbcTemplate 未打桩:行分布两查询空跑 → 已清 0/待清理 0;无 marker → 上次清理: 从未
        DiagnosticsService service = new DiagnosticsService(settingRepository, jdbcTemplate, alistJdbcTemplate,
                aListLocalService, subscriptionRepository, notifyTaskRepository, driverAccountRepository,
                new SearchSourceThrottle(), flywayProvider, new cn.har01d.alist_tvbox.config.AppProperties());
        DiagnosticsReportDto report = service.buildReport();
        String text = report.getText();
        assertTrue(text.contains("自动清理: 开(通用保留 1h)"), text);
        assertTrue(text.contains("已清 0, 待清理 0"), text);
        assertTrue(text.contains("上次清理: 从未"), text);
    }
}
