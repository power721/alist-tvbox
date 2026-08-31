package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V44:media_subscription_resource 加 season_starts VARCHAR(120)。
 * <p>
 * 季包编号映射表(自动,豆瓣分季集数累推):多季合一包(S04E01 还带前 3 季)里各季文件
 * 季内集号互相碰撞,单值 start_episode 平移表达不了 —— 按文件各自 SxxEyy 的季逐个映射
 * 进全剧连续集号空间,映射成功即持久化,豆瓣缓存过期/条目下线后不再依赖外网。
 * 标识符不加引号(见 V21 教训)。
 */
public class V44__MediaSubscriptionResourceSeasonStarts extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "media_subscription_resource");
        if (table == null || findColumn(connection, table, "season_starts") != null) {
            return;
        }
        execute(connection, "ALTER TABLE " + table + " ADD COLUMN season_starts VARCHAR(120)");
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
