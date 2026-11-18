package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FixtureRuntime {
    private static final Set<String> FILES = Set.of("t4worker.py", "Atvp.py", "t4cenc.py",
            "demo.py", "bootstrap.py", "probe_fixture.py");
    private final ObjectMapper mapper;
    private final String python;
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final JsonNode manifest;
    private final String sha256;

    public FixtureRuntime(ObjectMapper mapper, @Value("${maintenance.python}") String python) throws IOException {
        this.mapper = mapper;
        this.python = python;
        byte[] manifestBytes = resource("manifest.json");
        manifest = mapper.readTree(manifestBytes);
        sha256 = Hashes.sha256(manifestBytes);
        if (!FILES.equals(manifest.path("files").properties().stream().map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet()))) throw new IOException("RUNTIME_MANIFEST_INVALID");
        for (String name : FILES) {
            byte[] body = resource(name);
            var expected = manifest.path("files").path(name);
            if (body.length != expected.path("bytes").asInt()
                    || !Hashes.sha256(body).equals(expected.path("sha256").asText())) {
                throw new IOException("RUNTIME_BUNDLE_MISMATCH");
            }
            files.put(name, body);
        }
        if (!Hashes.sha256(files.get("demo.py")).equals(manifest.path("fixtureSha256").asText())) {
            throw new IOException("FIXTURE_IDENTITY_MISMATCH");
        }
        files.put("manifest.json", manifestBytes);
    }

    public String sha256() { return sha256; }
    public JsonNode manifest() { return manifest.deepCopy(); }
    public String fixtureContent() { return new String(files.get("demo.py"), StandardCharsets.UTF_8); }

    public String blockedReason(String sourceSha, JsonNode profile) {
        if (!sourceSha.equals(manifest.path("fixtureSha256").asText())) return "TRUSTED_FIXTURE_REQUIRED";
        if (!profile.path("runtime").asText().equals("t4")) return "FIXTURE_RUNTIME_UNSUPPORTED";
        if (!profile.path("extFingerprint").isNull()) return "FIXTURE_EXT_UNAVAILABLE";
        return null;
    }

    public JsonNode inspect(String sourceSha, JsonNode profile, String expectedRuntimeSha)
            throws IOException, InterruptedException {
        // Validate again at the execution boundary; never write submitted source.
        if (blockedReason(sourceSha, profile) != null || !sha256.equals(expectedRuntimeSha)) {
            throw new IOException("FIXTURE_INPUT_MISMATCH");
        }
        Path directory = Files.createTempDirectory("at-maintenance-fixture-");
        try {
            for (var file : files.entrySet()) Files.write(directory.resolve(file.getKey()), file.getValue());
            JsonNode result = mapper.readTree(PythonProcess.run(python, directory.resolve("probe_fixture.py"),
                    directory, List.of(sourceSha, sha256), new byte[0], 15));
            JsonNode receipt = result.path("receipt");
            Set<String> passed = new java.util.HashSet<>();
            result.path("passedNodes").forEach(id -> passed.add(id.asText()));
            if (!receipt.path("sourceBytesSha256").asText().equals(sourceSha)
                    || !receipt.path("loadedTextSha256").asText().equals(sourceSha)
                    || !receipt.path("runtimeSha256").asText().equals(sha256)
                    || !receipt.path("authority").asText().equals("bootstrap_observed")
                    || !result.path("evidenceClass").asText().equals("offline_fixture")
                    || !passed.equals(Set.of("S06", "C01", "C07", "C10", "R01"))) {
                throw new IOException("FIXTURE_RECEIPT_MISMATCH");
            }
            return result;
        } finally {
            PythonProcess.deleteDirectory(directory);
        }
    }

    private byte[] resource(String name) throws IOException {
        return new ClassPathResource("worker/runtime/" + name).getContentAsByteArray();
    }
}
