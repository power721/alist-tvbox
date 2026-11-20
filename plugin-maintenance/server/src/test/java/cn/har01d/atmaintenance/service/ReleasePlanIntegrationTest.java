package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.repository.ReleasePlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:maintenance-release-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=integration-test-only", "maintenance.at.origin=",
        "maintenance.scheduling.enabled=false", "maintenance.model.enabled=false"
})
class ReleasePlanIntegrationTest {
    @Autowired SourceService sources;
    @Autowired ProfileService profiles;
    @Autowired ProbeService probes;
    @Autowired ProbeStore probeStore;
    @Autowired RepairStore repairs;
    @Autowired RepairService repairService;
    @Autowired ReleasePlanService plans;
    @Autowired ReleasePlanExportService exports;
    @MockitoSpyBean ReleasePlanRepository repository;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AtBindingService bindings;
    @MockitoSpyBean FixtureRuntime runtime;
    @MockitoSpyBean ContainerRuntime container;
    @MockitoSpyBean ManagedSourceRuntime managed;
    @MockitoSpyBean AtReadClient atClient;
    @LocalServerPort int port;

    @Test
    void preparationFreezesExactEvidenceWithoutExecutingOrSwitchingTheCandidate() throws Exception {
        var candidate = validated();
        var repair = repairs.get(candidate.repairId());
        var plan = plans.create(repair.sourceId(), input(candidate, "release-preparation"));
        assertEquals("AWAITING_RUNTIME_VALIDATION", plan.status());
        assertEquals("current", plan.freshness());
        assertFalse(plan.publishEligible());
        assertTrue(plan.blockers().containsAll(List.of("ISOLATED_RUNTIME_VALIDATION_REQUIRED",
                "AT_RUNTIME_VALIDATION_REQUIRED", "MEDIA_VALIDATION_REQUIRED",
                "AT_PUBLICATION_ADAPTER_UNAVAILABLE", "AT_BINDING_REQUIRED")));
        assertEquals(repair.baselineArtifactSha256(), sources.get(repair.sourceId()).artifactSha256());
        assertEquals(1, sources.versions(repair.sourceId()).size());
        var frozen = plans.frozen(plan.id());
        assertEquals(candidate.id(), frozen.path("candidate").path("id").asText());
        assertEquals(candidate.result(), frozen.path("candidate").path("result"));
        assertEquals(runtime.sha256(), frozen.path("runtimeSha256").asText());
        assertEquals(container.sha256(), frozen.path("executionPolicySha256").asText());
        assertEquals(plan.frozenSha256(), Hashes.sha256(frozen.toString()));
        var files = unzip(exports.export(plan.id()));
        assertEquals(RepairIntegrationTest.BAD, new String(files.get("baseline.py"), StandardCharsets.UTF_8));
        assertEquals(RepairIntegrationTest.BAD.replace(RepairIntegrationTest.OVERRIDE, ""),
                new String(files.get("candidate.py"), StandardCharsets.UTF_8));
        verifyManifest(files);
        assertEquals("unbound_proposal", mapper.readTree(files.get("provenance.json")).path("origin").asText());
        ((ObjectNode) frozen).put("publishEligible", true);
        assertFalse(plans.frozen(plan.id()).path("publishEligible").asBoolean());
    }

    @Test
    void idempotencyReplaysCancelledAndStalePlansButRejectsDifferentInputs() throws Exception {
        var candidate = validated();
        var repair = repairs.get(candidate.repairId());
        var request = input(candidate, "persistent-request");
        var plan = plans.create(repair.sourceId(), request);
        sources.addArtifact(repair.sourceId(), new AddArtifactRequest(repair.baselineArtifactSha256(),
                RepairIntegrationTest.BAD + "\n# new effective version\n"));
        assertEquals(plan.id(), plans.create(repair.sourceId(), request).id());
        assertEquals("inputs_changed", plans.get(plan.id()).freshness());
        var cancelled = plans.cancel(plan.id());
        assertEquals(cancelled, plans.cancel(plan.id()));
        assertEquals(cancelled, plans.create(repair.sourceId(), request));
        var changed = new StartReleasePlanRequest(candidate.id(), "0".repeat(64), candidate.sha256(), request.requestKey());
        assertEquals("RELEASE_REQUEST_KEY_CONFLICT", assertThrows(ResponseStatusException.class,
                () -> plans.create(repair.sourceId(), changed)).getReason());
        assertEquals(1, plans.list(repair.sourceId()).size());
    }

