package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V43:media_subscription_resource 加 start_episode(INT)。
 * <p>
 * 资源级手动集号偏移:同一订阅混着多套编号语义时(元数据全剧连续集号,S1 包裸 1-52、
 * 完结季季包裸 1-8、连续合集 1-168),按资源声明「该资源第 1 集对应全剧第 N 集」,
 * 该资源文件集号统一 +N-1 平移进官方连续集号空间 —— 单订阅即可覆盖全部集。
 * 与订阅级 season_start_episode 共存,资源级优先。标识符不加引号(见 V21 教训)。
 */
public class V43__MediaSubscriptionResourceStart extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "media_subscription_resource");
        if (table == null || findColumn(connection, table, "start_episode") != null) {
            return;
        }
        execute(connection, "ALTER TABLE " + table + " ADD COLUMN start_episode INT");
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
