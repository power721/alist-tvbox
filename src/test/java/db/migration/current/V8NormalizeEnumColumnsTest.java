package db.migration.current;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V8NormalizeEnumColumnsTest {

    @Test
    void convertsH2NativeEnumColumnsToVarchar() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:enum-columns;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE x_user (
                            id INTEGER PRIMARY KEY,
                            role ENUM('ADMIN', 'USER')
                        )
                        """);
                statement.execute("INSERT INTO x_user (id, role) VALUES (1, 'USER')");
                statement.execute("""
                        CREATE TABLE task (
                            id INTEGER PRIMARY KEY,
                            task_type ENUM('INDEX', 'SCRAPE')
                        )
                        """);
                statement.execute("INSERT INTO task (id, task_type) VALUES (1, 'INDEX')");
            }

            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);

            new V8__Normalize_enum_columns().migrate(context);

            assertThat(columnType(connection, "x_user", "role")).contains("CHARACTER VARYING");
            assertThat(columnType(connection, "task", "task_type")).contains("CHARACTER VARYING");

            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO x_user (id, role) VALUES (2, 'CLIENT')");
                statement.execute("INSERT INTO task (id, task_type) VALUES (2, 'DOWNLOAD')");
            }

            assertThat(queryString(connection, "SELECT role FROM x_user WHERE id = 2")).isEqualTo("CLIENT");
            assertThat(queryString(connection, "SELECT task_type FROM task WHERE id = 2")).isEqualTo("DOWNLOAD");
        }
    }

    @Test
    void keepsDriverAccountTypeNumericAndRemovesOldOrdinalConstraint() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:driver-type;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE driver_account (
                            id INTEGER PRIMARY KEY,
                            type TINYINT CONSTRAINT chk_driver_account_type CHECK (type IN (0, 1))
                        )
                        """);
                statement.execute("INSERT INTO driver_account (id, type) VALUES (1, 0)");
            }

            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);

            new V8__Normalize_enum_columns().migrate(context);

            assertThat(columnType(connection, "driver_account", "type")).contains("SMALLINT");

            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO driver_account (id, type) VALUES (2, 26)");
            }

            assertThat(queryInt(connection, "SELECT type FROM driver_account WHERE id = 2")).isEqualTo(26);
        }
    }

    @Test
    void convertsLegacyTaskWithMultipleCheckConstraintsAndStaysIdempotent() throws Exception {
        // Legacy schema shape (deleted historical V1): task_type as VARCHAR plus three
        // unnamed table-level CHECK constraints. New enum values must be insertable after V8.
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:legacy-task;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE task (
                            id INTEGER PRIMARY KEY,
                            result TINYINT,
                            status TINYINT,
                            task_type CHARACTER VARYING(255),
                            check ("RESULT" BETWEEN 0 AND 2),
                            check ("STATUS" BETWEEN 0 AND 2),
                            check ("TASK_TYPE" IN ('INDEX', 'VALIDATE_INDEX', 'SCRAPE', 'SYNC_META', 'DOWNLOAD'))
                        )
                        """);
                statement.execute("INSERT INTO task (id, task_type) VALUES (1, 'INDEX')");
            }

            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);

            V8__Normalize_enum_columns migration = new V8__Normalize_enum_columns();
            migration.migrate(context);
            // Re-running must be a no-op (constraints already dropped) and must not throw.
            migration.migrate(context);

            assertThat(columnType(connection, "task", "task_type")).contains("CHARACTER VARYING");

            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO task (id, task_type) VALUES (2, 'DOWNLOAD')");
            }
            assertThat(queryString(connection, "SELECT task_type FROM task WHERE id = 2")).isEqualTo("DOWNLOAD");
        }
    }

    @Test
    void droppingAnAlreadyAbsentCheckConstraintDoesNotThrow() throws Exception {
        // On legacy databases INFORMATION_SCHEMA can report a constraint name that is no
        // longer in the live registry; the migration must tolerate the resulting 90057
        // instead of aborting.
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:absent-check;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE task (id INTEGER PRIMARY KEY, task_type VARCHAR(255))");
            }
            String table = actualTableName(connection, "task");
            new V8__Normalize_enum_columns().dropCheckConstraint(connection, table, "CONSTRAINT_DOES_NOT_EXIST", false);
        }
    }

    private String actualTableName(Connection connection, String table) throws Exception {
        try (ResultSet resultSet = connection.getMetaData().getTables(connection.getCatalog(), null, null, null)) {
            while (resultSet.next()) {
                if (resultSet.getString("TABLE_NAME").equalsIgnoreCase(table)) {
                    return resultSet.getString("TABLE_NAME");
                }
            }
        }
        return table;
    }

    private String columnType(Connection connection, String table, String column) throws Exception {
        try (ResultSet resultSet = connection.getMetaData().getColumns(connection.getCatalog(), null, null, null)) {
            while (resultSet.next()) {
                if (resultSet.getString("TABLE_NAME").equalsIgnoreCase(table)
                        && resultSet.getString("COLUMN_NAME").equalsIgnoreCase(column)) {
                    return resultSet.getString("TYPE_NAME");
                }
            }
        }
        return "";
    }

    private String queryString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private int queryInt(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
