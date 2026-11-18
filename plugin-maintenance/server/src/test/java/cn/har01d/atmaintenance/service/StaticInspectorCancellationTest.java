package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StaticInspectorCancellationTest {
    @TempDir Path directory;

    @Test
    void interruptKillsTheActualPythonProcessBeforeReturning() throws Exception {
        // This test-owned program records its PID, then waits. It never loads
        // submitted Python and is not a configurable production inspector.
        byte[] script = """
                import json, os, pathlib, sys, time
                request = json.loads(sys.stdin.buffer.read().decode("utf-8"))
                pid_file = pathlib.Path(request["content"])
                pending = pid_file.with_suffix(".pending")
                pending.write_text(str(os.getpid()), encoding="ascii")
                pending.replace(pid_file)
                time.sleep(60)
                """.getBytes(StandardCharsets.UTF_8);
        String python = System.getenv().getOrDefault("MAINTENANCE_PYTHON", "python3");
        var inspector = new StaticInspector(new ObjectMapper(), python, script);
        Path pidFile = directory.resolve("inspector.pid");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                inspector.inspect(pidFile.toString());
            } catch (Exception error) {
                failure.set(error);
            }
        });
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(pidFile) && thread.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(pidFile), () -> "Inspector did not start: " + failure.get());
            long pid = Long.parseLong(Files.readString(pidFile));
            assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
            thread.interrupt();
            assertTrue(thread.join(Duration.ofSeconds(5)), "Inspector cleanup did not return");
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertInstanceOf(InterruptedException.class, failure.get(),
                    () -> failure.get() + "; cause: " + (failure.get() == null ? null : failure.get().getCause()));
        } finally {
            thread.interrupt();
            thread.join(Duration.ofSeconds(5));
            inspector.close();
        }
    }
}
