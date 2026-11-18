package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:source-import-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=source-import-test-only",
        "maintenance.at.origin=",
        "maintenance.scheduling.enabled=false",
        "maintenance.model.enabled=false",
        "maintenance.media.enabled=false",
        "maintenance.container.enabled=false"
})
class SourceImportIntegrationTest {
    @Autowired SourceImportService imports;
    @Autowired SourceService sources;
    @Autowired ExportService exports;
    @Autowired ProfileService profiles;
    @Autowired ProbeService probes;
    @Autowired ProbeStore probeStore;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    private static final String SOURCE = """
            # 中文源码只作为数据，绝不能导入执行
            raise RuntimeError("PRIVATE_IMPORT_SOURCE_MUST_NOT_EXECUTE")
            class Spider:
                def init(self, extend=""):
                    return ""
            """;

    @Test
    void actualExportPreviewsWithoutWritingAndRestoresToANewLocalIdentity() throws Exception {
        var original = create(SOURCE);
        byte[] archive = exports.export(original.id(), null);
        long[] before = counts();
        var preview = imports.preview(new PreviewSourceImportRequest(encoded(archive)));
        assertArrayEquals(before, counts());
        assertEquals(Hashes.sha256(archive), preview.archiveSha256());
        assertEquals(original.id(), preview.originalSourceId());
        assertEquals(original.artifactSha256(), preview.artifactSha256());
        assertEquals("archive_hashes_checked", preview.authority());
        assertFalse(preview.reportPresent());
        assertFalse(preview.configRebindRequired());
        assertEquals(unzip(archive).size(), preview.fileCount());

        var restored = imports.confirm(confirm(archive, key(), "恢复后的源"));
        assertNotEquals(original.id(), restored.sourceId());
        assertEquals(original.id(), restored.originalSourceId());
        assertEquals("恢复后的源", sources.get(restored.sourceId()).name());
        assertNull(sources.get(restored.sourceId()).externalId());
        assertNull(sources.get(restored.sourceId()).originUrl());
        assertEquals(original.artifactSha256(), sources.get(restored.sourceId()).artifactSha256());
        assertEquals("imported_data_only", restored.authority());
        assertEquals(restored, imports.get(restored.id()));
        assertEquals(List.of(restored), imports.list(restored.sourceId()));
        assertTrue(imports.list(original.id()).isEmpty());
        assertArrayEquals(archive, imports.archive(restored.id()));
        assertEquals(1, sources.versions(restored.sourceId()).size());
    }

    @Test
    void selectedHttpPlaybackGroupSurvivesExportAndImport() throws Exception {
        var original = create(SOURCE);
        var profile = profiles.get(original.id());
        var selected = profiles.update(original.id(), new UpdateProfileRequest(profile.sha256(), "t4",
                "playback-groups", List.of("S01", "S02", "S03"), List.of(), null, null, null,
                1, "sample-id", "page-window"));
        byte[] archive = exports.export(original.id(), null);
        var preview = imports.preview(new PreviewSourceImportRequest(encoded(archive)));
        assertEquals(1, preview.profile().path("httpPlaybackGroup").asInt());
        assertEquals("sample-id", preview.profile().path("httpDetailSample").asText());
        assertEquals("page-window", preview.profile().path("paginationMode").asText());
        var restored = imports.confirm(confirm(archive, key(), "playback-groups"));
        assertEquals(selected.sha256(), profiles.get(restored.sourceId()).sha256());
        assertEquals(1, profiles.get(restored.sourceId()).snapshot().path("httpPlaybackGroup").asInt());
        assertEquals("sample-id", profiles.get(restored.sourceId()).snapshot().path("httpDetailSample").asText());
        assertEquals("page-window", profiles.get(restored.sourceId()).snapshot().path("paginationMode").asText());
    }