    @Test
    void activePlanRequiresExplicitCancellationBeforeAnotherKeyCanReserve() throws Exception {
        var candidate = validated();
        var repair = repairs.get(candidate.repairId());
        var first = plans.create(repair.sourceId(), input(candidate, "first-release-key"));
        assertEquals("RELEASE_PLAN_ALREADY_ACTIVE", assertThrows(ResponseStatusException.class,
                () -> plans.create(repair.sourceId(), input(candidate, "second-release-key"))).getReason());
        plans.cancel(first.id());
        var next = plans.create(repair.sourceId(), input(candidate, "second-release-key"));
        assertNotEquals(first.id(), next.id());
        assertEquals("CANCELLED", plans.get(first.id()).status());
        assertEquals(2, plans.list(repair.sourceId()).size());
    }

    @Test
    void concurrentDifferentKeysReserveOnlyOneActivePlan() throws Exception {
        var candidate = validated();
        var sourceId = repairs.get(candidate.repairId()).sourceId();
        var results = parallel(() -> attempt(sourceId, input(candidate, "concurrent-key-a")),
                () -> attempt(sourceId, input(candidate, "concurrent-key-b")));
        assertEquals(1, results.stream().filter(ReleasePlanSummary.class::isInstance).count());
        assertTrue(results.contains("RELEASE_PLAN_ALREADY_ACTIVE"));
        assertEquals(1, plans.list(sourceId).size());
    }

    @Test
    void concurrentSameKeyReplaysOnePersistedPlan() throws Exception {
        var candidate = validated();
        var sourceId = repairs.get(candidate.repairId()).sourceId();
        var request = input(candidate, "concurrent-same-key");
        var results = parallel(() -> plans.create(sourceId, request), () -> plans.create(sourceId, request));
        assertEquals(((ReleasePlanSummary) results.getFirst()).id(), ((ReleasePlanSummary) results.getLast()).id());
        assertEquals(1, plans.list(sourceId).size());
    }

    @Test
    void concurrentCancellationReturnsTheSameTerminalRecordAndReleasesSlot() throws Exception {
        var candidate = validated();
        var sourceId = repairs.get(candidate.repairId()).sourceId();
        var plan = plans.create(sourceId, input(candidate, "concurrent-cancel"));
        var results = parallel(() -> plans.cancel(plan.id()), () -> plans.cancel(plan.id()));
        assertEquals(results.getFirst(), results.getLast());
        assertFalse(repository.existsByActiveSourceId(sourceId));
        assertEquals("AWAITING_RUNTIME_VALIDATION",
                plans.create(sourceId, input(candidate, "after-concurrent-cancel")).status());
    }

    @Test
    void rejectsUnvalidatedWrongSourceAndWrongIdentityBeforeWritingAnyPlan() throws Exception {
        var repair = begin();
        var queued = repairs.reserve(repair.id(), proposal(repair));
        var queuedSummary = repairs.candidate(queued.getId());
        assertEquals("RELEASE_CANDIDATE_NOT_READY", assertThrows(ResponseStatusException.class,
                () -> plans.create(repair.sourceId(), input(queuedSummary, "not-validated-key"))).getReason());
        assertTrue(plans.list(repair.sourceId()).isEmpty());
        var candidate = validated();
        var owner = repairs.get(candidate.repairId()).sourceId();
        assertEquals("RELEASE_SOURCE_MISMATCH", assertThrows(ResponseStatusException.class,
                () -> plans.create(repair.sourceId(), input(candidate, "wrong-source-key"))).getReason());
        var wrongSha = new StartReleasePlanRequest(candidate.id(), repairs.get(candidate.repairId()).baselineArtifactSha256(),
                "0".repeat(64), "wrong-candidate-key");
        assertEquals("RELEASE_CANDIDATE_CHANGED", assertThrows(ResponseStatusException.class,
                () -> plans.create(owner, wrongSha)).getReason());
        assertTrue(plans.list(owner).isEmpty());
    }

