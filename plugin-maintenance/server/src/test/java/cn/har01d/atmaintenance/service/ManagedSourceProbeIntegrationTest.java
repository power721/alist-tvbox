package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.CreateSourceRequest;
import cn.har01d.atmaintenance.dto.ProbeSummary;
import cn.har01d.atmaintenance.dto.UpdateProfileRequest;
import cn.har01d.atmaintenance.repository.ProbeRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task/database boundaries only. Host operations below are explicitly mocked. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "maintenance.scheduling.enabled=false", "maintenance.at.origin=",
        "maintenance.managed-source.enabled=false", "maintenance.managed-source.socket=",
        "maintenance.managed-source.instance-id=", "maintenance.managed-source.image-id=",
        "spring.datasource.url=jdbc:h2:mem:maintenance-managed-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=integration-test-only"
})
class ManagedSourceProbeIntegrationTest {
    @Autowired SourceService sources;
    @Autowired ProbeService probes;
    @Autowired ProbeStore store;
    @Autowired ProfileService profiles;
    @Autowired StaticInspector inspector;
    @Autowired KnowledgeService knowledge;
    @Autowired ExportService exports;
    @Autowired ProbeRunRepository runs;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    @MockitoSpyBean ManagedSourceRuntime managed;
    @MockitoSpyBean FixtureRuntime fixture;
    @MockitoSpyBean ContainerRuntime container;

