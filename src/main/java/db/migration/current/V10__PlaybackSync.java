package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V10:多端播放记录同步。
 * ① 给 history 加规范化身份列(source_kind/source_key/vod_id)+ 冲突时钟(updated_at)+ 设备(client_key);
 * ② 建 playback_token(同步令牌)、playback_tombstone(删除墓碑,90天)表;
 * ③ 建索引。
 * 全程跨库(H2/MySQL/PG)幂等:列/索引先查 metadata 再建,新表用 CREATE TABLE IF NOT EXISTS。
 */
public class V10__PlaybackSync extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        // ① history 加列(裸字段经命名策略映射为 snake_case)
        addColumnIfMissing(connection, "history", "source_kind", "VARCHAR(255)");
        addColumnIfMissing(connection, "history", "source_key", "VARCHAR(255)");
        addColumnIfMissing(connection, "history", "source_name", "VARCHAR(255)");
        addColumnIfMissing(connection, "history", "vod_id", "VARCHAR(255)");
        addColumnIfMissing(connection, "history", "updated_at", "BIGINT");
        addColumnIfMissing(connection, "history", "client_key", "VARCHAR(64)");

        // ② 新表
        execute(connection, """
                CREATE TABLE IF NOT EXISTS playback_token (
                    id INTEGER NOT NULL PRIMARY KEY,
                    uid INTEGER NOT NULL,
                    token VARCHAR(64) NOT NULL,
                    name VARCHAR(128),
                    created_time BIGINT NOT NULL,
                    last_used_at BIGINT NOT NULL
                )""");
        execute(connection, """
                CREATE TABLE IF NOT EXISTS playback_tombstone (
                    id INTEGER NOT NULL PRIMARY KEY,
                    uid INTEGER NOT NULL,
                    scope VARCHAR(16),
                    source_kind VARCHAR(255),
                    source_key VARCHAR(255),
                    vod_id VARCHAR(255),
                    history_key TEXT,
                    deleted_at BIGINT NOT NULL,
                    expire_at BIGINT NOT NULL
                )""");

        // ③ 索引(令牌 token 唯一;墓碑与 history 同步查询索引)
        createIndexIfMissing(connection, "playback_token", "uk_pb_token", true, "token");
        createIndexIfMissing(connection, "playback_tombstone", "idx_pb_tomb", false, "uid", "source_kind", "source_key", "vod_id");
        createIndexIfMissing(connection, "history", "idx_history_sync", false, "uid", "source_kind", "source_key", "vod_id");
    }

    private void addColumnIfMissing(Connection connection, String table, String column, String type) throws SQLException {
        String actualTable = findTable(connection, table);
        if (actualTable == null) {
            return;
        }
        if (findColumn(connection, actualTable, column) == null) {
            // 新列名不加引号:让各库按自身规则归一(H2 存为大写、PG 存为小写),与 Hibernate 引用裸标识符一致。
            // 加引号会在 H2 上建出区分大小写的小写列,后续 DDL 与 ddl-auto=validate 都找不到它。
            execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                    + " ADD COLUMN " + column + " " + type);
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
            // 列名取 metadata 里的实际大小写再加引号:CREATE TABLE 未加引号,H2 把 token 存成 TOKEN,
            // 直接引用小写 "token" 会以 Column "token" not found 中断迁移。
            String actualColumn = findColumn(connection, actualTable, column);
            if (actualColumn == null) {
                System.err.println("V10: skip index " + indexName + ", column " + column + " missing on " + actualTable);
                return;
            }
            if (!cols.isEmpty()) {
                cols.append(", ");
            }
            cols.append(quote(connection, actualColumn));
        }
        execute(connection, "CREATE " + (unique ? "UNIQUE " : "") + "INDEX "
                + indexName + " ON " + quote(connection, actualTable) + " (" + cols + ")");
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
