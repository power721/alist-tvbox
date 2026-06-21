package cn.har01d.alist_tvbox;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlMigrationScriptTest {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS\\s+`?(\\w+)`?\\s*\\((.*?)\\);",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TEXT_COLUMN = Pattern.compile("^\\s*`?(\\w+)`?\\s+TEXT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "CREATE INDEX\\s+\\w+\\s+ON\\s+`?(\\w+)`?\\s*\\((.*?)\\);",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern INDEX_COLUMN = Pattern.compile("^`?(\\w+)`?(?:\\s*\\(\\s*\\d+\\s*\\))?$");

    @Test
    void textColumnsUsePrefixLengthWhenIndexed() throws IOException {
        String sql = readMigration();
        Set<String> textColumns = textColumns(sql);
        Set<String> invalidIndexes = new HashSet<>();

        Matcher matcher = CREATE_INDEX.matcher(sql);
        while (matcher.find()) {
            String table = matcher.group(1);
            for (String rawColumn : matcher.group(2).split(",")) {
                String column = rawColumn.trim();
                Matcher columnMatcher = INDEX_COLUMN.matcher(column);
                if (columnMatcher.matches()
                        && textColumns.contains(table + "." + columnMatcher.group(1))
                        && !column.contains("(")) {
                    invalidIndexes.add(table + "." + columnMatcher.group(1));
                }
            }
        }

        assertThat(invalidIndexes)
                .as("MySQL requires a prefix length when indexing TEXT columns")
                .isEmpty();
    }

    @Test
    void mysqlV4AvoidsUnsupportedConditionalAddColumn() throws IOException {
        String sql = readResource("/db/migration/mysql/V4__fix_missing_external_id.sql");

        assertThat(sql)
                .as("MySQL does not support ALTER TABLE ... ADD COLUMN IF NOT EXISTS")
                .doesNotContainPattern("(?i)ADD\\s+COLUMN\\s+IF\\s+NOT\\s+EXISTS");
    }

    @Test
    void mysqlV4MigratesOldJpaGeneratedSchema() throws IOException {
        String sql = readResource("/db/migration/mysql/V4__fix_missing_external_id.sql");

        assertThat(sql)
                .as("Old MySQL users may have JPA-generated plugin table without external_id")
                .contains("information_schema.columns")
                .contains("ALTER TABLE plugin ADD COLUMN external_id VARCHAR(255)");
        assertThat(sql)
                .as("Old MySQL users may not have feiniu table")
                .contains("CREATE TABLE IF NOT EXISTS feiniu");
    }

    @Test
    void commonV4IsNotResolvedForMysql() {
        assertThat(MySqlMigrationScriptTest.class.getResource(
                "/db/migration/common/V4__fix_missing_external_id.sql"))
                .as("V4 is vendor-specific because MySQL and H2 need different SQL")
                .isNull();
    }

    private static Set<String> textColumns(String sql) {
        Set<String> columns = new HashSet<>();
        Matcher tableMatcher = CREATE_TABLE.matcher(sql);
        while (tableMatcher.find()) {
            String table = tableMatcher.group(1);
            for (String line : tableMatcher.group(2).split("\\R")) {
                Matcher columnMatcher = TEXT_COLUMN.matcher(line);
                if (columnMatcher.find()) {
                    columns.add(table + "." + columnMatcher.group(1));
                }
            }
        }
        return columns;
    }

    private static String readMigration() throws IOException {
        return readResource("/db/migration/mysql/V1__Create_current_schema.sql");
    }

    private static String readResource(String path) throws IOException {
        try (InputStream input = MySqlMigrationScriptTest.class.getResourceAsStream(path)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
