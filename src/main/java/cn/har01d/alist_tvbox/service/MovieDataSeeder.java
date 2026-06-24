package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.MovieRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.util.H2SqlConverter;
import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static cn.har01d.alist_tvbox.util.Constants.MOVIE_VERSION;

/**
 * Seeds the base douban movie dataset into MySQL/PostgreSQL on first boot.
 *
 * <p>On H2 (xiaoya) the full export is loaded by {@code spring.sql.init} from
 * {@code file:/data/atv/data.sql}. The {@code mysql}/{@code postgresql} profiles set
 * {@code spring.sql.init.mode=never}, so without this runner a fresh MySQL/PG box starts
 * with an empty {@code movie} table and only ever receives incremental diff files
 * ({@code sql/*.sql}). This runner closes that gap: for a non-H2 dialect with an empty
 * {@code movie} table it converts the same {@code data.sql} via {@link H2SqlConverter}
 * and applies it in batches.
 *
 * <p>Idempotent — skipped when {@code movie} is already populated (so it never fights the
 * H2 {@code spring.sql.init} path or a previous seed). After a successful seed it records
 * the bundled {@code movie_version}, when present, so the scheduled diff sync does not
 * re-download the whole dataset.
 */
@Slf4j
@Component
public class MovieDataSeeder implements ApplicationRunner {
    private static final int BATCH_SIZE = 1000;

    private final Environment environment;
    private final JdbcTemplate jdbcTemplate;
    private final MovieRepository movieRepository;
    private final SettingRepository settingRepository;

    public MovieDataSeeder(Environment environment,
                           JdbcTemplate jdbcTemplate,
                           MovieRepository movieRepository,
                           SettingRepository settingRepository) {
        this.environment = environment;
        this.jdbcTemplate = jdbcTemplate;
        this.movieRepository = movieRepository;
        this.settingRepository = settingRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        H2SqlConverter.Dialect dialect = H2SqlConverter.detect(environment);
        if (dialect == H2SqlConverter.Dialect.H2) {
            return;
        }
        if (movieRepository.count() > 0) {
            return;
        }
        Path file = Utils.getDataPath("atv", "data.sql");
        if (!Files.exists(file)) {
            log.debug("skip movie seed: {} not found", file);
            return;
        }
        seed(file, dialect);
    }

    private void seed(Path file, H2SqlConverter.Dialect dialect) {
        try {
            List<String> lines = Files.readAllLines(file);
            List<String> batch = new ArrayList<>(BATCH_SIZE);
            int applied = 0;
            for (String line : lines) {
                String sql = H2SqlConverter.convert(line, dialect);
                if (sql == null) {
                    continue;
                }
                batch.add(sql);
                if (batch.size() >= BATCH_SIZE) {
                    applied += executeBatch(batch);
                }
            }
            applied += executeBatch(batch);
            log.info("seeded {} movie statements from {}", applied, file);
            if (applied > 0) {
                writeBundledVersion();
            }
        } catch (Exception e) {
            log.warn("seed movie data failed: {}", file, e);
        }
    }

    private int executeBatch(List<String> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            int[] results = jdbcTemplate.batchUpdate(batch.toArray(new String[0]));
            batch.clear();
            return results.length;
        } catch (Exception e) {
            log.debug("batch update failed, falling back to per-statement execution", e);
            int count = 0;
            for (String sql : batch) {
                try {
                    jdbcTemplate.execute(sql);
                    count++;
                } catch (Exception ex) {
                    log.debug("execute sql failed: {}", ex);
                }
            }
            batch.clear();
            return count;
        }
    }

    private void writeBundledVersion() {
        try {
            Path versionFile = Utils.getDataPath("atv", "movie_version");
            if (Files.exists(versionFile)) {
                String version = Files.readString(versionFile).trim();
                if (!version.isEmpty()) {
                    settingRepository.save(new Setting(MOVIE_VERSION, version));
                    log.info("movie_version set to {}", version);
                }
            }
        } catch (Exception e) {
            log.warn("write movie_version failed", e);
        }
    }
}
