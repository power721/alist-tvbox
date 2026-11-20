package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real H2/migrations and version reservation, without executing any source. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "maintenance.scheduling.enabled=false", "maintenance.at.origin=",
        "spring.datasource.url=jdbc:h2:mem:maintenance-workflow-store;DB_CLOSE_DELAY=-1"
})
class MaintenanceWorkflowStoreTest {
    @Autowired ProbeStore store;
    @Autowired SourceService sources;
    @Autowired StaticInspector inspector;
    @Autowired KnowledgeService knowledge;
    @Autowired ProfileService profiles;
    @Autowired ManagedSourceRuntime managed;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @MockitoBean MaintenanceWorkflowService workflow;
    @MockitoBean ManagedSourceSetup setup;
    @Autowired ProbeService probes;
    @Autowired RepairStore repairs;

    @Test
    void customRoundsSurvivePreparationFailureAndRequestReplayWithoutCreatingAnotherTask() {
        var source = sources.create(new CreateSourceRequest("custom-rounds", null, "custom.py", null,
                "class Spider:\n    pass\n"));
        var input = new StartMaintenanceRequest(source.artifactSha256(), "a".repeat(64), "custom_rounds", 8);
        var run = store.recordPreparationFailure(source.id(), inspector.sha256(), knowledge.sha256(), input,
                "SOURCE_SETUP_DNS_NO_ADDRESS");
        assertEquals(8, store.existingMaintenance(source.id(), input).getMaintenanceMaxRounds());
        assertEquals(run.getId(), store.existingMaintenance(source.id(), input).getId());
        assertEquals("MAINTENANCE_INPUTS_CHANGED", assertThrows(ResponseStatusException.class,
                () -> store.existingMaintenance(source.id(), new StartMaintenanceRequest(source.artifactSha256(),
                        "a".repeat(64), "custom_rounds", 9))).getReason());
        assertEquals(1, store.maintenanceList(source.id()).size());
    }

    @Test
    void preparationFailureIsPersistentUnexecutedAndSameRequestNeverPreparesAgain() {
        var source = sources.create(new CreateSourceRequest("unresolved", null, "unresolved.py", null,
                "class Spider:\n    pass\n"));
        var historical = store.create(source.id(), inspector.sha256(), knowledge.sha256(), "static");
        var input = new StartMaintenanceRequest(source.artifactSha256(), "a".repeat(64), "failed_setup");
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_SETUP_DNS_NO_ADDRESS"))
                .when(setup).ensure(source.id(), source.artifactSha256());
        var first = probes.startMaintenance(source.id(), input);
        var repeated = probes.startMaintenance(source.id(), input);
        assertEquals(first.id(), repeated.id());
        verify(setup, times(1)).ensure(source.id(), source.artifactSha256());
        assertEquals("BLOCKED", first.status());
        assertEquals("SOURCE_SETUP_DNS_NO_ADDRESS", first.errorCode());
        assertEquals("CHECKING", store.maintenance(first.id()).status());
        assertNull(store.maintenance(first.id()).repairId());
        assertEquals(first.id(), store.maintenanceList(source.id()).getFirst().runId());
        assertEquals(2, store.list(source.id()).size());
        assertEquals("QUEUED", store.get(historical.getId()).status());
        var result = first.result();
        assertFalse(result.path("sourcePreparation").path("sourceExecuted").asBoolean(true));
        assertFalse(result.path("sourcePreparation").path("sourceHttpObserved").asBoolean(true));
        assertFalse(result.has("managedSourceEvidence"));
        assertEquals(0, result.path("requiredCoverage").path("pass").asInt(-1));
        assertEquals(9, result.path("requiredCoverage").path("total").asInt());
        assertEquals(1, result.path("requiredCoverage").path("blocked").asInt());
        assertEquals(8, result.path("requiredCoverage").path("notTested").asInt());
    }

