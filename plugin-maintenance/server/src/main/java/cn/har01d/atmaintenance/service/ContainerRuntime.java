package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Experimental Docker lane for the pinned trusted fixture, never submitted code. */
@Service
public class ContainerRuntime {
    private final ObjectMapper mapper;
    private final FixtureRuntime runtime;
    private final String python;
    private final boolean enabled;
    private final String executable;
    private final String endpoint;
    private final String imageId;
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final ObjectNode snapshot;
    private final String sha256;
    private final String executableSha;

    public ContainerRuntime(ObjectMapper mapper, FixtureRuntime runtime,
                            @Value("${maintenance.python}") String python,
                            @Value("${maintenance.container.enabled:false}") boolean enabled,
                            @Value("${maintenance.container.executable:}") String executable,
                            @Value("${maintenance.container.endpoint:}") String endpoint,
                            @Value("${maintenance.container.image-id:}") String imageId) throws IOException {
        this.mapper = mapper;
        this.runtime = runtime;
        this.python = python;
        this.enabled = enabled;
        this.executable = executable;
        this.endpoint = endpoint.isBlank() ? System.getProperty("os.name").startsWith("Windows")
                ? "npipe:////./pipe/docker_engine" : "unix:///var/run/docker.sock" : endpoint;
        this.imageId = imageId;
        byte[] manifestBytes = resource("container-manifest.json");
        JsonNode manifest = mapper.readTree(manifestBytes);
        if (!manifest.path("runtimeSha256").asText().equals(runtime.sha256())
                || !Set.of("container_supervisor.py", "container_entry.py").equals(
                manifest.path("files").properties().stream().map(Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toSet()))) throw new IOException("CONTAINER_MANIFEST_INVALID");
        for (var record : manifest.path("files").properties()) {
            byte[] body = resource(record.getKey());
            if (body.length != record.getValue().path("bytes").asInt()
                    || !Hashes.sha256(body).equals(record.getValue().path("sha256").asText()))
                throw new IOException("CONTAINER_BUNDLE_MISMATCH");
            files.put(record.getKey(), body);
        }
        byte[] runtimeBytes = new ClassPathResource("worker/runtime/manifest.json").getContentAsByteArray();
        if (!Hashes.sha256(runtimeBytes).equals(runtime.sha256())) throw new IOException("CONTAINER_RUNTIME_MISMATCH");
        files.put("runtime/manifest.json", runtimeBytes);
        // Disabled configuration never executes or reads an administrator's supplied executable.
        executableSha = enabled && validExecutable() ? executableDigest() : null;
        snapshot = mapper.createObjectNode().put("schemaVersion", 1).put("authority", "expected_configuration")
                .put("enabled", enabled).put("bundleSha256", Hashes.sha256(manifestBytes))
                .put("enginePathSha256", Hashes.sha256(executable)).put("endpointSha256", Hashes.sha256(this.endpoint))
                .put("engineExecutableSha256", executableSha)
                .put("imageId", pinnedImage() ? imageId : null).put("configuredImageSha256", Hashes.sha256(imageId));
        snapshot.set("bundle", manifest);
        snapshot.put("boundary", "预期容器执行配置，不是 Engine 或运行回执；"
                + "仅支持固定可信示例，不接受提交源码、EXT、命令、挂载、出站网络或生产凭据。");
        sha256 = Hashes.sha256(snapshot.toString());
    }

    public String sha256() { return sha256; }
    public JsonNode snapshot() { return snapshot.deepCopy(); }
    public String configurationBlockedReason() {
        if (!enabled) return "CONTAINER_EXECUTION_DISABLED";
        if (!pinnedImage()) return "PINNED_IMAGE_REQUIRED";
        if (!validExecutable()) return "ENGINE_EXECUTABLE_REQUIRED";
        if (!localEndpoint()) return "LOCAL_ENGINE_REQUIRED";
        return null;
    }

    public String blockedReason(String sourceSha, JsonNode profile) {
        String fixture = runtime.blockedReason(sourceSha, profile);
        if (fixture != null) return fixture;
        return configurationBlockedReason();
    }

    public JsonNode inspect(String jobId, String sourceSha, JsonNode profile,
                            String expectedRuntimeSha, String expectedPolicySha) throws IOException, InterruptedException {
        String blocked = blockedReason(sourceSha, profile);
        if (blocked != null) throw new IOException(blocked);
        if (!runtime.sha256().equals(expectedRuntimeSha) || !sha256.equals(expectedPolicySha))
            throw new IOException("CONTAINER_INPUT_MISMATCH");
        if (!java.util.Objects.equals(executableSha, executableDigest())) throw new IOException("ENGINE_BINARY_CHANGED");
        var request = mapper.createObjectNode().put("schemaVersion", 1).put("operation", "run")
                .put("jobId", jobId).put("sourceSha256", sourceSha)
                .put("runtimeSha256", expectedRuntimeSha).put("imageId", imageId);
        JsonNode result;
        try {
            result = control(request, 55);
        } finally {
            // A killed Python/CLI process cannot prove daemon-owned containers are gone.
            cleanup(jobId);
        }
        var entry = result.path("entry");
        var evidence = entry.path("runtimeResult");
        if (!result.path("authority").asText().equals("container_supervisor_observed")
                || !result.path("jobId").asText().equals(jobId)
                || !result.path("imageId").asText().equals(imageId)
                || !result.path("cleanup").path("confirmed").asBoolean()
                || !result.path("cleanup").path("containerAbsent").asBoolean()
                || !entry.path("sourceSha256").asText().equals(sourceSha)
                || !entry.path("runtimeSha256").asText().equals(expectedRuntimeSha)
                || !entry.path("entrySha256").asText().equals(Hashes.sha256(files.get("container_entry.py")))
                || !evidence.path("evidenceClass").asText().equals("offline_fixture"))
            throw new IOException("CONTAINER_RECEIPT_MISMATCH");
        var wrapped = ((ObjectNode) evidence).deepCopy();
        var containerEvidence = ((ObjectNode) result).deepCopy();
        ((ObjectNode) containerEvidence.path("entry")).remove("runtimeResult");
        wrapped.set("containerEvidence", containerEvidence);
        return wrapped;
    }

