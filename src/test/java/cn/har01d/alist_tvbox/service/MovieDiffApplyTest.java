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
        RestTemplateBuilder chained = mock(RestTemplateBuilder.class);
        when(builder.defaultHeader(anyString(), anyString())).thenReturn(chained);
        when(chained.defaultHeader(anyString(), anyString())).thenReturn(chained);
        when(chained.build()).thenReturn(null);
        service = new DoubanService(mock(cn.har01d.alist_tvbox.config.AppProperties.class), metaRepository,
                movieRepository, aliasRepository, settingRepository, mock(SiteService.class),
                mock(TaskService.class), mock(FileDownloader.class), builder,
                jdbcTemplate, environment);
        // movie_diff 无记录 → attempts null(执行),queryForObject 不会被调用到 SUCCESS 分支
        org.mockito.Mockito.lenient().when(jdbcTemplate.queryForList(anyString(), eq(Integer.class), anyString()))
                .thenReturn(Collections.emptyList());
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

        java.lang.reflect.Method method = DoubanService.class.getDeclaredMethod("applyPendingSqlFiles");
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

        java.lang.reflect.Method method = DoubanService.class.getDeclaredMethod("applyPendingSqlFiles");
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

        java.lang.reflect.Method method = DoubanService.class.getDeclaredMethod("applyPendingSqlFiles");
        method.setAccessible(true);
        method.invoke(service);

        verify(jdbcTemplate, never()).execute(anyString());
        verify(settingRepository, never()).save(org.mockito.ArgumentMatchers.any(Setting.class));
    }
}
