package cn.har01d.atmaintenance.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PythonProcessTest {
    @TempDir Path directory;

    @Test
    void completedTrustedProgramsReleaseProcessAndOutputHandles() throws Exception {
        Path script = directory.resolve("completed.py");
        Files.writeString(script, "import sys\nsys.stdout.write('completed')\n");
        for (int i = 0; i < 100; i++) {
            assertArrayEquals("completed".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    PythonProcess.run(python(), script, directory, List.of(), new byte[0], 10),
                    "Trusted program attempt " + i);
        }
        try (var paths = Files.list(directory)) {
            assertEquals(List.of(script), paths.toList());
        }
    }

    @Test
    void cancellationStopsNestedTrustedInterpreterAndRemovesRedirectedOutput() throws Exception {
        Path childPid = directory.resolve("child.pid");
        Path script = directory.resolve("parent.py");
        Files.writeString(script, """
                import pathlib, subprocess, sys
                code = "import os, pathlib, sys, time; pathlib.Path(sys.argv[1]).write_text(str(os.getpid())); time.sleep(60)"
                process = subprocess.Popen([sys.executable, "-I", "-c", code, sys.argv[1]])
                process.wait()
                """);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                PythonProcess.run(python(), script, directory, List.of(childPid.toString()), new byte[0], 15);
            } catch (Exception e) {
                failure.set(e);
            }
        });
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while ((!Files.exists(childPid) || Files.size(childPid) == 0) && thread.isAlive()
                    && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(childPid), () -> String.valueOf(failure.get()));
            long pid = Long.parseLong(Files.readString(childPid));
            assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
            thread.interrupt();
            assertTrue(thread.join(Duration.ofSeconds(6)));
            assertInstanceOf(InterruptedException.class, failure.get());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            try (var paths = Files.list(directory)) {
                assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith("result-")
                        || p.getFileName().toString().startsWith("error-")));
            }
        } finally {
            thread.interrupt();
            thread.join(Duration.ofSeconds(6));
        }
    }

    @Test
    void totalTimeoutStopsTrustedProgramAndCleansOutputs() throws Exception {
        Path script = directory.resolve("timeout.py");
        Path pidFile = directory.resolve("timeout.pid");
        Files.writeString(script, """
                import os, pathlib, sys, time
                pathlib.Path(sys.argv[1]).write_text(str(os.getpid()))
                time.sleep(60)
                """);
        var error = assertThrows(IOException.class, () -> PythonProcess.run(python(), script, directory,
                List.of(pidFile.toString()), new byte[0], 1));
        assertEquals("PYTHON_TIMEOUT", error.getMessage());
        long pid = Long.parseLong(Files.readString(pidFile));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    private String python() {
        return System.getenv().getOrDefault("MAINTENANCE_PYTHON", "python3");
    }
}