    @Test
    void utf8BomChineseAndCrLfSourceBytesSurviveRestoration() throws Exception {
        String content = "\uFEFF# 中文原字节\r\nraise RuntimeError('must not execute')\r\nclass Spider: pass\r\n";
        var original = create(content);
        byte[] archive = exports.export(original.id(), null);
        var restored = imports.confirm(confirm(archive, key(), "字节恢复"));
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), sources.artifact(
                restored.sourceId(), restored.artifactSha256()).getBody().getBytes(StandardCharsets.UTF_8));
        assertEquals(Hashes.sha256(content), restored.artifactSha256());
        assertArrayEquals(archive, imports.archive(restored.id()));
    }

    @Test
    void extFingerprintIsNotReusedAndProfileDeclarationsRemainIntact() throws Exception {
        var original = create(SOURCE);
        var baseline = profiles.get(original.id());
        var declared = profiles.update(original.id(), new UpdateProfileRequest(baseline.sha256(), "fongmi",
                "declared-runtime-only", List.of("S01", "S02", "S03", "C01"), List.of("C02"),
                mapper.readTree("{\"token\":\"PRIVATE_EXT_NOT_EXPORTED\"}"), null));
        byte[] archive = exports.export(original.id(), null);
        var preview = imports.preview(new PreviewSourceImportRequest(encoded(archive)));
        assertTrue(preview.configRebindRequired());
        var restored = imports.confirm(confirm(archive, key(), "需要重新配置"));
        var active = profiles.get(restored.sourceId());
        assertTrue(restored.configRebindRequired());
        assertEquals(declared.sha256(), restored.originalProfileSha256());
        assertEquals(active.sha256(), restored.restoredProfileSha256());
        assertNotEquals(declared.sha256(), active.sha256());
        assertTrue(active.snapshot().path("extFingerprint").isNull());
        assertEquals("fongmi", active.snapshot().path("runtime").asText());
        assertEquals(declared.snapshot().path("runtimeRevision"), active.snapshot().path("runtimeRevision"));
        assertEquals(declared.snapshot().path("requiredNodes"), active.snapshot().path("requiredNodes"));
        assertEquals(declared.snapshot().path("optionalNodes"), active.snapshot().path("optionalNodes"));
        assertNull(jdbc.queryForObject("select config_fingerprint_salt from maintenance_source where id = ?",
                String.class, restored.sourceId()));
        assertArrayEquals(archive, imports.archive(restored.id()));
        String profileJson = new String(unzip(imports.archive(restored.id())).get("profile.json"), StandardCharsets.UTF_8);
        assertEquals(declared.sha256(), Hashes.sha256(profileJson));
        assertFalse(profileJson.contains("PRIVATE_EXT_NOT_EXPORTED"));
    }

    @Test
    void publicSettingsSurviveExplicitArchiveRestorationAndInvalidSettingsAreRejected() throws Exception {
        var original = create(SOURCE);
        var baseline = profiles.get(original.id());
        var data = mapper.createObjectNode().put("label", "PUBLIC_ARCHIVE_SENTINEL");
        profiles.update(original.id(), new UpdateProfileRequest(baseline.sha256(), "t4", "public-config",
                List.of("S01", "S02", "S03"), List.of(), null, data));
        byte[] archive = exports.export(original.id(), null);
        assertFalse(imports.preview(new PreviewSourceImportRequest(encoded(archive))).configRebindRequired());
        var restored = imports.confirm(confirm(archive, key(), "公开配置恢复"));
        assertEquals(data, profiles.get(restored.sourceId()).snapshot().path("publicSourceData"));
        for (JsonNode invalid : List.of(mapper.createObjectNode().put("cookie", "PRIVATE"),
                mapper.createObjectNode().put("label", 20), mapper.createObjectNode())) {
            var files = unzip(archive);
            var profile = (ObjectNode) mapper.readTree(files.get("profile.json"));
            profile.set("publicSourceData", invalid);
            files.put("profile.json", mapper.writeValueAsBytes(profile));
            byte[] changed = rehashed(files);
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> imports.preview(
                    new PreviewSourceImportRequest(encoded(changed)))).getStatusCode().value());
        }
    }

    @Test
    void archivedProbeRemainsEvidenceOnlyAndDoesNotActivateOtherState() throws Exception {
        var original = create(SOURCE);
        var run = await(probes.start(original.id()).id());
        assertEquals("COMPLETED", run.status());
        byte[] archive = exports.export(original.id(), null);
        assertTrue(imports.preview(new PreviewSourceImportRequest(encoded(archive))).reportPresent());
        var restored = imports.confirm(confirm(archive, key(), "不继承绿灯"));
        assertTrue(probeStore.list(restored.sourceId()).isEmpty());
        assertEquals(0, count("maintenance_at_binding", restored.sourceId()));
        assertEquals(0, count("maintenance_probe_schedule", restored.sourceId()));
        assertEquals(0, count("maintenance_model_consent", restored.sourceId()));
        assertArrayEquals(unzip(archive).get("probe.json"), unzip(imports.archive(restored.id())).get("probe.json"));
    }

    @Test
    void exactReplayReturnsTheHistoricalReceiptWithoutRevertingSubsequentEdits() throws Exception {
        var original = create(SOURCE);
        byte[] archive = exports.export(original.id(), null);
        var request = confirm(archive, key(), "幂等恢复");
        var first = imports.confirm(request);
        var edited = sources.addArtifact(first.sourceId(),
                new AddArtifactRequest(first.artifactSha256(), SOURCE + "\n# later local edit\n"));
        long[] beforeReplay = counts();
        var replay = imports.confirm(request);
        assertEquals(first, replay);
        assertEquals(edited.artifactSha256(), sources.get(first.sourceId()).artifactSha256());
        assertArrayEquals(beforeReplay, counts());
    }

    @Test
    void reusingAKeyForAnotherNameOrArchiveIsAConflictWithoutSideEffects() throws Exception {
        var original = create(SOURCE);
        byte[] archive = exports.export(original.id(), null);
        String key = key();
        imports.confirm(confirm(archive, key, "first"));
        long[] before = counts();
        assertError(409, "IMPORT_REQUEST_KEY_REUSED",
                () -> imports.confirm(confirm(archive, key, "changed-name")));
        var otherSource = create(SOURCE + "\n# another archive\n");
        byte[] otherArchive = exports.export(otherSource.id(), null);
        long[] afterOther = counts();
        assertError(409, "IMPORT_REQUEST_KEY_REUSED",
                () -> imports.confirm(confirm(otherArchive, key, "first")));
        assertArrayEquals(afterOther, counts());
        assertEquals(before[0] + 1, afterOther[0]);
    }

    @Test
    void archiveMustMatchTheExplicitPreviewIdentity() throws Exception {
        byte[] archive = exports.export(create(SOURCE).id(), null);
        long[] before = counts();
        var request = new ConfirmSourceImportRequest(encoded(archive), "f".repeat(64), key(), "wrong-sha", "source.py");
        assertError(409, "IMPORT_ARCHIVE_CHANGED", () -> imports.confirm(request));
        assertArrayEquals(before, counts());
    }

    @Test
    void competingIdenticalRequestsCommitExactlyOneSourceAndImport() throws Exception {
        byte[] archive = exports.export(create(SOURCE).id(), null);
        var input = confirm(archive, key(), "concurrent");
        long[] before = counts();
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<SourceImportSummary>> pending = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                pending.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        return imports.confirm(input);
                    } catch (DataIntegrityViolationException conflict) {
                        return null;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Set<String> importedIds = new HashSet<>();
            for (var future : pending) {
                var result = future.get(30, TimeUnit.SECONDS);
                if (result != null) importedIds.add(result.id());
            }
            assertEquals(1, importedIds.size());
        }
        long[] after = counts();
        assertEquals(before[0] + 1, after[0]);
        assertEquals(before[1] + 1, after[1]);
        assertEquals(before[2], after[2]);
        assertEquals(before[3] + 1, after[3]);
        assertEquals(imports.confirm(input).archiveSha256(), Hashes.sha256(archive));
    }

    @Test
    void malformedBase64ZipAndCompressedByteLimitRejectBeforePersistence() {
        long[] before = counts();
        assertError(400, "IMPORT_BASE64_INVALID",
                () -> imports.preview(new PreviewSourceImportRequest("PRIVATE-not-base64%")));
        assertError(400, "IMPORT_ZIP_INVALID",
                () -> imports.preview(new PreviewSourceImportRequest(encoded("not a zip".getBytes(StandardCharsets.UTF_8)))));
        assertError(413, "IMPORT_ARCHIVE_TOO_LARGE", () -> imports.preview(
                new PreviewSourceImportRequest(encoded(new byte[4_194_305]))));
        assertArrayEquals(before, counts());
    }

    @Test
    void unknownTraversalAndDuplicateEntriesAreRejectedWithoutWritingFilesOrRows() throws Exception {
        byte[] original = exports.export(create(SOURCE).id(), null);
        for (String name : List.of("../source.py", "/source.py", "C:/source.py", "unknown.py")) {
            var files = unzip(original);
            files.put(name, "PRIVATE_PAYLOAD".getBytes(StandardCharsets.UTF_8));
            rejectArchive(zip(files), "IMPORT_ENTRY_INVALID");
        }
        var duplicate = unzip(original);
        duplicate.put("foobar.json", "{}".getBytes(StandardCharsets.UTF_8));
        byte[] duplicateArchive = replace(zip(duplicate), "foobar.json", "source.json");
        rejectArchive(duplicateArchive, "IMPORT_ENTRY_INVALID");
    }

    @Test
    void missingFilesAndTamperedPayloadOrManifestAreRejectedBeforePersistence() throws Exception {
        byte[] original = exports.export(create(SOURCE).id(), null);
        var missing = unzip(original);
        missing.remove("source.py");
        rejectArchive(zip(missing), "IMPORT_REQUIRED_FILE_MISSING");
        var tampered = unzip(original);
        tampered.put("source.py", "PRIVATE_REPLACED_SOURCE".getBytes(StandardCharsets.UTF_8));
        rejectArchive(zip(tampered), "IMPORT_FILE_HASH_MISMATCH");
        var undeclared = unzip(original);
        ObjectNode manifest = (ObjectNode) mapper.readTree(undeclared.get("manifest.json"));
        ((ObjectNode) manifest.path("files")).remove("profile.json");
        undeclared.put("manifest.json", mapper.writeValueAsBytes(manifest));
        rejectArchive(zip(undeclared), "IMPORT_MANIFEST_INVALID");
        var wrongIdentity = unzip(original);
        manifest = (ObjectNode) mapper.readTree(wrongIdentity.get("manifest.json"));
        manifest.put("profileSha256", "f".repeat(64));
        wrongIdentity.put("manifest.json", mapper.writeValueAsBytes(manifest));
        rejectArchive(zip(wrongIdentity), "IMPORT_IDENTITY_MISMATCH");
    }

    @Test
    void duplicateJsonFieldsUnknownProfileNodesAndInvalidUtf8DoNotCreateSources() throws Exception {
        byte[] original = exports.export(create(SOURCE).id(), null);
        var duplicateJson = unzip(original);
        String manifest = new String(duplicateJson.get("manifest.json"), StandardCharsets.UTF_8);
        duplicateJson.put("manifest.json", ("{\"schemaVersion\":1," + manifest.substring(1))
                .getBytes(StandardCharsets.UTF_8));
        rejectArchive(zip(duplicateJson), "IMPORT_JSON_INVALID");
        var invalidProfile = unzip(original);
        ObjectNode profile = (ObjectNode) mapper.readTree(invalidProfile.get("profile.json"));
        profile.withArray("optionalNodes").add("UNKNOWN_IMPORT_NODE");
        invalidProfile.put("profile.json", mapper.writeValueAsBytes(profile));
        rejectArchive(rehashed(invalidProfile), "IMPORT_PROFILE_NODES_INVALID");
        var invalidUtf8 = unzip(original);
        invalidUtf8.put("source.py", new byte[] {(byte) 0xc3, 0x28});
        rejectArchive(rehashed(invalidUtf8), "IMPORT_SOURCE_UTF8_INVALID");
    }

    @Test
    void compressedBombAndOversizedSourceStopAtBoundedImportLimits() throws Exception {
        byte[] original = exports.export(create(SOURCE).id(), null);
        var bomb = unzip(original);
        bomb.put("probe.json", new byte[8_388_609]);
        byte[] compressed = zip(bomb);
        assertTrue(compressed.length < 4_194_304);
        rejectArchive(compressed, "IMPORT_EXPANDED_LIMIT");
        var oversizedSource = unzip(original);
        oversizedSource.put("source.py", new byte[1_048_577]);
        rejectArchive(rehashed(oversizedSource), "IMPORT_SOURCE_TOO_LARGE");
    }

    @Test
    void apiRequiresAdminAndCsrfAndNeverReturnsUploadedSourceOrBase64InDtos() throws Exception {
        byte[] archive = exports.export(create(SOURCE).id(), null);
        var input = confirm(archive, key(), "HTTP恢复");
        var client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String auth = "Basic " + encoded("admin:source-import-test-only".getBytes(StandardCharsets.UTF_8));
        assertEquals(401, client.send(HttpRequest.newBuilder(uri("/source-imports/unknown")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, client.send(HttpRequest.newBuilder(uri("/source-imports"))
                        .header("Authorization", auth).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input))).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        var csrfResponse = client.send(HttpRequest.newBuilder(uri("/csrf")).header("Authorization", auth).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, csrfResponse.statusCode());
        JsonNode csrf = mapper.readTree(csrfResponse.body());
        var preview = post(client, auth, csrf, "/source-imports/preview",
                new PreviewSourceImportRequest(encoded(archive)));
        assertEquals(200, preview.statusCode(), preview.body());
        var confirmed = post(client, auth, csrf, "/source-imports", input);
        assertEquals(201, confirmed.statusCode(), confirmed.body());
        for (String body : List.of(preview.body(), confirmed.body())) {
            assertFalse(body.contains("PRIVATE_IMPORT_SOURCE_MUST_NOT_EXECUTE"));
            assertFalse(body.contains(encoded(archive)));
            assertFalse(body.contains("\"archiveBase64\""));
        }
        JsonNode saved = mapper.readTree(confirmed.body());
        var downloaded = client.send(HttpRequest.newBuilder(uri("/source-imports/" + saved.path("id").asText()
                        + "/archive")).header("Authorization", auth).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, downloaded.statusCode());
        assertEquals("application/zip", downloaded.headers().firstValue("Content-Type").orElseThrow());
        assertArrayEquals(archive, downloaded.body());
        var invalid = post(client, auth, csrf, "/source-imports", new ConfirmSourceImportRequest(
                encoded(archive), Hashes.sha256(archive), key(), " ", "../unsafe.py"));
        assertEquals(400, invalid.statusCode());
        assertFalse(invalid.body().contains("PRIVATE_IMPORT_SOURCE_MUST_NOT_EXECUTE"));
    }

    @Test
    void missingImportOrSourceReturnsNotFound() {
        assertError(404, "SOURCE_IMPORT_NOT_FOUND", () -> imports.get(UUID.randomUUID().toString()));
        assertError(404, "SOURCE_IMPORT_NOT_FOUND", () -> imports.archive(UUID.randomUUID().toString()));
        assertError(404, "SOURCE_NOT_FOUND", () -> imports.list(UUID.randomUUID().toString()));
    }

    private SourceSummary create(String content) {
        return sources.create(new CreateSourceRequest("原始中文源", null, "original.py", null, content));
    }

    private ConfirmSourceImportRequest confirm(byte[] archive, String key, String name) {
        return new ConfirmSourceImportRequest(encoded(archive), Hashes.sha256(archive), key, name, "restored.py");
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private String encoded(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private long[] counts() {
        return new long[] {
                jdbc.queryForObject("select count(*) from maintenance_source", Long.class),
                jdbc.queryForObject("select count(*) from maintenance_artifact", Long.class),
                jdbc.queryForObject("select count(*) from maintenance_profile", Long.class),
                jdbc.queryForObject("select count(*) from maintenance_source_import", Long.class)
        };
    }

    private int count(String table, String sourceId) {
        return jdbc.queryForObject("select count(*) from " + table + " where source_id = ?", Integer.class, sourceId);
    }

    private void rejectArchive(byte[] archive, String code) {
        long[] before = counts();
        assertError(400, code, () -> imports.confirm(confirm(archive, key(), "must-not-be-created")));
        assertArrayEquals(before, counts());
    }

    private void assertError(int status, String code, org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(ResponseStatusException.class, action);
        assertEquals(status, error.getStatusCode().value());
        assertEquals(code, error.getReason());
    }

    private Map<String, byte[]> unzip(byte[] body) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(body), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), zip.readAllBytes());
            }
        }
        return files;
    }

    private byte[] zip(Map<String, byte[]> files) throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private byte[] rehashed(Map<String, byte[]> files) throws Exception {
        ObjectNode manifest = (ObjectNode) mapper.readTree(files.get("manifest.json"));
        manifest.put("artifactSha256", Hashes.sha256(files.get("source.py")));
        manifest.put("profileSha256", Hashes.sha256(files.get("profile.json")));
        ObjectNode metadata = (ObjectNode) mapper.readTree(files.get("source.json"));
        metadata.put("artifactSha256", manifest.path("artifactSha256").asText());
        files.put("source.json", mapper.writeValueAsBytes(metadata));
        var records = manifest.putObject("files");
        files.forEach((name, body) -> {
            if (!name.equals("manifest.json")) {
                records.putObject(name).put("bytes", body.length).put("sha256", Hashes.sha256(body));
            }
        });
        files.put("manifest.json", mapper.writeValueAsBytes(manifest));
        return zip(files);
    }

    private byte[] replace(byte[] bytes, String oldName, String newName) {
        byte[] oldBytes = oldName.getBytes(StandardCharsets.US_ASCII);
        byte[] newBytes = newName.getBytes(StandardCharsets.US_ASCII);
        assertEquals(oldBytes.length, newBytes.length);
        byte[] result = bytes.clone();
        for (int start = 0; start <= result.length - oldBytes.length; start++) {
            boolean matches = true;
            for (int offset = 0; offset < oldBytes.length; offset++) {
                if (result[start + offset] != oldBytes[offset]) matches = false;
            }
            if (matches) System.arraycopy(newBytes, 0, result, start, newBytes.length);
        }
        return result;
    }

    private ProbeSummary await(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            var run = probeStore.get(id);
            if (!Set.of("QUEUED", "RUNNING").contains(run.status())) return run;
            Thread.sleep(30);
        }
        throw new AssertionError("Static probe did not finish within 15 seconds");
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + "/api/plugin-maintenance" + path);
    }

    private HttpResponse<String> post(HttpClient client, String auth, JsonNode csrf, String path, Object body)
            throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Authorization", auth)
                        .header("Content-Type", "application/json")
                        .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
