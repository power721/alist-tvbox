package cn.har01d.alist_tvbox.config;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
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
    private static final String TEMP_LOGIN_IP = "login_ip_v6_existing";
    private static final String TEMP_USER_AGENT = "user_agent_v6_existing";

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

    private void prepareForV6(Connection connection) throws SQLException {
        if (hasSuccessfulV6(connection)) {
            return;
        }
        String table = findTable(connection, SESSION_TABLE);
        if (table == null) {
            return;
        }

        moveExistingColumnAside(connection, table, LOGIN_IP, TEMP_LOGIN_IP);
        moveExistingColumnAside(connection, table, USER_AGENT, TEMP_USER_AGENT);
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