    @Test
    void dnsOriginRepairFreezesContractsWithoutRewritingFailedProbeOrSpendingRounds() throws Exception {
        String body = "raise RuntimeError('MUST_NOT_EXECUTE')\nclass Spider:\n"
                + "    def homeContent(self, filter):\n        return {'class': []}\n";
        var source = sources.create(new CreateSourceRequest("dns-recovery", null, "dns.py", null, body));
        var input = new StartMaintenanceRequest(source.artifactSha256(), "a".repeat(64), "dns_origin");
        var failed = store.recordPreparationFailure(source.id(), inspector.sha256(), knowledge.sha256(), input,
                "SOURCE_SETUP_DNS_NO_ADDRESS");
        String original = mapper.writeValueAsString(store.get(failed.getId()).result());
        var repair = repairs.createOriginRecovery(source.id(), new StartRepairRequest(failed.getId(), source.artifactSha256()));
        assertEquals(repair.id(), repairs.createOriginRecovery(source.id(),
                new StartRepairRequest(failed.getId(), source.artifactSha256())).id());
        assertEquals(0, repair.usedRounds());
        assertEquals(3, repair.maxRounds());
        assertEquals("managed_origin_candidate", repair.frozen().path("policy").path("scope").asText());
        assertEquals(repair.id(), repair.frozen().path("originRecoveryRepairId").asText());
        assertTrue(repair.frozen().path("preparationInspection").path("parseOk").asBoolean());
        assertFalse(repair.frozen().path("baseline").path("result").has("managedSourceEvidence"));
        assertEquals(original, mapper.writeValueAsString(store.get(failed.getId()).result()));
        assertEquals("BLOCKED", store.get(failed.getId()).status());
        assertEquals(0, store.get(failed.getId()).result().path("requiredCoverage").path("pass").asInt(-1));
        assertEquals(body, sources.artifact(source.id(), source.artifactSha256()).getBody());
        var settings = new cn.har01d.atmaintenance.config.ModelSettings();
        settings.setEnabled(true);
        settings.setEndpoint("http://127.0.0.1:9/v1/chat/completions");
        settings.setModel("fixture-model");
        var client = new ModelClient(mapper, jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator(), settings);
        try {
            assertEquals("VERIFIED_ORIGIN_ROUTE_REQUIRED", assertThrows(ResponseStatusException.class,
                    () -> client.request(repair, body, java.util.List.of(), true)).getReason());
            repairs.attachReverseAnalysis(repair.id(), repair.faultSha256(),
                    RepairEvidenceMaterialTest.originReceipt(mapper, repair));
            var request = client.request(repairs.get(repair.id()), body, java.util.List.of(), true);
            var data = RepairEvidenceMaterial.requestData(mapper, request);
            assertEquals("SOURCE_SETUP_DNS_NO_ADDRESS", data.path("failureCodes").path(0).asText());
            assertEquals(6, data.path("validationTargets").size());
            assertEquals(repair.frozen().path("baseline").path("profile").path("requiredNodes"), data.path("requiredNodes"));
            assertEquals("https://alternate.example.com", data.path("verifiedAlternateOriginRoute").path("candidateOrigin").asText());
            assertFalse(data.path("sourcePreparation").path("sourceExecuted").asBoolean(true));
            assertFalse(data.path("loaderFailures").isArray() && !data.path("loaderFailures").isEmpty());
        } finally { client.close(); }
        assertEquals(0, repairs.get(repair.id()).usedRounds());
        assertTrue(repairs.candidates(repair.id()).isEmpty());
        var direct = store.recordPreparationFailure(source.id(), inspector.sha256(), knowledge.sha256(), null,
                "SOURCE_SETUP_DNS_NONPUBLIC");
        assertEquals("REPAIR_BASELINE_NOT_READY", assertThrows(ResponseStatusException.class, () ->
                repairs.createOriginRecovery(source.id(), new StartRepairRequest(direct.getId(), source.artifactSha256()))).getReason());
    }

