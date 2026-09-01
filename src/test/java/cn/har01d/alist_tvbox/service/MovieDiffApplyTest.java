package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.AliasRepository;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.entity.MovieRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 豆瓣 diff 逐文件执行记录(movie_diff)的语义:无记录文件执行并记 SUCCESS、失败整文件
 * 重放至多 3 次、SUCCESS 不再重放、失败不推进 movie_version。
 */
@ExtendWith(MockitoExtension.class)
class MovieDiffApplyTest {

    @TempDir
    Path dataDir;

    @Mock
    private MetaRepository metaRepository;
    @Mock
    private MovieRepository movieRepository;
    @Mock
    private AliasRepository aliasRepository;
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private Environment environment;
    @Mock
    private RestTemplateBuilder builder;

    private DoubanService service;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("atv.data.dir", dataDir.toString());
        Files.createDirectories(dataDir.resolve("atv").resolve("sql"));
        Files.createDirectories(dataDir.resolve("atv").resolve("json"));
        RestTemplateBuilder chained = mock(RestTemplateBuilder.class);
        when(builder.defaultHeader(anyString(), anyString())).thenReturn(chained);
        when(chained.defaultHeader(anyString(), anyString())).thenReturn(chained);
        when(chained.build()).thenReturn(null);
        service = new DoubanService(mock(cn.har01d.alist_tvbox.config.AppProperties.class), metaRepository,
                movieRepository, aliasRepository, settingRepository, mock(SiteService.class),
                mock(TaskService.class), mock(FileDownloader.class),
                builder, jdbcTemplate, environment);
        // movie_diff 无记录 → attempts null(执行),queryForObject 不会被调用到 SUCCESS 分支
        org.mockito.Mockito.lenient().when(jdbcTemplate.queryForList(anyString(), eq(Integer.class), anyString()))
                .thenReturn(Collections.emptyList());
        // 瞬态重试的退避清零,测试不被 sleep 拖慢
        java.lang.reflect.Field backoff = DoubanService.class.getDeclaredField("diffRetryBackoffMs");
        backoff.setAccessible(true);
        backoff.setLong(service, 0L);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("atv.data.dir");
    }

    private void writeSql(String name, String content) throws Exception {
        Files.writeString(dataDir.resolve("atv").resolve("sql").resolve(name), content);
    }

    @Test
    void appliesPendingFilesAndStampsVersion() throws Exception {
        writeSql("1000.1.sql", "DELETE FROM x;\nINSERT INTO x VALUES(1);\n");
        writeSql("1001.2.sql", "DELETE FROM y;\n");

        java.lang.reflect.Method method = DoubanService.class.getDeclaredMethod("applyPendingDiffFiles");
        method.setAccessible(true);
        method.invoke(service);

        // 两个文件各执行一轮成功:每条语句一次 execute,版本推进到最后一个文件
        verify(jdbcTemplate, times(3)).execute(anyString());
        verify(settingRepository).save(org.mockito.ArgumentMatchers.argThat(s ->
                "movie_version".equals(s.getName()) && "1001.2".equals(s.getValue())));
        // 每文件一条 SUCCESS 记录
        verify(jdbcTemplate, times(2)).update(eq("DELETE FROM movie_diff WHERE version = ?"), anyString());
        verify(jdbcTemplate).update(eq("INSERT INTO movie_diff (version, status, statements, failed, attempts, updated_time) "
                + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"),
                eq("1000.1"), eq("SUCCESS"), eq(2), eq(0), eq(1));
        verify(jdbcTemplate).update(eq("INSERT INTO movie_diff (version, status, statements, failed, attempts, updated_time) "
                + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"),
                eq("1001.2"), eq("SUCCESS"), eq(1), eq(0), eq(1));
    }

    @Test
    void retriesFailedFileUpToLimitWithoutStampingVersion() throws Exception {
        writeSql("1002.3.sql", "BAD STATEMENT;\n");
        org.mockito.Mockito.doThrow(new RuntimeException("syntax")).when(jdbcTemplate).execute(anyString());

        java.lang.reflect.Method method = DoubanService.class.getDeclaredMethod("applyPendingDiffFiles");
        method.setAccessible(true);
        method.invoke(service);

        // 3 次尝试 × 1 条语句
        verify(jdbcTemplate, times(3)).execute(anyString());
        verify(jdbcTemplate).update(eq("INSERT INTO movie_diff (version, status, statements, failed, attempts, updated_time) "
                + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"),
                eq("1002.3"), eq("FAILED"), eq(0), eq(1), eq(3));
        verify(settingRepository, never()).save(org.mockito.ArgumentMatchers.any(Setting.class));
    }

    @Test
    void skipsSuccessfulFiles() throws Exception {
        writeSql("1003.4.sql", "DELETE FROM x;\n");
        when(jdbcTemplate.queryForList(anyString(), eq(Integer.class), anyString()))
                .thenReturn(List.of(1));                       // attempts=1
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), anyString()))
                .thenReturn("SUCCESS");                        // 已成功 → 跳过

        java.lang.reflect.Method method = DoubanService.class.getDeclaredMethod("applyPendingDiffFiles");
        method.setAccessible(true);
        method.invoke(service);

        verify(jdbcTemplate, never()).execute(anyString());
        verify(settingRepository, never()).save(org.mockito.ArgumentMatchers.any(Setting.class));
    }

    @Test
    void prefersJsonAndAppliesViaRepositories() throws Exception {
        writeJson("1004.5.json", """
                {"movieUpserts":[{"id":36406417,"name":"师兄太稳健","year":2026,"actors":"敖瑞鹏","dbScore":""}],
                 "movieDeletes":[111],"metaUpserts":[{"id":9,"path":"/p","name":"n","movieId":36406417}],
                 "metaDeletes":[8]}
                """);

        java.lang.reflect.Method method = DoubanService.class.getDeclaredMethod("applyPendingDiffFiles");
        method.setAccessible(true);
        method.invoke(service);

        // 同版本无 sql,JSON 直接走 JPA repo:saveAll + deleteAllById,MOVIE 行完整落字段
        verify(jdbcTemplate, never()).execute(anyString());
        org.mockito.ArgumentCaptor<cn.har01d.alist_tvbox.entity.Movie> movie =
                org.mockito.ArgumentCaptor.forClass(cn.har01d.alist_tvbox.entity.Movie.class);
        verify(movieRepository, org.mockito.Mockito.times(1)).saveAll(
                org.mockito.ArgumentMatchers.<java.util.List<cn.har01d.alist_tvbox.entity.Movie>>argThat(
                        list -> list.size() == 1 && list.get(0).getId() == 36406417
                                && "师兄太稳健".equals(list.get(0).getName()) && Integer.valueOf(2026).equals(list.get(0).getYear())));
        verify(movieRepository).deleteAllById(List.of(111));
        verify(metaRepository).deleteAllById(List.of(8));
        verify(settingRepository).save(org.mockito.ArgumentMatchers.argThat(s2 ->
                "movie_version".equals(s2.getName()) && "1004.5".equals(s2.getValue())));
        verify(jdbcTemplate).update(eq("INSERT INTO movie_diff (version, status, statements, failed, attempts, updated_time) "
                + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"),
                eq("1004.5"), eq("SUCCESS"), eq(4), eq(0), eq(1));
    }

    @Test
    void jsonUpsertUpdatesExistingMetaNatively() throws Exception {
        // 2 参 queryForList 是 upsert 的存在性探测:movie 行已存在、meta 行已存在
        when(jdbcTemplate.queryForList(anyString(), eq(Integer.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            return sql.contains("FROM movie") ? List.of(36406417) : List.of(9);
        });
        writeJson("1005.6.json", """
                {"movieUpserts":[{"id":36406417,"name":"师兄太稳健","year":2026}],
                 "metaUpserts":[{"id":9,"path":"/p","name":"n","movieId":36406417,"time":1788264073587}]}
                """);

        java.lang.reflect.Method method = DoubanService.class.getDeclaredMethod("applyPendingDiffFiles");
        method.setAccessible(true);
        method.invoke(service);

        // 已存在 meta 行原生 UPDATE 全列:禁用行也被直接覆盖,不再依赖 JPA merge 的可见性
        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.argThat(
                        (org.mockito.ArgumentMatcher<String>) sql -> sql.startsWith("UPDATE meta SET path = ?")),
                eq("/p"), eq("n"), eq(null), eq(null), eq(36406417),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), eq(false),
                org.mockito.ArgumentMatchers.isA(java.sql.Timestamp.class), eq(9));
        verify(jdbcTemplate, never()).update(org.mockito.ArgumentMatchers.argThat(
                        (org.mockito.ArgumentMatcher<String>) sql -> sql.startsWith("INSERT INTO meta")),
                org.mockito.ArgumentMatchers.<Object[]>any());
        verify(jdbcTemplate, never()).update(org.mockito.ArgumentMatchers.argThat(
                        (org.mockito.ArgumentMatcher<String>) sql -> sql.startsWith("INSERT INTO movie")),
                org.mockito.ArgumentMatchers.<Object[]>any());
        verify(movieRepository).saveAll(org.mockito.ArgumentMatchers.anyList());
        // META 不再走 JPA(merge 解析关联代理会在外键悬空时炸 ObjectNotFoundException)
        verify(metaRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
        verify(settingRepository).save(org.mockito.ArgumentMatchers.argThat(s2 ->
                "movie_version".equals(s2.getName()) && "1005.6".equals(s2.getValue())));
    }

    @Test
    void jsonUpsertToleratesDanglingMovieReference() throws Exception {
        // 线上 1341.1923 形态:基线重灌抹掉 movie 行后 meta 引用悬空 —— 全原生 upsert 照写不炸,版本照常推进
        when(jdbcTemplate.queryForList(anyString(), eq(Integer.class))).thenReturn(Collections.emptyList());
        writeJson("1013.4.json", """
                {"metaUpserts":[{"id":96234,"path":"/每日更新/电视剧/TVB Viu/日落下的彩虹","name":"日落下的彩虹","movieId":37816238}]}
                """);

        java.lang.reflect.Method method = DoubanService.class.getDeclaredMethod("applyPendingDiffFiles");
        method.setAccessible(true);
        method.invoke(service);

        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.argThat(
                        (org.mockito.ArgumentMatcher<String>) sql -> sql.startsWith("INSERT INTO meta (id, path, name")),
                eq(96234), eq("/每日更新/电视剧/TVB Viu/日落下的彩虹"), eq("日落下的彩虹"),
                eq(null), eq(null), eq(37816238),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), eq(false),
                org.mockito.ArgumentMatchers.isA(java.sql.Timestamp.class));
        verify(settingRepository).save(org.mockito.ArgumentMatchers.argThat(s2 ->
                "movie_version".equals(s2.getName()) && "1013.4".equals(s2.getValue())));
    }

    @Test
    void jsonUpsertInsertsMissingMovieRows() throws Exception {
        when(jdbcTemplate.queryForList(anyString(), eq(Integer.class))).thenReturn(Collections.emptyList());
        writeJson("1006.7.json", """
                {"movieUpserts":[{"id":35811064,"name":"欢迎来龙餐馆","year":2025,"genre":"剧情"}]}
                """);

        java.lang.reflect.Method method = DoubanService.class.getDeclaredMethod("applyPendingDiffFiles");
        method.setAccessible(true);
        method.invoke(service);

        // 新片行缺失 → 原生 INSERT 补行,saveAll merge 不再抛 StaleObjectStateException
        verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.argThat(
                        (org.mockito.ArgumentMatcher<String>) sql -> sql.startsWith("INSERT INTO movie (id, name, genre")),
                eq(35811064), eq("欢迎来龙餐馆"), eq("剧情"), eq(null), eq(null), eq(null),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), eq(2025));
        verify(movieRepository).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void resetsFailedDiffAttemptsOnStartup() throws Exception {
        java.lang.reflect.Method method = DoubanService.class.getDeclaredMethod("setup");
        method.setAccessible(true);
        method.invoke(service);

        // 3/3 卡死的版本随重启获得新的重试预算,修复后的构建才能补放
        verify(jdbcTemplate).update("UPDATE movie_diff SET attempts = 0 WHERE status = ?", "FAILED");
    }

    @Test
    void deterministicJsonFailureSkipsRetry() throws Exception {
        // 约束冲突 = 确定性失败:一次即弃,不连炸 3 次(movie 仍走 JPA saveAll,由此注入失败)
        when(jdbcTemplate.queryForList(anyString(), eq(Integer.class))).thenReturn(Collections.emptyList());
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("unique path"))
                .when(movieRepository).saveAll(org.mockito.ArgumentMatchers.anyList());
        writeJson("1010.1.json", """
                {"movieUpserts":[{"id":36406417,"name":"师兄太稳健","year":2026}],
                 "metaUpserts":[{"id":9,"path":"/p","name":"n"}]}
                """);

        java.lang.reflect.Method method = DoubanService.class.getDeclaredMethod("applyPendingDiffFiles");
        method.setAccessible(true);
        method.invoke(service);

        verify(movieRepository, times(1)).saveAll(org.mockito.ArgumentMatchers.anyList());
        verify(jdbcTemplate).update(eq("INSERT INTO movie_diff (version, status, statements, failed, attempts, updated_time) "
                + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"),
                eq("1010.1"), eq("FAILED"), eq(0), eq(1), eq(1));
        verify(settingRepository, never()).save(org.mockito.ArgumentMatchers.any(Setting.class));
    }

    @Test
    void malformedJsonFailsFastWithoutRetry() throws Exception {
        writeJson("1011.2.json", "{not json");

        java.lang.reflect.Method method = DoubanService.class.getDeclaredMethod("applyPendingDiffFiles");
        method.setAccessible(true);
        method.invoke(service);

        // JSON 解析错误确定性失败:1 次尝试,版本不推进
        verify(metaRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
        verify(jdbcTemplate).update(eq("INSERT INTO movie_diff (version, status, statements, failed, attempts, updated_time) "
                + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"),
                eq("1011.2"), eq("FAILED"), eq(0), eq(1), eq(1));
        verify(settingRepository, never()).save(org.mockito.ArgumentMatchers.any(Setting.class));
    }

    @Test
    void deterministicSqlLineFailureSkipsRetry() throws Exception {
        writeSql("1012.3.sql", "INSERT INTO x VALUES(1);\n");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"))
                .when(jdbcTemplate).execute(anyString());

        java.lang.reflect.Method method = DoubanService.class.getDeclaredMethod("applyPendingDiffFiles");
        method.setAccessible(true);
        method.invoke(service);

        // 确定性 SQL 失败整文件只跑一轮(对比:未分类异常 retriesFailedFileUpToLimit 跑满 3 轮)
        verify(jdbcTemplate, times(1)).execute(anyString());
        verify(jdbcTemplate).update(eq("INSERT INTO movie_diff (version, status, statements, failed, attempts, updated_time) "
                + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"),
                eq("1012.3"), eq("FAILED"), eq(0), eq(1), eq(1));
    }

    private void writeJson(String name, String content) throws Exception {
        Files.writeString(dataDir.resolve("atv").resolve("json").resolve(name), content);
    }

    @Test
    void neutralizesDestructiveBaseDataScriptAndClearsDiffLog() throws Exception {
        Files.createDirectories(dataDir.resolve("atv"));
        Files.writeString(dataDir.resolve("atv").resolve("data.sql"),
                "DROP TABLE IF EXISTS META;\nDROP TABLE IF EXISTS MOVIE;\nCREATE TABLE ...");
        when(movieRepository.count()).thenReturn(59008L);

        java.lang.reflect.Method method = DoubanService.class.getDeclaredMethod("neutralizeBaseDataScript");
        method.setAccessible(true);
        method.invoke(service);

        org.junit.jupiter.api.Assertions.assertEquals("SELECT 1;",
                Files.readString(dataDir.resolve("atv").resolve("data.sql")));
        verify(jdbcTemplate).update("DELETE FROM movie_diff");
    }

    @Test
    void keepsNeutralizedBaseDataScriptUntouched() throws Exception {
        Files.createDirectories(dataDir.resolve("atv"));
        Files.writeString(dataDir.resolve("atv").resolve("data.sql"), "SELECT 1;");
        when(movieRepository.count()).thenReturn(59008L);

        java.lang.reflect.Method method = DoubanService.class.getDeclaredMethod("neutralizeBaseDataScript");
        method.setAccessible(true);
        method.invoke(service);

        org.junit.jupiter.api.Assertions.assertEquals("SELECT 1;",
                Files.readString(dataDir.resolve("atv").resolve("data.sql")));
        verify(jdbcTemplate, never()).update("DELETE FROM movie_diff");
    }
}
