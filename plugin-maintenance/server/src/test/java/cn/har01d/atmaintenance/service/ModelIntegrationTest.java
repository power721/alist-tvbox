package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.RepairCandidate;
import cn.har01d.atmaintenance.entity.RepairSession;
import cn.har01d.atmaintenance.repository.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.io.ByteArrayInputStream;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:maintenance-model-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=integration-test-only", "maintenance.at.origin=",
        "maintenance.scheduling.enabled=false", "maintenance.model.enabled=true",
        "maintenance.model.model=fixture-model", "maintenance.model.key-env=",
        "maintenance.model.input-price-usd-per-million=1.25", "maintenance.model.output-price-usd-per-million=2.5",
        "maintenance.model.auto-submit-enabled=true",
        "maintenance.model.timeout-millis=2000", "maintenance.model.daily-call-limit=100",
        "maintenance.model.daily-input-bytes=26214400", "maintenance.model.daily-output-tokens=1638400",
        "maintenance.model.model-daily-call-limit=100", "maintenance.model.model-daily-input-bytes=26214400",
        "maintenance.model.model-daily-output-tokens=1638400",
        "maintenance.managed-source.enabled=true",
        "maintenance.managed-source.socket=/run/model-test/executor.sock",
        "maintenance.managed-source.instance-id=d0f4909a-5709-4e57-9a54-4ded3c19424a",
        "maintenance.managed-source.image-id=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "maintenance.managed-source.egress-policy-sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
})
class ModelIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<Function<JsonNode, String>> responder = new AtomicReference<>();
    private static final AtomicReference<String> retryAfter = new AtomicReference<>();
    private static final AtomicInteger received = new AtomicInteger();
    private static final AtomicInteger status = new AtomicInteger(200);
    private static final AtomicReference<String> contentType = new AtomicReference<>("application/json");
    private static final HttpServer fake = fake();
    @Autowired SourceService sources;
    @Autowired ProfileService profiles;
    @Autowired ProbeService probes;
    @Autowired ProbeStore probeStore;
    @Autowired RepairStore repairs;
    @Autowired RepairService repairService;
    @Autowired ModelStore models;
    @Autowired ModelService service;
    @Autowired ModelClient client;
    @Autowired ModelCallRepository calls;
    @Autowired RepairRepository repairRows;
    @Autowired RepairCandidateRepository candidateRows;
    @Autowired ModelBudgetRepository budgets;
    @Autowired RepairExportService exports;
    @Autowired ModelSubmissionService submissions;
    @Autowired AutomaticRepairService automation;
    @Autowired AutomaticRepairStore automaticStore;
    @Autowired ReleasePlanService releasePlans;
    @Autowired ReleasePlanExportService releaseExports;
    @MockitoSpyBean StaticInspector inspector;
    @MockitoSpyBean ManagedSourceRuntime managed;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("maintenance.model.endpoint", () -> "http://127.0.0.1:" + fake.getAddress().getPort() + "/v1/chat/completions");
    }

    @BeforeEach
    void reset() {
        status.set(200);
        contentType.set("application/json");
        retryAfter.set(null);
        responder.set(input -> envelope(proposal(input), "stop"));
    }

    @AfterAll
    static void close() { fake.stop(0); }

    @Test
    void storedHostConfigurationBlockCannotReserveAModelCallOrSpendBudget() throws Exception {
        var base = beginHome();
        var frozen = (com.fasterxml.jackson.databind.node.ObjectNode) base.frozen().deepCopy();
        var evidence = (com.fasterxml.jackson.databind.node.ObjectNode) frozen.at("/baseline/result/managedSourceEvidence");
        var loader = evidence.putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION)
                .put("inputSourceSha256", base.baselineArtifactSha256())
                .put("inputProfileSha256", frozen.at("/baseline/profileSha256").asText());
        loader.putArray("nodes").addObject().put("id", "C01").put("status", "fail")
                .put("reason", "LOADER_METHOD_FAILED").putObject("failureContext").put("hostStage", "atvp_backend_endpoint");
        var row = new RepairSession();
        row.setSourceId(base.sourceId()); row.setBaselineRunId(base.baselineRunId());
        row.setBaselineArtifactSha256(base.baselineArtifactSha256());
        row.setFaultSha256(Hashes.sha256(UUID.randomUUID().toString()));
        row.setFrozenJson(frozen.toString());
        repairRows.saveAndFlush(row);
        var repair = repairs.get(row.getId());
        var diagnostic = new RepairDiagnostics(mapper).forRepair(repair, List.of());
        assertTrue(diagnostic.path("hostConfigurationRequired").asBoolean(), diagnostic::toString);
        var budget = models.budget();
        int before = received.get();
        assertCode("MODEL_HOST_CONFIGURATION_REQUIRED", () -> models.reserve(repair.id(), input(repair, "host_block_001")));
        assertEquals(budget, models.budget());
        assertEquals(before, received.get());
        assertTrue(models.list(repair.id()).isEmpty());
        assertEquals(0, repairs.get(repair.id()).usedRounds());
    }

    @Test
    void customRoundLimitAlsoControlsModelCallsPastThreeWithoutClearingAccounting() throws Exception {
        var repair = begin();
        repairs.updateRoundLimit(repair.id(), new UpdateRepairRoundLimitRequest(repair.faultSha256(), 3, 4));
        long before = models.budget().usedCalls();
        int receivedBefore = received.get();
        for (int index = 0; index < 4; index++) {
            var call = models.reserve(repair.id(), input(repair, "custom_rounds_" + index));
            assertTrue(models.start(call.getId()));
            models.finish(call.getId(), null, "MODEL_HTTP_ERROR");
        }
        assertEquals(4, models.list(repair.id()).size());
        assertCode("MODEL_FAULT_CALL_LIMIT", () -> models.reserve(repair.id(), input(repair, "custom_rounds_blocked")));
        repairs.updateRoundLimit(repair.id(), new UpdateRepairRoundLimitRequest(repair.faultSha256(), 4, 8));
        var fifth = models.reserve(repair.id(), input(repair, "custom_rounds_fifth"));
        assertEquals(5, models.list(repair.id()).size());
        assertEquals(before + 5, models.budget().usedCalls());
        assertEquals(0, repairs.get(repair.id()).usedRounds());
        assertEquals(receivedBefore, received.get());
        assertEquals("REPAIR_ROUND_LIMIT_BUSY", assertThrows(ResponseStatusException.class,
                () -> repairs.updateRoundLimit(repair.id(), new UpdateRepairRoundLimitRequest(repair.faultSha256(), 8, 9))).getReason());
        assertTrue(models.start(fifth.getId()));
        models.finish(fifth.getId(), null, "MODEL_HTTP_ERROR");
    }

    @Test
    void originDiscoveryUsesOneDurableBudgetCallButNoRepairRoundOrProposal() throws Exception {
        var repair = begin();
        var search = mapper.createObjectNode().put("status", "results_observed");
        search.putArray("results").addObject().put("url", "https://mirror.example/")
                .put("origin", "https://mirror.example");
        responder.set(input -> {
            assertTrue(ModelClient.discoveryRequest(input.toString()));
            assertFalse(input.toString().contains(RepairIntegrationTest.BAD));
            var result = mapper.createObjectNode();
            result.putArray("candidates").addObject().put("origin", "https://mirror.example")
                    .put("evidenceUrl", "https://mirror.example/");
            return envelope(result, "stop");
        });
        long used = models.budget().usedCalls();
        int requests = received.get();
        var result = await(service.discoverOrigins(repair.id(), repair.faultSha256(), search).id());
        assertEquals("DISCOVERED", result.status());
        assertNull(result.proposal());
        assertNull(result.candidateId());
        assertEquals(1, result.responseDiagnostics().path("originCandidates").size());
        assertEquals(result.id(), service.discoverOrigins(repair.id(), repair.faultSha256(), search).id());
        assertEquals(requests + 1, received.get());
        assertEquals(used + 1, models.budget().usedCalls());
        assertEquals(0, repairs.get(repair.id()).usedRounds());
        assertTrue(models.repairCalls(repair.id()).isEmpty());
        assertEquals(1, models.list(repair.id()).size());
        assertEquals("NOT_REQUESTED", result.submissionStatus());
    }

    @Test
    void originDiscoveryNeverRetriesProviderFailureOrReservesAnotherCall() throws Exception {
        var repair = begin();
        status.set(503);
        responder.set(input -> "{\"error\":{\"message\":\"temporary unavailable\"}}");
        var search = mapper.createObjectNode().put("status", "results_observed");
        int requests = received.get();
        var result = await(service.discoverOrigins(repair.id(), repair.faultSha256(), search).id());
        assertEquals("FAILED", result.status());
        assertFalse(result.responseDiagnostics().path("retryScheduled").asBoolean());
        assertEquals(result.id(), service.discoverOrigins(repair.id(), repair.faultSha256(), search).id());
        assertEquals(requests + 1, received.get());
        assertEquals(0, repairs.get(repair.id()).usedRounds());
    }

    @Test
    void enhancedRequestIsDurableIdempotentAndModeCannotReuseAnOrdinaryKey() throws Exception {
        var repair = begin();
        try (var http = HttpClient.newHttpClient()) {
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                    "admin:integration-test-only".getBytes(StandardCharsets.UTF_8));
            for (String path : List.of("/repairs/" + repair.id(), "/runs/" + repair.baselineRunId())) {
                var address = URI.create("http://127.0.0.1:" + port + "/api/plugin-maintenance" + path + "/diagnostics");
                assertEquals(401, http.send(HttpRequest.newBuilder(address).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
                var response = http.send(HttpRequest.newBuilder(address).header("Authorization", auth).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                var diagnostic = mapper.readTree(response.body());
                assertTrue(diagnostic.path("readOnly").asBoolean());
                assertEquals(0, diagnostic.path("newModelCalls").asInt());
                assertFalse(response.body().contains("not-forwarded-secret"));
            }
        }
        var config = client.connection().configurationSha256();
        var input = new StartModelCallRequest(repair.faultSha256(), config, "enhanced-mode-001", false, true);
        int before = received.get();
        var call = await(service.propose(repair.id(), input).id());
        assertTrue(call.enhancedDiagnostics());
        assertTrue(ModelClient.enhancedRequest(mapper, calls.findById(call.id()).orElseThrow().getRequestJson()));
        assertEquals(call.id(), models.reserve(repair.id(), input).getId());
        assertCode("MODEL_REQUEST_KEY_CONFLICT", () -> models.reserve(repair.id(),
                new StartModelCallRequest(repair.faultSha256(), config, input.requestKey(), false, false)));
        assertEquals(1, received.get() - before);
        assertEquals(0, repairs.get(repair.id()).usedRounds());
        var automatic = automaticStore.start(repair.id(), config, true);
        assertTrue(automatic.enhancedDiagnostics());
        assertTrue(automaticStore.get(repair.id()).enhancedDiagnostics());
        assertCode("AUTOMATIC_DIAGNOSTIC_MODE_CHANGED", () -> automaticStore.start(repair.id(), config, false));
        automaticStore.finish(repair.id(), "CANCELLED", "TEST_FINISHED");
    }

    @Test
    void automaticRepairPersistsAndFinishesWithoutClientDrivingSubmission() throws Exception {
        var repair = begin();
        int before = received.get();
        var started = automation.start(repair.id(), autoInput(repair, "automatic-static-001"));
        assertEquals("ACTIVE", started.status());
        assertEquals(client.connection().configurationSha256(), automaticStore.get(repair.id()).configurationSha256());
        awaitAutomatic(repair.id());
        assertEquals("STATIC_ONLY", automaticStore.get(repair.id()).status(),
                () -> automaticStore.get(repair.id()) + " calls=" + models.list(repair.id()).stream()
                        .map(call -> call.status() + ":" + call.errorCode() + ":" + call.submissionErrorCode()).toList());
        assertEquals(1, received.get() - before);
        var candidate = repairs.candidates(repair.id()).getFirst();
        assertEquals("STATIC_VALIDATED", candidate.status());
        assertNotNull(candidate.modelCallId());
        assertFalse(candidate.result().path("repairSuccessful").asBoolean());
        assertEquals(repair.baselineArtifactSha256(), sources.get(repair.sourceId()).artifactSha256());
    }

    @Test
    void automaticRepairFeedsRejectedCandidateToNextModelRound() throws Exception {
        var repair = begin();
        int before = received.get();
        responder.set(input -> {
            try {
                var data = JSON.readTree(input.path("messages").get(1).path("content").asText());
                var next = proposal(input);
                if (data.path("previousCandidates").isEmpty()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) next.path("edits").get(0))
                            .put("newText", "    def broken(:\n");
                } else assertEquals(1, data.path("previousCandidates").size());
                return envelope(next, "stop");
            } catch (IOException error) { throw new AssertionError(error); }
        });
        automation.start(repair.id(), autoInput(repair, "automatic-feedback-001"));
        awaitAutomatic(repair.id());
        assertEquals("STATIC_ONLY", automaticStore.get(repair.id()).status(),
                () -> automaticStore.get(repair.id()) + " calls=" + models.list(repair.id()).stream()
                        .map(call -> call.status() + ":" + call.errorCode() + ":" + call.submissionErrorCode()).toList());
        assertEquals(2, received.get() - before);
        var history = repairs.candidates(repair.id());
        assertEquals(List.of("REJECTED", "STATIC_VALIDATED"), history.stream()
                .map(RepairCandidateSummary::status).toList());
        assertEquals(2, repairs.get(repair.id()).usedRounds());
    }

    private void awaitAutomatic(String id) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while ("ACTIVE".equals(automaticStore.get(id).status()) && System.nanoTime() < until) {
            automation.poll();
            Thread.sleep(20);
        }
        assertNotEquals("ACTIVE", automaticStore.get(id).status());
    }

    @Test
    void reviewedModelProposalReusesStaticValidatorAndCannotExecuteOrPublish() throws Exception {
        var repair = begin();
        int before = received.get();
        var input = input(repair, "reviewed-001");
        var result = await(service.propose(repair.id(), input).id());
        assertEquals("PROPOSED", result.status());
        assertEquals(1, received.get() - before);
        assertEquals("fixture-model", result.modelName());
        assertEquals(10, result.usage().path("prompt_tokens").asInt());
        assertEquals("0.0000625", result.costEstimate().path("totalUsd").asText());
        assertEquals(result.id(), service.propose(repair.id(), input).id());
        assertEquals(1, received.get() - before);
        assertEquals(0, repairs.get(repair.id()).usedRounds());
        var candidate = repairService.propose(repair.id(), mapper.treeToValue(result.proposal(), RepairProposal.class));
        var validated = awaitCandidate(candidate.id());
        assertEquals("STATIC_VALIDATED", validated.status());
        assertEquals("AWAITING_RUNTIME_VALIDATION", repairs.get(repair.id()).status());
        assertFalse(validated.result().path("publishEligible").asBoolean());
        assertTrue(repairs.content(validated.id()).contains("'duplicate': True"));
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertNull(validated.modelCallId()); // Pasting JSON does not establish model provenance.
        assertEquals(repair.baselineArtifactSha256(), sources.get(repair.sourceId()).artifactSha256());
        assertTrue(calls.findById(result.id()).orElseThrow().getRequestJson().contains("data_only"));
        assertFalse(calls.findById(result.id()).orElseThrow().getRequestJson().contains("not-forwarded-secret"));
        assertEquals(result.requestSha256(), Hashes.sha256(calls.findById(result.id()).orElseThrow().getRequestJson()));
    }

    @Test
    void nextModelRequestFreezesPreviousCandidateFeedbackBeforeTransport() throws Exception {
        var repair = begin();
        var candidate = awaitCandidate(repairService.propose(repair.id(), repairProposal(repair,
                List.of(new RepairEdit(RepairIntegrationTest.OVERRIDE, "def broken(:\n")))).id());
        assertEquals("REJECTED", candidate.status());
        assertEquals("AWAITING_PROPOSAL", repairs.get(repair.id()).status());
        int before = received.get();
        var call = models.reserve(repair.id(), input(repair, "feedback-without-send"));
        try {
            var request = mapper.readTree(call.getRequestJson());
            var data = mapper.readTree(request.path("messages").get(1).path("content").asText());
            var feedback = data.path("previousCandidates");
            assertEquals(1, feedback.size());
            assertEquals(candidate.sha256(), feedback.get(0).path("candidateSha256").asText());
            assertEquals(mapper.readTree("[\"invalid_syntax\"]"), feedback.get(0).path("staticIssues"));
            assertFalse(feedback.get(0).path("runtimeEvidenceMatched").asBoolean());
            assertEquals(before, received.get());
            assertEquals(call.getRequestJson().getBytes(StandardCharsets.UTF_8).length,
                    models.get(call.getId()).requestBytes());
            assertEquals(Hashes.sha256(call.getRequestJson()), models.get(call.getId()).requestSha256());
        } finally { models.cancel(call.getId()); }
    }

    @Test
    void homeFaultModelProposalReusesExplicitRuntimeValidationWithoutPublishing() throws Exception {
        var repair = beginHome();
        var baseline = probeStore.get(repair.baselineRunId());
        responder.set(request -> envelope(homeProposal(request), "stop"));
        int before = received.get();
        var call = await(service.propose(repair.id(), input(repair, "home-reviewed-001")).id());
        assertEquals("PROPOSED", call.status());
        assertEquals(1, received.get() - before);
        var candidate = awaitCandidate(submissions.submit(call.id(), submission(call)).id());
        assertEquals("STATIC_VALIDATED", candidate.status());
        assertEquals(call.id(), candidate.modelCallId());
        assertFalse(candidate.result().path("targetRecovered").asBoolean());
        verify(managed, never()).inspect(anyString(), anyString(), any(), anyString(), anyString());
        doAnswer(invocation -> {
            assertEquals(candidate.id(), invocation.getArgument(0));
            assertEquals(candidate.sha256(), Hashes.sha256((String) invocation.getArgument(1)));
            assertEquals(baseline.profile(), invocation.getArgument(2));
            assertEquals(baseline.runtimeSha256(), invocation.getArgument(3));
            assertEquals(baseline.executionPolicySha256(), invocation.getArgument(4));
            return homeEvidence(baseline, candidate.sha256(), true);
        }).when(managed).inspect(anyString(), anyString(), any(), anyString(), anyString());
        repairService.validateRuntime(candidate.id(), candidate.sha256());
        var observed = awaitCandidate(candidate.id());
        assertEquals("RUNTIME_OBSERVED", observed.status());
        assertTrue(observed.result().path("targetObservedRecovered").asBoolean());
        assertEquals("AWAITING_PROPOSAL", repairs.get(repair.id()).status());
        assertFalse(observed.result().path("repairSuccessful").asBoolean());
        for (String field : List.of("targetRecovered", "businessVerdictTrusted", "publishEligible"))
            assertFalse(observed.result().path(field).asBoolean(), field);
        verify(managed, times(1)).inspect(anyString(), anyString(), any(), anyString(), anyString());
        assertEquals(repair.baselineArtifactSha256(), sources.get(repair.sourceId()).artifactSha256());
        assertEquals(1, sources.versions(repair.sourceId()).size());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals(1, received.get() - before);
    }

    @Test
    void homeModelCannotSubstituteAStaticTargetOrConsumeACandidateRound() throws Exception {
        var repair = beginHome();
        responder.set(request -> {
            var proposal = homeProposal(request);
            proposal.withArray("validationTargets").removeAll().add("S03");
            return envelope(proposal, "stop");
        });
        var call = await(service.propose(repair.id(), input(repair, "home-wrong-target-001")).id());
        assertEquals("FAILED", call.status());
        assertEquals("PATCH_TARGET_MISMATCH", call.errorCode());
        assertNull(call.proposal());
        assertTrue(repairs.candidates(repair.id()).isEmpty());
        assertEquals(0, repairs.get(repair.id()).usedRounds());
        verify(managed, never()).inspect(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void reviewedSubmissionBindsExactProposalAndConcurrentRetriesReturnOneCandidate() throws Exception {
        var repair = begin();
        var call = await(service.propose(repair.id(), input(repair, "bound-review-001")).id());
        assertFalse(call.autoSubmit());
        assertEquals("NOT_REQUESTED", call.submissionStatus());
        assertEquals(Hashes.sha256(calls.findById(call.id()).orElseThrow().getProposalJson()), call.proposalSha256());
        var budget = models.budget();
        assertCode("MODEL_PROPOSAL_CHANGED", () -> submissions.submit(call.id(),
                new SubmitModelProposalRequest("0".repeat(64))));
        assertEquals(0, repairs.get(repair.id()).usedRounds());
        List<RepairCandidateSummary> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return submissions.submit(call.id(), submission(call)); });
            var b = pool.submit(() -> { start.await(); return submissions.submit(call.id(), submission(call)); });
            start.countDown();
            results.add(a.get(10, TimeUnit.SECONDS));
            results.add(b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(results.getFirst().id(), results.getLast().id());
        var candidate = awaitCandidate(results.getFirst().id());
        assertEquals("STATIC_VALIDATED", candidate.status());
        assertEquals(call.id(), candidate.modelCallId());
        assertEquals(call.proposalSha256(), candidate.proposalSha256());
        assertEquals(candidate.id(), models.get(call.id()).candidateId());
        assertEquals("MANUAL", models.get(call.id()).submissionMethod());
        assertEquals("SUBMITTED", models.get(call.id()).submissionStatus());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals(budget, models.budget());
        assertEquals(candidate.id(), submissions.submit(call.id(), submission(call)).id());
        try (var zip = new ZipInputStream(new ByteArrayInputStream(exports.export(candidate.id())))) {
            var files = new HashMap<String, byte[]>();
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                files.put(entry.getName(), zip.readAllBytes());
            var provenance = mapper.readTree(files.get("provenance.json"));
            assertEquals("model_call", provenance.path("origin").asText());
            assertEquals(call.id(), provenance.path("modelCallId").asText());
            assertEquals(call.proposalSha256(), provenance.path("proposalSha256").asText());
            assertEquals("MANUAL", provenance.path("submissionMethod").asText());
            assertEquals(call.requestSha256(), provenance.path("requestSha256").asText());
        }
        var plan = releasePlans.create(repair.sourceId(), new StartReleasePlanRequest(candidate.id(),
                repair.baselineArtifactSha256(), candidate.sha256(), "model-release-preparation"));
        var frozen = releasePlans.frozen(plan.id());
        assertEquals("model_call", frozen.path("provenance").path("origin").asText());
        assertEquals(candidate.id(), frozen.path("provenance").path("call").path("candidateId").asText());
        assertEquals(call.requestSha256(), frozen.path("provenance").path("call").path("requestSha256").asText());
        assertFalse(plan.publishEligible());
        int beforeExport = received.get();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(releaseExports.export(plan.id())))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("provenance.json"))
                    assertEquals(frozen.path("provenance"), mapper.readTree(zip.readAllBytes()));
            }
        }
        assertEquals(beforeExport, received.get());
        assertEquals(budget, models.budget());
    }

    @Test
    void explicitAutoSubmissionUsesOriginalGateAndNeverCreatesAnAutomaticNextCall() throws Exception {
        var repair = begin();
        int before = received.get();
        var input = autoInput(repair, "auto-good-001");
        var call = awaitSubmission(service.propose(repair.id(), input).id());
        assertEquals("PROPOSED", call.status());
        assertTrue(call.autoSubmit());
        assertEquals("AUTO", call.submissionMethod());
        var candidate = awaitCandidate(call.candidateId());
        assertEquals(call.id(), candidate.modelCallId());
        assertEquals("STATIC_VALIDATED", candidate.status());
        assertFalse(candidate.result().path("publishEligible").asBoolean());
        assertEquals("AWAITING_RUNTIME_VALIDATION", repairs.get(repair.id()).status());
        assertEquals(repair.baselineArtifactSha256(), sources.get(repair.sourceId()).artifactSha256());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals(before + 1, received.get());
        assertEquals(call.id(), service.propose(repair.id(), input).id());
        submissions.autoSubmit(call.id());
        assertEquals(1, repairs.candidates(repair.id()).size());
        assertCode("MODEL_REQUEST_KEY_CONFLICT", () -> service.propose(repair.id(),
                input(repair, "auto-good-001")));
    }

    @Test
    void autoSubmissionRejectsContractsAndStopsRepeatedFailuresWithoutAnotherModelRound() throws Exception {
        var repair = begin();
        for (int round = 0; round < 2; round++) {
            int current = round;
            responder.set(request -> {
                var value = proposal(request);
                value.withArray("edits").addObject().put("oldText", "def init(self, extend=\"\"):")
                        .put("newText", current == 0 ? "def init(self):" : "def init(self, other):");
                return envelope(value, "stop");
            });
            var call = awaitSubmission(service.propose(repair.id(), autoInput(repair, "auto-regression-" + round)).id());
            assertEquals("CONTRACT_REGRESSION", awaitCandidate(call.candidateId()).errorCode());
        }
        assertEquals(2, models.list(repair.id()).size());
        assertEquals(2, repairs.get(repair.id()).usedRounds());
        assertEquals("STOPPED", repairs.get(repair.id()).status());
        assertEquals("REPEATED_FAILURE", repairs.get(repair.id()).stopCode());
        assertCode("REPAIR_NOT_ACCEPTING_PROPOSALS", () ->
                service.propose(repair.id(), autoInput(repair, "auto-regression-003")));
    }

    @Test
    void automaticThirdCandidateSharesTheExistingManualRoundBudget() throws Exception {
        var repair = begin();
        for (int round = 1; round <= 2; round++) {
            var bad = repairProposal(repair, List.of(new RepairEdit(RepairIntegrationTest.OVERRIDE,
                    "\n".repeat(round) + "    def broken(\n")));
            assertEquals("REJECTED", awaitCandidate(repairService.propose(repair.id(), bad).id()).status());
        }
        var call = awaitSubmission(service.propose(repair.id(), autoInput(repair, "third-candidate-001")).id());
        assertEquals("STATIC_VALIDATED", awaitCandidate(call.candidateId()).status());
        assertEquals(3, repairs.get(repair.id()).usedRounds());
        assertEquals(1, models.list(repair.id()).size());
        assertCode("REPAIR_NOT_ACCEPTING_PROPOSALS", () ->
                service.propose(repair.id(), autoInput(repair, "fourth-candidate-001")));
    }

    @Test
    void pendingSubmissionCanBeCancelledAndOnlyExplicitReviewedActionCanRestartIt() throws Exception {
        var repair = begin();
        var call = savedProposal(repair, "pending-cancel-001", true);
        var budget = models.budget();
        assertEquals("CANCELLED", service.cancel(call.id()).submissionStatus());
        submissions.autoSubmit(call.id());
        assertTrue(repairs.candidates(repair.id()).isEmpty());
        assertEquals("PROPOSED", models.get(call.id()).status());
        var candidate = submissions.submit(call.id(), submission(call));
        assertEquals("STATIC_VALIDATED", awaitCandidate(candidate.id()).status());
        assertEquals("MANUAL", models.get(call.id()).submissionMethod());
        assertEquals(budget, models.budget());
    }

    @Test
    void restartBeforeCandidateReservationDisablesPendingAutoWorkWithoutRefundOrReplay() throws Exception {
        var repair = begin();
        var call = savedProposal(repair, "pending-restart-001", true);
        var budget = models.budget();
        models.recover();
        assertEquals("INTERRUPTED", models.get(call.id()).submissionStatus());
        assertEquals("MODEL_SUBMISSION_RESTARTED", models.get(call.id()).submissionErrorCode());
        assertEquals(call.id(), service.propose(repair.id(), autoInput(repair, "pending-restart-001")).id());
        submissions.autoSubmit(call.id());
        assertTrue(repairs.candidates(repair.id()).isEmpty());
        assertEquals(budget, models.budget());
        var candidate = submissions.submit(call.id(), submission(call));
        assertEquals("STATIC_VALIDATED", awaitCandidate(candidate.id()).status());
    }

    @Test
    void restartAfterAtomicReservationKeepsLinkAndRoundAndNeverRequeuesItsCandidate() throws Exception {
        var repair = begin();
        var call = savedProposal(repair, "reserved-restart-001", true);
        var reserved = models.reserveSubmission(call.id(), call.proposalSha256(), true);
        assertEquals("QUEUED", reserved.getStatus());
        assertEquals("SUBMITTED", models.get(call.id()).submissionStatus());
        models.recover();
        repairs.interruptUnfinished();
        repairs.resume(repair.id(), repair.faultSha256());
        var retried = submissions.submit(call.id(), submission(call));
        assertEquals(reserved.getId(), retried.id());
        assertEquals("INTERRUPTED", retried.status());
        assertNull(retried.result());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals("AWAITING_PROPOSAL", repairs.get(repair.id()).status());
    }

    @Test
    void consentRevocationRepairCancellationAndSourceDriftBlockBeforeAtomicReservation() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            var repair = begin();
            var call = savedProposal(repair, "changed-input-001", true);
            if (mode == 0) models.consent(repair.sourceId(),
                    new UpdateModelConsentRequest(repair.baselineArtifactSha256(), null));
            if (mode == 1) repairService.cancel(repair.id());
            if (mode == 2) sources.addArtifact(repair.sourceId(),
                    new AddArtifactRequest(repair.baselineArtifactSha256(), RepairIntegrationTest.BAD + "\n"));
            var budget = models.budget();
            submissions.autoSubmit(call.id());
            var failed = models.get(call.id());
            assertEquals("PROPOSED", failed.status());
            assertEquals("FAILED", failed.submissionStatus());
            assertEquals(List.of("MODEL_SOURCE_NOT_AUTHORIZED", "REPAIR_NOT_ACCEPTING_PROPOSALS",
                    "REPAIR_INPUTS_CHANGED").get(mode), failed.submissionErrorCode());
            assertNull(failed.candidateId());
            assertTrue(repairs.candidates(repair.id()).isEmpty());
            assertEquals(0, repairs.get(repair.id()).usedRounds());
            assertEquals(budget, models.budget());
        }
    }

    @Test
    void concurrentAutomaticAndReviewedReservationsCommitOneProvenanceAndOneRound() throws Exception {
        var repair = begin();
        var call = savedProposal(repair, "submission-race-001", true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return models.reserveSubmission(call.id(), call.proposalSha256(), true); });
            var b = pool.submit(() -> { start.await(); return models.reserveSubmission(call.id(), call.proposalSha256(), false); });
            start.countDown();
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            assertEquals(first.getId(), second.getId());
            assertEquals("STATIC_VALIDATED", awaitCandidate(repairService.enqueue(first).id()).status());
        }
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals(1, repairs.candidates(repair.id()).size());
        assertEquals("SUBMITTED", models.get(call.id()).submissionStatus());
        assertTrue(Set.of("AUTO", "MANUAL").contains(models.get(call.id()).submissionMethod()));
    }

    @Test
    void queueRejectionKeepsAcceptedModelLinkAndRoundAndRetryCannotCreateAnotherCandidate() throws Exception {
        var fillers = new ArrayList<RepairSummary>();
        for (int i = 0; i < 9; i++) fillers.add(begin());
        var repair = begin();
        var call = savedProposal(repair, "queue-rejection-001", true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Inspector fixture wait timed out");
            return invocation.callRealMethod();
        }).when(inspector).inspect(anyString());
        var candidates = new ArrayList<RepairCandidateSummary>();
        try {
            for (var filler : fillers) {
                candidates.add(repairService.propose(filler.id(), repairProposal(filler,
                        List.of(new RepairEdit(RepairIntegrationTest.OVERRIDE, "")))));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
            }
            submissions.autoSubmit(call.id());
            var submitted = models.get(call.id());
            assertEquals("SUBMITTED", submitted.submissionStatus());
            var rejected = repairs.candidate(submitted.candidateId());
            assertEquals("QUEUE_FULL", rejected.errorCode());
            assertEquals("REJECTED", rejected.status());
            assertEquals(call.id(), rejected.modelCallId());
            assertEquals(rejected.id(), submissions.submit(call.id(), submission(call)).id());
            assertEquals(1, repairs.get(repair.id()).usedRounds());
        } finally { release.countDown(); }
        for (var candidate : candidates) assertEquals("STATIC_VALIDATED", awaitCandidate(candidate.id()).status());
    }

    @Test
    void modelCannotLowerContractsEvenWhenItsPatchIsStructurallyValid() throws Exception {
        var repair = begin();
        responder.set(input -> {
            var proposal = proposal(input);
            proposal.withArray("edits").addObject().put("oldText", "def init(self, extend=\"\"):")
                    .put("newText", "def init(self):");
            return envelope(proposal, "stop");
        });
        var result = await(service.propose(repair.id(), input(repair, "regression-001")).id());
        assertEquals("PROPOSED", result.status());
        var candidate = awaitCandidate(repairService.propose(repair.id(),
                mapper.treeToValue(result.proposal(), RepairProposal.class)).id());
        assertEquals("CONTRACT_REGRESSION", candidate.errorCode());
    }

    @Test
    void exactSourceConsentAndConfigurationAreRequiredBeforeReservingBudget() throws Exception {
        var repair = begin();
        models.consent(repair.sourceId(), new UpdateModelConsentRequest(repair.baselineArtifactSha256(), null));
        var before = models.budget();
        assertCode("MODEL_SOURCE_NOT_AUTHORIZED", () -> service.propose(repair.id(), input(repair, "no-consent-001")));
        assertEquals(before, models.budget());
        models.consent(repair.sourceId(), new UpdateModelConsentRequest(null, repair.baselineArtifactSha256()));
        assertCode("MODEL_CONFIGURATION_CHANGED", () -> service.propose(repair.id(),
                new StartModelCallRequest(repair.faultSha256(), "0".repeat(64), "wrong-config-001")));
        assertEquals(before, models.budget());
        sources.addArtifact(repair.sourceId(), new AddArtifactRequest(repair.baselineArtifactSha256(), RepairIntegrationTest.BAD + "\n"));
        assertCode("REPAIR_INPUTS_CHANGED", () -> service.propose(repair.id(), input(repair, "changed-source-001")));
        assertEquals(before, models.budget());
    }

    @Test
    void invalidTruncatedAndExtraAuthorityResponsesConsumeCallsButNoCandidateRounds() throws Exception {
        var repair = begin();
        var before = models.budget();
        for (int i = 0; i < 3; i++) {
            int mode = i;
            responder.set(input -> {
                var value = proposal(input);
                if (mode == 0) value.put("deploymentAllowed", true);
                if (mode == 1) value.put("targetFile", "../PRIVATE_RESPONSE_ONLY.py");
                return envelope(value, mode == 2 ? "length" : "stop");
            });
            var result = await(service.propose(repair.id(), input(repair, "invalid-response-" + i)).id());
            assertEquals("FAILED", result.status());
            assertEquals(List.of("MODEL_RESPONSE_PROPOSAL_SCHEMA_INVALID",
                    "MODEL_RESPONSE_PROPOSAL_CONSTRAINT_INVALID", "MODEL_RESPONSE_OUTPUT_TRUNCATED").get(i),
                    result.errorCode());
            assertNull(result.proposal());
            assertEquals(30, result.usage().path("total_tokens").asInt());
            assertEquals("0.0000625", result.costEstimate().path("totalUsd").asText());
            assertEquals(result.errorCode(), result.responseDiagnostics().path("stage").asText());
            assertEquals(result.responseDiagnostics().toString(), calls.findById(result.id()).orElseThrow().getResponseDiagnosticsJson());
            assertFalse(result.responseDiagnostics().toString().contains("PRIVATE_RESPONSE_ONLY"));
            var request = mapper.readTree(calls.findById(result.id()).orElseThrow().getRequestJson());
            var data = mapper.readTree(request.path("messages").get(1).path("content").asText());
            assertEquals(i, data.path("previousModelFailures").size());
            if (i == 2) assertEquals("targetFile", data.path("previousModelFailures").get(1)
                    .path("responseDiagnostics").path("violations").get(0).path("field").asText());
            models.finish(result.id(), client.parse(envelope(proposal(request), "stop").getBytes(StandardCharsets.UTF_8)), null);
            assertEquals(result, models.get(result.id()));
        }
        assertEquals(before.usedCalls() + 3, models.budget().usedCalls());
        assertEquals(before.reservedOutputTokens() + 3L * client.connection().maxCompletionTokens(),
                models.budget().reservedOutputTokens());
        assertEquals(0, repairs.get(repair.id()).usedRounds());
        assertCode("MODEL_FAULT_CALL_LIMIT", () -> service.propose(repair.id(), input(repair, "fourth-response-001")));
    }

    @Test
    void repeatedModelProposalStopsFurtherGenerationAndReprobeDoesNotResetFault() throws Exception {
        var repair = begin();
        assertEquals("PROPOSED", await(service.propose(repair.id(), input(repair, "repeat-001")).id()).status());
        var repeated = await(service.propose(repair.id(), input(repair, "repeat-002")).id());
        assertEquals("REPEATED_MODEL_PROPOSAL", repeated.errorCode());
        assertEquals(30, repeated.usage().path("total_tokens").asInt());
        assertEquals("0.0000625", repeated.costEstimate().path("totalUsd").asText());
        assertCode("MODEL_REPEATED_PROPOSAL_STOP", () -> service.propose(repair.id(), input(repair, "repeat-003")));
        var probe = awaitProbe(probes.start(repair.sourceId()).id());
        assertEquals(repair.id(), repairs.create(repair.sourceId(), new StartRepairRequest(probe.id(),
                repair.baselineArtifactSha256())).id());
    }

    @Test
    void cancellationAndRevocationDiscardLateResponsesWithoutRefundOrAutomaticRetry() throws Exception {
        var repair = begin();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        responder.set(input -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return envelope(proposal(input), "stop");
        });
        int before = received.get();
        var started = service.propose(repair.id(), input(repair, "cancelled-001"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.cancel(started.id());
            assertEquals("CANCELLED", await(started.id()).status());
            assertNull(models.get(started.id()).proposal());
            assertEquals(started.id(), service.propose(repair.id(), input(repair, "cancelled-001")).id());
            assertEquals(before + 1, received.get());
        } finally { release.countDown(); }
        var another = begin();
        CountDownLatch enteredAgain = new CountDownLatch(1), releaseAgain = new CountDownLatch(1);
        responder.set(input -> {
            enteredAgain.countDown();
            try { releaseAgain.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return envelope(proposal(input), "stop");
        });
        var running = service.propose(another.id(), input(another, "revoked-001"));
        try {
            assertTrue(enteredAgain.await(5, TimeUnit.SECONDS));
            models.consent(another.sourceId(), new UpdateModelConsentRequest(another.baselineArtifactSha256(), null));
        } finally { releaseAgain.countDown(); }
        assertEquals("MODEL_SOURCE_NOT_AUTHORIZED", await(running.id()).errorCode());
        assertNull(models.get(running.id()).proposal());
        assertEquals(30, models.get(running.id()).usage().path("total_tokens").asInt());
        assertNotNull(models.get(running.id()).costEstimate());
    }

    @Test
    void restartPreservesReservationAndOldKeyCannotResendUnknownOutcome() throws Exception {
        var repair = begin();
        var input = input(repair, "restart-001");
        int before = received.get();
        var reserved = models.reserve(repair.id(), input);
        var budget = models.budget();
        models.recover();
        assertEquals("INTERRUPTED", models.get(reserved.getId()).status());
        assertEquals("MODEL_RESTARTED_OUTCOME_UNKNOWN", models.get(reserved.getId()).errorCode());
        assertEquals(budget.usedCalls(), models.budget().usedCalls());
        assertEquals(reserved.getId(), service.propose(repair.id(), input).id());
        assertEquals(before, received.get());
        assertFalse(models.start(reserved.getId()));
    }

    @Test
    void explicitLegacyHttpRecoveryKeepsOldReceiptAndEnhancedModeThenAutoSubmits() throws Exception {
        var repair = begin();
        String config = client.connection().configurationSha256();
        var old = models.reserve(repair.id(), new StartModelCallRequest(
                repair.faultSha256(), config, "legacy_http_failed_001", true, true));
        assertTrue(models.start(old.getId()));
        models.finish(old.getId(), null, "MODEL_HTTP_ERROR");
        var row = calls.findById(old.getId()).orElseThrow();
        row.setResponseDiagnosticsJson(null);
        calls.saveAndFlush(row);
        var oldReceipt = models.get(old.getId());
        var before = models.budget();
        int networkBefore = received.get();
        var request = new StartModelCallRequest(repair.faultSha256(), config, "legacy_http_resume_001", true);
        assertTrue(automation.start(repair.id(), request).enhancedDiagnostics());
        var resumed = models.list(repair.id()).getLast();
        var complete = awaitSubmission(resumed.id());
        awaitCandidate(complete.candidateId());
        assertNotEquals(old.getId(), complete.id());
        assertEquals(oldReceipt, models.get(old.getId()));
        assertTrue(complete.enhancedDiagnostics());
        assertEquals("AUTO", complete.submissionMethod());
        assertEquals(2, models.list(repair.id()).size());
        assertEquals(1, repairs.candidates(repair.id()).size());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals(before.usedCalls() + 1, models.budget().usedCalls());
        assertEquals(networkBefore + 1, received.get());
        automaticStore.finish(repair.id(), "CANCELLED", "TEST_FINISHED");
    }

    @Test
    void transientResponseWaitsThenRetriesSameCallAndSubmitsOnlyOneCandidate() throws Exception {
        var repair = begin();
        int before = received.get();
        status.set(503);
        retryAfter.set("3600");
        responder.set(input -> "{\"error\":{\"type\":\"server_error\",\"message\":\"PRIVATE_RESPONSE_ONLY\"}}");
        var call = service.propose(repair.id(), autoInput(repair, "transient-retry-001"));
        var waiting = awaitRetry(call.id(), 1);
        var row = calls.findById(call.id()).orElseThrow();
        String originalRequest = row.getRequestJson();
        var budget = models.budget();
        assertEquals(call.id(), budget.activeCallId());
        assertEquals("AWAITING_MODEL", waiting.submissionStatus());
        assertTrue(Instant.parse(waiting.responseDiagnostics().path("nextRetryAt").asText())
                .isAfter(Instant.now().plusSeconds(3500)));
        assertFalse(models.start(call.id()));
        assertEquals(call.id(), service.propose(repair.id(), autoInput(repair, "transient-retry-001")).id());
        assertEquals(before + 1, received.get());
        automaticStore.start(repair.id(), client.connection().configurationSha256(), false);
        automation.poll();
        assertEquals("ACTIVE", automaticStore.get(repair.id()).status());
        assertFalse(waiting.responseDiagnostics().toString().contains("PRIVATE_RESPONSE_ONLY"));
        status.set(200);
        responder.set(input -> envelope(proposal(input), "stop"));
        forceRetryDue(call.id());
        var complete = awaitSubmission(call.id());
        assertEquals("PROPOSED", complete.status());
        assertEquals(2, complete.responseDiagnostics().path("transportAttempts").size());
        assertEquals(before + 2, received.get());
        assertEquals(originalRequest, calls.findById(call.id()).orElseThrow().getRequestJson());
        assertEquals(call.requestSha256(), complete.requestSha256());
        assertEquals(1, models.list(repair.id()).size());
        assertEquals(1, repairs.candidates(repair.id()).size());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        assertEquals(budget.usedCalls(), models.budget().usedCalls());
        assertEquals(budget.reservedInputBytes(), models.budget().reservedInputBytes());
        assertEquals(budget.reservedCostUsd(), models.budget().reservedCostUsd());
        automaticStore.finish(repair.id(), "CANCELLED", "TEST_FINISHED");
        awaitCandidate(complete.candidateId());
    }

    @Test
    void transientRetriesAreBoundedAndKeepEveryFailureReceipt() throws Exception {
        var repair = begin();
        int before = received.get();
        status.set(429);
        retryAfter.set("3600");
        responder.set(input -> "{\"error\":{\"code\":\"rate_limit_exceeded\"}}");
        String id = service.propose(repair.id(), input(repair, "transient-exhaust-001")).id();
        for (int attempt = 1; attempt <= 2; attempt++) {
            awaitRetry(id, attempt);
            forceRetryDue(id);
        }
        var result = await(id);
        assertEquals("FAILED", result.status());
        assertEquals("MODEL_RATE_LIMITED", result.errorCode());
        assertEquals("MODEL_TRANSPORT_RETRIES_EXHAUSTED", result.responseDiagnostics().path("pauseReason").asText());
        assertEquals(3, result.responseDiagnostics().path("transportAttempts").size());
        assertEquals(before + 3, received.get());
        service.retryDue();
        assertNull(models.budget().activeCallId());
        assertFalse(result.responseDiagnostics().path("retryScheduled").asBoolean());
        assertEquals(0, repairs.get(repair.id()).usedRounds());
    }

    @Test
    void exhaustedTransientCallsKeepEveryLedgerButAllowExplicitRecoveryWithEnhancedMode() throws Exception {
        var repair = begin();
        var before = models.budget();
        int networkBefore = received.get();
        status.set(429);
        retryAfter.set("3600");
        responder.set(input -> "{\"error\":{\"code\":\"rate_limit_exceeded\"}}");
        var failed = new ArrayList<ModelCallSummary>();
        for (int i = 0; i < 3; i++) {
            String id = service.propose(repair.id(), input(repair, "fault_transient_" + i)).id();
            for (int attempt = 1; attempt <= 2; attempt++) {
                awaitRetry(id, attempt);
                forceRetryDue(id);
            }
            var result = await(id);
            assertEquals("MODEL_TRANSPORT_RETRIES_EXHAUSTED", result.responseDiagnostics().path("pauseReason").asText());
            assertEquals("MODEL_RATE_LIMITED", result.errorCode());
            failed.add(result);
        }
        assertEquals(before.usedCalls() + 3, models.budget().usedCalls());
        assertEquals(0, repairs.get(repair.id()).usedRounds());
        status.set(200);
        retryAfter.set(null);
        responder.set(input -> envelope(proposal(input), "stop"));
        var request = new StartModelCallRequest(repair.faultSha256(), client.connection().configurationSha256(),
                "fault_transient_explicit_recovery", true, true);
        assertTrue(automation.start(repair.id(), request).enhancedDiagnostics());
        var fourth = models.list(repair.id()).getLast();
        var complete = awaitSubmission(fourth.id());
        assertEquals("SUBMITTED", complete.submissionStatus(),
                () -> complete.submissionErrorCode() + " repair=" + repairs.get(repair.id()).status());
        awaitCandidate(complete.candidateId());
        assertEquals("PROPOSED", complete.status());
        assertTrue(complete.enhancedDiagnostics());
        assertEquals("AUTO", complete.submissionMethod());
        assertEquals(4, complete.attemptNumber());
        assertEquals(complete.id(), models.reserve(repair.id(), request).getId());
        assertEquals(failed, models.list(repair.id()).subList(0, 3));
        assertEquals(before.usedCalls() + 4, models.budget().usedCalls());
        assertEquals(networkBefore + 10, received.get());
        assertEquals(1, repairs.candidates(repair.id()).size());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        automaticStore.finish(repair.id(), "CANCELLED", "TEST_FINISHED");
    }

    @Test
    void usageUnknownAndLegacyFailuresStillConsumeFaultCallSlots() throws Exception {
        var repair = begin();
        var before = models.budget();
        for (int i = 0; i < 3; i++) {
            var call = models.reserve(repair.id(), input(repair, "fault_occupied_" + i));
            assertTrue(models.start(call.getId()));
            ModelReply reply = switch (i) {
                case 0 -> new ModelReply(null, null, "MODEL_HTTP_ERROR", null);
                case 1 -> ModelResponsePolicy.transport(mapper, new HttpTimeoutException("fixture"));
                default -> ModelResponsePolicy.http(mapper, 503, HttpHeaders.of(Map.of(), (key, value) -> true),
                        ("{\"error\":{\"type\":\"server_error\"},\"usage\":{\"prompt_tokens\":10,"
                                + "\"completion_tokens\":20,\"total_tokens\":30}}").getBytes(StandardCharsets.UTF_8), Instant.now());
            };
            models.finish(call.getId(), reply, reply.errorCode());
            assertEquals("FAILED", models.get(call.getId()).status());
        }
        var request = new StartModelCallRequest(repair.faultSha256(), client.connection().configurationSha256(),
                "fault_occupied_fourth", true, true);
        assertCode("MODEL_FAULT_CALL_LIMIT", () -> models.reserve(repair.id(), request));
        assertCode("MODEL_FAULT_CALL_LIMIT", () -> service.propose(repair.id(), request));
        assertEquals(before.usedCalls() + 3, models.budget().usedCalls());
        assertEquals(3, models.list(repair.id()).size());
        assertEquals(0, repairs.get(repair.id()).usedRounds());
    }

    @Test
    void freshReverseMaterialResumesSameRepairAndAllowsOneEnhancedCallWithoutRefundingHistory() throws Exception {
        var repair = begin();
        var before = models.budget();
        var proposed = savedProposal(repair, "material_original", false);
        var candidate = submissions.submit(proposed.id(), submission(proposed));
        awaitCandidate(candidate.id());
        var rejected = candidateRows.findById(candidate.id()).orElseThrow();
        rejected.setStatus("REJECTED");
        rejected.setErrorCode("RUNTIME_TARGET_UNRESOLVED");
        candidateRows.saveAndFlush(rejected);
        var row = repairRows.findById(repair.id()).orElseThrow();
        row.setStatus("AWAITING_PROPOSAL");
        repairRows.saveAndFlush(row);
        for (int index = 0; index < 2; index++) {
            var call = models.reserve(repair.id(), input(repair, "material_failure_" + index));
            assertTrue(models.start(call.getId()));
            models.finish(call.getId(), null, "MODEL_HTTP_ERROR");
        }
        var history = models.list(repair.id());
        row = repairRows.findById(repair.id()).orElseThrow();
        row.setStatus("STOPPED");
        row.setStopCode("REPEATED_FAILURE");
        repairRows.saveAndFlush(row);
        assertCode("REPAIR_NOT_RESUMABLE", () -> repairs.resume(repair.id(), repair.faultSha256()));
        String code = "define(['services/http'], function(http) { return http; });";
        var report = mapper.createObjectNode().put("mode", "reverse_analysis_v1")
                .put("authority", "host_reverse_browser_observed").put("repairId", repair.id())
                .put("runId", repair.baselineRunId()).put("artifactSha256", repair.baselineArtifactSha256())
                .put("taskId", UUID.randomUUID().toString()).put("cleanupConfirmed", true);
        report.putArray("scripts").addObject().put("sourceSha256", Hashes.sha256(code))
                .putObject("excerpt").put("complete", true).put("text", code);
        repairs.attachReverseAnalysis(repair.id(), repair.faultSha256(), report);
        assertTrue(repairs.hasNewReverseEvidence(repair.id()));
        assertEquals("AWAITING_PROPOSAL", repairs.resume(repair.id(), repair.faultSha256()).status());
        assertCode("MODEL_FAULT_CALL_LIMIT", () -> models.reserve(repair.id(), input(repair, "material_ordinary")));
        var enhanced = new StartModelCallRequest(repair.faultSha256(), client.connection().configurationSha256(),
                "material_enhanced", true, true);
        var extra = models.reserve(repair.id(), enhanced);
        assertEquals(4, extra.getAttemptNumber());
        assertTrue(ReverseAnalysis.modelReport(mapper, extra.getRequestJson()).path("taskId").equals(report.path("taskId")));
        assertEquals(extra.getId(), models.reserve(repair.id(), enhanced).getId());
        assertTrue(models.start(extra.getId()));
        models.finish(extra.getId(), null, "MODEL_HTTP_ERROR");
        assertCode("MODEL_FAULT_CALL_LIMIT", () -> models.reserve(repair.id(), new StartModelCallRequest(
                repair.faultSha256(), enhanced.expectedConfigurationSha256(), "material_again", true, true)));
        assertEquals(history, models.list(repair.id()).subList(0, 3));
        assertEquals(before.usedCalls() + 4, models.budget().usedCalls());
        assertEquals(1, repairs.get(repair.id()).usedRounds());
        row = repairRows.findById(repair.id()).orElseThrow();
        row.setStatus("STOPPED");
        row.setStopCode("REPEATED_FAILURE");
        row.setUsedRounds(3);
        repairRows.saveAndFlush(row);
        assertFalse(repairs.hasNewReverseEvidence(repair.id()));
        assertCode("REPAIR_NOT_RESUMABLE", () -> repairs.resume(repair.id(), repair.faultSha256()));
    }

    @Test
    void waitingRetrySurvivesRecoveryButCancellationCannotBeRevived() throws Exception {
        var repair = begin();
        int before = received.get();
        status.set(503);
        retryAfter.set("3600");
        responder.set(input -> "busy");
        String id = service.propose(repair.id(), input(repair, "transient-recover-001")).id();
        var waiting = awaitRetry(id, 1);
        models.recover();
        assertEquals("QUEUED", models.get(id).status());
        assertEquals(id, models.budget().activeCallId());
        assertEquals(waiting.responseDiagnostics(), models.get(id).responseDiagnostics());
        service.cancel(id);
        models.recover();
        service.retryDue();
        assertEquals("CANCELLED", models.get(id).status());
        assertFalse(models.get(id).responseDiagnostics().path("retryScheduled").asBoolean());
        assertNull(models.budget().activeCallId());
        assertFalse(models.start(id));
        assertEquals(before + 1, received.get());
    }

    @Test
    void revokingSourceConsentWhileWaitingStopsBeforeTheNextTransmission() throws Exception {
        var repair = begin();
        int before = received.get();
        status.set(503);
        retryAfter.set("3600");
        responder.set(input -> "busy");
        String id = service.propose(repair.id(), input(repair, "transient-revoked-001")).id();
        awaitRetry(id, 1);
        models.consent(repair.sourceId(), new UpdateModelConsentRequest(repair.baselineArtifactSha256(), null));
        forceRetryDue(id);
        var result = await(id);
        assertEquals("MODEL_SOURCE_NOT_AUTHORIZED", result.errorCode());
        assertEquals(1, result.responseDiagnostics().path("transportAttempts").size());
        assertEquals(before + 1, received.get());
        assertNull(models.budget().activeCallId());
    }

    @Test
    void quotaAndRelay200PermanentErrorsStopWithoutAutomaticRetries() throws Exception {
        for (int httpStatus : List.of(429, 200)) {
            var repair = begin();
            int before = received.get();
            status.set(httpStatus);
            responder.set(input -> "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"PRIVATE_RESPONSE_ONLY\"}}");
            var result = await(service.propose(repair.id(), input(repair, "quota-pause-001")).id());
            assertEquals("MODEL_QUOTA_EXHAUSTED", result.errorCode());
            assertEquals("pause", result.responseDiagnostics().path("action").asText());
            assertEquals(httpStatus, result.responseDiagnostics().path("httpStatus").asInt());
            service.retryDue();
            assertEquals(before + 1, received.get());
            assertFalse(result.responseDiagnostics().toString().contains("PRIVATE_RESPONSE_ONLY"));
        }
    }

    private ModelCallSummary awaitRetry(String id, int attempts) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < until) {
            var call = models.get(id);
            if ("QUEUED".equals(call.status()) && call.responseDiagnostics() != null
                    && call.responseDiagnostics().path("retryScheduled").asBoolean()
                    && call.responseDiagnostics().path("transportAttempts").size() == attempts) return call;
            Thread.sleep(20);
        }
        throw new AssertionError("Model retry was not scheduled: " + models.get(id));
    }

    private void forceRetryDue(String id) throws Exception {
        var row = calls.findById(id).orElseThrow();
        var diagnostic = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(row.getResponseDiagnosticsJson());
        diagnostic.put("nextRetryAt", Instant.now().minusSeconds(1).toString());
        row.setResponseDiagnosticsJson(diagnostic.toString());
        calls.saveAndFlush(row);
        service.retryDue();
    }

    @Test
    void concurrentKeysReserveOnlyOneGlobalCallAndBudgetSurvivesDailyLimit() throws Exception {
        var repair = begin();
        CountDownLatch start = new CountDownLatch(1);
        List<Object> values = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> reserveAfter(start, repair, "race-key-001"));
            var b = pool.submit(() -> reserveAfter(start, repair, "race-key-002"));
            start.countDown();
            values.add(a.get(5, TimeUnit.SECONDS));
            values.add(b.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, values.stream().filter(v -> v instanceof cn.har01d.atmaintenance.entity.ModelCall).count());
        assertTrue(values.contains("MODEL_BUSY"));
        var call = models.list(repair.id()).getFirst();
        service.cancel(call.id());
        var row = budgets.findById(1).orElseThrow();
        int previousCalls = row.getUsedCalls();
        row.setUsedCalls(client.connection().dailyCallLimit());
        budgets.saveAndFlush(row);
        try {
            assertCode("MODEL_DAILY_BUDGET", () -> service.propose(repair.id(), input(repair, "budget-key-003")));
        } finally {
            row = budgets.findById(1).orElseThrow();
            row.setUsedCalls(previousCalls);
            budgets.saveAndFlush(row);
        }
    }

    @Test
    void inputAndOutputDailyReservationsAndBackwardClockBlockBeforeCreatingCalls() throws Exception {
        var repair = begin();
        var original = models.budget();
        var row = budgets.findById(1).orElseThrow();
        row.setReservedInputBytes(client.connection().dailyInputBytes());
        budgets.saveAndFlush(row);
        try {
            assertCode("MODEL_DAILY_BUDGET", () -> service.propose(repair.id(), input(repair, "input-budget-001")));
            row = budgets.findById(1).orElseThrow();
            row.setReservedInputBytes(original.reservedInputBytes());
            row.setReservedOutputTokens(client.connection().dailyOutputTokens());
            budgets.saveAndFlush(row);
            assertCode("MODEL_DAILY_BUDGET", () -> service.propose(repair.id(), input(repair, "output-budget-001")));
            row = budgets.findById(1).orElseThrow();
            row.setReservedOutputTokens(original.reservedOutputTokens());
            row.setBudgetDay(LocalDate.now(ZoneOffset.UTC).plusDays(1));
            budgets.saveAndFlush(row);
            assertCode("MODEL_CLOCK_ROLLBACK", () -> service.propose(repair.id(), input(repair, "clock-budget-001")));
            assertTrue(models.list(repair.id()).isEmpty());
        } finally {
            row = budgets.findById(1).orElseThrow();
            row.setReservedInputBytes(original.reservedInputBytes());
            row.setReservedOutputTokens(original.reservedOutputTokens());
            row.setBudgetDay(original.budgetDay());
            budgets.saveAndFlush(row);
        }
        assertEquals(original, models.budget());
    }

    @Test
    void transportRefusesRedirectsOversizeNonJsonAndUnknownTimeoutWithoutRetry() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            var repair = begin();
            status.set(mode == 0 ? 302 : 200);
            contentType.set(mode == 2 ? "text/html" : "application/json");
            int selectedMode = mode;
            responder.set(input -> {
                if (selectedMode == 1) return "x".repeat(70000);
                if (selectedMode == 3) {
                    try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return envelope(proposal(input), "stop");
            });
            int before = received.get();
            var result = await(service.propose(repair.id(), input(repair, "transport-001")).id());
            assertEquals(List.of("MODEL_REDIRECT_REFUSED", "MODEL_RESPONSE_TOO_LARGE",
                    "MODEL_RESPONSE_TYPE", "MODEL_OUTCOME_UNKNOWN").get(mode), result.errorCode());
            assertEquals(before + 1, received.get());
            assertNull(result.proposal());
        }
    }

    @Test
    void modelHttpEndpointsUseAdminAndCsrfAndDoNotExposeEndpointOrRequestBody() throws Exception {
        String base = "http://127.0.0.1:" + port + "/api/plugin-maintenance";
        String auth = "Basic " + Base64.getEncoder().encodeToString("admin:integration-test-only".getBytes(StandardCharsets.UTF_8));
        try (var http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(base + "/model/connection")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var repair = begin();
            String path = base + "/repairs/" + repair.id() + "/model-calls";
            assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(path)).header("Authorization", auth)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var csrf = mapper.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/csrf"))
                    .header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            var response = http.send(HttpRequest.newBuilder(URI.create(path)).header("Authorization", auth)
                    .header("Content-Type", "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input(repair, "http-key-001")))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, response.statusCode(), response.body());
            var result = await(mapper.readTree(response.body()).path("id").asText());
            String encoded = mapper.writeValueAsString(result);
            assertFalse(encoded.contains("requestJson"));
            assertFalse(encoded.contains("not-forwarded-secret"));
            var config = http.send(HttpRequest.newBuilder(URI.create(base + "/model/connection"))
                    .header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            assertFalse(config.contains("127.0.0.1"));
            String submitPath = base + "/model-calls/" + result.id() + "/submit";
            assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(submitPath)).header("Authorization", auth)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var submitted = http.send(HttpRequest.newBuilder(URI.create(submitPath)).header("Authorization", auth)
                    .header("Content-Type", "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(submission(result)))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, submitted.statusCode(), submitted.body());
            assertEquals(result.id(), mapper.readTree(submitted.body()).path("modelCallId").asText());
            awaitCandidate(mapper.readTree(submitted.body()).path("id").asText());
        }
    }

    private Object reserveAfter(CountDownLatch start, RepairSummary repair, String key) throws Exception {
        start.await();
        try { return models.reserve(repair.id(), input(repair, key)); }
        catch (ResponseStatusException e) { return e.getReason(); }
    }
    @Test
    void explicitNewStructureAddsOneRoundAndOneCallWithoutResettingFrozenInputsOrLedgers() throws Exception {
        var repair = stoppedJsonRepair(false);
        repairs.updateRoundLimit(repair.id(), new UpdateRepairRoundLimitRequest(repair.faultSha256(), 3, 3));
        var candidate = repairs.candidates(repair.id()).getLast();
        var frozen = repairRows.findById(repair.id()).orElseThrow().getFrozenJson();
        var history = models.list(repair.id());
        var budget = models.budget();
        var offer = repairs.evidenceContinuation(repair.id());
        assertTrue(offer.path("available").asBoolean(), offer::toString);
        var input = new ContinueMaintenanceEvidenceRequest(repair.baselineArtifactSha256(),
                client.connection().configurationSha256(), repair.faultSha256(), candidate.sha256(),
                offer.path("evidenceSha256").asText(), "new_structure_grant_001");
        assertCode("REPAIR_EVIDENCE_CHANGED", () -> repairs.continueWithEvidence(repair.id(),
                new ContinueMaintenanceEvidenceRequest(input.expectedArtifactSha256(), input.expectedConfigurationSha256(),
                        input.expectedFaultSha256(), input.expectedCandidateSha256(), "0".repeat(64), input.requestKey())));
        var resumed = repairs.continueWithEvidence(repair.id(), input);
        assertEquals(3, resumed.usedRounds());
        assertEquals(4, resumed.maxRounds());
        assertEquals("AWAITING_PROPOSAL", resumed.status());
        assertEquals(3, resumed.frozen().path("policy").path("maxRounds").asInt());
        assertEquals(1, resumed.frozen().path("evidenceContinuations").size());
        assertEquals(resumed, repairs.continueWithEvidence(repair.id(), input));
        assertEquals(history, models.list(repair.id()));
        assertEquals(budget, models.budget());
        var request = new StartModelCallRequest(repair.faultSha256(), input.expectedConfigurationSha256(),
                "new_structure_call_001", true, true);
        var call = models.reserve(repair.id(), request);
        assertEquals(call.getId(), models.reserve(repair.id(), request).getId());
        var data = RepairEvidenceMaterial.requestData(mapper, call.getRequestJson());
        assertEquals(input.expectedEvidenceSha256(), data.path("evidenceContinuation").path("evidenceSha256").asText());
        assertEquals(candidate.sha256(), data.path("previousCandidates").get(0).path("candidateSha256").asText());
        assertEquals(1, data.path("enhancedDiagnostics").path("jsonResponseEvidence").path("addressFields").size());
        assertTrue(models.start(call.getId()));
        models.finish(call.getId(), null, "MODEL_HTTP_ERROR");
        assertCode("MODEL_FAULT_CALL_LIMIT", () -> models.reserve(repair.id(), new StartModelCallRequest(
                repair.faultSha256(), request.expectedConfigurationSha256(), "new_structure_call_again", true, true)));
        assertEquals(history, models.list(repair.id()).subList(0, 3));
        assertEquals(budget.usedCalls() + 1, models.budget().usedCalls());
        var proposal = new RepairProposal(1, repair.baselineArtifactSha256(), "source.py", "fixture", "fixture",
                List.of("R01"), List.of(new RepairEdit(RepairIntegrationTest.OVERRIDE, "")), null);
        var fourth = repairs.reserve(repair.id(), proposal);
        assertEquals(4, fourth.getRoundNumber());
        assertEquals(4, repairs.get(repair.id()).usedRounds());
        assertEquals(candidate, repairs.candidates(repair.id()).getFirst());
        assertEquals(frozen, repairRows.findById(repair.id()).orElseThrow().getFrozenJson());
        assertEquals(4, repairs.continueWithEvidence(repair.id(), input).maxRounds());
        repairs.cancel(repair.id());
    }

    @Test
    void structureAlreadySentToModelDoesNotGrantAnotherRound() throws Exception {
        var repair = stoppedJsonRepair(true);
        var before = models.budget();
        var offer = repairs.evidenceContinuation(repair.id());
        assertFalse(offer.path("available").asBoolean());
        assertEquals("RESPONSE_STRUCTURE_ALREADY_USED", offer.path("reason").asText());
        assertCode("RESPONSE_STRUCTURE_ALREADY_USED", () -> repairs.continueWithEvidence(repair.id(),
                new ContinueMaintenanceEvidenceRequest(repair.baselineArtifactSha256(),
                        client.connection().configurationSha256(), repair.faultSha256(),
                        repairs.candidates(repair.id()).getLast().sha256(), offer.path("evidenceSha256").asText(),
                        "already_seen_grant_001")));
        assertEquals(3, repairs.get(repair.id()).maxRounds());
        assertEquals(3, repairs.get(repair.id()).usedRounds());
        assertEquals(before, models.budget());
    }

    @Test
    void explicitPlayerEvidenceAddsOnlyOneRoundAndPreservesOriginalHistory() throws Exception {
        var repair = stoppedJsonRepair(false);
        var candidate = repairs.candidates(repair.id()).getLast();
        var candidateRow = candidateRows.findById(candidate.id()).orElseThrow();
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) candidate.result().deepCopy();
        var runtime = result.withObject("runtimeEvidence");
        runtime.remove("resourceEvidence");
        var loader = runtime.withObject("loaderValidation");
        loader.putArray("calls").addObject().put("id", 2).put("method", "detail").put("ok", true);
        loader.withArray("calls").addObject().put("id", 3).put("method", "play").put("ok", true);
        ((com.fasterxml.jackson.databind.node.ObjectNode) loader.path("nodes").get(0))
                .set("playerContract", RepairEvidenceMaterialTest.playerContract(mapper));
        result.put("repairSuccessful", false).putObject("runtimeAcceptance").put("status", "required_incomplete")
                .putArray("nodes").addObject().put("id", "R01").put("status", "blocked").put("reason", "PLAYER_URL_EMPTY");
        candidateRow.setStatus("RUNTIME_OBSERVED"); candidateRow.setErrorCode(null); candidateRow.setResultJson(result.toString());
        candidateRows.saveAndFlush(candidateRow);
        var before = models.list(repair.id());
        var budget = models.budget();
        String frozen = repairRows.findById(repair.id()).orElseThrow().getFrozenJson();
        var offer = repairs.evidenceContinuation(repair.id());
        assertTrue(offer.path("available").asBoolean(), offer::toString);
        assertEquals("observed_player_output_contract", offer.path("materialKind").asText());
        var input = new ContinueMaintenanceEvidenceRequest(repair.baselineArtifactSha256(),
                client.connection().configurationSha256(), repair.faultSha256(), candidate.sha256(),
                offer.path("evidenceSha256").asText(), "player_evidence_grant_001");
        var continued = repairs.continueWithEvidence(repair.id(), input);
        assertEquals(3, continued.usedRounds()); assertEquals(4, continued.maxRounds());
        assertEquals(continued, repairs.continueWithEvidence(repair.id(), input));
        assertEquals(before, models.list(repair.id())); assertEquals(budget, models.budget());
        assertEquals(frozen, repairRows.findById(repair.id()).orElseThrow().getFrozenJson());
        var request = new StartModelCallRequest(repair.faultSha256(), input.expectedConfigurationSha256(),
                "player_evidence_call_001", true, true);
        var call = models.reserve(repair.id(), request);
        var data = RepairEvidenceMaterial.requestData(mapper, call.getRequestJson());
        assertEquals("R01", data.path("evidenceContinuation").path("targetNode").asText());
        assertEquals("opaque", data.path("evidenceContinuation").path("playerContract").path("returnedUrlShape").asText());
        assertTrue(models.start(call.getId())); models.finish(call.getId(), null, "MODEL_HTTP_ERROR");
        assertCode("MODEL_FAULT_CALL_LIMIT", () -> models.reserve(repair.id(), new StartModelCallRequest(
                repair.faultSha256(), request.expectedConfigurationSha256(), "player_evidence_call_002", true, true)));
        assertEquals(before, models.list(repair.id()).subList(0, 3));
        assertEquals(budget.usedCalls() + 1, models.budget().usedCalls());
    }

    private RepairSummary stoppedJsonRepair(boolean alreadySeen) throws Exception {
        var base = begin();
        var frozen = (com.fasterxml.jackson.databind.node.ObjectNode) base.frozen().deepCopy();
        frozen.withObject("policy").put("scope", "managed_loader_candidate").putArray("targetNodes").add("R01");
        frozen.withObject("baseline").put("runtimeSha256", "c".repeat(64))
                .withObject("profile").putArray("requiredNodes").add("R01");
        var row = new RepairSession();
        row.setSourceId(base.sourceId()); row.setBaselineRunId(base.baselineRunId());
        row.setBaselineArtifactSha256(base.baselineArtifactSha256());
        row.setFaultSha256(Hashes.sha256(UUID.randomUUID().toString())); row.setFrozenJson(frozen.toString());
        var temporary = new RepairSummary(row.getId(), base.sourceId(), base.baselineRunId(), base.baselineArtifactSha256(),
                row.getFaultSha256(), "AWAITING_PROPOSAL", null, 0, 3, null, Instant.now(), frozen, "current", List.of());
        var baselineEvidence = RepairEvidenceMaterialTest.runtime(mapper, temporary, base.baselineArtifactSha256());
        if (!alreadySeen) baselineEvidence.remove("resourceEvidence");
        frozen.withObject("baseline").withObject("result").set("managedSourceEvidence", baselineEvidence);
        row.setFrozenJson(frozen.toString());
        repairRows.saveAndFlush(row);
        var repair = repairs.get(row.getId());
        for (int index = 0; index < 3; index++) {
            var call = models.reserve(repair.id(), input(repair, "json_history_" + index));
            assertTrue(models.start(call.getId()));
            models.finish(call.getId(), null, "MODEL_HTTP_ERROR");
        }
        var candidate = new RepairCandidate();
        candidate.setRepairId(repair.id()); candidate.setRoundNumber(3);
        candidate.setBody(RepairIntegrationTest.BAD + "\n# candidate fixture\n");
        candidate.setSha256(Hashes.sha256(candidate.getBody())); candidate.setProposalJson("{}");
        candidate.setProposalSha256(Hashes.sha256("{}")); candidate.setStatus("REJECTED");
        candidate.setErrorCode("RUNTIME_TARGET_UNRESOLVED"); candidate.setFinishedAt(Instant.now());
        candidate.setResultJson(RepairEvidenceMaterialTest.result(mapper, repair, candidate.getSha256()).toString());
        candidateRows.saveAndFlush(candidate);
        row = repairRows.findById(repair.id()).orElseThrow();
        row.setUsedRounds(3); row.setStatus("STOPPED"); row.setStopCode("REPEATED_FAILURE");
        repairRows.saveAndFlush(row);
        return repairs.get(repair.id());
    }

    private RepairSummary begin() throws Exception {
        var source = sources.create(new CreateSourceRequest("模型坏例", null, "repair.py",
                "https://example.invalid/?token=not-forwarded-secret", RepairIntegrationTest.BAD));
        var probe = awaitProbe(probes.start(source.id()).id());
        assertEquals("COMPLETED", probe.status(),
                () -> "Baseline probe " + probe.id() + ": " + probe.status() + "/" + probe.errorCode());
        var repair = repairs.create(source.id(), new StartRepairRequest(probe.id(), source.artifactSha256()));
        models.consent(source.id(), new UpdateModelConsentRequest(null, source.artifactSha256()));
        return repair;
    }
    private RepairSummary beginHome() throws Exception {
        var source = sources.create(new CreateSourceRequest("模型首页夹具", null, "home.py", null,
                RepairIntegrationTest.BAD.replace(RepairIntegrationTest.OVERRIDE, "")));
        var profile = profiles.get(source.id());
        profiles.update(source.id(), new UpdateProfileRequest(profile.sha256(), "t4", "unverified",
                List.of("S01", "S02", "S03", "C01", "C02"), List.of(), null, null));
        var inspected = awaitProbe(probes.start(source.id()).id());
        assertEquals("COMPLETED", inspected.status());
        var run = probeStore.create(source.id(), inspected.inspectorSha256(), inspected.knowledgeSha256(), "managed_source");
        assertTrue(probeStore.start(run.getId()));
        var baseline = probeStore.get(run.getId());
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) inspected.result().deepCopy();
        report.set("managedSourceEvidence", homeEvidence(baseline, source.artifactSha256(), false));
        probeStore.finish(run.getId(), "COMPLETED", report, inspected.context(), null);
        var repair = repairs.create(source.id(), new StartRepairRequest(run.getId(), source.artifactSha256()));
        models.consent(source.id(), new UpdateModelConsentRequest(null, source.artifactSha256()));
        return repair;
    }
    private com.fasterxml.jackson.databind.node.ObjectNode homeEvidence(ProbeSummary baseline, String sha, boolean recovered) {
        var evidence = mapper.createObjectNode().put("authority", "managed_source_host_observed")
                .put("executionKind", "t4_business_sample").put("inputSourceSha256", sha)
                .put("bundleSha256", baseline.runtimeSha256());
        evidence.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
        evidence.putArray("deniedCodes");
        var observed = evidence.putObject("untrustedRuntimeSummary")
                .put("status", recovered ? "detail_returned" : "home_empty");
        for (String field : List.of("homeClasses", "homeItems", "homeCovers", "categoryItems", "categoryCovers",
                "page2Items", "detailItems", "detailCovers", "playGroups", "playEpisodes"))
            observed.put(field, recovered ? 20 : 0);
        return evidence;
    }
    private static com.fasterxml.jackson.databind.node.ObjectNode homeProposal(JsonNode request) {
        try {
            var data = JSON.readTree(request.path("messages").get(1).path("content").asText());
            assertEquals("managed_home_candidate", data.path("repairScope").asText());
            assertEquals("T4_HOME_EMPTY", data.path("failureCodes").get(0).asText());
            assertEquals(JSON.readTree("[\"C01\",\"C02\"]"), data.path("validationTargets"));
            var result = JSON.createObjectNode().put("schemaVersion", 1)
                    .put("expectedBaselineSha256", data.path("expectedBaselineSha256").asText())
                    .put("targetFile", "source.py").put("diagnosis", "Repair home parser fixture")
                    .put("expectedEffect", "Requires explicit isolated runtime validation");
            result.putArray("validationTargets").add("C01").add("C02");
            result.putArray("edits").addObject().put("oldText", "return {'class': []}")
                    .put("newText", "return {'class': [{'type_id': 'one', 'type_name': 'One'}]}");
            return result;
        } catch (IOException e) { throw new AssertionError(e); }
    }
    private StartModelCallRequest input(RepairSummary repair, String key) {
        return new StartModelCallRequest(repair.faultSha256(), client.connection().configurationSha256(), key);
    }
    private StartModelCallRequest autoInput(RepairSummary repair, String key) {
        return new StartModelCallRequest(repair.faultSha256(), client.connection().configurationSha256(), key, true);
    }
    private SubmitModelProposalRequest submission(ModelCallSummary call) {
        return new SubmitModelProposalRequest(call.proposalSha256());
    }
    private RepairProposal repairProposal(RepairSummary repair, List<RepairEdit> edits) {
        return new RepairProposal(1, repair.baselineArtifactSha256(), "source.py", "Fixture diagnosis",
                "Static only", List.of("S03"), edits, null);
    }
    private ModelCallSummary savedProposal(RepairSummary repair, String key, boolean automatic) {
        var input = automatic ? autoInput(repair, key) : input(repair, key);
        var call = models.reserve(repair.id(), input);
        assertTrue(models.start(call.getId()));
        models.finish(call.getId(), new ModelReply(repairProposal(repair,
                List.of(new RepairEdit(RepairIntegrationTest.OVERRIDE, ""))), null, null, null), null);
        return models.get(call.getId());
    }
    private ModelCallSummary awaitSubmission(String id) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < until) {
            var call = models.get(id);
            if ("SUBMITTED".equals(call.submissionStatus()) || "FAILED".equals(call.submissionStatus())) return call;
            if (!Set.of("QUEUED", "RUNNING", "CANCELLING", "PROPOSED").contains(call.status()))
                throw new AssertionError("Model failed before submission: " + call.errorCode());
            Thread.sleep(20);
        }
        throw new AssertionError("Model submission did not finish");
    }
    private ModelCallSummary await(String id) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < until) {
            var value = models.get(id);
            if (!Set.of("QUEUED", "RUNNING", "CANCELLING").contains(value.status())) return value;
            Thread.sleep(20);
        }
        throw new AssertionError("Model call did not finish");
    }
    private ProbeSummary awaitProbe(String id) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < until) {
            var value = probeStore.get(id);
            if (!Set.of("QUEUED", "RUNNING", "CANCELLING").contains(value.status())) return value;
            Thread.sleep(20);
        }
        throw new AssertionError("Probe did not finish");
    }
    private RepairCandidateSummary awaitCandidate(String id) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < until) {
            var value = repairs.candidate(id);
            if (!Set.of("QUEUED", "RUNNING", "CANCELLING", "RUNTIME_QUEUED", "RUNTIME_RUNNING").contains(value.status())) return value;
            Thread.sleep(20);
        }
        throw new AssertionError("Candidate did not finish");
    }
    private static void assertCode(String code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(ResponseStatusException.class, operation).getReason());
    }
    private static com.fasterxml.jackson.databind.node.ObjectNode proposal(JsonNode request) {
        try {
            assertEquals("POST", lastMethod.get());
            assertFalse(request.path("stream").asBoolean());
            assertFalse(request.path("store").asBoolean());
            assertEquals("json_object", request.path("response_format").path("type").asText());
            assertFalse(request.has("tools"));
            var data = JSON.readTree(request.path("messages").get(1).path("content").asText());
            assertEquals(RepairIntegrationTest.BAD, data.path("source").asText());
            var result = JSON.createObjectNode().put("schemaVersion", 1)
                    .put("expectedBaselineSha256", data.path("expectedBaselineSha256").asText())
                    .put("targetFile", "source.py").put("diagnosis", "Remove duplicate")
                    .put("expectedEffect", "Static target only");
            result.putArray("validationTargets").add("S03");
            result.putArray("edits").addObject().put("oldText",
                    "    def homeContent(self, filter):\n        return {'class': []}\n").put("newText", "");
            return result;
        } catch (IOException e) { throw new AssertionError(e); }
    }
    private static String envelope(JsonNode proposal, String finish) {
        var value = JSON.createObjectNode();
        var choice = value.putArray("choices").addObject().put("finish_reason", finish);
        choice.putObject("message").put("role", "assistant").put("content", proposal.toString());
        value.putObject("usage").put("prompt_tokens", 10).put("completion_tokens", 20).put("total_tokens", 30)
                .put("private_provider_data", "not-forwarded-secret");
        return value.toString();
    }
    private static final AtomicReference<String> lastMethod = new AtomicReference<>();
    private static HttpServer fake() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/v1/chat/completions", exchange -> {
                received.incrementAndGet();
                lastMethod.set(exchange.getRequestMethod());
                try {
                    var input = JSON.readTree(exchange.getRequestBody().readAllBytes());
                    var body = responder.get().apply(input).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", contentType.get());
                    if (retryAfter.get() != null) exchange.getResponseHeaders().set("Retry-After", retryAfter.get());
                    exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/must-not-follow");
                    exchange.sendResponseHeaders(status.get(), body.length);
                    exchange.getResponseBody().write(body);
                } catch (IOException e) {
                    // Cancellation/timeout closes the connection before the fixture writes its late response.
                } finally { exchange.close(); }
            });
            server.start();
            return server;
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
}
