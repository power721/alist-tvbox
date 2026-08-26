package cn.har01d.alist_tvbox.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 追更系统 V30 后的 schema 契约:H2 上按应用真实配置跑完整 Flyway 链
 * ({vendor}=h2 + common + current SPI Java 迁移),然后断言实体映射的每一列都存在 ——
 * ddl-auto=validate 在测试里没有上下文可跑(PostgreSqlMigrationTest 注释说明了全上下文
 * 启动的代价),列清单在这里钉死,任何"实体加了字段/迁移漏了列"的组合都会当场爆。
 */
class MsubSchemaContractTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:msubschema_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2", "classpath:db/migration/common",
                        "classpath:db/migration/current")
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void migratedSchemaCoversEveryEntityColumn() throws Exception {
        // MediaSubscription:episode_list/broken_episodes 已 drop,max_episode/schedule/cover_url 保留
        assertColumns("media_subscription", "id", "uid", "name", "keyword", "season", "douban_id",
                "meta_provider", "meta_id", "official_episodes", "official_total", "official_status",
                "next_air_time", "meta_sync_time", "cover_url", "aliases", "main_drives", "filter_config", "mode",
                "account_id", "account_ids", "mount_path", "share_id", "expected_episodes",
                "current_episodes", "max_episode", "caught_up_episode", "schedule", "cross_drive", "status", "stall_count",
                "check_interval_hours", "next_check_time", "last_check_time", "created_time", "updated_time");
        // MediaSubscriptionResource:validity/active/gap/episode_list 已 drop,state 新增;
        // link_hash 为 V34 全链唯一键(MySQL 前缀索引只比前 760 字符)
        assertColumns("media_subscription_resource", "id", "subscription_id", "link", "link_hash", "type", "source",
                "title", "password", "episodes_found", "score", "state", "mount_path", "share_id",
                "checked_time", "created_time");
        assertColumns("msub_episode", "id", "subscription_id", "season", "number", "title", "air_time", "aired");
        assertColumns("msub_episode_source", "id", "episode_id", "resource_id", "rel_path", "file_size",
                "state", "success_count", "fail_count", "last_verified_time");
        assertColumns("dead_link", "id", "link", "reason", "fail_count", "time");
        assertColumns("media_metadata", "id", "provider", "meta_id", "season", "status", "payload", "fetch_time");
    }

    @Test
    void entityRoundTripThroughRealSchema() throws Exception {
        // 完整链路上的真实往返:rel_path/state/success_count 等列名以 Hibernate 生成的 SQL 形态(不带引号)访问
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO msub_episode (id, subscription_id, season, number, title, air_time, aired)"
                        + " VALUES (10, 1, 1, 17, '第17集', NULL, TRUE)")) {
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO msub_episode_source (id, episode_id, resource_id, rel_path, file_size, state,"
                        + " success_count, fail_count, last_verified_time)"
                        + " VALUES (20, 10, 3, 'Season 1/第17集.mkv', 524288000, 'VERIFIED', 2, 0, 456000)")) {
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT e.number, s.rel_path, s.state FROM msub_episode_source s"
                        + " JOIN msub_episode e ON s.episode_id = e.id WHERE s.resource_id = 3")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(17, rs.getInt(1));
                assertEquals("Season 1/第17集.mkv", rs.getString(2));
                assertEquals("VERIFIED", rs.getString(3));
            }
        }
        // (subscription_id, season, number) 与 (episode_id, resource_id) 唯一约束生效
        assertUniqueViolation("INSERT INTO msub_episode (id, subscription_id, season, number)"
                + " VALUES (11, 1, 1, 17)");
        assertUniqueViolation("INSERT INTO msub_episode_source (id, episode_id, resource_id, rel_path)"
                + " VALUES (21, 10, 3, 'dup.mkv')");
    }

    @Test
    void resourceLinkHashBackfilledAndPinsFullLinkUniqueness() throws Exception {
        String prefix = "https://pan.example/s/" + "a".repeat(800) + "?token="; // > 760 字符前缀相同、尾巴不同
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO media_subscription_resource (id, subscription_id, link, link_hash, created_time)"
                        + " VALUES (30, 5, ?, ?, 1)")) {
            ps.setString(1, prefix + "sig-a");
            ps.setString(2, cn.har01d.alist_tvbox.entity.MediaSubscriptionResource.hashOf(prefix + "sig-a"));
            ps.executeUpdate();
        }
        // 同 (subscription_id, link_hash) 撞唯一索引(链接文本不同也拦:哈希才是唯一键)
        String hashA = cn.har01d.alist_tvbox.entity.MediaSubscriptionResource.hashOf(prefix + "sig-a");
        assertUniqueViolation("INSERT INTO media_subscription_resource (id, subscription_id, link, link_hash, created_time)"
                + " VALUES (31, 5, 'https://another.example/link', '" + hashA + "', 1)");
        // 评审场景:两条链接前 760 字符完全一致(旧 MySQL 前缀唯一索引会误拒),哈希不同应都能入库
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO media_subscription_resource (id, subscription_id, link, link_hash, created_time)"
                        + " VALUES (32, 5, ?, ?, 1)")) {
            ps.setString(1, prefix + "sig-b");
            ps.setString(2, cn.har01d.alist_tvbox.entity.MediaSubscriptionResource.hashOf(prefix + "sig-b"));
            ps.executeUpdate();
        }
    }

    @Test
    void v34BackfillsLinkHashOfRowsCreatedBeforeIt() throws Exception {
        // 独立库先只迁移到 V33(建 media_subscription_resource 但无 link_hash),插入存量行后再放行 V34,
        // 验证回填与唯一索引重建:回填口径 = 实体 hashOf(小写 hex SHA-256/UTF-8)
        String url = "jdbc:h2:mem:msubv34_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection legacy = DriverManager.getConnection(url, "sa", "")) {
            migrateTo(url, "33");
            try (PreparedStatement ps = legacy.prepareStatement(
                    "INSERT INTO media_subscription_resource (id, subscription_id, link, created_time)"
                            + " VALUES (40, 5, 'https://pan.example/s/abc?pwd=1', 1)")) {
                ps.executeUpdate();
            }
            migrateTo(url, null);
            try (ResultSet rs = legacy.createStatement()
                    .executeQuery("SELECT link_hash FROM media_subscription_resource WHERE id = 40")) {
                assertTrue(rs.next());
                assertEquals(cn.har01d.alist_tvbox.entity.MediaSubscriptionResource.hashOf("https://pan.example/s/abc?pwd=1"),
                        rs.getString(1));
            }
        }
    }

    private void migrateTo(String url, String target) {
        var configure = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2", "classpath:db/migration/common",
                        "classpath:db/migration/current")
                .baselineOnMigrate(true);
        if (target != null) {
            configure.target(target);
        }
        configure.load().migrate();
    }

    private void assertColumns(String table, String... columns) throws SQLException {
        Map<String, Boolean> found = new LinkedHashMap<>();
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, table.toUpperCase(), null)) {
            while (rs.next()) {
                found.put(rs.getString("COLUMN_NAME").toLowerCase(), true);
            }
        }
        // H2 折叠大写;按小写比对(应用侧 Hibernate 亦用不带引号标识符)
        for (String column : columns) {
            assertTrue(found.containsKey(column), table + "." + column + " 缺失(实体与 V30 DDL 契约破裂),现有:" + found.keySet());
        }
    }

    private void assertUniqueViolation(String sql) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (Exception expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("unique")
                            || expected.getMessage().toLowerCase().contains("primary key"),
                    "应撞唯一约束,实际:" + expected.getMessage());
            return;
        }
        throw new AssertionError("重复行未被唯一约束拦截");
    }
}