    @Test
    void sourceAndProfileDriftInvalidatePlanButKeepFrozenExportAndActiveSlot() throws Exception {
        var candidate = validated();
        var repair = repairs.get(candidate.repairId());
        var plan = plans.create(repair.sourceId(), input(candidate, "drift-release-key"));
        var original = plans.frozen(plan.id());
        var profile = profiles.get(repair.sourceId());
        profiles.update(repair.sourceId(), new UpdateProfileRequest(profile.sha256(), "fongmi", "different",
                List.of("S01", "S02", "S03", "C01"), List.of(), null, null));
        sources.addArtifact(repair.sourceId(), new AddArtifactRequest(repair.baselineArtifactSha256(),
                RepairIntegrationTest.BAD + "\n# changed\n"));
        assertTrue(plans.get(plan.id()).staleInputs().containsAll(List.of("source", "profile")));
        assertTrue(plans.get(plan.id()).blockers().contains("RELEASE_INPUTS_CHANGED"));
        assertEquals(original, plans.frozen(plan.id()));
        assertTrue(repository.existsByActiveSourceId(repair.sourceId()));
        var files = unzip(exports.export(plan.id()));
        assertEquals(original, mapper.readTree(files.get("frozen.json")));
        assertEquals("inputs_changed", mapper.readTree(files.get("plan.json")).path("freshness").asText());
        verifyManifest(files);
        plans.cancel(plan.id());
        assertEquals("RELEASE_INPUTS_CHANGED", assertThrows(ResponseStatusException.class,
                () -> plans.create(repair.sourceId(), input(candidate, "drift-new-key"))).getReason());
    }

    @Test
    void runtimeAndExecutionConfigurationChangesAreVisibleEvenForStaticBaselines() throws Exception {
        var candidate = validated();
        var sourceId = repairs.get(candidate.repairId()).sourceId();
        var plan = plans.create(sourceId, input(candidate, "runtime-drift-key"));
        var original = plans.frozen(plan.id());
        doReturn("a".repeat(64)).when(runtime).sha256();
        doReturn("b".repeat(64)).when(container).sha256();
        var changed = plans.get(plan.id());
        assertTrue(changed.staleInputs().containsAll(List.of("runtime", "execution_policy")));
        assertFalse(changed.publishEligible());
        assertEquals(original, plans.frozen(plan.id()));
        verifyManifest(unzip(exports.export(plan.id())));
    }

    @Test
    void observedBindingIsFrozenAndUnbindingInvalidatesWithoutLosingOriginalIdentity() throws Exception {
        var candidate = validated(true);
        var sourceId = repairs.get(candidate.repairId()).sourceId();
        var binding = bindings.get(sourceId);
        var plan = plans.create(sourceId, input(candidate, "binding-release-key"));
        assertFalse(plan.blockers().contains("AT_BINDING_REQUIRED"));
        var frozen = plans.frozen(plan.id());
        var frozenBinding = frozen.path("repair").path("frozen").path("baseline").path("binding");
        assertEquals(binding.snapshot(), frozenBinding);
        assertEquals("metadata_observed", frozenBinding.path("authority").asText());
        assertFalse(plan.publishEligible());
        bindings.unbind(sourceId, binding.sha256());
        assertTrue(plans.get(plan.id()).staleInputs().contains("at_binding"));
        assertEquals(frozen, plans.frozen(plan.id()));
        verifyManifest(unzip(exports.export(plan.id())));
    }

    @Test
    void cancelledRepairInvalidatesPreparationAndCannotBeUsedForANewPlan() throws Exception {
        var candidate = validated();
        var sourceId = repairs.get(candidate.repairId()).sourceId();
        var plan = plans.create(sourceId, input(candidate, "repair-state-key"));
        repairService.cancel(candidate.repairId());
        assertTrue(plans.get(plan.id()).staleInputs().contains("repair_state"));
        plans.cancel(plan.id());
        assertEquals("RELEASE_CANDIDATE_NOT_READY", assertThrows(ResponseStatusException.class,
                () -> plans.create(sourceId, input(candidate, "cancelled-repair-key"))).getReason());
    }

