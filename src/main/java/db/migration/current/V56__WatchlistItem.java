package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V56:稍后再看(watchlist_item 用户级想看队列,docs/watchlist-design.md)。
 * <p>
 * 跨库(H2/MySQL/PG)幂等:新表 CREATE TABLE IF NOT EXISTS,索引先查 metadata 再建。
 * 标识符不加引号(见 V21 教训)。status 为 String 常量(同 media_subscription.status 形态)。
 */
public class V56__WatchlistItem extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        execute(connection, """
                CREATE TABLE IF NOT EXISTS watchlist_item (
                    id INTEGER NOT NULL PRIMARY KEY,
                    uid INTEGER NOT NULL,
                    vod_id VARCHAR(250) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    title VARCHAR(250) NOT NULL,
                    "year" INTEGER,
                    season INTEGER,
                    pic VARCHAR(512),
                    remarks VARCHAR(250),
                    created_time BIGINT NOT NULL,
                    status_time BIGINT
                )""");

        // (uid, vod_id) 唯一:防重复加入;uid 前缀即可按用户查列表
        createIndexIfMissing(connection, "watchlist_item", "uk_watchlist_item", true, "uid", "vod_id");
        createIndexIfMissing(connection, "watchlist_item", "idx_watchlist_uid_status", false, "uid", "status");
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void createIndexIfMissing(Connection connection, String table, String indexName, boolean unique, String... columns)
            throws SQLException {
        String actualTable = findTable(connection, table);
        if (actualTable == null || findIndex(connection, actualTable, indexName)) {
            return;
        }
        StringBuilder cols = new StringBuilder();
        for (String column : columns) {
            String actualColumn = findColumn(connection, actualTable, column);
            if (actualColumn == null) {
                System.err.println("V56: skip index " + indexName + ", column " + column + " missing on " + actualTable);
                return;
            }
            if (!cols.isEmpty()) {
                cols.append(", ");
            }
            cols.append(actualColumn);
        }
        execute(connection, "CREATE " + (unique ? "UNIQUE " : "") + "INDEX "
                + indexName + " ON " + actualTable + " (" + cols + ")");
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
}
