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

class V2NormalizeReservedColumnsTest {

    @Test
    void migratesReservedColumnsAndPreservesData() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:reserved-columns;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE site (
                            id INTEGER PRIMARY KEY,
                            "order" INTEGER,
                            "version" INTEGER
                        )
                        """);
                statement.execute("""
                        INSERT INTO site (id, "order", "version")
                        VALUES (1, 8, 4)
                        """);
                statement.execute("""
                        CREATE TABLE navigation (
                            id INTEGER PRIMARY KEY,
                            "order" INTEGER
                        )
                        """);
                statement.execute("""
                        INSERT INTO navigation (id, "order")
                        VALUES (2, 6)
                        """);
            }

            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);

            new V2__Normalize_reserved_columns().migrate(context);

            assertThat(columnExists(connection, "site", "sort_order")).isTrue();
            assertThat(columnExists(connection, "site", "storage_version")).isTrue();
            assertThat(columnExists(connection, "site", "order")).isFalse();
            assertThat(columnExists(connection, "site", "version")).isFalse();
            assertThat(queryInt(connection, "SELECT sort_order FROM site WHERE id = 1")).isEqualTo(8);
            assertThat(queryInt(connection, "SELECT storage_version FROM site WHERE id = 1")).isEqualTo(4);

            assertThat(columnExists(connection, "navigation", "sort_order")).isTrue();
            assertThat(columnExists(connection, "navigation", "order")).isFalse();
            assertThat(queryInt(connection, "SELECT sort_order FROM navigation WHERE id = 2")).isEqualTo(6);
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (ResultSet resultSet = connection.getMetaData().getColumns(connection.getCatalog(), null, null, null)) {
            while (resultSet.next()) {
                if (resultSet.getString("TABLE_NAME").equalsIgnoreCase(table)
                        && resultSet.getString("COLUMN_NAME").equalsIgnoreCase(column)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int queryInt(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