    @Test
    void databaseRejectsForgedDeploymentStateAndRetainsThePendingRecord() throws Exception {
        var candidate = validated();
        var sourceId = repairs.get(candidate.repairId()).sourceId();
        var plan = plans.create(sourceId, input(candidate, "db-state-constraint"));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE maintenance_release_plan SET status = 'DEPLOYED' WHERE id = ?", plan.id()));
        assertEquals("AWAITING_RUNTIME_VALIDATION", plans.get(plan.id()).status());
        assertFalse(plans.get(plan.id()).publishEligible());
    }

    @Test
    void localApplyAndRollbackPersistReceiptsAndReplayWithoutAnotherSourceSwitch() throws Exception {
        var candidate = acceptedFixture();
        var sourceId = repairs.get(candidate.repairId()).sourceId();
        var plan = plans.create(sourceId, input(candidate, "local-cycle"));
        var frozen = plans.frozen(plan.id());
        assertTrue(plan.localApplyEligible());
        var apply = localInput(plan, false);
        var applied = plans.applyLocal(plan.id(), apply);
        assertEquals("LOCAL_APPLIED", applied.status());
        assertFalse(applied.localApplyEligible());
        assertTrue(applied.localRollbackEligible());
        assertEquals("current", applied.freshness());
        assertEquals(plan.sourceRevision() + 1, applied.sourceRevision());
        assertEquals(candidate.sha256(), sources.get(sourceId).artifactSha256());
        assertEquals(applied, plans.applyLocal(plan.id(), apply));
        assertFalse(repository.existsByActiveSourceId(sourceId));
        assertEquals("RELEASE_LOCAL_TRANSITION_TERMINAL", assertThrows(ResponseStatusException.class,
                () -> plans.cancel(plan.id())).getReason());
        assertEquals(applied.localReceipt(), mapper.readTree(repository.findById(plan.id()).orElseThrow().getLocalReceiptJson()));
        var files = unzip(exports.export(plan.id()));
        assertEquals(applied.localReceipt(), mapper.readTree(files.get("local-receipt.json")));
        assertFalse(mapper.readTree(files.get("manifest.json")).path("productionModified").asBoolean(true));
        verifyManifest(files);

        var rollback = localInput(applied, true);
        var rolledBack = plans.rollbackLocal(plan.id(), rollback);
        assertEquals("LOCAL_ROLLED_BACK", rolledBack.status());
        assertFalse(rolledBack.localRollbackEligible());
        assertEquals(plan.baselineArtifactSha256(), sources.get(sourceId).artifactSha256());
        assertEquals(applied.sourceRevision() + 1, rolledBack.sourceRevision());
        assertEquals(2, sources.versions(sourceId).size());
        assertEquals(rolledBack, plans.rollbackLocal(plan.id(), rollback));
        assertEquals(rolledBack, plans.applyLocal(plan.id(), apply));
        assertEquals(frozen, plans.frozen(plan.id()));
        assertFalse(rolledBack.publishEligible());
        files = unzip(exports.export(plan.id()));
        assertEquals(rolledBack.localReceipt(), mapper.readTree(files.get("local-receipt.json")));
        assertTrue(rolledBack.localReceipt().has("rollback"));
        verifyManifest(files);
    }

