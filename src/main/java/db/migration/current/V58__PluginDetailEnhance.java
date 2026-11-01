package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V58:plugin 表加 detail_enhance(T4 详情增强三态,订阅源管理「增强」开关)。
 * <p>
 * NULL = 跟随全局 t4_detail_enhance 开关 + 短剧/音乐源自动检测(存量默认,行为无感);
 * TRUE = 强制增强(检测误判的逃生阀);FALSE = 该源永不增强。
 * 可空三态,与 t4_visible(V57) 的 NOT NULL 不同。跨库(H2/MySQL/PG)幂等:列已存在时静默跳过
 * (同 V37 模式);标识符不加引号(见 V21 教训)。
 */
public class V58__PluginDetailEnhance extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "plugin");
        if (table == null || findColumn(connection, table, "detail_enhance") != null) {
            return;
        }
        execute(connection, "ALTER TABLE " + table + " ADD COLUMN detail_enhance BOOLEAN NULL");
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
