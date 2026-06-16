package cn.har01d.alist_tvbox.entity;

import db.migration.current.V2__Normalize_reserved_columns;
import db.migration.current.V3__Rename_reserved_columns;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false",
        "logging.file.name=target/legacy-schema-validation.log"
})
class LegacySchemaValidationTest {
    private static final String JDBC_URL = "jdbc:h2:mem:legacy-schema-validation-" + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1";

    @Autowired
    private DataSource dataSource;

    static {
        prepareMigratedSchema();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
    }

    @Test
    void upgradedSchemaSatisfiesJpaValidation() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(columnNullable(connection, "site", "sort_order"))
                    .isEqualTo(DatabaseMetaData.columnNoNulls);
            assertThat(columnNullable(connection, "navigation", "sort_order"))
                    .isEqualTo(DatabaseMetaData.columnNoNulls);
            assertThat(columnNullable(connection, "telegram_channel", "sort_order"))
                    .isEqualTo(DatabaseMetaData.columnNoNulls);

            assertThat(columnNullable(connection, "emby", "sort_order"))
                    .isEqualTo(DatabaseMetaData.columnNullable);
            assertThat(columnNullable(connection, "jellyfin", "sort_order"))
                    .isEqualTo(DatabaseMetaData.columnNullable);
            assertThat(columnNullable(connection, "feiniu", "sort_order"))
                    .isEqualTo(DatabaseMetaData.columnNullable);
        }
    }

    private static void prepareMigratedSchema() {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "")) {
            applyFreshSchema(connection);
            restoreLegacyReservedColumns(connection);
            new V2__Normalize_reserved_columns().migrate(new TestMigrationContext(connection));
            new V3__Rename_reserved_columns().migrate(new TestMigrationContext(connection));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void applyFreshSchema(Connection connection) throws Exception {
        try (var input = LegacySchemaValidationTest.class.getResourceAsStream(
                "/db/migration/current/V1__Create_current_schema.sql")) {
            if (input == null) {
                throw new IllegalStateException("V1 migration not found");
            }
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String sql : migration.split(";")) {
                if (!sql.isBlank()) {
                    execute(connection, sql);
                }
            }
        }
    }

    private static void restoreLegacyReservedColumns(Connection connection) throws Exception {
        restoreLegacySortOrder(connection, "site", true);
        restoreLegacySortOrder(connection, "navigation", true);
        restoreLegacySortOrder(connection, "telegram_channel", true);
        restoreLegacySortOrder(connection, "emby", false);
        restoreLegacySortOrder(connection, "jellyfin", false);
        restoreLegacySortOrder(connection, "feiniu", false);

        execute(connection, "ALTER TABLE site ADD COLUMN \"version\" INTEGER");
        execute(connection, "UPDATE site SET \"version\" = storage_version");
        execute(connection, "ALTER TABLE site DROP COLUMN storage_version");
    }

    private static void restoreLegacySortOrder(Connection connection, String table, boolean required) throws Exception {
        String nullability = required ? " NOT NULL DEFAULT 0" : "";
        execute(connection, "ALTER TABLE " + table + " ADD COLUMN \"order\" INTEGER" + nullability);
        execute(connection, "UPDATE " + table + " SET \"order\" = sort_order");
        execute(connection, "ALTER TABLE " + table + " DROP COLUMN sort_order");
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int columnNullable(Connection connection, String table, String column) throws Exception {
        try (ResultSet columns = connection.getMetaData()
                .getColumns(connection.getCatalog(), connection.getSchema(), table.toUpperCase(), column.toUpperCase())) {
            if (!columns.next()) {
                throw new IllegalStateException(table + "." + column + " not found");
            }
            return columns.getInt("NULLABLE");
        }
    }

    private record TestMigrationContext(Connection connection) implements Context {
        @Override
        public Configuration getConfiguration() {
            return null;
        }

        @Override
        public Connection getConnection() {
            return connection;
        }
    }
}
