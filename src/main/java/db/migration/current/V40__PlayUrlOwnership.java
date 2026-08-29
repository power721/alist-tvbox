package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V40:盘线路长效 pid(PlayUrl)加 owner_uid。
 * <p>
 * owner_uid=0 为共享行(存量 365 天盘线路与全部短时代理行,默认放行);&gt;0 为该用户订阅注册的盘线路,
 * /p/{token}/{pid} 校验 token 用户与 pid 归属一致,支持按用户吊销(/play-urls 按归属过滤)。
 * 标识符不加引号(见 V21 教训);列已存在时静默跳过。
 */
public class V40__PlayUrlOwnership extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        addColumn(connection, "play_url", "owner_uid", "ALTER TABLE %s ADD COLUMN owner_uid INTEGER NOT NULL DEFAULT 0");
    }

    private void addColumn(Connection connection, String table, String column, String ddl) throws SQLException {
        String name = findTable(connection, table);
        if (name == null || findColumn(connection, name, column) != null) {
            return;
        }
        execute(connection, String.format(ddl, name));
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
