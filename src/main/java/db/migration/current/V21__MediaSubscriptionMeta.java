package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V21:追剧订阅 P1 —— 元数据平台接入与缺集补搜。
 * media_subscription 增加元数据来源与官方集数/日程快照;
 * media_subscription_resource 增加集数覆盖快照与补缺挂载信息。跨库幂等:列先查 metadata 再加。
 */
public class V21__MediaSubscriptionMeta extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        addColumnIfMissing(connection, "media_subscription", "meta_provider", "VARCHAR(16)");
        addColumnIfMissing(connection, "media_subscription", "meta_id", "VARCHAR(64)");
        addColumnIfMissing(connection, "media_subscription", "official_episodes", "INTEGER");
        addColumnIfMissing(connection, "media_subscription", "official_total", "INTEGER");
        addColumnIfMissing(connection, "media_subscription", "official_status", "VARCHAR(16)");
        addColumnIfMissing(connection, "media_subscription", "next_air_time", "BIGINT");
        addColumnIfMissing(connection, "media_subscription", "meta_sync_time", "BIGINT");

        addColumnIfMissing(connection, "media_subscription_resource", "episode_list", "TEXT");
        addColumnIfMissing(connection, "media_subscription_resource", "mount_path", "VARCHAR(512)");
        addColumnIfMissing(connection, "media_subscription_resource", "share_id", "INTEGER");
        addColumnIfMissing(connection, "media_subscription_resource", "gap", "BOOLEAN DEFAULT FALSE");
    }

    private void addColumnIfMissing(Connection connection, String table, String column, String definition) throws SQLException {
        String actualTable = findTable(connection, table);
        if (actualTable == null || findColumn(connection, actualTable, column) != null) {
            return;
        }
        // 注意:列名/表名一律不加引号 —— 带引号的小写名在 H2 会按区分大小写精确存储,
        // 而 Hibernate 生成的 SQL 不带引号(解析为大写 META_ID),导致 "Column not found"。
        // 不加引号则各库按自身约定折叠(H2→大写/PG→小写/MySQL→不敏感),与 ORM 行为一致。
        execute(connection, "ALTER TABLE " + actualTable + " ADD COLUMN " + column + " " + definition);
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
