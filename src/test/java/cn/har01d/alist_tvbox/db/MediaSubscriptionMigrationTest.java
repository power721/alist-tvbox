package cn.har01d.alist_tvbox.db;

import db.migration.current.V20__MediaSubscription;
import db.migration.current.V21__MediaSubscriptionMeta;
import db.migration.current.V22__MediaSubscriptionMetaFix;
import db.migration.current.V27__MediaSubscriptionAliases;
import db.migration.current.V28__MediaSubscriptionMainDrives;
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
