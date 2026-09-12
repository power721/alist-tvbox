package cn.har01d.alist_tvbox.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reproduces and verifies the fix for the SQL-backup restore failure: {@code restore_database}
 * in the docker init script rebuilds the H2 file from a {@code SCRIPT TO ... TABLE
 * <blacklist-filtered>} dump that excludes FLYWAY_SCHEMA_HISTORY (and MOVIE/META/ALIAS). On boot
 * Flyway sees a non-empty schema with no history table, baseline-on-migrate baselines it at
 * version 1 and replays V2+ against the already-evolved schema, which crashes startup (V5's
 * unique index already exists) — the "restore deletes and rebuilds the db file but never
 * recovers" failure.
 */
class FlywayRepairConfigTest {

    private Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/h2", "classpath:db/migration/common", "classpath:db/migration/current")
            .baselineOnMigrate(true)
            .load();
    }

    /** Flyway 11 creates the history table quoted-lowercase while V1's DDL folds to uppercase. */
    private List<String> tableNames(JdbcTemplate jdbc) {
        return jdbc.queryForList(
            "select lower(table_name) from information_schema.tables where table_schema = 'PUBLIC'", String.class);
    }

    private void breakSchemaLikeSqlRestore(JdbcTemplate jdbc) {
        jdbc.execute("drop table \"flyway_schema_history\"");
        jdbc.execute("drop table movie");
        jdbc.execute("drop table meta");
        jdbc.execute("drop table alias");
    }

    @Test
    void replayingMigrationsOnSqlRestoredSchemaFailsWithoutRepair() {
        DataSource dataSource = TestH2dataSource.newDataSource();
        Flyway flyway = flyway(dataSource);
        // Normal first boot: everything migrates.
        MigrateResult first = flyway.migrate();
        assertThat(first.success).isTrue();
        assertThat(tableNames(new JdbcTemplate(dataSource))).contains("flyway_schema_history", "movie", "meta", "alias");

        breakSchemaLikeSqlRestore(new JdbcTemplate(dataSource));

        // Without repair Flyway baselines at 1 and replays V2+ on the evolved schema -> crash.
        assertThatThrownBy(() -> flyway.migrate()).isInstanceOf(Exception.class);
    }

    @Test
    void repairedMigrationStrategyHealsSqlRestoredSchema() {
        DataSource dataSource = TestH2dataSource.newDataSource();
        Flyway flyway = flyway(dataSource);
        flyway.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        breakSchemaLikeSqlRestore(jdbc);

        FlywayRepairConfig.RepairedMigrationStrategy strategy =
            new FlywayRepairConfig.RepairedMigrationStrategy(dataSource);
        assertThatCode(() -> strategy.migrate(flyway)).doesNotThrowAnyException();

        List<String> tables = tableNames(jdbc);
        assertThat(tables).contains("flyway_schema_history", "movie", "meta", "alias");
        // The injected baseline must pin the schema at the app's newest migration so nothing replays.
        Integer baselineCount = jdbc.queryForObject(
            "select count(*) from \"flyway_schema_history\" where \"type\" = 'BASELINE'", Integer.class);
        assertThat(baselineCount).isEqualTo(1);
        String baselineVersion = jdbc.queryForObject(
            "select \"version\" from \"flyway_schema_history\" where \"type\" = 'BASELINE'", String.class);
        assertThat(baselineVersion).isEqualTo(flyway.info().current().getVersion().getVersion());
    }

    @Test
    void repairedMigrationStrategyLeavesFreshAndNormalDatabasesUntouched() {
        DataSource dataSource = TestH2dataSource.newDataSource();
        Flyway flyway = flyway(dataSource);
        FlywayRepairConfig.RepairedMigrationStrategy strategy =
            new FlywayRepairConfig.RepairedMigrationStrategy(dataSource);

        // Fresh empty database: the full chain runs, no baseline is injected, douban tables exist.
        assertThatCode(() -> strategy.migrate(flyway)).doesNotThrowAnyException();
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().applied().length).isPositive();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(tableNames(jdbc)).contains("movie", "meta", "alias");
        Integer baselines = jdbc.queryForObject(
            "select count(*) from \"flyway_schema_history\" where \"type\" = 'BASELINE'", Integer.class);
        assertThat(baselines).isZero();

        // Normal restart: the strategy is a harmless no-op.
        assertThatCode(() -> strategy.migrate(flyway)).doesNotThrowAnyException();
        Integer baselinesAfter = jdbc.queryForObject(
            "select count(*) from \"flyway_schema_history\" where \"type\" = 'BASELINE'", Integer.class);
        assertThat(baselinesAfter).isZero();
    }

    static final class TestH2dataSource {
        static DataSource newDataSource() {
            org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
            ds.setURL("jdbc:h2:mem:" + System.identityHashCode(new Object()) + ";DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            ds.setPassword("");
            return ds;
        }
    }
}