    public void cleanup(String jobId, JsonNode frozenPolicy) throws IOException, InterruptedException {
        if (frozenPolicy == null
                || !snapshot.path("enginePathSha256").equals(frozenPolicy.path("enginePathSha256"))
                || !snapshot.path("endpointSha256").equals(frozenPolicy.path("endpointSha256")))
            throw new IOException("CONTAINER_CLEANUP_UNCONFIRMED");
        cleanup(jobId);
    }

    public void cleanup(String jobId) throws IOException, InterruptedException {
        boolean interrupted = Thread.interrupted();
        try {
            if (!validExecutable() || !localEndpoint()) throw new IOException("CONTAINER_CLEANUP_UNCONFIRMED");
            var input = mapper.createObjectNode().put("schemaVersion", 1).put("operation", "cleanup").put("jobId", jobId);
            JsonNode result = control(input, 25);
            if (!result.path("jobId").asText().equals(jobId) || !result.path("cleanup").path("confirmed").asBoolean()
                    || !result.path("cleanup").path("containerAbsent").asBoolean())
                throw new IOException("CONTAINER_CLEANUP_UNCONFIRMED");
        } catch (IOException | InterruptedException e) {
            throw new IOException("CONTAINER_CLEANUP_UNCONFIRMED", e);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private JsonNode control(ObjectNode request, int timeout) throws IOException, InterruptedException {
        Path directory = Files.createTempDirectory("at-maintenance-container-control-");
        try {
            Files.createDirectory(directory.resolve("runtime"));
            for (var entry : files.entrySet()) Files.write(directory.resolve(entry.getKey()), entry.getValue());
            JsonNode response;
            try {
                response = mapper.readTree(PythonProcess.run(python, directory.resolve("container_supervisor.py"),
                        directory, List.of("--docker", executable, "--host", endpoint),
                        mapper.writeValueAsBytes(request), timeout));
            } catch (IOException | InterruptedException e) {
                // Killing the supervisor loses its create acknowledgement. A late
                // daemon response cannot be excluded by a subsequent empty lookup.
                if (request.path("operation").asText().equals("run"))
                    throw new IOException("CONTAINER_CLEANUP_UNCONFIRMED", e);
                throw e;
            }
            if (!response.path("ok").asBoolean()) {
                String code = response.path("code").asText();
                if (!code.matches("[A-Z_]{1,100}")) code = "CONTAINER_PROTOCOL_ERROR";
                throw new IOException(code);
            }
            return response.path("result");
        } finally {
            PythonProcess.deleteDirectory(directory);
        }
    }

    public String errorCode(IOException error) {
        String code = error.getMessage();
        return code != null && Set.of("CONTAINER_INPUT_MISMATCH", "ENGINE_BINARY_CHANGED",
                "ENGINE_TIMEOUT", "ENGINE_OUTPUT_LIMIT", "ENGINE_COMMAND_FAILED", "ENGINE_STREAM_UNCLOSED",
                "LINUX_CGROUP_V2_REQUIRED", "LOCAL_IMAGE_MISMATCH", "IMAGE_BUNDLE_MISMATCH",
                "JOB_ALREADY_EXISTS", "CONTAINER_CREATE_INVALID", "CONTAINER_INSPECT_INVALID",
                "CONTAINER_OWNERSHIP_MISMATCH", "CONTAINER_IMAGE_MISMATCH", "CONTAINER_CONFIG_MISMATCH",
                "CONTAINER_ENV_MISMATCH", "CONTAINER_LIMITS_MISMATCH", "CONTAINER_ACCESS_MISMATCH",
                "CONTAINER_STATE_MISMATCH", "CONTAINER_CONTROLS_UNCONFIRMED", "CONTAINER_RECEIPT_MISMATCH",
                "FIXTURE_RECEIPT_MISMATCH", "CONTAINER_EXECUTION_FAILED", "CONTAINER_PROTOCOL_ERROR").contains(code)
                ? code : "PYTHON_TIMEOUT".equals(code) ? "CONTAINER_TIMEOUT" : "CONTAINER_EXECUTION_ERROR";
    }

    private boolean validExecutable() {
        if (executable.isBlank()) return false;
        try {
            Path path = Path.of(executable);
            return path.isAbsolute() && Files.isRegularFile(path);
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }

    private String executableDigest() throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(Path.of(executable)), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean pinnedImage() { return imageId.matches("sha256:[a-f0-9]{64}"); }
    private boolean localEndpoint() {
        return endpoint.equals("npipe:////./pipe/docker_engine") || endpoint.matches("unix:///[A-Za-z0-9_./-]+\\.sock")
                && !List.of(endpoint.split("/")).contains("..");
    }
    private byte[] resource(String name) throws IOException {
        return new ClassPathResource("worker/" + name).getContentAsByteArray();
    }
}
