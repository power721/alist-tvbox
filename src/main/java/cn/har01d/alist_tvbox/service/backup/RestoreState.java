package cn.har01d.alist_tvbox.service.backup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * Tracks whether a startup JSON restore is pending/running so that initializer services can skip
 * default-record creation while a restore package is present.
 * <p>
 * The pending flag is decided in the constructor: every guarded initializer injects this bean, so
 * Spring constructs it (running this detection) before those initializers' {@code @PostConstruct}
 * methods execute. An {@link org.springframework.boot.ApplicationRunner} would be too late – it
 * runs only after the context is fully refreshed.
 */
@Component
public class RestoreState {
    private volatile boolean startupRestorePending;
    private volatile boolean startupRestoreRunning;
    private volatile boolean startupRestoreCompleted;

    public RestoreState(@Value("${app.backup.json-restore-path:/data/database-json.zip}") String startupRestorePath) {
        this.startupRestorePending = new File(startupRestorePath).exists();
    }

    public boolean shouldSkipInitializationWrites() {
        return startupRestorePending || startupRestoreRunning;
    }

    public boolean isStartupRestorePending() {
        return startupRestorePending;
    }

    public boolean isStartupRestoreCompleted() {
        return startupRestoreCompleted;
    }

    public void markRunning() {
        startupRestorePending = false;
        startupRestoreRunning = true;
    }

    public void markCompleted() {
        startupRestoreRunning = false;
        startupRestoreCompleted = true;
    }
}
