package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * V37:三张账号表(account/driver_account/pik_pak_account)加 owner_uid 与 shared。
 * <p>
 * owner_uid=0 为全局账号(管理员所有);&gt;0 为该用户的个人账号。shared 仅对全局账号有意义:
 * 是否允许普通用户经服务端代理使用(凭证仍不下发)。存量账号全部是管理员添加的,默认 0/true,
 * 升级后普通用户行为无感(经代理使用)。标识符不加引号(见 V21 教训);列已存在时静默跳过。
 */
public class V37__AccountOwnership extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        addColumn(connection, "driver_account", "owner_uid", "ALTER TABLE %s ADD COLUMN owner_uid INTEGER NOT NULL DEFAULT 0");
        addColumn(connection, "driver_account", "shared", "ALTER TABLE %s ADD COLUMN shared BOOLEAN NOT NULL DEFAULT TRUE");
        addColumn(connection, "account", "owner_uid", "ALTER TABLE %s ADD COLUMN owner_uid INTEGER NOT NULL DEFAULT 0");
        addColumn(connection, "account", "shared", "ALTER TABLE %s ADD COLUMN shared BOOLEAN NOT NULL DEFAULT TRUE");
        addColumn(connection, "pik_pak_account", "owner_uid", "ALTER TABLE %s ADD COLUMN owner_uid INTEGER NOT NULL DEFAULT 0");
        addColumn(connection, "pik_pak_account", "shared", "ALTER TABLE %s ADD COLUMN shared BOOLEAN NOT NULL DEFAULT TRUE");
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