    @Test
    void localMutationsRejectStaticCancelledFrozenAndRevisionMismatches() throws Exception {
        var candidate = validated();
        var sourceId = repairs.get(candidate.repairId()).sourceId();
        var plan = plans.create(sourceId, input(candidate, "local-denied-static"));
        assertFalse(plan.localApplyEligible());
        assertEquals("RELEASE_LOCAL_APPLY_NOT_READY", assertThrows(ResponseStatusException.class,
                () -> plans.applyLocal(plan.id(), localInput(plan, false))).getReason());
        assertEquals("RELEASE_INPUTS_CHANGED", assertThrows(ResponseStatusException.class,
                () -> plans.applyLocal(plan.id(), new LocalReleaseRequest("0".repeat(64),
                        plan.baselineArtifactSha256(), plan.sourceRevision()))).getReason());
        assertEquals("RELEASE_INPUTS_CHANGED", assertThrows(ResponseStatusException.class,
                () -> plans.applyLocal(plan.id(), new LocalReleaseRequest(plan.frozenSha256(),
                        plan.baselineArtifactSha256(), plan.sourceRevision() + 1))).getReason());
        plans.cancel(plan.id());
        assertEquals("RELEASE_LOCAL_APPLY_NOT_READY", assertThrows(ResponseStatusException.class,
                () -> plans.applyLocal(plan.id(), localInput(plan, false))).getReason());
        assertEquals(plan.baselineArtifactSha256(), sources.get(sourceId).artifactSha256());
        assertEquals(1, sources.versions(sourceId).size());
    }

    @Test
    void sourceAbaCannotReviveApplyOrRollbackEligibility() throws Exception {
        var candidate = acceptedFixture();
        var sourceId = repairs.get(candidate.repairId()).sourceId();
        var plan = plans.create(sourceId, input(candidate, "local-apply-aba"));
        var changed = sources.addArtifact(sourceId, new AddArtifactRequest(plan.baselineArtifactSha256(),
                RepairIntegrationTest.BAD + "\n# intermediate version\n"));
        var restored = sources.addArtifact(sourceId, new AddArtifactRequest(changed.artifactSha256(), RepairIntegrationTest.BAD));
        var stale = plans.get(plan.id());
        assertFalse(stale.localApplyEligible());
        assertTrue(stale.staleInputs().contains("source_revision"));
        assertEquals("RELEASE_INPUTS_CHANGED", assertThrows(ResponseStatusException.class,
                () -> plans.applyLocal(plan.id(), localInput(plan, false))).getReason());
        assertEquals("RELEASE_LOCAL_APPLY_NOT_READY", assertThrows(ResponseStatusException.class,
                () -> plans.applyLocal(plan.id(), localInput(stale, false))).getReason());
        assertEquals(restored.revision(), sources.get(sourceId).revision());
        plans.cancel(plan.id());

        var fresh = plans.create(sourceId, input(candidate, "local-rollback-aba"));
        var applied = plans.applyLocal(fresh.id(), localInput(fresh, false));
        changed = sources.addArtifact(sourceId, new AddArtifactRequest(candidate.sha256(), RepairIntegrationTest.BAD));
        sources.addArtifact(sourceId, new AddArtifactRequest(changed.artifactSha256(), repairs.content(candidate.id())));
        var staleRollback = plans.get(fresh.id());
        assertFalse(staleRollback.localRollbackEligible());
        assertEquals("RELEASE_LOCAL_ROLLBACK_NOT_READY", assertThrows(ResponseStatusException.class,
                () -> plans.rollbackLocal(fresh.id(), localInput(staleRollback, true))).getReason());
        assertEquals(applied.localReceipt(), plans.get(fresh.id()).localReceipt());
        assertEquals(candidate.sha256(), sources.get(sourceId).artifactSha256());
    }

    @Test
    void candidateEvidenceDriftDisablesApplyButDoesNotPreventUndoingAnAppliedVersion() throws Exception {
        var candidate = acceptedFixture();
        var sourceId = repairs.get(candidate.repairId()).sourceId();
        var plan = plans.create(sourceId, input(candidate, "local-evidence-drift"));
        var original = candidate.result().deepCopy();
        var changed = (ObjectNode) original.deepCopy();
        changed.put("fixtureEvidenceRevision", 2);
        jdbc.update("UPDATE maintenance_repair_candidate SET result_json = ? WHERE id = ?", changed.toString(), candidate.id());
        assertTrue(plans.get(plan.id()).staleInputs().contains("candidate"));
        assertEquals("RELEASE_LOCAL_APPLY_NOT_READY", assertThrows(ResponseStatusException.class,
                () -> plans.applyLocal(plan.id(), localInput(plan, false))).getReason());
        jdbc.update("UPDATE maintenance_repair_candidate SET result_json = ? WHERE id = ?", original.toString(), candidate.id());
        var applied = plans.applyLocal(plan.id(), localInput(plan, false));
        doReturn("e".repeat(64)).when(container).sha256();
        var stale = plans.get(plan.id());
        assertTrue(stale.staleInputs().contains("execution_policy"));
        assertTrue(stale.localRollbackEligible());
        assertEquals("LOCAL_ROLLED_BACK", plans.rollbackLocal(plan.id(), localInput(stale, true)).status());
        assertEquals(applied.localReceipt().path("apply"), plans.get(plan.id()).localReceipt().path("apply"));
    }

