package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Configuration and rejection checks only; no Docker executable is invoked. */
class ContainerRuntimeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FixtureRuntime fixture = new FixtureRuntime(mapper, "not-invoked");
    @TempDir Path directory;

    ContainerRuntimeTest() throws IOException {}

    @Test
    void disabledSnapshotContainsOnlyConfigurationAndNeverReadsTheConfiguredBinary() throws Exception {
        var container = configured(false, directory.toString(), "", "private-image:tag");
        assertEquals("CONTAINER_EXECUTION_DISABLED", container.configurationBlockedReason());
        var snapshot = container.snapshot();
        assertEquals("expected_configuration", snapshot.path("authority").asText());
        assertNull(snapshot.path("engineExecutableSha256").textValue());
        assertNull(snapshot.path("imageId").textValue());
        assertFalse(snapshot.toString().contains(directory.toString()));
        assertFalse(snapshot.toString().contains("private-image:tag"));
        assertEquals(container.sha256(), Hashes.sha256(snapshot.toString()));
        assertEquals("CONTAINER_EXECUTION_DISABLED", assertThrows(IOException.class, () ->
                container.inspect("unused", sourceSha(), profile(), fixture.sha256(), container.sha256())).getMessage());
    }

    @Test
    void unknownSourceExtAndUnsupportedRuntimeAreRejectedBeforeAnyEngineCall() throws Exception {
        var container = configured(true, "", "", image());
        assertEquals("TRUSTED_FIXTURE_REQUIRED", container.blockedReason("0".repeat(64), profile()));
        var unsupported = profile().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unsupported).put("runtime", "fongmi");
        assertEquals("FIXTURE_RUNTIME_UNSUPPORTED", container.blockedReason(sourceSha(), unsupported));
        ((com.fasterxml.jackson.databind.node.ObjectNode) unsupported).put("runtime", "t4").put("extFingerprint", "x");
        assertEquals("FIXTURE_EXT_UNAVAILABLE", container.blockedReason(sourceSha(), unsupported));
        assertEquals("ENGINE_EXECUTABLE_REQUIRED", container.blockedReason(sourceSha(), profile()));
    }

    @Test
    void mutableImagesRemoteEndpointsAndChangedBinaryCannotExecute() throws Exception {
        Path binary = directory.resolve("docker-never-executed");
        Files.writeString(binary, "first-binary");
        assertEquals("PINNED_IMAGE_REQUIRED",
                configured(true, binary.toString(), "", "python:latest").configurationBlockedReason());
        assertEquals("LOCAL_ENGINE_REQUIRED",
                configured(true, binary.toString(), "tcp://127.0.0.1:2375", image()).configurationBlockedReason());
        assertEquals("LOCAL_ENGINE_REQUIRED",
                configured(true, binary.toString(), "unix:///run/../docker.sock", image()).configurationBlockedReason());
        var container = configured(true, binary.toString(), "", image());
        Files.writeString(binary, "second-binary");
        assertEquals("ENGINE_BINARY_CHANGED", assertThrows(IOException.class, () -> container.inspect(
                "unused", sourceSha(), profile(), fixture.sha256(), container.sha256())).getMessage());
        assertEquals("CONTAINER_INPUT_MISMATCH", assertThrows(IOException.class, () -> container.inspect(
                "unused", sourceSha(), profile(), fixture.sha256(), "0".repeat(64))).getMessage());
    }

    @Test
    void restartCannotClaimCleanupAgainstADifferentEngineAndRawErrorsAreRedacted() throws Exception {
        var container = configured(false, "", "", "");
        var prior = (com.fasterxml.jackson.databind.node.ObjectNode) container.snapshot();
        prior.put("endpointSha256", "0".repeat(64));
        assertEquals("CONTAINER_CLEANUP_UNCONFIRMED", assertThrows(IOException.class,
                () -> container.cleanup("unused", prior)).getMessage());
        assertEquals("CONTAINER_CLEANUP_UNCONFIRMED", assertThrows(IOException.class,
                () -> container.cleanup("unused", null)).getMessage());
        assertEquals("ENGINE_TIMEOUT", container.errorCode(new IOException("ENGINE_TIMEOUT")));
        assertEquals("CONTAINER_EXECUTION_ERROR", container.errorCode(new IOException("secret/path/token")));
    }

    private ContainerRuntime configured(boolean enabled, String binary, String endpoint, String image) throws IOException {
        return new ContainerRuntime(mapper, fixture, "not-invoked", enabled, binary, endpoint, image);
    }
    private com.fasterxml.jackson.databind.JsonNode profile() throws IOException {
        return mapper.readTree("{\"runtime\":\"t4\",\"extFingerprint\":null}");
    }
    private String sourceSha() { return fixture.manifest().path("fixtureSha256").asText(); }
    private String image() { return "sha256:" + "a".repeat(64); }
}
