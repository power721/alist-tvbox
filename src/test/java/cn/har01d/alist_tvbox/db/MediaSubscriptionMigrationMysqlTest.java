package cn.har01d.alist_tvbox.db;

import db.migration.current.V20__MediaSubscription;
import db.migration.current.V21__MediaSubscriptionMeta;
import db.migration.current.V22__MediaSubscriptionMetaFix;
import db.migration.current.V23__MediaSubscriptionAccounts;
import db.migration.current.V24__MediaSubscriptionBrokenEpisodes;
import db.migration.current.V25__MediaSubscriptionSchedule;
import db.migration.current.V26__MediaSubscriptionCrossDrive;
import db.migration.current.V27__MediaSubscriptionAliases;
import db.migration.current.V28__MediaSubscriptionMainDrives;
import db.migration.current.V29__MediaSubscriptionMaxEpisode;
import db.migration.current.V30__MediaSubscriptionEpisodeSource;
import db.migration.current.V31__MediaSubscriptionCover;
import db.migration.current.V32__MediaMetadata;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * MySQL 迁移链路验证(真实 MySQL,非 H2 兼容模式):utf8mb4 下 InnoDB 唯一索引键长上限 3072 字节,
 * link VARCHAR(1024) 整列建索引即 ERROR 1071 —— V20 uk_msub_resource / V30 dead_link 必须走前缀索引。
 * 环境变量门控(平时跳过,不进 CI 默认路径):
 * <pre>MSUB_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/msub_mig_test?useSSL=false&allowPublicKeyRetrieval=true
 * MSUB_MYSQL_USER=root MSUB_MYSQL_PASSWORD=...</pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MediaSubscriptionMigrationMysqlTest {

    private static Connection open() throws Exception {
        String url = System.getenv("MSUB_MYSQL_URL");
        String user = System.getenv("MSUB_MYSQL_USER");
        String password = System.getenv("MSUB_MYSQL_PASSWORD");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "MSUB_MYSQL_URL 未配置,跳过真实 MySQL 验证");
        return DriverManager.getConnection(url, user == null ? "root" : user, password);
    }

    @BeforeAll
    void runChain() throws Exception {
        try (Connection connection = open()) {
            // 干净库起跑:清掉上一轮的表(测试专用库,可重复执行)
            for (String table : List.of("msub_episode_source", "msub_episode", "dead_link", "media_metadata",
                    "media_subscription_resource", "media_subscription_event", "media_subscription", "user_preference")) {
                execute(connection, "DROP TABLE IF EXISTS " + table);
            }
            Context context = context(connection);
            for (var migration : List.of(new V20__MediaSubscription(), new V21__MediaSubscriptionMeta(),
                    new V22__MediaSubscriptionMetaFix(), new V23__MediaSubscriptionAccounts(),
                    new V24__MediaSubscriptionBrokenEpisodes(), new V25__MediaSubscriptionSchedule(),
                    new V26__MediaSubscriptionCrossDrive(), new V27__MediaSubscriptionAliases(),
                    new V28__MediaSubscriptionMainDrives(), new V29__MediaSubscriptionMaxEpisode(),
                    new V30__MediaSubscriptionEpisodeSource(), new V31__MediaSubscriptionCover(),
                    new V32__MediaMetadata())) {
                migration.migrate(context);
            }
            // 幂等:重复执行不炸
            new V20__MediaSubscription().migrate(context);
            new V30__MediaSubscriptionEpisodeSource().migrate(context);
        }
    }

    @AfterAll
    void cleanup() throws Exception {
        String url = System.getenv("MSUB_MYSQL_URL");
        if (url == null || url.isBlank()) {
            return;
        }
        try (Connection connection = open()) {
            for (String table : List.of("msub_episode_source", "msub_episode", "dead_link", "media_metadata",
                    "media_subscription_resource", "media_subscription_event", "media_subscription", "user_preference")) {
                execute(connection, "DROP TABLE IF EXISTS " + table);
            }
        }
    }

    private Context context(Connection connection) {
        return new Context() {
            @Override
            public Connection getConnection() {
                return connection;
            }

            @Override
            public org.flywaydb.core.api.configuration.Configuration getConfiguration() {
                return null;
            }
        };
    }

    @Test
    void resourceLinkUniqueIndexUsesPrefix() throws Exception {
        try (Connection connection = open()) {
            Integer subPart = indexSubPart(connection, "media_subscription_resource", "uk_msub_resource");
            // Connector/J 的 getIndexInfo 不返回 SUB_PART,须查 information_schema(MySQL 前缀索引的标志列)
            assertTrue(subPart != null && subPart > 0 && subPart <= 768,
                    "uk_msub_resource 在 MySQL 必须是前缀索引(≤768 字符,utf8mb4 下 3072 字节键长上限),实际 " + subPart);
        }
    }

    @Test
    void resourceLinkUniqueStillRejectsDuplicates() throws Exception {
        try (Connection connection = open()) {
            execute(connection, "INSERT INTO media_subscription_resource (id, subscription_id, link, created_time)"
                    + " VALUES (1, 1, 'https://example.com/s/share-1', 0)");
            try {
                execute(connection, "INSERT INTO media_subscription_resource (id, subscription_id, link, created_time)"
                        + " VALUES (2, 1, 'https://example.com/s/share-1', 0)");
                fail("同订阅同链接应被唯一索引拒绝");
            } catch (Exception expected) {
                assertTrue(expected.getMessage().toLowerCase().contains("duplicate"), expected.getMessage());
            }
        }
    }

    @Test
    void deadLinkUniqueIndexUsesPrefixAndRejectsDuplicates() throws Exception {
        try (Connection connection = open()) {
            Integer subPart = indexSubPart(connection, "dead_link", "uk_dead_link_link");
            assertTrue(subPart != null && subPart > 0 && subPart <= 768,
                    "uk_dead_link_link 在 MySQL 必须是前缀索引(≤768 字符),实际 " + subPart);
            execute(connection, "INSERT INTO dead_link (id, link, reason, fail_count, time) VALUES (1, 'https://example.com/dead', 'r', 1, 0)");
            try {
                execute(connection, "INSERT INTO dead_link (id, link, reason, fail_count, time) VALUES (2, 'https://example.com/dead', 'r', 1, 0)");
                fail("dead_link 同链接应被唯一索引拒绝");
            } catch (Exception expected) {
                assertTrue(expected.getMessage().toLowerCase().contains("duplicate"), expected.getMessage());
            }
        }
    }

    /** 指定索引的 SUB_PART(前缀字符数;整列索引为 null/0)。 */
    private static Integer indexSubPart(Connection connection, String table, String index) throws Exception {
        try (ResultSet rs = query(connection,
                "SELECT MAX(SUB_PART) FROM information_schema.statistics"
                        + " WHERE table_schema = DATABASE() AND table_name = '" + table
                        + "' AND index_name = '" + index + "'")) {
            Object value = rs.next() ? rs.getObject(1) : null; // MySQL 返回 BIGINT,不能直接强转 Integer
            return value instanceof Number number ? number.intValue() : null;
        }
    }

    @Test
    void episodeSourceTablesUsable() throws Exception {
        try (Connection connection = open()) {
            execute(connection, "INSERT INTO media_subscription (id, uid, name, created_time) VALUES (1, 1, '测试剧', 0)");
            execute(connection, "INSERT INTO msub_episode (id, subscription_id, season, number, air_time, aired)"
                    + " VALUES (10, 1, 1, 3, 456000, TRUE)");
            execute(connection, "INSERT INTO msub_episode_source (id, episode_id, resource_id, rel_path, state)"
                    + " VALUES (20, 10, 1, 'S01E03.mkv', 'VERIFIED')");
            try (ResultSet rs = query(connection, "SELECT COUNT(*) FROM msub_episode_source")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static ResultSet query(Connection connection, String sql) throws Exception {
        Statement statement = connection.createStatement();
        return statement.executeQuery(sql);
    }
}
