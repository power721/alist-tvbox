package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V29:media_subscription.last_episode → max_episode。
 * <p>
 * 该列存的一直是<b>挂载目录里观测到的最大集号</b>(applyInventory 里
 * {@code episodes.stream().max(...)}),属于资源侧指标;而 {@code last_episode} 这个名字读起来
 * 是"最后观看集数",需求文档就是照字面理解写的,结果整套通知门槛都建立在一个不存在的数据上。
 * <p>
 * 观看进度不落这张表 —— 它在 History(uid + vod_id = msub:{订阅id}),由播放记录同步多端合并。
 * <p>
 * 标识符不加引号(见 V21 教训);已改名或列不存在时静默跳过,可重复执行。
 */
public class V29__MediaSubscriptionMaxEpisode extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String table = findTable(connection, "media_subscription");
        if (table == null) {
            return;
        }
        if (findColumn(connection, table, "max_episode") != null) {
            return; // 已迁移
        }
        if (findColumn(connection, table, "last_episode") == null) {
            execute(connection, "ALTER TABLE " + table + " ADD COLUMN max_episode INTEGER");
            return; // 全新库:直接建新列
        }
        execute(connection, "ALTER TABLE " + table + " RENAME COLUMN last_episode TO max_episode");
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