    @Test
    void concurrentLocalRequestsSwitchTheSourceOnlyOncePerTransition() throws Exception {
        var candidate = acceptedFixture();
        var sourceId = repairs.get(candidate.repairId()).sourceId();
        var plan = plans.create(sourceId, input(candidate, "local-concurrent"));
        var apply = localInput(plan, false);
        var applied = parallel(() -> plans.applyLocal(plan.id(), apply), () -> plans.applyLocal(plan.id(), apply));
        assertEquals(applied.getFirst(), applied.getLast());
        var after = (ReleasePlanSummary) applied.getFirst();
        assertEquals(plan.sourceRevision() + 1, sources.get(sourceId).revision());
        var rollback = localInput(after, true);
        var undone = parallel(() -> plans.rollbackLocal(plan.id(), rollback), () -> plans.rollbackLocal(plan.id(), rollback));
        assertEquals(undone.getFirst(), undone.getLast());
        assertEquals(plan.sourceRevision() + 2, sources.get(sourceId).revision());
        assertEquals(2, sources.versions(sourceId).size());
    }

    @Test
    void failureSavingReceiptRollsBackTheSourceAndArtifactTogether() throws Exception {
        var candidate = acceptedFixture();
        var sourceId = repairs.get(candidate.repairId()).sourceId();
        var plan = plans.create(sourceId, input(candidate, "local-atomicity"));
        org.mockito.Mockito.doThrow(new IllegalStateException("synthetic receipt flush failure")).when(repository).flush();
        assertThrows(IllegalStateException.class, () -> plans.applyLocal(plan.id(), localInput(plan, false)));
        assertEquals(plan.baselineArtifactSha256(), sources.get(sourceId).artifactSha256());
        assertEquals(plan.sourceRevision(), sources.get(sourceId).revision());
        assertEquals(1, sources.versions(sourceId).size());
        assertEquals(plan, plans.get(plan.id()));
        assertNull(repository.findById(plan.id()).orElseThrow().getLocalReceiptJson());
    }

    @Test
    void managedPlansFreezeAndTrackManagedRuntimeInsteadOfFixturePolicy() throws Exception {
        var staticRepair = begin();
        var baseline = probeStore.get(staticRepair.baselineRunId());
        var run = probeStore.create(baseline.sourceId(), baseline.inspectorSha256(), baseline.knowledgeSha256(), "managed_source");
        assertTrue(probeStore.start(run.getId()));
        probeStore.finish(run.getId(), "COMPLETED", (ObjectNode) baseline.result(), baseline.context(), null);
        var repair = repairs.create(baseline.sourceId(), new StartRepairRequest(run.getId(), baseline.artifactSha256()));
        var candidate = acceptFixture(awaitValidated(repair));
        var plan = plans.create(repair.sourceId(), input(candidate, "managed-local-plan"));
        var frozen = plans.frozen(plan.id());
        assertEquals("managed_source", frozen.path("runtimeMode").asText());
        assertEquals(managed.sha256(), frozen.path("runtimeSha256").asText());
        assertEquals(managed.policySha256(), frozen.path("executionPolicySha256").asText());
        assertTrue(plan.localApplyEligible());
        doReturn("a".repeat(64)).when(container).sha256();
        assertTrue(plans.get(plan.id()).localApplyEligible());
        doReturn("b".repeat(64)).when(managed).policySha256();
        var changed = plans.get(plan.id());
        assertTrue(changed.staleInputs().contains("execution_policy"));
        assertFalse(changed.localApplyEligible());
        assertEquals("RELEASE_LOCAL_APPLY_NOT_READY", assertThrows(ResponseStatusException.class,
                () -> plans.applyLocal(plan.id(), localInput(plan, false))).getReason());
    }

