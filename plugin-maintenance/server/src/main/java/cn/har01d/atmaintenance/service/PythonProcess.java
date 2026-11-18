package cn.har01d.atmaintenance.service;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Process lifecycle for trusted bundled programs only; this is not a sandbox. */
final class PythonProcess {
    private PythonProcess() {}

    static byte[] run(String python, Path script, Path directory, List<String> arguments,
                      byte[] input, int timeoutSeconds) throws IOException, InterruptedException {
        return run(python, script, directory, arguments, input, timeoutSeconds, Map.of());
    }

    static byte[] run(String python, Path script, Path directory, List<String> arguments,
                      byte[] input, int timeoutSeconds, Map<String, String> explicitEnvironment)
            throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of(python, "-I", script.toString()));
        command.addAll(arguments);
        return runCommand(command, directory, input, timeoutSeconds, explicitEnvironment);
    }

    static byte[] runCommand(List<String> command, Path directory, byte[] input, int timeoutSeconds,
                             Map<String, String> explicitEnvironment) throws IOException, InterruptedException {
        Path output = Files.createTempFile(directory, "result-", ".json");
        Path error = Files.createTempFile(directory, "error-", ".log");
        Process process = null;
        try {
            var builder = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectOutput(output.toFile()).redirectError(error.toFile());
            // Inspection callers pass no credentials; only the installed setup
            // helper receives explicitly selected infrastructure environment names.
            Map<String, String> environment = builder.environment();
            environment.keySet().removeIf(key -> !List.of("SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATH")
                    .contains(key.toUpperCase(java.util.Locale.ROOT)));
            environment.putAll(explicitEnvironment);
            environment.put("PYTHONIOENCODING", "utf-8");
            process = builder.start();
            try (var stream = process.getOutputStream()) {
                stream.write(input);
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) throw new IOException("PYTHON_TIMEOUT");
            if (process.exitValue() != 0 || Files.size(output) > 262_144) throw new IOException("PYTHON_FAILED");
            return Files.readAllBytes(output);
        } finally {
            try {
                if (process != null) stopProcessTree(process);
            } finally {
                IOException failure = null;
                for (Path path : new Path[]{output, error}) {
                    try {
                        deleteTemporary(path);
                    } catch (IOException e) {
                        if (failure == null) failure = e;
                        else failure.addSuppressed(e);
                    }
                }
                if (failure != null) throw new IOException("INSPECTOR_CLEANUP_FAILED", failure);
            }
        }
    }

    private static void stopProcessTree(Process process) throws IOException, InterruptedException {
        // Windows venv launchers can own a separate interpreter process.
        var children = process.descendants().toList();
        children.reversed().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) throw new IOException("INSPECTOR_CLEANUP_FAILED");
            for (ProcessHandle child : children) {
                if (child.isAlive()) {
                    child.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                }
            }
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("INSPECTOR_CLEANUP_FAILED", e);
        } catch (InterruptedException e) {
            if (process.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive)) {
                throw new IOException("INSPECTOR_CLEANUP_FAILED", e);
            }
            throw e;
        }
    }

    static void deleteTemporary(Path path) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (FileSystemException e) {
                if (System.nanoTime() >= deadline) throw e;
                try {
                    Thread.sleep(25);
                } catch (InterruptedException interrupted) {
                    throw new IOException("INSPECTOR_CLEANUP_FAILED", interrupted);
                }
            }
        }
    }

    static void deleteDirectory(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) deleteTemporary(path);
        } catch (IOException e) {
            throw new IOException("INSPECTOR_CLEANUP_FAILED", e);
        }
    }
}
