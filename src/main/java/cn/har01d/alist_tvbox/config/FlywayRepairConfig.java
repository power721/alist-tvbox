package cn.har01d.alist_tvbox.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.Objects;

/**
 * Heals the schema state produced by the SQL-file restore path ({@code /data/database.zip} →
 * {@code restore_database} in the docker init script, rebuilt with H2 RunScript from a
 * {@code SCRIPT TO ... TABLE} dump). That dump excludes FLYWAY_SCHEMA_HISTORY, so the rebuilt
 * database holds fully-evolved business tables but no migration history. On boot Flyway would
 * baseline that non-empty schema at version 1 and replay V2+ against the already-migrated
 * structure, which crashes startup (e.g. V5's unique index already exists) and leaves the
 * container crash-looping on every restart — the "restore deletes and rebuilds the db file but
 * never recovers" failure.
 * <p>
 * Repair: when a non-empty business schema has no history table, baseline it at the application's
 * newest migration version (nothing replays; the schema is already at that state by construction —
 * it was exported from this schema). After migration, recreate the three douban tables when
 * missing: the SQL dump excludes MOVIE/META/ALIAS on purpose (huge), V1 is skipped on a baselined
 * schema, and {@code ddl-auto: validate} then fails on the missing tables. {@code IF NOT EXISTS}
 * keeps this a no-op for healthy databases, including the JSON-restore path which keeps the
 * original db file and only overwrites rows through JPA.
 */
@Configuration
public class FlywayRepairConfig {

    @Bean
    public FlywayMigrationStrategy repairedMigrationStrategy(DataSource dataSource) {
        return new RepairedMigrationStrategy(dataSource);
    }

    static final class RepairedMigrationStrategy implements FlywayMigrationStrategy {
        private final DataSource dataSource;

        RepairedMigrationStrategy(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public void migrate(Flyway flyway) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            if (isSqlRestoredSchema(jdbc)) {
                baselineAtCurrentVersion(flyway, dataSource);
            }
            flyway.migrate();
            ensureDoubanTables(jdbc);
        }

        /** Non-empty business schema without a flyway history table = rebuilt from a SQL dump. */
        private boolean isSqlRestoredSchema(JdbcTemplate jdbc) {
            boolean hasHistory = tableExists(jdbc, "flyway_schema_history");
            boolean hasV1Table = tableExists(jdbc, "setting");
            return !hasHistory && hasV1Table;
        }

        private boolean tableExists(JdbcTemplate jdbc, String tableName) {
            // information_schema.tables with lower(table_name): unquoted H2 names fold to upper,
            // Flyway 11 creates its history table as quoted lowercase, MySQL/PG fold to lower.
            Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.tables where lower(table_name) = ?",
                Integer.class, tableName);
            return count != null && count > 0;
        }

        private void baselineAtCurrentVersion(Flyway flyway, DataSource dataSource) {
            MigrationVersion max = Arrays.stream(flyway.info().all())
                .map(MigrationInfo::getVersion)
                .filter(Objects::nonNull)
                .max(MigrationVersion::compareTo)
                .orElseThrow(() -> new IllegalStateException("no migrations resolved for baseline"));
            Flyway.configure()
                .dataSource(dataSource)
                .baselineVersion(max.toString())
                .baselineDescription("SQL backup restore: schema present, history table missing")
                .load()
                .baseline();
        }

        /**
         * Recreate MOVIE/META/ALIAS when the SQL dump omitted them. Column widths follow the
         * Movie/Meta entities (V1 DDL widened by V7); backticks on {@code year} mirror V1 so the
         * unquoted-name folding matches. H2/MySQL only — PostgreSQL never enters this state (no
         * database.zip restore path there) and rejects backtick DDL at parse time even when the
         * table exists.
         */
        private void ensureDoubanTables(JdbcTemplate jdbc) {
            String product = productName(jdbc);
            if (!product.contains("H2") && !product.contains("MySQL")) {
                return;
            }
            jdbc.execute("create table if not exists movie ("
                + "id integer not null primary key, actors varchar(255), country varchar(255),"
                + " cover varchar(255), db_score varchar(255), description varchar(1024),"
                + " directors varchar(255), editors varchar(255), genre varchar(255),"
                + " language varchar(255), name varchar(255), `year` integer)");
            jdbc.execute("create table if not exists alias ("
                + "name varchar(255) not null primary key, alias varchar(255), movie_id integer)");
            jdbc.execute("create table if not exists meta ("
                + "id integer not null primary key, disabled boolean default false, movie_id integer,"
                + " name varchar(512), path varchar(1024) unique, score integer, site_id integer,"
                + " time timestamp, tid integer, tm_id integer, tmdb_id integer, type varchar(255),"
                + " `year` integer)");
        }

        private String productName(JdbcTemplate jdbc) {
            try {
                return jdbc.execute((java.sql.Connection con) ->
                    con.getMetaData().getDatabaseProductName());
            } catch (Exception e) {
                return "";
            }
        }
    }
}
