package cn.har01d.alist_tvbox;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the MySQL migration path on a real MySQL container.
 * <p>
 * Why: Java migrations (V10+) are developed and exercised against H2, and MySQL's default
 * {@code sql_mode} (no {@code ANSI_QUOTES}) treats double quotes as string literals — quoted
 * identifier DDL that is valid on H2/PG fails on MySQL. This exact gap shipped in V56
 * ({@code "year"} literal) and broke the dev MySQL boot on 2026-11-16. This test runs Flyway
 * exactly as the app would on MySQL ({@code {vendor}} -> {@code mysql} + {@code common} +
 * {@code current} Java migrations) so the whole V1..VN chain is gated on the real dialect.
 */
@Testcontainers
class MySqlMigrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8");

    @Test
    void migrateAndVerifySchema() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                // Mirrors application.yaml: {vendor} + common SQL, plus current/ for the Java migrations
                .locations("classpath:db/migration/mysql", "classpath:db/migration/common",
                        "classpath:db/migration/current")
                .baselineOnMigrate(true)
                .load();
        // Throws on any SQL error -> every migration must execute cleanly on MySQL.
        flyway.migrate();

        try (Connection c = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {

            // V56 regression guard: the quoted reserved-word column must exist as a real column
            assertThat(columnExists(c, "watchlist_item", "year"))
                    .as("V56 watchlist_item.year must be a real column on MySQL")
                    .isTrue();

            // Recent Java migrations' columns
            assertThat(columnExists(c, "plugin", "t4_visible"))
                    .as("V57 plugin.t4_visible").isTrue();
            assertThat(columnExists(c, "plugin", "detail_enhance"))
                    .as("V58 plugin.detail_enhance").isTrue();

            // The Java migrations ran in order (new ones are gated by migrate() throwing)
            List<String> versions = appliedVersions(c);
            assertThat(versions).isNotEmpty();
            assertThat(versions).contains("56", "57", "58");
        }
    }

    private static boolean columnExists(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static List<String> appliedVersions(Connection c) throws SQLException {
        List<String> versions = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                versions.add(rs.getString("version"));
            }
        }
        return versions;
    }
}