    @Test
    void explicitDnsResumePreservesOriginalRunWhileCancellationRemainsTerminal() {
        var source = sources.create(new CreateSourceRequest("resume-dns", null, "dns.py", null, "class Spider: pass\n"));
        var input = new StartMaintenanceRequest(source.artifactSha256(), "a".repeat(64), "resume_dns");
        var run = store.recordPreparationFailure(source.id(), inspector.sha256(), knowledge.sha256(), input,
                "SOURCE_SETUP_DNS_NO_ADDRESS");
        store.finishMaintenance(run.getId(), "STOPPED", null, "SOURCE_SETUP_DNS_NO_ADDRESS");
        assertEquals("CHECKING", store.resumeMaintenance(run.getId(), input).status());
        assertEquals("BLOCKED", store.get(run.getId()).status());
        assertNull(store.maintenance(run.getId()).repairId());
        store.finishMaintenance(run.getId(), "CANCELLED", null, "USER_CANCELLED");
        assertEquals("MAINTENANCE_RESUME_NOT_READY", assertThrows(ResponseStatusException.class,
                () -> store.resumeMaintenance(run.getId(), input)).getReason());
    }

    @Test
    void directLiveInspectionAlsoPersistsSetupFailureWithoutStaticSuccess() {
        var source = sources.create(new CreateSourceRequest("direct", null, "direct.py", null,
                "class Spider:\n    pass\n"));
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_SETUP_DNS_FAILED"))
                .when(setup).ensure(source.id(), source.artifactSha256());
        var run = probes.start(source.id(), "managed_source");
        assertEquals("BLOCKED", run.status());
        assertEquals("managed_source", run.executionMode());
        assertEquals("SOURCE_SETUP_DNS_FAILED", run.errorCode());
        assertEquals(0, run.result().path("requiredCoverage").path("pass").asInt(-1));
        assertTrue(store.maintenanceList(source.id()).isEmpty());
        assertEquals(run.id(), store.list(source.id()).getFirst().id());
    }