    @Test
    void localHttpActionsRetainCsrfAndRejectInvalidRequests() throws Exception {
        var candidate = acceptedFixture();
        var sourceId = repairs.get(candidate.repairId()).sourceId();
        var plan = plans.create(sourceId, input(candidate, "local-http"));
        String base = "http://127.0.0.1:" + port + "/api/plugin-maintenance";
        String auth = "Basic " + Base64.getEncoder().encodeToString("admin:integration-test-only".getBytes(StandardCharsets.UTF_8));
        try (var http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            String apply = base + "/release-plans/" + plan.id() + "/local-apply";
            assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(apply)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(apply)).header("Authorization", auth)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var csrf = mapper.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/csrf"))
                    .header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(400, http.send(post(apply, auth, csrf, mapper.createObjectNode()),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, http.send(post(apply, auth, csrf, (ObjectNode) mapper.valueToTree(localInput(plan, false))),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var applied = plans.get(plan.id());
            String rollback = base + "/release-plans/" + plan.id() + "/local-rollback";
            assertEquals(200, http.send(post(rollback, auth, csrf, (ObjectNode) mapper.valueToTree(localInput(applied, true))),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals("LOCAL_ROLLED_BACK", plans.get(plan.id()).status());
        }
    }

    @Test
    void httpRequiresAdminCsrfAndValidKeysAndCannotAcceptForgedStateOrEligibility() throws Exception {
        var candidate = validated();
        var sourceId = repairs.get(candidate.repairId()).sourceId();
        String base = "http://127.0.0.1:" + port + "/api/plugin-maintenance";
        String path = "/sources/" + sourceId + "/release-plans";
        String auth = "Basic " + Base64.getEncoder().encodeToString("admin:integration-test-only".getBytes(StandardCharsets.UTF_8));
        try (var http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", auth)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            var csrf = mapper.readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/csrf"))
                    .header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            var body = (ObjectNode) mapper.valueToTree(input(candidate, "invalid key/private"));
            var invalid = http.send(post(base + path, auth, csrf, body), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalid.statusCode());
            assertEquals("{\"code\":\"INVALID_REQUEST\"}", invalid.body());
            assertTrue(plans.list(sourceId).isEmpty());
            body.put("requestKey", "http-valid-key").put("status", "DEPLOYED").put("publishEligible", true);
            var accepted = http.send(post(base + path, auth, csrf, body), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, accepted.statusCode(), accepted.body());
            var plan = mapper.readTree(accepted.body());
            assertEquals("AWAITING_RUNTIME_VALIDATION", plan.path("status").asText());
            assertFalse(plan.path("publishEligible").asBoolean());
            var exported = http.send(HttpRequest.newBuilder(URI.create(base + "/release-plans/" + plan.path("id").asText()
                    + "/export")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, exported.statusCode());
            verifyManifest(unzip(exported.body()));
        }
    }

    private HttpRequest post(String url, String auth, com.fasterxml.jackson.databind.JsonNode csrf, ObjectNode body) {
        return HttpRequest.newBuilder(URI.create(url)).header("Authorization", auth).header("Content-Type", "application/json")
                .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }
    private Object attempt(String sourceId, StartReleasePlanRequest input) {
        try { return plans.create(sourceId, input); }
        catch (ResponseStatusException e) { return e.getReason(); }
    }
    private List<Object> parallel(Callable<Object> first, Callable<Object> second) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return first.call(); });
            var b = pool.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }
    private RepairSummary begin() throws Exception {
        return begin(false);
    }
    private RepairSummary begin(boolean bound) throws Exception {
        var source = sources.create(new CreateSourceRequest("发布准备夹具", null, "release.py", null, RepairIntegrationTest.BAD));
        if (bound) {
            var connection = new AtConnection(true, "synthetic-at", "a".repeat(64),
                    "b".repeat(64), "unverified", "declared");
            var observation = new AtObservation("c".repeat(64), connection, java.time.Instant.now(), List.of(
                    new AtPluginSummary(7, "synthetic", null, true, true, 1, "python",
                            "plugin-7", "d".repeat(64), false)), "metadata_observed", "test fixture only", List.of());
            doReturn(connection).when(atClient).connection();
            doReturn(observation).when(atClient).requireObservation(observation.sha256());
            bindings.bind(source.id(), new BindAtRequest(null, observation.sha256(), 7));
        }
        String runId = probes.start(source.id()).id();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            var probe = probeStore.get(runId);
            if (probe.status().equals("COMPLETED"))
                return repairs.create(source.id(), new StartRepairRequest(runId, source.artifactSha256()));
            Thread.sleep(20);
        }
        throw new AssertionError("Probe did not finish");
    }
    private RepairCandidateSummary validated() throws Exception {
        return validated(false);
    }
    private RepairCandidateSummary validated(boolean bound) throws Exception {
        return awaitValidated(begin(bound));
    }
    private RepairCandidateSummary awaitValidated(RepairSummary repair) throws Exception {
        String candidateId = repairService.propose(repair.id(), proposal(repair)).id();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            var candidate = repairs.candidate(candidateId);
            if (!Set.of("QUEUED", "RUNNING", "CANCELLING").contains(candidate.status())) {
                assertEquals("STATIC_VALIDATED", candidate.status());
                return candidate;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Candidate did not finish");
    }
    private RepairProposal proposal(RepairSummary repair) {
        return new RepairProposal(1, repair.baselineArtifactSha256(), "source.py", "移除重复定义",
                "仅静态修复，仍待隔离运行验证", List.of("S03"), List.of(new RepairEdit(RepairIntegrationTest.OVERRIDE, "")), null);
    }
    private StartReleasePlanRequest input(RepairCandidateSummary candidate, String key) {
        return new StartReleasePlanRequest(candidate.id(), repairs.get(candidate.repairId()).baselineArtifactSha256(),
                candidate.sha256(), key);
    }
    // Persist a synthetic accepted state to exercise local transitions; no source runtime is executed.
    private RepairCandidateSummary acceptedFixture() throws Exception {
        return acceptFixture(validated());
    }
    private RepairCandidateSummary acceptFixture(RepairCandidateSummary candidate) {
        var result = (ObjectNode) candidate.result().deepCopy();
        result.put("repairSuccessful", true).put("evidenceClass", "synthetic_release_transition_fixture");
        result.putObject("runtimeAcceptance").put("successful", true);
        jdbc.update("UPDATE maintenance_repair_candidate SET status = 'RUNTIME_OBSERVED', result_json = ? WHERE id = ?",
                result.toString(), candidate.id());
        jdbc.update("UPDATE maintenance_repair SET status = 'RUNTIME_OBSERVED' WHERE id = ?", candidate.repairId());
        return repairs.candidate(candidate.id());
    }
    private LocalReleaseRequest localInput(ReleasePlanSummary plan, boolean rollback) {
        return new LocalReleaseRequest(plan.frozenSha256(), rollback ? plan.candidateSha256() : plan.baselineArtifactSha256(),
                plan.sourceRevision());
    }
    private Map<String, byte[]> unzip(byte[] body) throws IOException {
        var files = new HashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(body))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertNull(files.put(entry.getName(), zip.readAllBytes()), "Duplicate ZIP entry");
            }
        }
        return files;
    }
    private void verifyManifest(Map<String, byte[]> files) throws IOException {
        var manifest = mapper.readTree(files.get("manifest.json"));
        assertFalse(manifest.path("publishEligible").asBoolean());
        assertEquals(files.size() - 1, manifest.path("files").size());
        for (var record : manifest.path("files").properties()) {
            byte[] body = files.get(record.getKey());
            assertEquals(record.getValue().path("sha256").asText(), Hashes.sha256(body));
            assertEquals(record.getValue().path("bytes").asInt(), body.length);
        }
        assertEquals(manifest.path("frozenSha256").asText(), Hashes.sha256(files.get("frozen.json")));
    }
}