    @Test
    void metadataReportMarksPlaybackNotApplicableWithoutPretendingItPassed() throws Exception {
        String id = create();
        var before = profiles.get(id);
        profiles.update(id, new UpdateProfileRequest(before.sha256(), "t4", "test",
                List.of("S01", "S02", "S03", "S06", "C05", "C07"), List.of(),
                null, null, null, null, null, "page-window", "metadata-navigation", "title"));
        var run = await(probes.start(id, "static").id());
        var player = run.result().path("nodes").valueStream().filter(node -> node.path("id").asText().equals("R01"))
                .findFirst().orElseThrow();
        assertEquals("not_applicable", player.path("requirement").asText());
        assertEquals("not_tested", player.path("status").asText());
        assertEquals("SOURCE_PURPOSE_NOT_APPLICABLE", player.path("reason").asText());
        assertEquals(6, run.result().path("requiredCoverage").path("total").asInt());
        verify(managed, never()).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void sourceRoutesFreezeInTasksAndOnlyTheRemappedSourceBecomesStale() throws Exception {
        String a = create(), b = create(), ordinary = create();
        Path file = Files.createTempFile("maintenance-routes-", ".json");
        try {
            var routes = mapper.createObjectNode().put("schemaVersion", 1);
            var definitions = routes.putArray("runners");
            var first = definitions.addObject().put("id", "a").put("label", "Image API")
                    .put("socket", "tcp://127.0.0.1:19001")
                    .put("instanceId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
                    .put("imageId", "sha256:" + "a".repeat(64)).put("egressPolicySha256", "a".repeat(64));
            first.putArray("sourceIds").add(a);
            var second = definitions.addObject().put("id", "b").put("label", "HTML HLS")
                    .put("socket", "tcp://127.0.0.1:19002")
                    .put("instanceId", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
                    .put("imageId", "sha256:" + "b".repeat(64)).put("egressPolicySha256", "b".repeat(64));
            second.putArray("sourceIds").add(b);
            Files.writeString(file, routes.toString());
            managed.configureRoutes(fixture, file.toString());
            var runA = await(probes.start(a, "managed_source").id());
            var runB = await(probes.start(b, "managed_source").id());
            var plain = await(probes.start(ordinary, "managed_source").id());
            assertEquals("MANAGED_EXECUTION_DISABLED", runA.errorCode());
            assertEquals("a".repeat(64), runA.executionPolicy().path("egressPolicySha256").asText());
            assertEquals("b".repeat(64), runB.executionPolicy().path("egressPolicySha256").asText());
            assertNotEquals(runA.executionPolicySha256(), runB.executionPolicySha256());
            assertEquals(managed.policySha256(), plain.executionPolicySha256());
            String base = "http://127.0.0.1:" + port + "/api/plugin-maintenance/managed-source-runtime";
            String authorization = "Basic " + java.util.Base64.getEncoder().encodeToString(
                    "admin:integration-test-only".getBytes(StandardCharsets.UTF_8));
            try (var http = HttpClient.newHttpClient()) {
                var response = http.send(HttpRequest.newBuilder(URI.create(base + "?sourceId=" + a))
                        .header("Authorization", authorization).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                var observed = mapper.readTree(response.body());
                assertEquals("Image API", observed.path("routeLabel").asText());
                assertEquals(runA.executionPolicySha256(), observed.path("policySha256").asText());
                assertFalse(response.body().contains("19001"));
                var health = http.send(HttpRequest.newBuilder(URI.create(base + "/connection?sourceId=" + a))
                        .header("Authorization", authorization).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, health.statusCode());
                assertEquals("no-store", health.headers().firstValue("Cache-Control").orElseThrow());
                assertFalse(mapper.readTree(health.body()).path("ready").asBoolean());
            }
            first.put("egressPolicySha256", "c".repeat(64));
            Files.writeString(file, routes.toString());
            managed.configureRoutes(fixture, file.toString());
            assertEquals(List.of("execution_policy"), store.get(runA.id()).staleInputs());
            assertEquals("current", store.get(runB.id()).freshness());
            assertEquals("current", store.get(plain.id()).freshness());
        } finally {
            Files.writeString(file, "{\"schemaVersion\":1,\"runners\":[]}");
            managed.configureRoutes(fixture, file.toString());
            Files.delete(file);
        }
    }

    @Test
    void managedModeReachesHttpControllerOnlyWithAuthenticationAndCsrf() throws Exception {
        String source = create();
        String base = "http://127.0.0.1:" + port + "/api/plugin-maintenance";
        String path = base + "/sources/" + source + "/probe-runs";
        String authorization = "Basic " + java.util.Base64.getEncoder().encodeToString(
                "admin:integration-test-only".getBytes(StandardCharsets.UTF_8));
        try (var http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(path))
                    .header("Authorization", authorization).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"managed_source\"}")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var csrf = mapper.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/csrf"))
                    .header("Authorization", authorization).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            var invalid = http.send(HttpRequest.newBuilder(URI.create(path))
                    .header("Authorization", authorization).header("Content-Type", "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"arbitrary_shell\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalid.statusCode());
            var accepted = http.send(HttpRequest.newBuilder(URI.create(path))
                    .header("Authorization", authorization).header("Content-Type", "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"managed_source\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, accepted.statusCode(), accepted.body());
            var done = await(mapper.readTree(accepted.body()).path("id").asText());
            assertEquals("managed_source", done.executionMode());
            assertEquals("BLOCKED", done.status());
            assertEquals("MANAGED_EXECUTION_DISABLED", done.errorCode());
        }
    }

    @Test
    void disabledModeFreezesManagedIdentityAndNeverFallsBackToHostOrTrustedFixture() throws Exception {
        String source = create();
        var done = await(probes.start(source, "managed_source").id());
        assertEquals("BLOCKED", done.status());
        assertEquals("MANAGED_EXECUTION_DISABLED", done.errorCode());
        assertEquals(managed.sha256(), done.runtimeSha256());
        assertEquals(managed.policySha256(), done.executionPolicySha256());
        assertEquals(done.executionPolicySha256(), Hashes.sha256(done.executionPolicy().toString()));
        assertEquals(3, done.result().path("nodes").valueStream()
                .filter(node -> node.path("status").asText().equals("pass")).count());
        assertFalse(done.result().path("publishEligible").asBoolean());
        assertFalse(done.result().has("managedSourceEvidence"));
        verify(managed, never()).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        verify(fixture, never()).inspect(anyString(), any(), anyString());
        verify(container, never()).inspect(anyString(), anyString(), any(), anyString(), anyString());
        var files = new HashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(exports.export(source, null)))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) files.put(entry.getName(), zip.readAllBytes());
        }
        assertEquals(done.runtimeSha256(), Hashes.sha256(files.get("probe-runtime.json")),
                "Frozen JSON uses canonical compact bytes, not resource whitespace");
        assertEquals(done.executionPolicySha256(), Hashes.sha256(files.get("probe-execution-policy.json")));
    }

    @Test
    void sourceOutputCannotPromoteInitializationBusinessMediaOrPublishEligibility() throws Exception {
        String source = create();
        var profile = profiles.get(source);
        profiles.update(source, new UpdateProfileRequest(profile.sha256(), "t4", "unverified",
                List.of("S01", "S02", "S03", "S06"), List.of(), null, null));
        doReturn(null).when(managed).blockedReason(any());
        var evidence = mapper.createObjectNode().put("authority", "managed_source_host_observed")
                .put("businessChainVerified", false).put("publishEligible", false);
        evidence.putObject("untrustedRuntimeSummary").put("status", "init_returned");
        doReturn(evidence).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        var done = await(probes.start(source, "managed_source").id());
        assertEquals("COMPLETED", done.status());
        assertEquals("managed_source_observed", done.result().path("verdict").asText());
        assertEquals(3, done.result().path("nodes").valueStream()
                .filter(node -> node.path("status").asText().equals("pass")).count());
        var init = done.result().path("nodes").valueStream()
                .filter(node -> node.path("id").asText().equals("S06")).findFirst().orElseThrow();
        assertEquals("blocked", init.path("status").asText());
        assertEquals("MANAGED_RUNTIME_UNATTESTED", init.path("reason").asText());
        assertFalse(done.result().path("sourceHealthEligible").booleanValue());
        assertFalse(done.result().path("publishEligible").booleanValue());
        assertEquals(1, done.result().path("requiredCoverage").path("blocked").asInt());
        assertEquals(0, done.result().path("requiredCoverage").path("notTested").asInt());
    }

    @Test
    void frozenManagedRuntimeAndPolicyDriftAreVisibleWithoutComparingFixtureIdentity() throws Exception {
        var done = await(probes.start(create(), "managed_source").id());
        assertEquals("current", done.freshness());
        var stored = runs.findById(done.id()).orElseThrow();
        stored.setRuntimeSha256("0".repeat(64));
        stored.setExecutionPolicySha256("1".repeat(64));
        runs.saveAndFlush(stored);
        assertEquals(List.of("runtime", "execution_policy"), store.get(done.id()).staleInputs());
        var staticRun = await(probes.start(done.sourceId()).id());
        assertNull(staticRun.executionPolicy());
        assertNull(staticRun.runtime());
    }

    @Test
    void acceptedCancellationCannotBeOverwrittenByLateEvidence() throws Exception {
        doReturn(null).when(managed).blockedReason(any());
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1),
                release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            while (true) {
                try { release.await(); break; }
                catch (InterruptedException e) { interrupted.countDown(); }
            }
            return mapper.createObjectNode();
        }).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        var run = probes.start(create(), "managed_source");
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertEquals("CANCELLING", probes.cancel(run.id()).status());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertNull(store.get(run.id()).finishedAt());
        } finally {
            release.countDown();
        }
        assertEquals("CANCELLED", await(run.id()).status());
        assertNull(store.get(run.id()).result());
    }

    @Test
    void cleanupFailureRemainsFailedEvenAfterCancellationOrLateCompletion() throws Exception {
        doReturn(null).when(managed).blockedReason(any());
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            try { Thread.sleep(20000); }
            catch (InterruptedException e) { throw new IOException("MANAGED_CLEANUP_UNCONFIRMED", e); }
            throw new AssertionError("Expected cancellation");
        }).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        var run = probes.start(create(), "managed_source");
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        probes.cancel(run.id());
        var failed = await(run.id());
        assertEquals("FAILED", failed.status());
        assertEquals("MANAGED_CLEANUP_UNCONFIRMED", failed.errorCode());
        store.finish(run.id(), "COMPLETED", mapper.createObjectNode(), null, null);
        assertEquals(failed, store.get(run.id()));
    }

    @Test
    void restartCleansOnlyStartedManagedJobsAndDoesNotReplayThem() throws Exception {
        String source = create();
        var cleaned = store.create(source, inspector.sha256(), knowledge.sha256(), "managed_source");
        var failed = store.create(source, inspector.sha256(), knowledge.sha256(), "managed_source");
        var queued = store.create(source, inspector.sha256(), knowledge.sha256(), "managed_source");
        store.start(cleaned.getId());
        store.start(failed.getId());
        store.requestCancel(failed.getId());
        doNothing().when(managed).cancelAcceptance(eq(cleaned.getId()), any());
        doThrow(new IOException("MANAGED_CLEANUP_UNCONFIRMED")).when(managed).cancelAcceptance(eq(failed.getId()), any());
        probes.recover();
        assertEquals("INTERRUPTED", store.get(cleaned.getId()).status());
        assertEquals("SERVICE_RESTARTED", store.get(cleaned.getId()).errorCode());
        assertEquals("FAILED", store.get(failed.getId()).status());
        assertEquals("MANAGED_CLEANUP_UNCONFIRMED", store.get(failed.getId()).errorCode());
        assertEquals("INTERRUPTED", store.get(queued.getId()).status());
        verify(managed).cancelAcceptance(eq(cleaned.getId()), any());
        verify(managed).cancelAcceptance(eq(failed.getId()), any());
        verify(managed, never()).cancelAcceptance(eq(queued.getId()), any());
        verify(managed, never()).cleanup(anyString(), any());
        verify(managed, never()).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void failedManagedTaskKeepsDiagnosticAndStaticNodesWithoutGrantingBusinessHealth() throws Exception {
        doReturn(null).when(managed).blockedReason(any());
        var diagnostic = mapper.createObjectNode().put("schemaVersion", 1)
                .put("authority", "managed_source_broker_observed")
                .put("failureStage", "container_create").put("primaryCode", "ENGINE_TIMEOUT")
                .put("createIssued", true).put("createConfirmed", false).put("createAckBytes", 0);
        doThrow(new ManagedExecutionFailure("MANAGED_CLEANUP_UNCONFIRMED", diagnostic))
                .when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        var failed = await(probes.start(create(), "managed_source").id());
        assertEquals("FAILED", failed.status());
        assertEquals("MANAGED_CLEANUP_UNCONFIRMED", failed.errorCode());
        assertEquals("ENGINE_TIMEOUT", failed.result().path("managedExecutionDiagnostic").path("primaryCode").asText());
        assertEquals(3, failed.result().path("nodes").valueStream()
                .filter(node -> node.path("status").asText().equals("pass")).count());
        assertFalse(failed.result().path("sourceHealthEligible").booleanValue());
        assertFalse(failed.result().path("publishEligible").booleanValue());
        assertFalse(failed.result().has("managedSourceEvidence"));
        assertTrue(failed.result().path("nodes").valueStream().anyMatch(node ->
                node.path("id").asText().equals("S06") && node.path("status").asText().equals("blocked")
                        && node.path("evidence").path("scope").asText().equals("failure_diagnostic_only")));
    }

    @Test
    void deniedPlayerIsVisibleInTheOriginalReportAndDoesNotPretendMediaWasRequested() throws Exception {
        doReturn(null).when(managed).blockedReason(any());
        var evidence = mapper.createObjectNode().put("executionKind", "t4_business_sample");
        var counts = evidence.putObject("untrustedRuntimeSummary").put("status", "detail_returned")
                .put("playerStatus", "policy_denied").put("playerUrlKind", "absent")
                .put("playerParse", "invalid").put("mediaStatus", "not_requested");
        counts.put("homeItems", 20);
        doReturn(evidence).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        var done = await(probes.start(create(), "managed_source").id());
        var player = done.result().path("nodes").valueStream()
                .filter(node -> node.path("id").asText().equals("R01")).findFirst().orElseThrow();
        var media = done.result().path("nodes").valueStream()
                .filter(node -> node.path("id").asText().equals("M01")).findFirst().orElseThrow();
        assertEquals("T4_PLAYER_POLICY_DENIED", player.path("reason").asText());
        assertEquals("T4_MEDIA_PLAYER_PREREQUISITE", media.path("reason").asText());
        assertEquals("blocked", player.path("status").asText());
        assertEquals("blocked", media.path("status").asText());
        assertFalse(done.result().path("sourceHealthEligible").booleanValue());
        assertFalse(done.result().path("publishEligible").booleanValue());

        evidence.putArray("deniedCodes").add("EGRESS_NONPUBLIC_TARGET_DENIED");
        var denied = await(probes.start(create(), "managed_source").id());
        var privatePlayer = denied.result().path("nodes").valueStream()
                .filter(node -> node.path("id").asText().equals("R01")).findFirst().orElseThrow();
        assertEquals("T4_PLAYER_PRIVATE_DEPENDENCY_DENIED", privatePlayer.path("reason").asText());
        assertEquals("policy_denied", privatePlayer.path("evidence").path("counts").path("playerStatus").asText());
        assertEquals("not_requested", denied.result().path("managedSourceEvidence")
                .path("untrustedRuntimeSummary").path("mediaStatus").asText());
    }

    @Test
    void businessSampleLivesInOriginalTaskReportAndExistingNodeDrawersWithoutPromotion() throws Exception {
        doReturn(null).when(managed).blockedReason(any());
        var evidence = mapper.createObjectNode().put("authority", "managed_source_host_observed")
                .put("executionKind", "t4_business_sample").put("businessChainVerified", false);
        var counts = evidence.putObject("untrustedRuntimeSummary").put("status", "detail_returned");
        for (String field : List.of("homeClasses", "homeItems", "homeCovers", "categoryItems", "categoryCovers",
                "page2Items", "detailItems", "detailCovers", "playGroups", "playEpisodes")) counts.put(field, 20);
        doReturn(evidence).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        String source = create();
        var profile = profiles.get(source);
        profiles.update(source, new UpdateProfileRequest(profile.sha256(), "t4", "unverified",
                List.of("S01", "S02", "S03", "S06", "C03", "C07"), List.of(), null, null));
        var done = await(probes.start(source, "managed_source").id());
        assertEquals("COMPLETED", done.status());
        assertEquals("t4_business_sample_observed", done.result().path("verdict").asText());
        assertEquals(3, done.result().path("requiredCoverage").path("pass").intValue());
        assertEquals(3, done.result().path("requiredCoverage").path("blocked").intValue());
        assertEquals(0, done.result().path("requiredCoverage").path("notTested").intValue());
        var detail = done.result().path("nodes").valueStream()
                .filter(node -> node.path("id").asText().equals("C07")).findFirst().orElseThrow();
        assertEquals(20, detail.path("evidence").path("counts").path("detailItems").intValue());
        assertEquals("untrusted_source_output", detail.path("evidenceClass").asText());
        assertFalse(done.result().path("sourceHealthEligible").booleanValue());
        assertFalse(done.result().path("publishEligible").booleanValue());
        assertFalse(done.result().path("nodes").valueStream()
                .anyMatch(node -> node.path("id").asText().startsWith("M")
                        && !node.path("status").asText().equals("not_tested")));
    }

    @Test
    void projectedLoaderReceiptsPopulateExistingNodesAndRequiredCoverage() throws Exception {
        doReturn(null).when(managed).blockedReason(any());
        var evidence = mapper.createObjectNode().put("authority", "managed_source_host_observed")
                .put("executionKind", "t4_business_sample");
        evidence.putObject("untrustedRuntimeSummary").put("status", "detail_returned");
        var loader = evidence.putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION);
        var nodes = loader.putArray("nodes");
        nodes.addObject().put("id", "S06").put("status", "pass")
                .put("reason", "PRE_EXEC_SOURCE_SHA_AND_INIT_RETURNED");
        nodes.addObject().put("id", "C03").put("status", "fail").put("reason", "PAGINATION_METADATA_INVALID");
        nodes.addObject().put("id", "C05").put("status", "not_tested").put("reason", "NODE_NOT_VALIDATED_BY_THIS_RUNNER");
        doReturn(evidence).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        String source = create();
        var profile = profiles.get(source);
        profiles.update(source, new UpdateProfileRequest(profile.sha256(), "t4", "unverified",
                List.of("S01", "S02", "S03", "S06", "C03", "C05"), List.of(), null, null));
        var done = await(probes.start(source, "managed_source").id());
        var coverage = done.result().path("requiredCoverage");
        assertEquals(4, coverage.path("pass").asInt());
        assertEquals(1, coverage.path("fail").asInt());
        assertEquals(0, coverage.path("blocked").asInt());
        assertEquals(1, coverage.path("notTested").asInt());
        assertEquals("required_failed", coverage.path("conclusion").asText());
        var paging = done.result().path("nodes").valueStream()
                .filter(node -> node.path("id").asText().equals("C03")).findFirst().orElseThrow();
        assertEquals("PAGINATION_METADATA_INVALID", paging.path("reason").asText());
        assertEquals("host_loader_contract_observed", paging.path("evidenceClass").asText());
        assertEquals("C03", done.context().path("node").asText());
        assertEquals("PAGINATION_METADATA_INVALID", done.context().path("failure").asText());
        assertTrue(done.context().path("rules").valueStream()
                .anyMatch(rule -> rule.path("id").asText().equals("response-contract-first")));
        assertFalse(done.result().path("publishEligible").asBoolean());
    }

    @Test
    void mediaHeaderFailureSelectsItsOwnGuidanceWithoutLettingOptionalFailuresHideIt() throws Exception {
        doReturn(null).when(managed).blockedReason(any());
        var evidence = mapper.createObjectNode().put("executionKind", "t4_business_sample");
        var nodes = evidence.putObject("loaderValidation").putArray("nodes");
        nodes.addObject().put("id", "C05").put("status", "fail").put("reason", "CONTENT_LIST_EMPTY_OR_INVALID");
        nodes.addObject().put("id", "S06").put("status", "pass")
                .put("reason", "PRE_EXEC_SOURCE_SHA_AND_INIT_RETURNED");
        nodes.addObject().put("id", "M01").put("status", "blocked").put("reason", "EGRESS_HEADER_ORIGIN_DENIED");
        doReturn(evidence).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString(), any());
        String source = create();
        var profile = profiles.get(source);
        profiles.update(source, new UpdateProfileRequest(profile.sha256(), "t4", "unverified",
                List.of("S01", "S02", "S03", "S06", "M01"), List.of("C05"), null, null));
        var done = await(probes.start(source, "managed_source").id());
        assertEquals("M01", done.context().path("node").asText());
        assertEquals("EGRESS_HEADER_ORIGIN_DENIED", done.context().path("failure").asText());
        assertEquals("session-header-scope", done.context().path("rules").get(0).path("id").asText());
        assertEquals("required_blocked", done.result().path("requiredCoverage").path("conclusion").asText());
        assertFalse(done.result().path("publishEligible").asBoolean());
    }

    private String create() {
        return sources.create(new CreateSourceRequest("受管执行任务测试", null, "source.py", null,
                "from base.spider import Spider as Base\nclass Spider(Base):\n def init(self, extend=''): pass\n")).id();
    }

    private ProbeSummary await(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            var run = store.get(id);
            if (!Set.of("QUEUED", "RUNNING", "CANCELLING").contains(run.status())) return run;
            Thread.sleep(25);
        }
        throw new AssertionError("Managed task did not finish");
    }
}