    @Test
    void retainedCompletedRequestBypassesSetupAndChangedConfigurationStillConflicts() {
        var source = sources.create(new CreateSourceRequest("retained", null, "retained.py", null,
                "class Spider:\n    pass\n"));
        var input = new StartMaintenanceRequest(source.artifactSha256(), "a".repeat(64), "retained_setup");
        var run = store.createMaintenance(source.id(), inspector.sha256(), knowledge.sha256(), input);
        store.finish(run.getId(), "COMPLETED", null, null, null);
        store.finishMaintenance(run.getId(), "CHECKED", null, null);
        assertEquals(run.getId(), probes.startMaintenance(source.id(), input).id());
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> probes.startMaintenance(source.id(), new StartMaintenanceRequest(
                    source.artifactSha256(), "b".repeat(64), input.requestKey())));
        assertEquals("MAINTENANCE_INPUTS_CHANGED", error.getReason());
        verifyNoInteractions(setup);
    }

    @Test
    void unrelatedSetupExceptionIsNotConvertedIntoAnAcceptedWorkflow() {
        var source = sources.create(new CreateSourceRequest("conflict", null, "conflict.py", null,
                "class Spider:\n    pass\n"));
        var input = new StartMaintenanceRequest(source.artifactSha256(), "a".repeat(64), "rejected_setup");
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "MAINTENANCE_SOURCE_CHANGED"))
                .when(setup).ensure(source.id(), source.artifactSha256());
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> probes.startMaintenance(source.id(), input));
        assertEquals("MAINTENANCE_SOURCE_CHANGED", error.getReason());
        assertTrue(store.list(source.id()).isEmpty());
        assertTrue(store.maintenanceList(source.id()).isEmpty());
    }

    @Test
    void metadataProfileIsVersionedAndRequiresNavigationDetailButNeverPlayback() throws Exception {
        var source = sources.create(new CreateSourceRequest("metadata", null, "metadata.py", null,
                "class Spider:\n    pass\n"));
        var old = profiles.prepareLiveDefault(source.id(), "runtime");
        var historical = store.create(source.id(), inspector.sha256(), knowledge.sha256(), "static");
        var required = java.util.List.of("S01", "S02", "S03", "S06", "C01", "C03", "C05", "C07");
        var current = profiles.update(source.id(), new UpdateProfileRequest(old.sha256(), "t4", "runtime",
                required, java.util.List.of(), null, null, null, null, null, "page-window",
                "metadata-navigation", "title"));
        assertEquals("metadata-navigation", current.snapshot().path("sourcePurpose").asText());
        assertEquals(old.sha256(), historical.getProfileSha256());
        assertEquals(old.snapshot(), mapper.readTree(historical.getProfileJson()));
        assertEquals(2, profiles.history(source.id()).size());
        var withPlayback = new java.util.ArrayList<>(required);
        withPlayback.add("R01");
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> profiles.update(source.id(), new UpdateProfileRequest(current.sha256(), "t4", "runtime",
                    withPlayback, java.util.List.of(), null, null, null, null, null, "page-window", null, null)));
        assertEquals("METADATA_PROFILE_NODES_INVALID", error.getReason());
        var listOnly = required.stream().filter(id -> !id.equals("C05")).toList();
        var listProfile = profiles.update(source.id(), new UpdateProfileRequest(current.sha256(), "t4", "runtime",
                listOnly, java.util.List.of(), null, null, null, null, null, "page-window", null, null));
        assertEquals("metadata-navigation", listProfile.snapshot().path("sourcePurpose").asText());
        var missingEntry = required.stream().filter(id -> !java.util.Set.of("C03", "C05").contains(id)).toList();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> profiles.update(source.id(), new UpdateProfileRequest(listProfile.sha256(), "t4", "runtime",
                    missingEntry, java.util.List.of(), null, null, null, null, null, "page-window", null, null)));
    }

    @Test
    void firstMaintenanceFreezesALiveDefaultWithoutRewritingEarlierStaticRuns() throws Exception {
        var source = sources.create(new CreateSourceRequest("new source", null, "new.py", null,
                "class Spider:\n    pass\n"));
        var declared = profiles.get(source.id());
        var historical = store.create(source.id(), inspector.sha256(), knowledge.sha256(), "static");
        var input = new StartMaintenanceRequest(source.artifactSha256(), "a".repeat(64), "live_default");
        var run = store.createMaintenance(source.id(), inspector.sha256(), knowledge.sha256(), input);
        var live = profiles.get(source.id());
        assertNotNull(live.id());
        assertNotEquals(declared.sha256(), live.sha256());
        assertEquals(live.sha256(), run.getProfileSha256());
        assertEquals("page-window", live.snapshot().path("paginationMode").asText());
        assertEquals(managed.manifest().path("runtimeSha256"), live.snapshot().path("runtimeRevision"));
        assertEquals(mapper.readTree("[\"C01\",\"C03\",\"C07\",\"C10\",\"R01\",\"S01\",\"S02\",\"S03\",\"S06\"]"),
                live.snapshot().path("requiredNodes"));
        assertTrue(live.snapshot().path("optionalNodes").isEmpty());
        assertEquals("declared", live.snapshot().path("authority").asText());
        assertEquals(declared.sha256(), historical.getProfileSha256());
        assertEquals(declared.snapshot(), mapper.readTree(historical.getProfileJson()));
        assertEquals(run.getId(), store.createMaintenance(source.id(), inspector.sha256(), knowledge.sha256(), input).getId());
        assertEquals(1, profiles.history(source.id()).size());
        store.finishMaintenance(run.getId(), "CANCELLED", null, "USER_CANCELLED");
    }

    @Test
    void savedSiteRequirementsAndConfigurationAreNotReplacedByAutomaticDefaults() throws Exception {
        var source = sources.create(new CreateSourceRequest("configured", null, "configured.py", null,
                "class Spider:\n    pass\n"));
        var previous = profiles.get(source.id());
        var custom = profiles.update(source.id(), new UpdateProfileRequest(previous.sha256(), "t4", "custom",
                java.util.List.of("S01", "S02", "S03", "S06", "C09", "R05"), java.util.List.of("C07"),
                mapper.readTree("{\"private\":\"not persisted\"}"),
                mapper.readTree("{\"host\":\"https://public.example\"}"),
                mapper.readTree("{\"host\":\"https://different.example\"}"), 2, "selected-detail", "exact-total"));
        var input = new StartMaintenanceRequest(source.artifactSha256(), "a".repeat(64), "custom_live");
        var run = store.createMaintenance(source.id(), inspector.sha256(), knowledge.sha256(), input);
        assertEquals(custom.sha256(), profiles.get(source.id()).sha256());
        assertEquals(custom.snapshot(), profiles.get(source.id()).snapshot());
        assertEquals(custom.sha256(), run.getProfileSha256());
        assertEquals(custom.snapshot(), mapper.readTree(run.getProfileJson()));
        store.finishMaintenance(run.getId(), "CANCELLED", null, "USER_CANCELLED");
    }

    @Test
    void changedSourceCannotCreateAnAutomaticProfile() {
        var source = sources.create(new CreateSourceRequest("changed", null, "changed.py", null,
                "class Spider:\n    pass\n"));
        var before = profiles.get(source.id());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> store.createMaintenance(source.id(), inspector.sha256(), knowledge.sha256(),
                        new StartMaintenanceRequest("b".repeat(64), "a".repeat(64), "wrong_source")));
        assertEquals(before, profiles.get(source.id()));
    }

    @Test
    void duplicateRequestsAndPageReloadReusePersistentWorkflowWithoutExecutingSource() {
        var source = sources.create(new CreateSourceRequest("workflow", null, "workflow.py", null,
                "class Spider:\n    pass\n"));
        var input = new StartMaintenanceRequest(source.artifactSha256(), "a".repeat(64), "request_first");
        var first = store.createMaintenance(source.id(), inspector.sha256(), knowledge.sha256(), input);
        var same = store.createMaintenance(source.id(), inspector.sha256(), knowledge.sha256(), input);
        var reloaded = store.createMaintenance(source.id(), inspector.sha256(), knowledge.sha256(),
                new StartMaintenanceRequest(source.artifactSha256(), "a".repeat(64), "request_reload"));
        assertEquals(first.getId(), same.getId());
        assertEquals(first.getId(), reloaded.getId());
        assertEquals("CHECKING", store.maintenance(first.getId()).status());
        store.finishMaintenance(first.getId(), "CHECKED", null, null);
        assertEquals(first.getId(), store.createMaintenance(source.id(), inspector.sha256(), knowledge.sha256(), input).getId());
        var next = store.createMaintenance(source.id(), inspector.sha256(), knowledge.sha256(),
                new StartMaintenanceRequest(source.artifactSha256(), "a".repeat(64), "request_second"));
        assertNotEquals(first.getId(), next.getId());
        assertEquals(2, store.maintenanceList(source.id()).size());
        store.finishMaintenance(next.getId(), "CANCELLED", null, "USER_CANCELLED");
        store.finishMaintenance(next.getId(), "SUCCEEDED", "repair", null);
        assertEquals("CANCELLED", store.maintenance(next.getId()).status());
    }

    @Test
    void onlyExplicitBudgetStopsCanRebindModelConfigurationAndSourceIdentityCannotChange() {
        var source = sources.create(new CreateSourceRequest("resume", null, "resume.py", null,
                "class Spider:\n    pass\n"));
        var input = new StartMaintenanceRequest(source.artifactSha256(), "a".repeat(64), "request_resume");
        var run = store.createMaintenance(source.id(), inspector.sha256(), knowledge.sha256(), input);
        store.finishMaintenance(run.getId(), "STOPPED", "repair", "MODEL_NAME_DAILY_BUDGET");
        var changed = new StartMaintenanceRequest(source.artifactSha256(), "b".repeat(64), "changed_config");
        var resumed = store.resumeMaintenance(run.getId(), changed);
        assertEquals("REPAIRING", resumed.status());
        assertEquals("repair", resumed.repairId());
        assertEquals(run.getId(), store.resumeMaintenance(run.getId(), changed).runId());
        assertEquals("b".repeat(64), store.activeMaintenance().stream()
                .filter(row -> run.getId().equals(row.getId())).findFirst().orElseThrow()
                .getMaintenanceConfigurationSha256());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> store.resumeMaintenance(run.getId(), input));
        store.finishMaintenance(run.getId(), "STOPPED", "repair", "MODEL_NAME_DAILY_BUDGET");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> store.resumeMaintenance(run.getId(),
                        new StartMaintenanceRequest("c".repeat(64), "b".repeat(64), "changed_source")));
        assertEquals("STOPPED", store.maintenance(run.getId()).status());
        store.resumeMaintenance(run.getId(), changed);
        store.finishMaintenance(run.getId(), "CANCELLED", "repair", "USER_CANCELLED");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> store.resumeMaintenance(run.getId(), input));
    }

    @Test
    void inputPackingStopResumesTheSameInspectionAndRepairWithoutCreatingAnotherRun() {
        var source = sources.create(new CreateSourceRequest("packing", null, "packing.py", null,
                "class Spider:\n    pass\n"));
        var input = new StartMaintenanceRequest(source.artifactSha256(), "a".repeat(64), "request_packing");
        var run = store.createMaintenance(source.id(), inspector.sha256(), knowledge.sha256(), input);
        store.finishMaintenance(run.getId(), "STOPPED", "repair", "MODEL_INPUT_TOO_LARGE");
        var resumed = store.resumeMaintenance(run.getId(), input);
        assertEquals(run.getId(), resumed.runId());
        assertEquals("repair", resumed.repairId());
        assertEquals("REPAIRING", resumed.status());
        assertEquals(1, store.maintenanceList(source.id()).size());
    }

    @Test
    void responseFailureRecoveryKeepsOriginalInspectionAndRepairButUnknownOutcomesStayStopped() {
        var source = sources.create(new CreateSourceRequest("response", null, "response.py", null,
                "class Spider:\n    pass\n"));
        var input = new StartMaintenanceRequest(source.artifactSha256(), "a".repeat(64), "request_response");
        var run = store.createMaintenance(source.id(), inspector.sha256(), knowledge.sha256(), input);
        for (String code : java.util.List.of("MODEL_RESPONSE_INVALID", "MODEL_RESPONSE_PROPOSAL_SCHEMA_INVALID",
                "MODEL_RESPONSE_OUTPUT_TRUNCATED", "MODEL_HTTP_ERROR", "MODEL_RATE_LIMITED",
                "MODEL_SERVICE_UNAVAILABLE", "MODEL_CONNECTION_FAILED")) {
            store.finishMaintenance(run.getId(), "STOPPED", "repair", code);
            var resumed = store.resumeMaintenance(run.getId(), input);
            assertEquals(run.getId(), resumed.runId());
            assertEquals("repair", resumed.repairId());
            assertEquals("REPAIRING", resumed.status());
        }
        assertEquals(1, store.maintenanceList(source.id()).size());
        for (String code : java.util.List.of("MODEL_OUTCOME_UNKNOWN", "MODEL_RESTARTED_OUTCOME_UNKNOWN",
                "MODEL_AUTHENTICATION_REJECTED", "MODEL_QUOTA_EXHAUSTED", "MODEL_REQUEST_REJECTED")) {
            store.finishMaintenance(run.getId(), "STOPPED", "repair", code);
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> store.resumeMaintenance(run.getId(), input));
        }
    }

    @Test
    void networkRecoveryRequiresVerifiedRepairAndKeepsTheInspectionIdentity() {
        var source = sources.create(new CreateSourceRequest("network", null, "network.py", null,
                "class Spider:\n    pass\n"));
        var input = new StartMaintenanceRequest(source.artifactSha256(), "a".repeat(64), "request_network");
        var run = store.createMaintenance(source.id(), inspector.sha256(), knowledge.sha256(), input);
        for (String code : java.util.List.of("RUNTIME_NETWORK_UNAVAILABLE", "REPEATED_FAILURE")) {
            store.finishMaintenance(run.getId(), "STOPPED", "repair", code);
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> store.resumeMaintenance(run.getId(), input));
            assertEquals(run.getId(), store.resumeMaintenance(run.getId(), input, true).runId());
        }
        assertEquals(1, store.maintenanceList(source.id()).size());
        store.finishMaintenance(run.getId(), "STOPPED", "repair", "MANAGED_CLEANUP_UNCONFIRMED");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> store.resumeMaintenance(run.getId(), input, true));
    }
}
