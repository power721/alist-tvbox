package cn.har01d.alist_tvbox.db;

import db.migration.current.V20__MediaSubscription;
import db.migration.current.V21__MediaSubscriptionMeta;
import db.migration.current.V22__MediaSubscriptionMetaFix;
import db.migration.current.V27__MediaSubscriptionAliases;
import db.migration.current.V28__MediaSubscriptionMainDrives;
import db.migration.current.V30__MediaSubscriptionEpisodeSource;
import db.migration.current.V31__MediaSubscriptionCover;
import db.migration.current.V32__MediaMetadata;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 追剧订阅迁移链路回归测试:V20→V21→V22 在 H2 上执行后,
 * 新列必须能用"不带引号"(即 Hibernate 生成的 SQL 形态,会被 H2 折叠为大写)访问 ——
 * V21 曾因带引号小写列名导致运行时 "Column MS1_0.META_ID not found"(迁移成功但 ORM 不可见)。
 */
class MediaSubscriptionMigrationTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:msubmig_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    private Context context() {
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
    void freshChainSupportsUnquotedColumnAccess() throws Exception {
        new V20__MediaSubscription().migrate(context());
        new V21__MediaSubscriptionMeta().migrate(context());
        new V22__MediaSubscriptionMetaFix().migrate(context());

        execute("INSERT INTO media_subscription (id, uid, name, created_time, meta_provider, meta_id, official_total, next_air_time)"
                + " VALUES (1, 1, '测试剧', 0, 'tmdb', '12345', 24, 456000)");
        try (ResultSet rs = query("SELECT meta_id, official_total, next_air_time FROM media_subscription WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals("12345", rs.getString(1));
            assertEquals(24, rs.getInt(2));
            assertEquals(456000, rs.getLong(3));
        }

        execute("INSERT INTO media_subscription_resource (id, subscription_id, link, created_time, episode_list, mount_path, share_id, gap)"
                + " VALUES (1, 1, 'https://example.com/s/1', 0, '[1,2]', '/追剧/1-测试', 100, TRUE)");
        try (ResultSet rs = query("SELECT episode_list, gap FROM media_subscription_resource WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals("[1,2]", rs.getString(1));
            assertTrue(rs.getBoolean(2));
        }
    }

    @Test
    void v30DerivesResourceStateDropsLegacyColumnsAndCreatesTables() throws Exception {
        new V20__MediaSubscription().migrate(context());
        new V21__MediaSubscriptionMeta().migrate(context());
        new V22__MediaSubscriptionMetaFix().migrate(context());
        execute("INSERT INTO media_subscription (id, uid, name, created_time, mount_path, share_id)"
                + " VALUES (1, 1, '测试剧', 0, '/追剧/1-测试剧', 100)");
        execute("INSERT INTO media_subscription_resource (id, subscription_id, link, created_time, validity, active, gap, episode_list, mount_path, share_id)"
                + " VALUES (1, 1, 'https://example.com/active', 0, 'OK', TRUE, FALSE, '[1]', NULL, 100)"); // 主源
        execute("INSERT INTO media_subscription_resource (id, subscription_id, link, created_time, validity, active, gap, episode_list, mount_path, share_id)"
                + " VALUES (2, 1, 'https://example.com/gap', 0, 'OK', FALSE, TRUE, '[2]', '/追剧/.sources/1-测试剧-补1', 101)"); // 补缺挂载
        execute("INSERT INTO media_subscription_resource (id, subscription_id, link, created_time, validity, active, gap, episode_list)"
                + " VALUES (3, 1, 'https://example.com/bad', 0, 'BAD', FALSE, FALSE, NULL)"); // 判死候选
        execute("INSERT INTO media_subscription_resource (id, subscription_id, link, created_time, validity, active, gap, episode_list)"
                + " VALUES (4, 1, 'https://example.com/fresh', 0, 'UNKNOWN', FALSE, FALSE, NULL)"); // 普通候选

        new V30__MediaSubscriptionEpisodeSource().migrate(context());
        new V30__MediaSubscriptionEpisodeSource().migrate(context()); // 幂等:重复执行不炸

        try (ResultSet rs = query("SELECT id, state, mount_path FROM media_subscription_resource ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals("MOUNTED", rs.getString(2));
            assertEquals("/追剧/1-测试剧", rs.getString(3), "主源行回填订阅固定路径(旧代码从不写该字段)");
            assertTrue(rs.next());
            assertEquals("MOUNTED", rs.getString(2));
            assertEquals("/追剧/.sources/1-测试剧-补1", rs.getString(3));
            assertTrue(rs.next());
            assertEquals("RETIRED", rs.getString(2), "旧 BAD → RETIRED");
            assertTrue(rs.next());
            assertEquals("CANDIDATE", rs.getString(2));
        }
        // 旧列已 drop:再引用必须报错
        try (Statement ignored = connection.createStatement()) {
            ignored.execute("SELECT validity FROM media_subscription_resource");
            org.junit.jupiter.api.Assertions.fail("validity 应已删除");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("column"), expected.getMessage());
        }
        // 新表可用(Hibernate 形态的不带引号访问)
        execute("INSERT INTO msub_episode (id, subscription_id, season, number, title, air_time, aired) VALUES (10, 1, 1, 3, '第三集', 456000, TRUE)");
        execute("INSERT INTO msub_episode_source (id, episode_id, resource_id, rel_path, file_size, state, success_count, fail_count, last_verified_time)"
                + " VALUES (20, 10, 1, 'S01E03.mkv', 524288000, 'VERIFIED', 2, 0, 456000)");
        execute("INSERT INTO dead_link (id, link, reason, fail_count, time) VALUES (30, 'https://example.com/dead', '分享地址已失效', 1, 456000)");
        try (ResultSet rs = query("SELECT e.number, s.state FROM msub_episode_source s JOIN msub_episode e ON s.episode_id = e.id WHERE s.resource_id = 1")) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
            assertEquals("VERIFIED", rs.getString(2));
        }
    }

    @Test
    void v22RepairsQuotedLowercaseColumnsFromBrokenV21() throws Exception {
        new V20__MediaSubscription().migrate(context());
        // 复现旧 V21 的损坏形态:带引号小写列名(H2 按区分大小写精确存储)
        execute("ALTER TABLE media_subscription ADD COLUMN \"meta_id\" VARCHAR(64)");
        execute("ALTER TABLE media_subscription ADD COLUMN \"official_total\" INTEGER");
        // 损坏状态下,Hibernate 形态(不带引号)访问必然失败
        try (ResultSet rs = query("SELECT * FROM media_subscription WHERE 1=0")) {
            // 表可查,但 meta_id 不在不带引号可解析的列集中
        }
        new V22__MediaSubscriptionMetaFix().migrate(context());
        // 修复后:不带引号可访问,其余缺失列补齐
        execute("INSERT INTO media_subscription (id, uid, name, created_time, meta_provider, meta_id, official_total)"
                + " VALUES (2, 1, '修复剧', 0, 'bangumi', '999', 12)");
        try (ResultSet rs = query("SELECT meta_id, official_total, meta_provider FROM media_subscription WHERE id = 2")) {
            assertTrue(rs.next());
            assertEquals("999", rs.getString(1));
            assertEquals(12, rs.getInt(2));
            assertEquals("bangumi", rs.getString(3));
        }
    }

    @Test
    void v27AddsAliasesColumn() throws Exception {
        new V20__MediaSubscription().migrate(context());
        new V21__MediaSubscriptionMeta().migrate(context());
        new V22__MediaSubscriptionMetaFix().migrate(context());
        new V27__MediaSubscriptionAliases().migrate(context());

        execute("INSERT INTO media_subscription (id, uid, name, created_time, aliases)"
                + " VALUES (3, 1, '别名剧', 0, 'The Blue Whisper\n蒼蘭訣')");
        try (ResultSet rs = query("SELECT aliases FROM media_subscription WHERE id = 3")) {
            assertTrue(rs.next());
            assertEquals("The Blue Whisper\n蒼蘭訣", rs.getString(1)); // 不带引号可访问(Hibernate 形态)
        }
        // 幂等:重复执行不报错
        new V27__MediaSubscriptionAliases().migrate(context());
    }

    @Test
    void v28AddsMainDrivesColumn() throws Exception {
        new V20__MediaSubscription().migrate(context());
        new V28__MediaSubscriptionMainDrives().migrate(context());

        execute("INSERT INTO media_subscription (id, uid, name, created_time, main_drives)"
                + " VALUES (4, 1, '主盘剧', 0, '10,5')");
        try (ResultSet rs = query("SELECT main_drives FROM media_subscription WHERE id = 4")) {
            assertTrue(rs.next());
            assertEquals("10,5", rs.getString(1)); // 不带引号可访问(Hibernate 形态)
        }
        // 幂等:重复执行不报错
        new V28__MediaSubscriptionMainDrives().migrate(context());
    }

    @Test
    void v31AddsCoverUrlColumn() throws Exception {
        new V20__MediaSubscription().migrate(context());
        new V31__MediaSubscriptionCover().migrate(context());

        execute("INSERT INTO media_subscription (id, uid, name, created_time, cover_url)"
                + " VALUES (5, 1, '封面剧', 0, 'https://media.themoviedb.org/t/p/w300_and_h450_bestv2/abc.jpg')");
        try (ResultSet rs = query("SELECT cover_url FROM media_subscription WHERE id = 5")) {
            assertTrue(rs.next());
            assertEquals("https://media.themoviedb.org/t/p/w300_and_h450_bestv2/abc.jpg", rs.getString(1)); // 不带引号可访问(Hibernate 形态)
        }
        // 幂等:重复执行不报错
        new V31__MediaSubscriptionCover().migrate(context());
    }

    @Test
    void v32CreatesMediaMetadataTable() throws Exception {
        new V32__MediaMetadata().migrate(context());
        execute("INSERT INTO media_metadata (id, provider, meta_id, season, status, payload, fetch_time)"
                + " VALUES (1, 'tmdb', '12345', 2, 'RETURNING', '{\"name\":\"测试\"}', 456000)");
        try (ResultSet rs = query("SELECT provider, meta_id, season, status, payload FROM media_metadata WHERE id = 1")) {
            assertTrue(rs.next());
            assertEquals("tmdb", rs.getString(1));
            assertEquals("12345", rs.getString(2));
            assertEquals(2, rs.getInt(3));
            assertEquals("RETURNING", rs.getString(4)); // 不带引号可访问(Hibernate 形态)
        }
        // (provider, meta_id, season) 唯一约束生效
        try (Statement ignored = connection.createStatement()) {
            ignored.execute("INSERT INTO media_metadata (id, provider, meta_id, season, status, payload, fetch_time)"
                    + " VALUES (2, 'tmdb', '12345', 2, 'ENDED', '{}', 456000)");
            org.junit.jupiter.api.Assertions.fail("重复键应被唯一约束拦截");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("unique")
                    || expected.getMessage().toLowerCase().contains("index"), expected.getMessage());
        }
        // 幂等:重复执行不报错
        new V32__MediaMetadata().migrate(context());
    }

    private void execute(String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private ResultSet query(String sql) throws Exception {
        Statement statement = connection.createStatement();
        return statement.executeQuery(sql);
    }
}
