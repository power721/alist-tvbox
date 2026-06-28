package cn.har01d.alist_tvbox;

import cn.har01d.alist_tvbox.config.SessionLoginInfoMigrationCallback;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class H2MigrationTest {

    @Test
    void v6ToleratesExistingSessionLoginColumns() throws Exception {
        String url = "jdbc:h2:mem:v6-existing-session-login-columns;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            createSessionTableWithLoginColumns(connection);

            Flyway flyway = Flyway.configure()
                    .dataSource(url, "sa", "")
                    .locations("classpath:db/migration/common")
                    .callbacks(new SessionLoginInfoMigrationCallback())
                    .baselineOnMigrate(true)
                    .baselineVersion("5")
                    .load();

            assertThatCode(flyway::migrate).doesNotThrowAnyException();
            assertThat(appliedVersions(connection)).containsExactly("5", "6");
            assertThat(queryString(connection, "SELECT login_ip FROM session WHERE token = 'token-1'"))
                    .isEqualTo("127.0.0.1");
            assertThat(queryString(connection, "SELECT user_agent FROM session WHERE token = 'token-1'"))
                    .isEqualTo("Mozilla/5.0");
        }
    }

    @Test
    void v6RecoversAfterPreviousDuplicateColumnFailure() throws Exception {
        String url = "jdbc:h2:mem:v6-restart-after-duplicate-column;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            createSessionTableWithLoginColumns(connection);

            Flyway firstRun = Flyway.configure()
                    .dataSource(url, "sa", "")
                    .locations("classpath:db/migration/common")
                    .baselineOnMigrate(true)
                    .baselineVersion("5")
                    .load();
            assertThatThrownBy(firstRun::migrate).hasMessageContaining("Duplicate column");

            Flyway patchedRun = Flyway.configure()
                    .dataSource(url, "sa", "")
                    .locations("classpath:db/migration/common")
                    .callbacks(new SessionLoginInfoMigrationCallback())
                    .baselineOnMigrate(true)
                    .baselineVersion("5")
                    .load();

            assertThatCode(patchedRun::migrate).doesNotThrowAnyException();
            assertThat(appliedVersions(connection)).containsExactly("5", "6");
            assertThat(queryString(connection, "SELECT login_ip FROM session WHERE token = 'token-1'"))
                    .isEqualTo("127.0.0.1");
            assertThat(queryString(connection, "SELECT user_agent FROM session WHERE token = 'token-1'"))
                    .isEqualTo("Mozilla/5.0");
        }
    }

    private void createSessionTableWithLoginColumns(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE session (
                        id INTEGER AUTO_INCREMENT PRIMARY KEY,
                        token VARCHAR(255),
                        login_ip VARCHAR(45),
                        user_agent VARCHAR(512)
                    )
                    """);
            statement.execute("""
                    INSERT INTO session (token, login_ip, user_agent)
                    VALUES ('token-1', '127.0.0.1', 'Mozilla/5.0')
                    """);
        }
    }

    private List<String> appliedVersions(Connection connection) throws Exception {
        List<String> versions = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT \"version\" FROM \"flyway_schema_history\""
                             + " WHERE \"version\" IS NOT NULL AND \"success\" = true ORDER BY \"installed_rank\"")) {
            while (resultSet.next()) {
                versions.add(resultSet.getString(1));
            }
        }
        return versions;
    }

    private String queryString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }
}
