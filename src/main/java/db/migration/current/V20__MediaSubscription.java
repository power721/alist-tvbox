package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V20:追剧订阅(自动追更)。跨库(H2/MySQL/PG)幂等:新表 CREATE TABLE IF NOT EXISTS,索引先查 metadata 再建。
 * 详见 docs/media-subscription-design.md。
 */
public class V20__MediaSubscription extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        execute(connection, """
                CREATE TABLE IF NOT EXISTS media_subscription (
                    id INTEGER NOT NULL PRIMARY KEY,
                    uid INTEGER NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    keyword VARCHAR(255),
                    season INTEGER,
                    douban_id INTEGER,
                    filter_config TEXT,
                    mode VARCHAR(16) DEFAULT 'FOLLOW',
                    account_id INTEGER,
                    mount_path VARCHAR(512),
                    share_id INTEGER,
                    expected_episodes INTEGER,
                    current_episodes INTEGER,
                    last_episode INTEGER,
                    episode_list TEXT,
                    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    stall_count INTEGER DEFAULT 0,
                    check_interval_hours INTEGER,
                    next_check_time BIGINT,
                    last_check_time BIGINT,
                    created_time BIGINT NOT NULL,
                    updated_time BIGINT
                )""");
        createIndexIfMissing(connection, "media_subscription", "idx_msub_uid", false, "uid");
        createIndexIfMissing(connection, "media_subscription", "idx_msub_schedule", false, "status", "next_check_time");

        execute(connection, """
                CREATE TABLE IF NOT EXISTS media_subscription_resource (
                    id INTEGER NOT NULL PRIMARY KEY,
                    subscription_id INTEGER NOT NULL,
                    link VARCHAR(1024) NOT NULL,
                    type INTEGER,
                    source VARCHAR(16),
                    title VARCHAR(255),
                    password VARCHAR(128),
                    episodes_found INTEGER,
                    score INTEGER,
                    validity VARCHAR(16) DEFAULT 'UNKNOWN',
                    active BOOLEAN DEFAULT FALSE,
                    checked_time BIGINT,
                    created_time BIGINT NOT NULL
                )""");
        // (subscription_id, link) 唯一:同一分享在多源/多频道重复出现时按链接去重。
        // MySQL InnoDB 索引键长上限 3072 字节:utf8mb4 下 link VARCHAR(1024) 即 4096 字节,整列建唯一
        // 索引直接 ERROR 1071 → MySQL 用前缀索引(760 字符×4 + subscription_id 4 字节 ≤ 3072);
        // 前 760 字符不同即不同链接,超长前缀撞车的极端形态由入池前 findBySubscriptionIdAndLink 预查兜底
        createIndexIfMissing(connection, "media_subscription_resource", "uk_msub_resource", true, 760, "subscription_id", "link");

        execute(connection, """
                CREATE TABLE IF NOT EXISTS media_subscription_event (
                    id INTEGER NOT NULL PRIMARY KEY,
                    subscription_id INTEGER NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    detail TEXT,
                    created_time BIGINT NOT NULL
                )""");
        createIndexIfMissing(connection, "media_subscription_event", "idx_msub_event", false, "subscription_id", "created_time");

        execute(connection, """
                CREATE TABLE IF NOT EXISTS user_preference (
                    id INTEGER NOT NULL PRIMARY KEY,
                    uid INTEGER NOT NULL,
                    config TEXT,
                    updated_time BIGINT
                )""");
        createIndexIfMissing(connection, "user_preference", "uk_user_preference_uid", true, "uid");
    }

    private void createIndexIfMissing(Connection connection, String table, String indexName, boolean unique, String... columns)
            throws SQLException {
        createIndexIfMissing(connection, table, indexName, unique, null, columns);
    }

    /** @param mysqlLastColumnPrefix MySQL 专用:末列前缀索引字符数(避开 InnoDB 3072 字节键长上限),其它库忽略 */
    private void createIndexIfMissing(Connection connection, String table, String indexName, boolean unique,
                                      Integer mysqlLastColumnPrefix, String... columns)
            throws SQLException {
        String actualTable = findTable(connection, table);
        if (actualTable == null || findIndex(connection, actualTable, indexName)) {
            return;
        }
        StringBuilder cols = new StringBuilder();
        for (String column : columns) {
            String actualColumn = findColumn(connection, actualTable, column);
            if (actualColumn == null) {
                System.err.println("V20: skip index " + indexName + ", column " + column + " missing on " + actualTable);
                return;
            }
            if (!cols.isEmpty()) {
                cols.append(", ");
            }
            cols.append(quote(connection, actualColumn));
        }
        if (mysqlLastColumnPrefix != null && isMySql(connection)) {
            cols.append("(").append(mysqlLastColumnPrefix).append(")");
        }
        execute(connection, "CREATE " + (unique ? "UNIQUE " : "") + "INDEX "
                + indexName + " ON " + quote(connection, actualTable) + " (" + cols + ")");
    }

    private boolean isMySql(Connection connection) throws SQLException {
        String name = connection.getMetaData().getDatabaseProductName();
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("mysql") || lower.contains("mariadb");
    }

    private String findTable(Connection connection, String table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getTables(connection.getCatalog(), schemaPattern(connection), null, null)) {
            while (rs.next()) {
                if (rs.getString("TABLE_NAME").equalsIgnoreCase(table)) {
                    return rs.getString("TABLE_NAME");
                }
            }
        }
        return null;
    }

    private String findColumn(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getColumns(connection.getCatalog(), schemaPattern(connection), table, null)) {
            while (rs.next()) {
                if (rs.getString("COLUMN_NAME").equalsIgnoreCase(column)) {
                    return rs.getString("COLUMN_NAME");
                }
            }
        }
        return null;
    }

    private boolean findIndex(Connection connection, String table, String indexName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getIndexInfo(connection.getCatalog(), schemaPattern(connection), table, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null && name.equalsIgnoreCase(indexName)) {
                    return true;
                }
            }
        } catch (SQLException e) {
            // 部分驱动对无索引表会抛错,容忍并按"不存在"处理
        }
        return false;
    }

    private String schemaPattern(Connection connection) throws SQLException {
        String schema = connection.getSchema();
        return schema == null || schema.isBlank() ? null : schema;
    }

    private String quote(Connection connection, String identifier) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            return identifier;
        }
        return quote + identifier + quote;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
