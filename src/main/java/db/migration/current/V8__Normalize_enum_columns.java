package db.migration.current;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class V8__Normalize_enum_columns extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        normalizeStringEnumColumn(connection, "x_user", "role", "VARCHAR(32)");
        normalizeStringEnumColumn(connection, "task", "task_type", "VARCHAR(64)");
        normalizeOrdinalEnumColumn(connection, "driver_account", "type");
    }

    private void normalizeStringEnumColumn(Connection connection, String table, String column, String targetType)
            throws SQLException {
        String actualTable = findTable(connection, table);
        if (actualTable == null) {
            return;
        }
        ColumnInfo columnInfo = findColumn(connection, actualTable, column);
        if (columnInfo == null) {
            return;
        }

        dropCheckConstraints(connection, actualTable, columnInfo.name());
        if (isVarchar(columnInfo) && !isNativeEnum(columnInfo)) {
            return;
        }

        String db = databaseProduct(connection);
        if (isMySql(db)) {
            execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                    + " MODIFY COLUMN " + quote(connection, columnInfo.name()) + " " + targetType
                    + nullableClause(columnInfo));
        } else if (isPostgreSql(db)) {
            execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                    + " ALTER COLUMN " + quote(connection, columnInfo.name())
                    + " TYPE " + targetType + " USING " + quote(connection, columnInfo.name()) + "::text");
        } else {
            execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                    + " ALTER COLUMN " + quote(connection, columnInfo.name()) + " " + targetType);
        }
    }

    private void normalizeOrdinalEnumColumn(Connection connection, String table, String column) throws SQLException {
        String actualTable = findTable(connection, table);
        if (actualTable == null) {
            return;
        }
        ColumnInfo columnInfo = findColumn(connection, actualTable, column);
        if (columnInfo == null) {
            return;
        }

        dropCheckConstraints(connection, actualTable, columnInfo.name());
        if (isSmallInt(columnInfo) && !isNativeEnum(columnInfo)) {
            return;
        }

        String db = databaseProduct(connection);
        if (isMySql(db)) {
            execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                    + " MODIFY COLUMN " + quote(connection, columnInfo.name()) + " SMALLINT"
                    + nullableClause(columnInfo));
        } else if (isPostgreSql(db)) {
            execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                    + " ALTER COLUMN " + quote(connection, columnInfo.name())
                    + " TYPE SMALLINT USING " + quote(connection, columnInfo.name()) + "::smallint");
        } else {
            execute(connection, "ALTER TABLE " + quote(connection, actualTable)
                    + " ALTER COLUMN " + quote(connection, columnInfo.name()) + " SMALLINT");
        }
    }

    private void dropCheckConstraints(Connection connection, String table, String column) throws SQLException {
        String db = databaseProduct(connection);
        List<String> constraints = new ArrayList<>();
        if (isH2(db)) {
            constraints.addAll(h2CheckConstraints(connection, table, column));
        } else if (isPostgreSql(db)) {
            constraints.addAll(postgreSqlCheckConstraints(connection, table, column));
        } else if (isMySql(db)) {
            constraints.addAll(mySqlCheckConstraints(connection, table, column));
        }

        // INFORMATION_SCHEMA may surface the same auto-generated constraint name more
        // than once on legacy databases, or report a name already absent from the live
        // constraint registry (stale metadata carried across H2 upgrades). Drop each name
        // at most once and tolerate "constraint not found" so the migration stays
        // idempotent instead of aborting. A name that DROP reports as missing is not in
        // the live registry, so it enforces nothing and can be safely skipped.
        boolean mysql = isMySql(db);
        for (String constraint : new LinkedHashSet<>(constraints)) {
            dropCheckConstraint(connection, table, constraint, mysql);
        }
    }

    void dropCheckConstraint(Connection connection, String table, String constraint, boolean mysql)
            throws SQLException {
        String sql = "ALTER TABLE " + quote(connection, table)
                + (mysql ? " DROP CHECK " : " DROP CONSTRAINT ") + quote(connection, constraint);
        try {
            execute(connection, sql);
        } catch (SQLException e) {
            if (!isConstraintNotFound(e)) {
                throw e;
            }
            System.err.println("V8: skip already-absent constraint " + constraint + " on " + table + ": " + e.getMessage());
        }
    }

    private boolean isConstraintNotFound(SQLException e) {
        String state = e.getSQLState();
        if ("90057".equals(state) || e.getErrorCode() == 90057) {
            return true; // H2: Constraint not found
        }
        if ("42704".equals(state)) {
            return true; // PostgreSQL: undefined object
        }
        // MySQL and message-based fallback: only match constraint/check-not-found wording
        // so an unrelated "table not found" is never masked.
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("constraint") && message.contains("not found")
                || message.contains("check constraint") && message.contains("does not exist");
    }

    private List<String> h2CheckConstraints(Connection connection, String table, String column) {
        String sql = """
                SELECT tc.CONSTRAINT_NAME
                FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
                JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
                  ON tc.CONSTRAINT_CATALOG = cc.CONSTRAINT_CATALOG
                 AND tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
                 AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
                WHERE UPPER(tc.TABLE_NAME) = UPPER(?)
                  AND tc.CONSTRAINT_TYPE = 'CHECK'
                  AND UPPER(cc.CHECK_CLAUSE) LIKE UPPER(?)
                """;
        return queryConstraintNames(connection, sql, table, "%" + column + "%");
    }

    private List<String> postgreSqlCheckConstraints(Connection connection, String table, String column) {
        String sql = """
                SELECT c.conname
                FROM pg_constraint c
                JOIN pg_class t ON c.conrelid = t.oid
                JOIN pg_namespace n ON t.relnamespace = n.oid
                WHERE t.relname = ?
                  AND c.contype = 'c'
                  AND pg_get_constraintdef(c.oid) ILIKE ?
                """;
        return queryConstraintNames(connection, sql, table, "%" + column + "%");
    }

    private List<String> mySqlCheckConstraints(Connection connection, String table, String column) throws SQLException {
        String sql = """
                SELECT tc.CONSTRAINT_NAME
                FROM information_schema.TABLE_CONSTRAINTS tc
                JOIN information_schema.CHECK_CONSTRAINTS cc
                  ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
                 AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
                WHERE tc.TABLE_SCHEMA = DATABASE()
                  AND tc.TABLE_NAME = ?
                  AND tc.CONSTRAINT_TYPE = 'CHECK'
                  AND UPPER(cc.CHECK_CLAUSE) LIKE UPPER(?)
                """;
        try {
            return queryConstraintNames(connection, sql, table, "%" + column + "%");
        } catch (RuntimeException e) {
            System.err.println("V8: Unable to inspect MySQL check constraints: " + e.getMessage());
            return List.of();
        }
    }

    private List<String> queryConstraintNames(Connection connection, String sql, String table, String columnPattern) {
        List<String> constraints = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, columnPattern);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    constraints.add(resultSet.getString(1));
                }
            }
        } catch (SQLException e) {
            System.err.println("V8: Unable to inspect check constraints on " + table + ": " + e.getMessage());
        }
        return constraints;
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

    private ColumnInfo findColumn(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(connection.getCatalog(), schemaPattern(connection), table, null)) {
            while (resultSet.next()) {
                if (resultSet.getString("COLUMN_NAME").equalsIgnoreCase(column)) {
                    return new ColumnInfo(
                            resultSet.getString("COLUMN_NAME"),
                            resultSet.getString("TYPE_NAME"),
                            resultSet.getInt("DATA_TYPE"),
                            resultSet.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                }
            }
        }
        return null;
    }

    private boolean isVarchar(ColumnInfo columnInfo) {
        return columnInfo.dataType() == Types.VARCHAR
                || columnInfo.dataType() == Types.LONGVARCHAR
                || typeName(columnInfo).contains("CHARACTER VARYING")
                || typeName(columnInfo).contains("VARCHAR")
                || typeName(columnInfo).equals("TEXT");
    }

    private boolean isSmallInt(ColumnInfo columnInfo) {
        return columnInfo.dataType() == Types.SMALLINT || typeName(columnInfo).equals("SMALLINT");
    }

    private boolean isNativeEnum(ColumnInfo columnInfo) {
        return typeName(columnInfo).contains("ENUM");
    }

    private String typeName(ColumnInfo columnInfo) {
        return columnInfo.typeName() == null ? "" : columnInfo.typeName().toUpperCase(Locale.ROOT);
    }

    private String nullableClause(ColumnInfo columnInfo) {
        return columnInfo.nullable() ? "" : " NOT NULL";
    }

    private String schemaPattern(Connection connection) throws SQLException {
        String schema = connection.getSchema();
        return schema == null || schema.isBlank() ? null : schema;
    }

    private String databaseProduct(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    }

    private boolean isH2(String db) {
        return db.contains("h2");
    }

    private boolean isMySql(String db) {
        return db.contains("mysql") || db.contains("mariadb");
    }

    private boolean isPostgreSql(String db) {
        return db.contains("postgresql");
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

    private record ColumnInfo(String name, String typeName, int dataType, boolean nullable) {
    }
}
