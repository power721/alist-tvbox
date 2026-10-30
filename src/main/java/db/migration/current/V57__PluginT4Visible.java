package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V57:plugin 表加 t4_visible(T4 接口可见性,订阅源管理「T4」开关)。
 * <p>
 * false = 该插件源不进 /t4 配置(独立地址与订阅 T4 同一变换),插件五动作直连也 404;
 * 仅影响 T4 面,TVBox 订阅(T3)不受影响。存量插件默认 true,行为无感。
 * 跨库(H2/MySQL/PG)幂等:列已存在时静默跳过(同 V37 模式);标识符不加引号(见 V21 教训)。
 */
public class V57__PluginT4Visible extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "plugin");
        if (table == null || findColumn(connection, table, "t4_visible") != null) {
            return;
        }
        execute(connection, "ALTER TABLE " + table + " ADD COLUMN t4_visible BOOLEAN NOT NULL DEFAULT TRUE");
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
