package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.dto.backup.BackupRestoreMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * Performs the actual startup YAML restore from {@code /data/database-yaml.zip}. The restore-pending
 * flag is already set by {@link RestoreState}'s constructor, so initializer writes are skipped where
 * guarded. After a successful restore the package is removed and the JVM exits with
 * {@link #RESTART_EXIT_CODE} to request a clean restart, so the next boot re-runs all initializers
 * against the restored data with fresh in-memory caches (this boot's caches are stale because the
 * data changed after initialization). The entrypoint scripts / systemd ({@code Restart=on-failure})
 * relaunch on this exit code; the package is already consumed so the restore is not replayed.
 * <p>
 * On failure the package is kept for diagnosis and retry and the exception propagates to fail
 * startup (never continue on half-restored data).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StartupYamlRestoreRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupYamlRestoreRunner.class);

    /** Exit code understood by entrypoint scripts / systemd to trigger a clean restart. */
    public static final int RESTART_EXIT_CODE = 85;

    private final DatabaseBackupService databaseBackupService;
    private final RestoreState restoreState;
    private final String startupRestorePath;

    public StartupYamlRestoreRunner(DatabaseBackupService databaseBackupService,
                                    RestoreState restoreState,
                                    @Value("${app.backup.yaml-restore-path:/data/database-yaml.zip}") String startupRestorePath) {
        this.databaseBackupService = databaseBackupService;
        this.restoreState = restoreState;
        this.startupRestorePath = startupRestorePath;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        File file = new File(startupRestorePath);
        if (!file.exists()) {
            return;
        }
        log.info("Startup YAML restore package found at {}, restoring...", startupRestorePath);
        restoreState.markRunning();
        databaseBackupService.restoreBackupZip(file, BackupRestoreMode.OVERWRITE);
        restoreState.markCompleted();
        if (!file.delete()) {
            File renamed = new File(startupRestorePath + ".restored");
            if (!file.renameTo(renamed)) {
                log.warn("Failed to remove or rename restore package {}", startupRestorePath);
            }
        }
        log.info("Startup YAML restore completed. Restarting (exit {}) for a clean boot on restored data.",
            RESTART_EXIT_CODE);
        requestRestart();
    }

    /** Exit the JVM so the entrypoint / systemd relaunches for a clean boot. Overridable for tests. */
    protected void requestRestart() {
        System.exit(RESTART_EXIT_CODE);
    }
}
