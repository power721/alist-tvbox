package cn.har01d.alist_tvbox.service.backup;

import cn.har01d.alist_tvbox.dto.backup.BackupRestoreMode;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StartupJsonRestoreRunnerTest {

    @Test
    void shouldRestoreDatabaseJsonZipAndRequestRestartWhenPresent() throws Exception {
        DatabaseBackupService backupService = mock(DatabaseBackupService.class);
        Path tempDir = Files.createTempDirectory("json-restore");
        Path restoreFile = tempDir.resolve("database-json.zip");
        Files.write(restoreFile, new byte[]{1});
        RestoreState restoreState = new RestoreState(restoreFile.toString());

        AtomicBoolean restarted = new AtomicBoolean(false);
        StartupJsonRestoreRunner runner = new StartupJsonRestoreRunner(backupService, restoreState, restoreFile.toString()) {
            @Override
            protected void requestRestart() {
                restarted.set(true);
            }
        };

        runner.run(null);

        verify(backupService).restoreBackupZip(new File(restoreFile.toString()), BackupRestoreMode.OVERWRITE);
        assertThat(restoreState.isStartupRestoreCompleted()).isTrue();
        assertThat(restarted.get()).isTrue();
        assertThat(restoreFile.toFile().exists()).isFalse();
    }

    @Test
    void shouldDoNothingWhenNoPackage() throws Exception {
        DatabaseBackupService backupService = mock(DatabaseBackupService.class);
        RestoreState state = new RestoreState("/data/does-not-exist-database-json.zip");
        AtomicBoolean restarted = new AtomicBoolean(false);
        StartupJsonRestoreRunner runner = new StartupJsonRestoreRunner(backupService, state, "/data/does-not-exist-database-json.zip") {
            @Override
            protected void requestRestart() {
                restarted.set(true);
            }
        };

        runner.run(null);

        assertThat(restarted.get()).isFalse();
    }

    @Test
    void shouldDetectPendingFromConstructor() throws Exception {
        Path tempDir = Files.createTempDirectory("json-guard");
        Path restoreFile = tempDir.resolve("database-json.zip");
        Files.write(restoreFile, new byte[]{1});
        RestoreState state = new RestoreState(restoreFile.toString());
        assertThat(state.shouldSkipInitializationWrites()).isTrue();
    }

    @Test
    void shouldNotSkipWhenNoRestorePackage() {
        RestoreState state = new RestoreState("/data/does-not-exist-database-json.zip");
        assertThat(state.shouldSkipInitializationWrites()).isFalse();
    }
}
