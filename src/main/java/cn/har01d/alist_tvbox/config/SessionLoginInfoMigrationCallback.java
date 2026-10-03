package cn.har01d.alist_tvbox.config;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class SessionLoginInfoMigrationCallback implements Callback {
    private static final String SESSION_TABLE = "session";
    private static final String HISTORY_TABLE = "flyway_schema_history";
    private static final String LOGIN_IP = "login_ip";
    private static final String USER_AGENT = "user_agent";
    private static final String LOGIN_IP_DEFINITION = "VARCHAR(45)";
    private static final String USER_AGENT_DEFINITION = "VARCHAR(512)";
    private static final String TEMP_LOGIN_IP = "login_ip_v6_existing";
    private static final String TEMP_USER_AGENT = "user_agent_v6_existing";

    // 直接 new(测试)时保持 false,与生产默认一致
    @Value("${spring.flyway.out-of-order:false}")
    private boolean outOfOrder;

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_VALIDATE
                || event == Event.BEFORE_MIGRATE
                || event == Event.AFTER_MIGRATE
                || event == Event.AFTER_MIGRATE_ERROR;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        try {
            if (event == Event.BEFORE_VALIDATE) {
                clearFailedV6IfRetryable(context.getConnection());
                finalizeV6ColumnsIfOutOfReach(context.getConnection());
            } else if (event == Event.BEFORE_MIGRATE) {
                prepareForV6(context.getConnection());
            } else {
                restoreV6Columns(context.getConnection());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to handle session login-info migration compatibility", e);
        }
    }

    @Override
    public String getCallbackName() {
        return "session-login-info-migration-compatibility";
    }

    private void clearFailedV6IfRetryable(Connection connection) throws SQLException {
        if (hasSuccessfulV6(connection)) {
            return;
        }
        String table = findTable(connection, SESSION_TABLE);
        if (table == null || !hasAnyV6ColumnState(connection, table)) {
            return;
        }

        deleteFailedV6Rows(connection);
    }

    private void prepareForV6(Connection connection) throws SQLException {
        if (hasSuccessfulV6(connection)) {
            return;
        }
        if (finalizeV6ColumnsIfOutOfReach(connection)) {
            return;
        }
        String table = findTable(connection, SESSION_TABLE);
        if (table == null) {
            return;
        }

        moveExistingColumnAside(connection, table, LOGIN_IP, TEMP_LOGIN_IP);
        moveExistingColumnAside(connection, table, USER_AGENT, TEMP_USER_AGENT);
    }

    /**
     * 库的历史版本已越过 6 且未开 out-of-order 时,V6 永远不会按序重跑(集成版预置库、
     * 在 V6 引入前就越过版本 6 的老库都没有 V6 历史行)。此时绝不能把真列挪走等 V6 重建,
     * 否则列被销毁、Hibernate validate 报缺列进入崩溃循环;反而要把已损坏的状态收敛回
     * V6 的最终形态:临时列改名回真列、两列并存则拷回数据、彻底缺列则补列。
     *
     * @return true 表示 V6 已无重跑机会(调用方应放弃挪列等待)
     */
    private boolean finalizeV6ColumnsIfOutOfReach(Connection connection) throws SQLException {
        if (hasSuccessfulV6(connection) || !isV6OutOfReach(connection)) {
            return false;
        }
        // V6 不会再执行,残留的失败行只会阻断启动,无条件的列状态前提在这里不适用
        deleteFailedV6Rows(connection);
        String table = findTable(connection, SESSION_TABLE);
        if (table != null) {
            finalizeColumn(connection, table, TEMP_LOGIN_IP, LOGIN_IP, LOGIN_IP_DEFINITION);
            finalizeColumn(connection, table, TEMP_USER_AGENT, USER_AGENT, USER_AGENT_DEFINITION);
        }
        return true;
    }

    private boolean isV6OutOfReach(Connection connection) throws SQLException {
        return !outOfOrder && maxAppliedVersion(connection) > 6;
    }

    /**
     * 挪走真列等 V6 重建的前提是 V6 本次会执行;库版本已越过 6 时 Flyway 默认
     * 不补跑旧版本迁移,该前提不成立。
     */
    private int maxAppliedVersion(Connection connection) throws SQLException {
        String historyTable = findTable(connection, HISTORY_TABLE);
        if (historyTable == null) {
            return 0;
        }
        String versionColumn = findColumn(connection, historyTable, "version");
        String successColumn = findColumn(connection, historyTable, "success");
        if (versionColumn == null || successColumn == null) {
            return 0;
        }
        int max = 0;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT " + quote(connection, versionColumn)
                     + " FROM " + quote(connection, historyTable)
                     + " WHERE " + quote(connection, successColumn) + " = TRUE")) {
            while (resultSet.next()) {
                String version = resultSet.getString(1);
                if (version != null && version.matches("\\d+")) {
                    max = Math.max(max, Integer.parseInt(version));
                }
            }
        }
        return max;
    }

    private void deleteFailedV6Rows(Connection connection) throws SQLException {
        String historyTable = findTable(connection, HISTORY_TABLE);
        if (historyTable == null) {
            return;
        }
        String versionColumn = findColumn(connection, historyTable, "version");
        String successColumn = findColumn(connection, historyTable, "success");
        if (versionColumn == null || successColumn == null) {
            return;
        }
        execute(connection, "DELETE FROM " + quote(connection, historyTable)
                + " WHERE " + quote(connection, versionColumn) + " = '6'"
                + " AND " + quote(connection, successColumn) + " = FALSE");
    }

    private void finalizeColumn(Connection connection, String table, String tempColumn, String column, String definition)
            throws SQLException {
        String actualTempColumn = findColumn(connection, table, tempColumn);
        String actualColumn = findColumn(connection, table, column);
        if (actualColumn == null && actualTempColumn != null) {
            execute(connection, "ALTER TABLE " + quote(connection, table)
                    + " RENAME COLUMN " + quote(connection, actualTempColumn)
                    // 目标名不加引号:与 V6 自身的 ADD COLUMN 标识符折叠规则一致,避免 H2 存成引号小写列
                    + " TO " + column);
            return;
        }
        if (actualColumn != null && actualTempColumn != null) {
            copyColumn(connection, table, actualTempColumn, actualColumn);
            dropColumn(connection, table, actualTempColumn);
            return;
        }
        if (actualColumn == null) {
            execute(connection, "ALTER TABLE " + quote(connection, table)
                    + " ADD COLUMN " + column + " " + definition);
        }
    }

    private void restoreV6Columns(Connection connection) throws SQLException {
        String table = findTable(connection, SESSION_TABLE);
        if (table == null) {
            return;
        }

        restoreColumn(connection, table, TEMP_LOGIN_IP, LOGIN_IP);
        restoreColumn(connection, table, TEMP_USER_AGENT, USER_AGENT);
    }

    private boolean hasAnyV6ColumnState(Connection connection, String table) throws SQLException {
        return findColumn(connection, table, LOGIN_IP) != null
                || findColumn(connection, table, USER_AGENT) != null
                || findColumn(connection, table, TEMP_LOGIN_IP) != null
                || findColumn(connection, table, TEMP_USER_AGENT) != null;
    }

    private void moveExistingColumnAside(Connection connection, String table, String column, String tempColumn)
            throws SQLException {
        String actualColumn = findColumn(connection, table, column);
        String actualTempColumn = findColumn(connection, table, tempColumn);
        if (actualColumn == null) {
            return;
        }
        if (actualTempColumn != null) {
            copyColumn(connection, table, actualColumn, actualTempColumn);
            dropColumn(connection, table, actualColumn);
            return;
        }

        execute(connection, "ALTER TABLE " + quote(connection, table)
                + " RENAME COLUMN " + quote(connection, actualColumn)
                + " TO " + quote(connection, tempColumn));
    }

    private void restoreColumn(Connection connection, String table, String tempColumn, String column)
            throws SQLException {
        String actualTempColumn = findColumn(connection, table, tempColumn);
        String actualColumn = findColumn(connection, table, column);
        if (actualTempColumn == null || actualColumn == null) {
            return;
        }

        copyColumn(connection, table, actualTempColumn, actualColumn);
        dropColumn(connection, table, actualTempColumn);
    }

    private void copyColumn(Connection connection, String table, String fromColumn, String toColumn)
            throws SQLException {
        execute(connection, "UPDATE " + quote(connection, table)
                + " SET " + quote(connection, toColumn) + " = " + quote(connection, fromColumn)
                + " WHERE " + quote(connection, toColumn) + " IS NULL"
                + " AND " + quote(connection, fromColumn) + " IS NOT NULL");
    }

    private void dropColumn(Connection connection, String table, String column) throws SQLException {
        execute(connection, "ALTER TABLE " + quote(connection, table) + " DROP COLUMN " + quote(connection, column));
    }

    private boolean hasSuccessfulV6(Connection connection) throws SQLException {
        String historyTable = findTable(connection, HISTORY_TABLE);
        if (historyTable == null) {
            return false;
        }
        String versionColumn = findColumn(connection, historyTable, "version");
        String successColumn = findColumn(connection, historyTable, "success");
        if (versionColumn == null || successColumn == null) {
            return false;
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1 FROM " + quote(connection, historyTable)
                     + " WHERE " + quote(connection, versionColumn) + " = '6'"
                     + " AND " + quote(connection, successColumn) + " = TRUE")) {
            return resultSet.next();
        }
    }

    private String findTable(Connection connection, String table) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getTables(connection.getCatalog(), schemaPattern(connection), null, null)) {
            while (resultSet.next()) {
                if (resultSet.getString("TABLE_NAME").equalsIgnoreCase(table)) {
                    return resultSet.getString("TABLE_NAME");
                }
            }
        }
        return null;
    }

    private String findColumn(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(connection.getCatalog(), schemaPattern(connection), table, null)) {
            while (resultSet.next()) {
                if (resultSet.getString("COLUMN_NAME").equalsIgnoreCase(column)) {
                    return resultSet.getString("COLUMN_NAME");
                }
            }
        }
        return null;
    }

    private String schemaPattern(Connection connection) throws SQLException {
        String schema = connection.getSchema();
        return schema == null || schema.isBlank() ? null : schema;
    }

    private String quote(Connection connection, String identifier) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            return identifier;
        }
        return quote + identifier + quote;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
