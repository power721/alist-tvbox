package cn.har01d.alist_tvbox.util;

import cn.har01d.alist_tvbox.util.H2SqlConverter.Dialect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2SqlConverterTest {

    // real sample rows from the xiaoya-douban export (data.sql / diff.zip)
    private static final String MOVIE_INSERT =
            "INSERT INTO \"PUBLIC\".\"MOVIE\" VALUES(26970929, U&'\\674e\\4e00\\6850,\\66fe\\821c\\665e,\\9093\\4e3a,\\4ee3\\9732\\5a03,\\738b\\4ee5\\7eb6', "
                    + "U&'\\4e2d\\56fd\\5927\\9646', 'https://img3.doubanio.com/view/photo/s_ratio_poster/public/p2933353357.webp', '', "
                    + "U&'\\5267\\60c5', U&'\\6731\\5c11\\6770', U&'\\4e8e\\5c0f\\5343', U&'\\5267\\60c5,\\559c\\5267', "
                    + "U&'\\6c49\\8bed', U&'\\4e91\\79c0\\884c', 2026);";

    private static final String META_INSERT =
            "INSERT INTO \"PUBLIC\".\"META\" VALUES(1, FALSE, U&'Yakka Dee \\7b2c\\4e00\\5b63', "
                    + "U&'/\\52a8\\6f2b/\\513f\\7ae5/BBC \\5e7c\\513f\\82f1\\8bed\\542f\\8499 Yakka Dee', "
                    + "NULL, 1, 0, TIMESTAMP WITH TIME ZONE '2024-05-15 11:41:45.170659+00', NULL, NULL, NULL, 2017, 35461437);";

    private static final String DELETE = "DELETE FROM \"PUBLIC\".\"MOVIE\" WHERE id = 38433264;";

    @Test
    void convertMovieInsert_decodesUnicodeAndAddsColumns_pg() {
        String sql = H2SqlConverter.convert(MOVIE_INSERT, Dialect.POSTGRESQL);
        assertTrue(sql.startsWith("INSERT INTO movie (id,actors,country,cover,db_score,description,"
                + "directors,editors,genre,language,name,\"year\") VALUES("), sql);
        assertTrue(sql.contains("'李一桐,"), "U& actors should decode to UTF-8: " + sql);
        assertFalse(sql.contains("U&"), "no U& escapes should remain: " + sql);
        assertFalse(sql.contains("\"PUBLIC\""), "PUBLIC schema prefix must be stripped: " + sql);
        assertFalse(sql.endsWith(";"), "trailing statement separator must be stripped: " + sql);
        assertTrue(sql.endsWith(", 2026)"), sql);
    }

    @Test
    void convertMovieInsert_mysqlQuotesYearColumn() {
        String sql = H2SqlConverter.convert(MOVIE_INSERT, Dialect.MYSQL);
        assertTrue(sql.startsWith("INSERT INTO movie (id,actors,country,cover,db_score,description,"
                + "directors,editors,genre,language,name,`year`) VALUES("), sql);
        assertTrue(sql.contains("'李一桐,"), sql);
    }

    @Test
    void convertMetaInsert_normalizesTimestampAndKeepsColumnOrder() {
        String sql = H2SqlConverter.convert(META_INSERT, Dialect.POSTGRESQL);
        // movie_id is last in the H2 export order — must stay last, not third
        assertTrue(sql.startsWith("INSERT INTO meta (id,disabled,name,path,score,site_id,tid,time,"
                + "tm_id,tmdb_id,type,\"year\",movie_id) VALUES(1, FALSE, "), sql);
        assertTrue(sql.contains("'Yakka Dee 第一季'"), "name should decode: " + sql);
        assertTrue(sql.contains("'2024-05-15 11:41:45.170659'"), "timestamp prefix and +00 stripped: " + sql);
        assertFalse(sql.contains("TIMESTAMP WITH TIME ZONE"), sql);
        // FALSE kept; positional order: (..., 2017 year, 35461437 movie_id)
        assertTrue(sql.contains(", FALSE,"), sql);
        assertTrue(sql.endsWith(", NULL, NULL, NULL, 2017, 35461437)"), sql);
    }

    @Test
    void convertMetaInsert_mysqlAlsoStripsTimezoneOffset() {
        String sql = H2SqlConverter.convert(META_INSERT, Dialect.MYSQL);
        assertTrue(sql.contains("'2024-05-15 11:41:45.170659'"), sql);
        assertFalse(sql.contains("+00'"), sql);
    }

    @Test
    void convertDelete_stripsPublicPrefix() {
        assertEquals("DELETE FROM movie WHERE id = 38433264",
                H2SqlConverter.convert(DELETE, Dialect.MYSQL));
    }

    @Test
    void convertDelete_meta() {
        String sql = H2SqlConverter.convert(
                "DELETE FROM \"PUBLIC\".\"META\" WHERE id = 5;", Dialect.POSTGRESQL);
        assertEquals("DELETE FROM meta WHERE id = 5", sql);
    }

    @Test
    void convertSkipsDdlAndNoise() {
        assertNull(H2SqlConverter.convert("DROP TABLE IF EXISTS META;", Dialect.MYSQL));
        assertNull(H2SqlConverter.convert(
                "CREATE CACHED TABLE \"PUBLIC\".\"MOVIE\"( \"ID\" INTEGER NOT NULL );", Dialect.MYSQL));
        assertNull(H2SqlConverter.convert("-- H2 2.3.232; ", Dialect.MYSQL));
        assertNull(H2SqlConverter.convert(";", Dialect.MYSQL));
        assertNull(H2SqlConverter.convert("   ", Dialect.MYSQL));
        assertNull(H2SqlConverter.convert("", Dialect.MYSQL));
        assertNull(H2SqlConverter.convert(null, Dialect.MYSQL));
    }

    @Test
    void h2Dialect_isPassThrough() {
        // H2 keeps its working spring.sql.init path; converter must not alter lines.
        assertEquals(MOVIE_INSERT, H2SqlConverter.convert(MOVIE_INSERT, Dialect.H2));
    }

    @Test
    void unicodeStringWithEmbeddedQuote_isDoubled() {
        // U&'...' escapes a literal quote as \'; decoded output must double it ('').
        String line = "INSERT INTO \"PUBLIC\".\"MOVIE\" VALUES(1, U&'A\\'B', 'x', '', '', '', '', '', '', '', '', 2000);";
        String sql = H2SqlConverter.convert(line, Dialect.POSTGRESQL);
        assertTrue(sql.contains("'A''B'"), "embedded quote must be doubled: " + sql);
    }

    /**
     * Smoke test against the real xiaoya-douban export. Enabled only when
     * {@code -Datv.data.sql=/abs/path/to/data.sql} points at the file, so it is a
     * no-op in CI. Asserts every line converts without throwing and that the
     * converted output contains no H2-isms (U&, "PUBLIC").
     */
    @Test
    @EnabledIfSystemProperty(named = "atv.data.sql", matches = ".+")
    void convertRealExportFile_noExceptionsAndNoH2Remnants() throws Exception {
        Path file = Path.of(System.getProperty("atv.data.sql"));
        int converted = 0;
        int skipped = 0;
        for (String line : Files.readAllLines(file)) {
            String mysql = H2SqlConverter.convert(line, Dialect.MYSQL);
            String pg = H2SqlConverter.convert(line, Dialect.POSTGRESQL);
            if (mysql == null) {
                assertNull(pg, "dialects must agree on skip: " + line);
                skipped++;
                continue;
            }
            converted++;
            assertFalse(mysql.contains("U&"), mysql);
            assertFalse(mysql.contains("\"PUBLIC\""), mysql);
            assertFalse(pg.contains("U&"), pg);
            assertFalse(pg.contains("\"PUBLIC\""), pg);
            assertFalse(mysql.contains("TIMESTAMP WITH TIME ZONE"), mysql);
        }
        assertTrue(converted > 0, "expected some INSERT/DELETE lines to convert");
    }
}
