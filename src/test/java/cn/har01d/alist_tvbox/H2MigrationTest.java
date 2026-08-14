package cn.har01d.alist_tvbox;

import cn.har01d.alist_tvbox.config.SessionLoginInfoMigrationCallback;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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

    /**
     * V10 在 H2 上必须能跑完:CREATE TABLE 未加引号,H2 把列存为大写,
     * 若后续 DDL 直接引用小写 "token"/"source_kind",迁移会以 Column not found 中断,应用起不来。
     * 断言用裸标识符访问(与 Hibernate、ddl-auto=validate 一致)。
     */
    @Test
    void v10AppliesOnH2WithDefaultIdentifierCasing() throws Exception {
        String url = "jdbc:h2:mem:v10-playback-sync;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            Flyway flyway = Flyway.configure()
                    .dataSource(url, "sa", "")
                    // 与 application.yaml 一致:{vendor}=h2 + common SQL + current 下的 Java 迁移
                    .locations("classpath:db/migration/h2", "classpath:db/migration/common",
                            "classpath:db/migration/current")
                    .baselineOnMigrate(true)
                    .load();

            assertThatCode(flyway::migrate).doesNotThrowAnyException();
            assertThat(appliedVersions(connection)).contains("10", "11", "12", "13", "14");
            assertThat(queryString(connection,
                    "SELECT CAST(next_val AS VARCHAR) FROM playback_change_sequence WHERE id = 1"))
                    .isEqualTo("0");

            // history 新列必须能以裸标识符访问
            assertThatCode(() -> execute(connection,
                    "INSERT INTO history (id, cid, create_time, duration, ending, episode, opening, position,"
                            + " rev_play, rev_sort, scale, speed, uid, source_kind, source_key, source_name, vod_id, updated_at, client_key)"
                            + " VALUES (1, 0, 100, 0, 0, 1, 0, 0, false, false, -1, 1, 1, 'site', 'abc', '客厅', 'v1', 200, 'dev')"))
                    .doesNotThrowAnyException();
            assertThat(queryString(connection, "SELECT source_kind FROM history WHERE id = 1")).isEqualTo("site");
            assertThat(queryString(connection, "SELECT source_name FROM history WHERE id = 1")).isEqualTo("客厅");
            assertThatCode(() -> execute(connection,
                    "UPDATE history SET playlist_index = 0, source_group_index = 1, source_index = 2,"
                            + " source_subgroup_index = 6, source_subgroup_name = '07外海风云',"
                            + " drive_dir_id = 'stable-dir' WHERE id = 1"))
                    .doesNotThrowAnyException();
            assertThat(queryString(connection, "SELECT source_subgroup_name FROM history WHERE id = 1"))
                    .isEqualTo("07外海风云");
            assertThat(queryString(connection, "SELECT drive_dir_id FROM history WHERE id = 1"))
                    .isEqualTo("stable-dir");

            // playback_token 的唯一索引必须真的建在 token 列上
            execute(connection, "INSERT INTO playback_token (id, uid, token, created_time, last_used_at)"
                    + " VALUES (1, 1, 'tk-1', 0, 0)");
            assertThatThrownBy(() -> execute(connection,
                    "INSERT INTO playback_token (id, uid, token, created_time, last_used_at)"
                            + " VALUES (2, 1, 'tk-1', 0, 0)"))
                    .hasMessageContaining("Unique index or primary key violation");

            assertThatCode(() -> execute(connection,
                    "INSERT INTO playback_tombstone (id, uid, scope, source_kind, source_key, vod_id, deleted_at, expire_at)"
                            + " VALUES (1, 1, 'item', 'site', 'abc', 'v1', 300, 400)"))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * 网盘/电报源的 vod_id 是 URL 编码的 JSON,数百字符很常见:V10 的 VARCHAR(255) 会让上报以
     * "Value too long for column VOD_ID"(22001)失败,该条播放记录直接丢掉。V12 放宽为 TEXT。
     * 同时验证放宽后仍能按等值查回 —— 身份查询走的正是 vod_id 等值谓词。
     */
    @Test
    void v12StoresLongVodIdAndKeepsItComparable() throws Exception {
        String url = "jdbc:h2:mem:v12-long-vod-id;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            runFullMigration(url);
            String vodId = "奇异@" + "%7B%22title%22%3A%22我的弟子全是".repeat(20);
            assertThat(vodId.length()).isGreaterThan(255);

            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO history (id, cid, create_time, duration, ending, episode, opening, position,"
                            + " rev_play, rev_sort, scale, speed, uid, source_kind, source_key, vod_id, updated_at)"
                            + " VALUES (1, 0, 100, 0, 0, 1, 0, 0, false, false, -1, 1, 1, 'pan', 'abc', ?, 200)")) {
                statement.setString(1, vodId);
                assertThatCode(statement::executeUpdate).doesNotThrowAnyException();
            }

            // 身份查询按 vod_id 等值匹配:放宽后必须仍可比较(若建成不可比较的 CLOB 这里就会失败)
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT vod_id FROM history WHERE uid = 1 AND source_kind = 'pan'"
                            + " AND source_key = 'abc' AND vod_id = ?")) {
                statement.setString(1, vodId);
                try (ResultSet rs = statement.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo(vodId);
                }
            }

            // 墓碑承载同一个 vod_id,同样不能溢出
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO playback_tombstone (id, uid, scope, source_kind, source_key, vod_id, deleted_at, expire_at)"
                            + " VALUES (1, 1, 'item', 'pan', 'abc', ?, 300, 400)")) {
                statement.setString(1, vodId);
                assertThatCode(statement::executeUpdate).doesNotThrowAnyException();
            }

            // 同步索引必须已去掉 vod_id:MySQL 上 TEXT 列进索引必须给前缀长度,否则建索引直接失败
            assertThat(indexColumns(connection, "HISTORY", "idx_history_sync"))
                    .containsExactly("UID", "SOURCE_KIND", "SOURCE_KEY");
            assertThat(indexColumns(connection, "PLAYBACK_TOMBSTONE", "idx_pb_tomb"))
                    .containsExactly("UID", "SOURCE_KIND", "SOURCE_KEY");
        }
    }

    private List<String> indexColumns(Connection connection, String table, String indexName) throws Exception {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
            while (rs.next()) {
                if (indexName.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        return columns;
    }

    /** 迁移必须幂等:重复执行(如升级重启)不得因索引/列已存在而失败。 */
    @Test
    void playbackMigrationsAreIdempotentOnRerun() throws Exception {
        String url = "jdbc:h2:mem:v10-playback-sync-rerun;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            runFullMigration(url);
            // 清掉播放同步迁移记录,让 Flyway 在既有 schema 上按顺序重跑 V10–V14
            execute(connection, "DELETE FROM \"flyway_schema_history\" WHERE CAST(\"version\" AS INTEGER) >= 10");

            assertThatCode(() -> runFullMigration(url)).doesNotThrowAnyException();
        }
    }

    private void runFullMigration(String url) {
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2", "classpath:db/migration/common",
                        "classpath:db/migration/current")
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
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
