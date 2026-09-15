package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V54:offline_download_task 加离线清理五列(115 离线任务+文件自动清理,docs/pan115-offline-auto-delete-design.md)。
 * <p>
 * cleanup_state/cleanup_attempts/cleanup_time = 每日清理调度的执行状态(null 未处理/DONE 已清理/FAILED 重试);
 * completed_time = 完成/收割时间(通用入口 TTL 起算点);share_url = 清理前固化的 115 永久分享地址留档。
 * 标识符不加引号(见 V21 教训)。
 */
public class V54__OfflineDownloadTaskCleanup extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "offline_download_task");
        if (table == null) {
            return;
        }
        addColumn(connection, table, "cleanup_state", "VARCHAR(16)");
        addColumn(connection, table, "cleanup_attempts", "INT");
        addColumn(connection, table, "cleanup_time", "TIMESTAMP");
        addColumn(connection, table, "completed_time", "TIMESTAMP");
        addColumn(connection, table, "share_url", "VARCHAR(500)");
    }

    private void addColumn(Connection connection, String table, String column, String definition) throws SQLException {
        if (findColumn(connection, table, column) != null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
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
