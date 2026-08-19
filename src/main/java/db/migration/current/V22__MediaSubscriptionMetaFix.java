package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * V22:修复 V21 在 H2 上的引号大小写问题。V21 曾用带引号的小写名 ADD COLUMN
 * ("meta_id" 等),H2 按"区分大小写精确名"存储,而 Hibernate 生成的不带引号 SQL
 * 会被折叠为大写(META_ID),导致运行时 "Column MS1_0.META_ID not found"。
 * PG(折叠小写)与 MySQL(列名不敏感)不受影响;本迁移对 H2 实例:
 * 删除带引号创建的异常小写列(均为缓存/快照字段,可安全重建),再以不带引号方式补齐。
 */
public class V22__MediaSubscriptionMetaFix extends BaseJavaMigration {

    private record ColumnFix(String table, String column) {
    }

    private static final List<ColumnFix> COLUMNS = List.of(
            new ColumnFix("media_subscription", "meta_provider"),
            new ColumnFix("media_subscription", "meta_id"),
            new ColumnFix("media_subscription", "official_episodes"),
            new ColumnFix("media_subscription", "official_total"),
            new ColumnFix("media_subscription", "official_status"),
            new ColumnFix("media_subscription", "next_air_time"),
            new ColumnFix("media_subscription", "meta_sync_time"),
            new ColumnFix("media_subscription_resource", "episode_list"),
            new ColumnFix("media_subscription_resource", "mount_path"),
            new ColumnFix("media_subscription_resource", "share_id"),
            new ColumnFix("media_subscription_resource", "gap"));

    private static final List<String> DEFINITIONS = List.of(
            "VARCHAR(16)", "VARCHAR(64)", "INTEGER", "INTEGER", "VARCHAR(16)", "BIGINT", "BIGINT",
            "TEXT", "VARCHAR(512)", "INTEGER", "BOOLEAN DEFAULT FALSE");

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        // 修复仅限 H2:PG(折叠小写)与 MySQL(列名不敏感)上旧 V21 的带引号小写列恰好是正确约定,删了反而丢数据
        boolean h2 = connection.getMetaData().getDatabaseProductName().toUpperCase().contains("H2");
        for (int i = 0; i < COLUMNS.size(); i++) {
            ColumnFix fix = COLUMNS.get(i);
            String actualTable = findTable(connection, fix.table());
            if (actualTable == null) {
                continue;
            }
            String column = fix.column();
            String upper = findColumnExact(connection, actualTable, column.toUpperCase());
            String lower = findColumnExact(connection, actualTable, column);
            if (upper == null && lower != null) {
                if (!h2) {
                    continue; // 非 H2 的 lowercase 列名是库自身约定,保持不动
                }
                // V21 带引号小写列:删除后按不带引号方式重建(内容为快照/缓存,可丢弃)
                execute(connection, "ALTER TABLE " + actualTable + " DROP COLUMN \"" + column + "\"");
                execute(connection, "ALTER TABLE " + actualTable + " ADD COLUMN " + column + " " + DEFINITIONS.get(i));
            } else if (upper == null && lower == null) {
                // V21 未跑过(如修复后的新装实例走这里补齐)
                execute(connection, "ALTER TABLE " + actualTable + " ADD COLUMN " + column + " " + DEFINITIONS.get(i));
            }
        }
    }

    /** 大小写敏感的列查找(getColumns 在部分驱动不敏感,这里用 equals 精确比对)。 */
    private String findColumnExact(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet rs = metaData.getColumns(connection.getCatalog(), schemaPattern(connection), table, null)) {
            while (rs.next()) {
                if (column.equals(rs.getString("COLUMN_NAME"))) {
                    return rs.getString("COLUMN_NAME");
                }
            }
        }
        return null;
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

    private String schemaPattern(Connection connection) throws SQLException {
        String schema = connection.getSchema();
        return schema == null || schema.isBlank() ? null : schema;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
