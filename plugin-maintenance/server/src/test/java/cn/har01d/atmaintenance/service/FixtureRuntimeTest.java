package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.CreateSourceRequest;
import cn.har01d.atmaintenance.dto.AddArtifactRequest;
import cn.har01d.atmaintenance.dto.ProbeSummary;
import cn.har01d.atmaintenance.dto.UpdateProfileRequest;
import cn.har01d.atmaintenance.repository.ProbeRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "maintenance.scheduling.enabled=false",
        "maintenance.at.origin=",
        "spring.datasource.url=jdbc:h2:mem:maintenance-runtime-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=integration-test-only"
})
class FixtureRuntimeTest {
    @Autowired SourceService sources;
    @Autowired ProbeService probes;
    @Autowired ProbeStore store;
    @Autowired FixtureRuntime runtime;
    @Autowired ProfileService profiles;
    @Autowired ExportService exports;
    @Autowired ObjectMapper mapper;
    @Autowired ProbeRunRepository runs;
    @TempDir Path directory;

    @Test
    void actualWorkerReceiptAndDerivedInputsAreFrozenAndExportedWithoutBroaderClaims() throws Exception {
        String id = create(runtime.fixtureContent());
        var submitted = probes.start(id, "offline_fixture");
        var baseline = profiles.get(id);
        profiles.update(id, new UpdateProfileRequest(baseline.sha256(), "fongmi", "new-declaration",
                List.of("S01", "S02", "S03"), List.of(), null, null));
        sources.addArtifact(id, new AddArtifactRequest(submitted.artifactSha256(), runtime.fixtureContent() + "# new\n"));
        var done = await(submitted.id());
        assertEquals("COMPLETED", done.status(), () -> String.valueOf(done.errorCode()));
        assertEquals("offline_fixture", done.executionMode());
        assertEquals("source_and_profile_changed", done.freshness());
        assertEquals(baseline.sha256(), done.profileSha256());
        assertEquals("offline_fixture_checked", done.result().path("verdict").asText());
        assertEquals(runtime.sha256(), done.runtimeSha256());
        assertEquals(Hashes.sha256(done.runtime().toString()), done.runtimeSha256());
        var evidence = done.result().path("runtimeEvidence");
        assertEquals("bootstrap_observed", evidence.path("receipt").path("authority").asText());
        assertEquals(done.artifactSha256(), evidence.path("receipt").path("loadedTextSha256").asText());
        assertEquals(done.artifactSha256(), evidence.path("receipt").path("sourceBytesSha256").asText());
        assertEquals(8, evidence.path("steps").size());
        assertEquals("home.class[0].type_id", evidence.path("steps").get(2).path("inputFrom").asText());
        assertEquals("category.list[0].vod_id", evidence.path("steps").get(3).path("inputFrom").asText());
        assertEquals("detail.vod_play_from/vod_play_url", evidence.path("steps").get(5).path("inputFrom").asText());
        assertEquals("1", evidence.path("steps").get(2).path("input").path("t").asText());
        assertEquals("demo-1", evidence.path("steps").get(3).path("input").path("ids").asText());
        assertEquals("demo", evidence.path("steps").get(5).path("input").path("flag").asText());
        assertEquals("demo-play", evidence.path("steps").get(5).path("input").path("play").asText());
        for (int i = 0; i < 8; i++) {
            var step = evidence.path("steps").get(i);
            assertEquals(String.valueOf(i + 1), step.path("requestId").asText());
            assertTrue(step.path("responseSha256").asText().matches("[a-f0-9]{64}"));
        }
        long passed = done.result().path("nodes").valueStream()
                .filter(n -> n.path("status").asText().equals("pass")).count();
        assertEquals(8, passed);
        assertEquals("not_tested", node(done, "S07").path("status").asText());
        assertEquals("not_tested", node(done, "C03").path("status").asText());
        assertEquals("not_tested", node(done, "C05").path("status").asText());
        assertEquals("not_tested", node(done, "M01").path("status").asText());
        Map<String, byte[]> files = new HashMap<>();
        byte[] archive = exports.export(id, done.artifactSha256());
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) files.put(entry.getName(), zip.readAllBytes());
        }
        assertEquals(done.runtimeSha256(), Hashes.sha256(files.get("probe-runtime.json")));
        assertEquals(done.runtimeSha256(), mapper.readTree(files.get("manifest.json")).path("probeRuntimeSha256").asText());
        assertEquals(done.runtime(), mapper.readTree(files.get("probe-runtime.json")));
        assertFalse(files.containsKey("bootstrap.py"), "Single-source exports contain a manifest, not an environment");
    }

    @Test
    void modifiedSourceAndUnknownSourceAreBlockedWithoutExecutingSubmittedBytes() throws Exception {
        Path marker = directory.resolve("must-not-exist");
        String malicious = "from pathlib import Path\nPath(" + mapper.writeValueAsString(marker.toString())
                + ").write_text('executed')\nclass Spider: pass\n";
        for (String content : List.of(malicious, runtime.fixtureContent() + "\n# changed\n")) {
            var done = await(probes.start(create(content), "offline_fixture").id());
            assertEquals("BLOCKED", done.status());
            assertEquals("TRUSTED_FIXTURE_REQUIRED", done.errorCode());
            assertEquals("blocked", node(done, "S06").path("status").asText());
            assertFalse(done.result().has("runtimeEvidence"));
        }
        assertFalse(Files.exists(marker));
    }

    @Test
    void declaredRuntimeOrUnrecoverableExtCannotBeSilentlyIgnored() throws Exception {
        String id = create(runtime.fixtureContent());
        var baseline = profiles.get(id);
        var other = profiles.update(id, new UpdateProfileRequest(baseline.sha256(), "fongmi",
                "declared-only", List.of("S01", "S02", "S03", "S06"), List.of(), null, null));
        var blocked = await(probes.start(id, "offline_fixture").id());
        assertEquals("FIXTURE_RUNTIME_UNSUPPORTED", blocked.errorCode());
        var configured = profiles.update(id, new UpdateProfileRequest(other.sha256(), "t4", "declared-only",
                List.of("S01", "S02", "S03", "S06"), List.of(), mapper.createObjectNode().put("token", "secret"), null));
        var ext = await(probes.start(id, "offline_fixture").id());
        assertEquals("BLOCKED", ext.status());
        assertEquals("FIXTURE_EXT_UNAVAILABLE", ext.errorCode());
        assertEquals(configured.sha256(), ext.profileSha256());
        assertEquals("required_blocked", ext.result().path("requiredCoverage").path("conclusion").asText());
        assertFalse(ext.toString().contains("\"token\""));
    }

    @Test
    void runtimeDriftInvalidatesEvidenceAndStaticModeKeepsLegacyShape() throws Exception {
        String id = create(runtime.fixtureContent());
        var done = await(probes.start(id, "offline_fixture").id());
        assertEquals("COMPLETED", done.status());
        var stored = runs.findById(done.id()).orElseThrow();
        stored.setRuntimeSha256("0".repeat(64)); // Simulate a report bound to a prior runtime bundle.
        runs.saveAndFlush(stored);
        var stale = store.get(done.id());
        assertEquals("runtime_changed", stale.freshness());
        assertEquals(List.of("runtime"), stale.staleInputs());
        var regular = await(probes.start(id).id());
        assertEquals("static", regular.executionMode());
        assertNull(regular.runtime());
        assertNull(regular.runtimeSha256());
        assertEquals("static_checked", regular.result().path("verdict").asText());
    }

    private String create(String content) {
        return sources.create(new CreateSourceRequest("可信夹具检查", null, "demo.py", null, content)).id();
    }

    private ProbeSummary await(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < deadline) {
            var run = store.get(id);
            if (!Set.of("QUEUED", "RUNNING", "CANCELLING").contains(run.status())) return run;
            Thread.sleep(30);
        }
        throw new AssertionError("Runtime probe did not finish");
    }

    private JsonNode node(ProbeSummary run, String id) {
        return run.result().path("nodes").valueStream().filter(n -> n.path("id").asText().equals(id))
                .findFirst().orElseThrow();
    }
}
