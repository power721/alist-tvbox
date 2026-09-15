package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V55:回填 offline_download_task.cleanup_attempts 的存量 NULL。
 * <p>
 * V54 加列是裸 INT(无默认值),存量行该列为 NULL,而实体字段是 primitive int,
 * 任何加载存量行的查询(备份导出/定时备份/清理调度)都会抛
 * "Null value was assigned to a property ... of primitive type"。
 * 实体是唯一写入方且恒写 0,回填一次即永久。标识符不加引号(见 V21 教训)。
 */
public class V55__OfflineDownloadTaskCleanupBackfill extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "offline_download_task");
        if (table == null || findColumn(connection, table, "cleanup_attempts") == null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("UPDATE " + table + " SET cleanup_attempts = 0 WHERE cleanup_attempts IS NULL");
        }
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
}
