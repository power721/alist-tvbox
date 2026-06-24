package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.MovieRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MovieDataSeederTest {

    // \674e\4e00\6850 == 李一桐 — proves the seeder routes data through H2SqlConverter
    // (unicode decode) rather than feeding raw H2 dialect to MySQL/PG.
    private static final String MOVIE_LINE =
            "INSERT INTO \"PUBLIC\".\"MOVIE\" VALUES(1, U&'\\674e\\4e00\\6850', U&'\\4e2d\\56fd', "
                    + "'http://x', '', '', '', '', '', '', '', 2000);";

    @Mock MovieRepository movieRepository;
    @Mock SettingRepository settingRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock Environment environment;

    private Path dataDir;
    private String prevDataDir;

    @BeforeEach
    void setUp() throws Exception {
        dataDir = Files.createTempDirectory("atv-seed-test");
        prevDataDir = System.getProperty("atv.data.dir");
        System.setProperty("atv.data.dir", dataDir.toString());
    }

    @AfterEach
    void tearDown() {
        if (prevDataDir == null) {
            System.clearProperty("atv.data.dir");
        } else {
            System.setProperty("atv.data.dir", prevDataDir);
        }
    }

    private void writeFile(String name, String content) throws Exception {
        Path p = dataDir.resolve("atv").resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private MovieDataSeeder newSeeder() {
        return new MovieDataSeeder(environment, jdbcTemplate, movieRepository, settingRepository);
    }

    @Test
    void skipsH2Dialect() {
        when(environment.getProperty("spring.datasource.jdbc-url")).thenReturn("jdbc:h2:file:/data/atv");
        newSeeder().run(new DefaultApplicationArguments());
        verifyNoInteractions(movieRepository, jdbcTemplate, settingRepository);
    }

    @Test
    void skipsWhenMoviesAlreadyPresent() {
        when(environment.getProperty("spring.datasource.jdbc-url")).thenReturn("jdbc:mysql://localhost:3306/atv");
        when(movieRepository.count()).thenReturn(50000L);
        newSeeder().run(new DefaultApplicationArguments());
        verify(jdbcTemplate, never()).batchUpdate(any(String[].class));
        verify(settingRepository, never()).save(any(Setting.class));
    }

    @Test
    void skipsWhenDataSqlMissing() {
        when(environment.getProperty("spring.datasource.jdbc-url")).thenReturn("jdbc:mysql://localhost:3306/atv");
        when(movieRepository.count()).thenReturn(0L);
        newSeeder().run(new DefaultApplicationArguments());
        verify(jdbcTemplate, never()).batchUpdate(any(String[].class));
    }

    @Test
    void seedsMysql_convertsH2Dialect_andWritesVersionFromFile() throws Exception {
        when(environment.getProperty("spring.datasource.jdbc-url")).thenReturn("jdbc:mysql://localhost:3306/atv");
        when(movieRepository.count()).thenReturn(0L);
        when(jdbcTemplate.batchUpdate(any(String[].class))).thenReturn(new int[]{1});
        writeFile("data.sql", "-- H2 export\n" + MOVIE_LINE + "\nDROP TABLE IF EXISTS META;\n");
        writeFile("movie_version", "1.23");

        newSeeder().run(new DefaultApplicationArguments());

        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(captor.capture());
        List<String> sqls = Arrays.stream(captor.getAllValues().toArray(new String[0][]))
                .flatMap(Arrays::stream).toList();
        assertEquals(1, sqls.size(), "DDL/noise must be skipped, only the INSERT converts: " + sqls);
        String sql = sqls.get(0);
        assertTrue(sql.startsWith("INSERT INTO movie (id,actors,country,"), sql);
        assertTrue(sql.contains("李一桐"), "unicode escapes must be decoded: " + sql);
        assertFalse(sql.contains("PUBLIC"), sql);
        assertFalse(sql.contains("U&"), sql);
        // Setting has no equals(), so match by field rather than a constructed instance.
        verify(settingRepository).save(argThat((Setting s) ->
                "movie_version".equals(s.getName()) && "1.23".equals(s.getValue())));
    }

    @Test
    void seedsButSkipsVersionWhenMovieVersionFileAbsent() throws Exception {
        when(environment.getProperty("spring.datasource.jdbc-url")).thenReturn("jdbc:postgresql://localhost:5432/atv");
        when(movieRepository.count()).thenReturn(0L);
        when(jdbcTemplate.batchUpdate(any(String[].class))).thenReturn(new int[]{1});
        writeFile("data.sql", MOVIE_LINE);
        // no movie_version file

        newSeeder().run(new DefaultApplicationArguments());

        verify(jdbcTemplate, atLeastOnce()).batchUpdate(any(String[].class));
        verify(settingRepository, never()).save(any(Setting.class));
    }
}
