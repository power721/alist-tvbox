package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V35:media_subscription_resource 加 pinned(手动钉选主源标记)。
 * <p>
 * 钉选 = 用户对自动判定的否决:换源候选序置顶、主源归属复核豁免(误挂异剧不再自动换走);
 * 失效退役不清除钉选,恢复可用后优先回归。每订阅至多一个,由服务层维护(钉新清旧)。
 * 存量全部 null(=未钉选),不回填。标识符不加引号(见 V21 教训);列已存在时静默跳过。
 */
public class V35__MediaSubscriptionResourcePinned extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "media_subscription_resource");
        if (table == null || findColumn(connection, table, "pinned") != null) {
            return;
        }
        execute(connection, "ALTER TABLE " + table + " ADD COLUMN pinned BOOLEAN");
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

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
