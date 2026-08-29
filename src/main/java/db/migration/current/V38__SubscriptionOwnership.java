package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V38:subscription 表加 owner_uid(0=全局默认订阅,所有用户可用;&gt;0=该用户的个人订阅)。
 * 存量全部 0(全局),行为无感。标识符不加引号(见 V21 教训);列已存在时静默跳过。
 */
public class V38__SubscriptionOwnership extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "subscription");
        if (table == null || findColumn(connection, table, "owner_uid") != null) {
            return;
        }
        execute(connection, "ALTER TABLE " + table + " ADD COLUMN owner_uid INTEGER NOT NULL DEFAULT 0");
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
