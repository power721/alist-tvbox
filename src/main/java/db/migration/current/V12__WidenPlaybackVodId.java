package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * V12:放宽 vod_id 长度。
 * <p>
 * vod_id 存的是 TVBox 的复合条目 id —— 网盘/电报源里它是一段 URL 编码的 JSON(内含标题、链接),
 * 动辄数百字符,V10 建的 VARCHAR(255) 装不下:上报时以
 * {@code Value too long for column "VOD_ID"}(22001)失败,该条播放记录直接丢掉。
 * <p>
 * vod_id 是记录身份的一部分,**不能截断**(截断会把不同条目并成同一条),故放宽为 TEXT。
 * 同步索引随之去掉 vod_id 列:MySQL 的 TEXT 列进索引必须给前缀长度,PG 的 btree 也有键长上限;
 * 保留 {@code (uid, source_kind, source_key)},选择性已足够,余下按等值过滤即可。
 * <p>
 * 顺序:**先删索引再改列** —— MySQL 上直接把索引内的列改成 TEXT 会以
 * "used in key specification without a key length" 失败。
 * 全程跨库(H2/MySQL/PG)幂等:改列前先查 metadata,已是大文本则跳过。
 */
public class V12__WidenPlaybackVodId extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        dropIndexIfPresent(connection, "history", "idx_history_sync");
        dropIndexIfPresent(connection, "playback_tombstone", "idx_pb_tomb");

        widenToText(connection, "history", "vod_id");
        widenToText(connection, "playback_tombstone", "vod_id");

        createIndexIfMissing(connection, "history", "idx_history_sync", "uid", "source_kind", "source_key");
        createIndexIfMissing(connection, "playback_tombstone", "idx_pb_tomb", "uid", "source_kind", "source_key");
    }

    private void widenToText(Connection connection, String table, String column) throws SQLException {
        String actualTable = findTable(connection, table);
        if (actualTable == null) {
            return;
        }
        ColumnInfo columnInfo = findColumn(connection, actualTable, column);
        if (columnInfo == null || isLargeText(columnInfo)) {
            return;
        }
        String db = databaseProduct(connection);
        if (isMySql(db)) {
            execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                    + " MODIFY COLUMN " + quote(connection, columnInfo.name()) + " TEXT");
        } else if (isPostgreSql(db)) {
            execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                    + " ALTER COLUMN " + quote(connection, columnInfo.name())
                    + " TYPE TEXT USING " + quote(connection, columnInfo.name()) + "::text");
        } else {
            execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                    + " ALTER COLUMN " + quote(connection, columnInfo.name()) + " TEXT");
        }
    }

    /** 各库把 TEXT 报成 CLOB / TEXT / 超长 VARCHAR,统一按"已是大文本"处理,保证重复执行安全。 */
    private boolean isLargeText(ColumnInfo columnInfo) {
        String type = columnInfo.typeName() == null ? "" : columnInfo.typeName().toUpperCase(Locale.ROOT);
        if (type.contains("LOB") || type.contains("TEXT")) {
            return true;
        }
        return columnInfo.size() <= 0 || columnInfo.size() > 65535;
    }

    private void dropIndexIfPresent(Connection connection, String table, String indexName) throws SQLException {
        String actualTable = findTable(connection, table);
        if (actualTable == null || !findIndex(connection, actualTable, indexName)) {
            return;
        }
        // 索引名不加引号:各库按自身规则归一,与 V10 建索引时一致
        String sql = isMySql(databaseProduct(connection))
                ? "DROP INDEX " + indexName + " ON " + quote(connection, actualTable)
                : "DROP INDEX " + indexName;
        execute(connection, sql);
    }

    private void createIndexIfMissing(Connection connection, String table, String indexName, String... columns)
            throws SQLException {
        String actualTable = findTable(connection, table);
        if (actualTable == null || findIndex(connection, actualTable, indexName)) {
            return;
        }
        StringBuilder cols = new StringBuilder();
        for (String column : columns) {
            ColumnInfo columnInfo = findColumn(connection, actualTable, column);
            if (columnInfo == null) {
                System.err.println("V12: skip index " + indexName + ", column " + column + " missing on " + actualTable);
                return;
            }
            if (!cols.isEmpty()) {
                cols.append(", ");
            }
            cols.append(quote(connection, columnInfo.name()));
        }
        execute(connection, "CREATE INDEX " + indexName + " ON " + quote(connection, actualTable) + " (" + cols + ")");
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

    private ColumnInfo findColumn(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getColumns(connection.getCatalog(), schemaPattern(connection), table, null)) {
            while (rs.next()) {
                if (rs.getString("COLUMN_NAME").equalsIgnoreCase(column)) {
                    return new ColumnInfo(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"));
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

    private String databaseProduct(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    }

    private boolean isMySql(String db) {
        return db.contains("mysql") || db.contains("mariadb");
    }

    private boolean isPostgreSql(String db) {
        return db.contains("postgresql");
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

    private record ColumnInfo(String name, String typeName, int size) {
    }
}
