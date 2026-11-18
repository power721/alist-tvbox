package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.ProbeRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class MaintenanceWorkflowServiceTest {
    private final ProbeStore store = mock(ProbeStore.class);
    private final ProbeService probes = mock(ProbeService.class);
    private final SourceService sources = mock(SourceService.class);
    private final ModelStore models = mock(ModelStore.class);
    private final ModelClient client = mock(ModelClient.class);
    private final RepairStore repairs = mock(RepairStore.class);
    private final AutomaticRepairStore automatic = mock(AutomaticRepairStore.class);
    private final AutomaticRepairService automation = mock(AutomaticRepairService.class);
    private final ProbeScheduleStore schedules = mock(ProbeScheduleStore.class);
    private final SourceCredentialStore credentials = mock(SourceCredentialStore.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MaintenanceWorkflowService service = new MaintenanceWorkflowService(
            store, probes, sources, models, client, repairs, automatic, automation, schedules, credentials,
            new RepairDiagnostics(mapper));
    private final ProbeRun row = new ProbeRun();
    private final ProbeSummary run = mock(ProbeSummary.class);
    private final RepairSummary repair = mock(RepairSummary.class);
    private ObjectNode result;

    @BeforeEach
    void setup() throws Exception {
        row.setId("run"); row.setSourceId("source"); row.setArtifactSha256("sha");
        row.setMaintenanceStatus("CHECKING"); row.setMaintenanceConfigurationSha256("config");
        when(store.activeMaintenance()).thenReturn(List.of(row));
        when(store.get("run")).thenReturn(run);
        when(run.status()).thenReturn("COMPLETED");
        when(run.freshness()).thenReturn("current");
        result = (ObjectNode) new ObjectMapper().readTree("""
                {"nodes":[{"id":"C03","status":"fail","evidenceClass":"host_loader_contract_observed"}],
                 "requiredCoverage":{"total":1,"pass":0},
                 "managedSourceEvidence":{"deniedCodes":[],
                   "cleanup":{"confirmed":true,"containerAbsent":true},
                   "httpObservations":[{"responseComplete":true,"responseStatus":200,"responseBytes":512}]}}
                """);
        when(run.result()).thenReturn(result);
        var connection = mock(ModelConnection.class);
        when(client.connection()).thenReturn(connection);
        when(connection.configurationSha256()).thenReturn("config");
        when(connection.autoSubmitEnabled()).thenReturn(true);
        when(models.consent("source")).thenReturn(new ModelConsent("source", "sha"));
        when(repairs.create("source", new StartRepairRequest("run", "sha"))).thenReturn(repair);
        when(repair.id()).thenReturn("repair");
        when(repair.faultSha256()).thenReturn("fault");
        when(repairs.get("repair")).thenReturn(repair);
        when(automatic.get("repair")).thenReturn(new AutomaticRepairSummary("repair", "DISABLED", null, null));
        when(store.maintenance("run")).thenAnswer(call -> summary());
        when(store.finishMaintenance(anyString(), anyString(), nullable(String.class), nullable(String.class)))
                .thenAnswer(call -> {
                    row.setMaintenanceStatus(call.getArgument(1));
                    row.setMaintenanceRepairId(call.getArgument(2));
                    row.setMaintenanceStopCode(call.getArgument(3));
                    return summary();
                });
    }

    private MaintenanceSummary summary() {
        return new MaintenanceSummary("run", "source", "sha", row.getMaintenanceStatus(),
                row.getMaintenanceRepairId(), row.getMaintenanceStopCode());
    }

    @Test
    void originInfrastructureRecoveryKeepsTheRepairAndDoesNotResumeOrReinspectItsBaseline() {
        row.setMaintenanceStatus("STOPPED"); row.setMaintenanceRepairId("repair");
        row.setMaintenanceStopCode("REVERSE_ENGINE_NOT_READY");
        when(run.freshness()).thenReturn("runtime_changed");
        var source = mock(SourceSummary.class);
        when(source.artifactSha256()).thenReturn("sha");
        when(sources.get("source")).thenReturn(source);
        var input = new StartMaintenanceRequest("sha", "config", "resume_origin");
        when(store.resumeMaintenance("run", input, true)).thenReturn(
                new MaintenanceSummary("run", "source", "sha", "REPAIRING", "repair", null));
        assertEquals("repair", service.resume("run", input).repairId());
        verify(automation).start("repair", new StartModelCallRequest("fault", "config", "resume_origin", true));
        verify(repairs, never()).resume(anyString(), anyString());
        verifyNoInteractions(probes);
    }

    @Test
    void dnsPreparationEntersOriginRecoveryButOtherSetupFailuresDoNot() {
        when(run.status()).thenReturn("BLOCKED");
        when(run.executionMode()).thenReturn("managed_source");
        when(run.errorCode()).thenReturn("SOURCE_SETUP_DNS_NO_ADDRESS");
        result.remove("managedSourceEvidence");
        result.putObject("sourcePreparation").put("status", "FAILED").put("code", "SOURCE_SETUP_DNS_NO_ADDRESS")
                .put("sourceExecuted", false).put("sourceHttpObserved", false);
        when(repairs.createOriginRecovery("source", new StartRepairRequest("run", "sha"))).thenReturn(repair);
        String original = result.toString();
        service.poll();
        assertEquals("REPAIRING", row.getMaintenanceStatus());
        assertEquals(original, result.toString());
        verify(automation).start("repair", new StartModelCallRequest("fault", "config", "maintenance_run", true, true));
        verify(repairs, never()).create(anyString(), any());
        row.setMaintenanceStatus("CHECKING");
        row.setMaintenanceRepairId(null);
        when(run.errorCode()).thenReturn("SOURCE_SETUP_DNS_NONPUBLIC");
        result.withObject("sourcePreparation").put("code", "SOURCE_SETUP_DNS_NONPUBLIC");
        service.poll();
        assertEquals("STOPPED", row.getMaintenanceStatus());
        assertEquals("SOURCE_SETUP_DNS_NONPUBLIC", row.getMaintenanceStopCode());
        verify(automation, times(1)).start(anyString(), any());
    }

    @Test
    void healthySearchNavigationFinishesWithoutAModelOrAPlayer() {
        result.withArray("nodes").removeAll();
        for (String id : List.of("C05", "C07"))
            result.withArray("nodes").addObject().put("id", id).put("status", "pass")
                    .put("requirement", "required").put("evidenceClass", "host_loader_contract_observed");
        result.withObject("requiredCoverage").put("total", 2).put("pass", 2);
        service.poll();
        verify(store).finishMaintenance("run", "CHECKED", null, null);
        verifyNoInteractions(repairs, automation, client);
    }

    @Test
    void missingCredentialStopsBeforeStartingARepair() {
        when(credentials.blockedReason("source")).thenReturn("SOURCE_CREDENTIALS_REQUIRED");
        service.poll();
        assertEquals("SOURCE_CREDENTIALS_REQUIRED", row.getMaintenanceStopCode());
        verifyNoInteractions(repairs, automation, client);
    }

    @Test
    void missingCredentialResumeDoesNotReviveTheRepairOrWorkflow() {
        row.setMaintenanceStatus("STOPPED");
        row.setMaintenanceRepairId("repair");
        row.setMaintenanceStopCode("REPEATED_FAILURE");
        when(credentials.blockedReason("source")).thenReturn("SOURCE_CREDENTIALS_REQUIRED");
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.resume("run", new StartMaintenanceRequest("sha", "config", "resume")));
        assertEquals("SOURCE_CREDENTIALS_REQUIRED", error.getReason());
        assertEquals("STOPPED", row.getMaintenanceStatus());
        verify(store, never()).resumeMaintenance(anyString(), any(), anyBoolean());
        verify(store, never()).resumeMaintenance(anyString(), any());
        verifyNoInteractions(repairs, automation, client);
    }

    @Test
    void liveDiscoveredFaultLinksRepairBeforeStartingAutomaticWork() {
        service.poll();
        var order = inOrder(store, automation);
        order.verify(store).finishMaintenance("run", "REPAIRING", "repair", null);
        order.verify(automation).start("repair",
                new StartModelCallRequest("fault", "config", "maintenance_run", true));
    }

    private ObjectNode challengeEvidence(String html) {
        String sha = "a".repeat(64), profile = "b".repeat(64), runtime = "c".repeat(64);
        when(run.artifactSha256()).thenReturn(sha);
        when(run.profileSha256()).thenReturn(profile);
        when(run.runtimeSha256()).thenReturn(runtime);
        var evidence = new RepairDiagnosticsTest().evidence(sha, "C03", "CONTENT_LIST_EMPTY_OR_INVALID");
        evidence.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
        AccessPageClassifierTest.attach(mapper, evidence, sha, html);
        result.set("managedSourceEvidence", evidence);
        return evidence;
    }

    @Test
    void confirmedChallengeStopsBeforeCreatingARepairAndKeepsFailureUnchanged() {
        challengeEvidence(AccessPageClassifierTest.CHALLENGE);
        String before = result.toString();
        service.poll();
        verify(store).finishMaintenance("run", "STOPPED", null, "LIVE_INSPECTION_SOURCE_ACCESS_CHALLENGE");
        verifyNoInteractions(repairs, automatic, automation, client, models);
        assertEquals(before, result.toString());
    }

    @Test
    void cloudflareDenialStopsBeforeCreatingRepairAndPreservesFailure() {
        var evidence = challengeEvidence(AccessPageClassifierTest.CF_DENIAL);
        AccessPageClassifierTest.denied(mapper, evidence, "a".repeat(64));
        String before = result.toString();
        service.poll();
        verify(store).finishMaintenance("run", "STOPPED", null, "LIVE_INSPECTION_SOURCE_ACCESS_DENIED");
        verifyNoInteractions(repairs, automatic, automation, client, models);
        assertEquals(before, result.toString());
    }

    @Test
    void cloudflareObservationStopsOnlyTheMatchingActionAndKeepsOriginalFailures() {
        var evidence = challengeEvidence(AccessPageClassifierTest.CF_CHALLENGE);
        AccessPageClassifierTest.observeDuring(mapper, evidence, "detail");
        String before = result.toString();
        service.poll();
        assertEquals("REPAIRING", row.getMaintenanceStatus());
        assertEquals(before, result.toString());
        row.setMaintenanceStatus("CHECKING");
        row.setMaintenanceRepairId(null);
        evidence = challengeEvidence(AccessPageClassifierTest.CF_CHALLENGE);
        AccessPageClassifierTest.observeDuring(mapper, evidence, "category");
        before = result.toString();
        service.poll();
        assertEquals("LIVE_INSPECTION_SOURCE_ACCESS_CHALLENGE", row.getMaintenanceStopCode());
        verify(repairs, times(1)).create(eq("source"), any());
        verify(automation, times(1)).start(eq("repair"), any());
        assertEquals(before, result.toString());
    }

    @Test
    void ordinaryCookiePageOrUnmatchedChallengeDoesNotBlockSourceRepair() {
        for (boolean matched : List.of(true, false)) {
            row.setMaintenanceStatus("CHECKING");
            row.setMaintenanceRepairId(null);
            var evidence = challengeEvidence(matched
                    ? AccessPageClassifierTest.CHALLENGE.replace("Loading...", "Forum topics and magnet downloads")
                    : AccessPageClassifierTest.CHALLENGE);
            if (!matched) evidence.put("bundleSha256", "d".repeat(64));
            String before = result.toString();
            service.poll();
            assertEquals("REPAIRING", row.getMaintenanceStatus());
            assertEquals(before, result.toString());
        }
        verify(automation, times(2)).start(eq("repair"), any());
    }

    @Test
    @EnabledIfSystemProperty(named = "maintenance.challenge-replay", matches = ".+")
    void frozenPlatformChallengeReceiptStopsBeforeRepairWithoutNewExecution() throws Exception {
        var terminal = mapper.readTree(Files.readAllBytes(Path.of(System.getProperty("maintenance.challenge-replay"))));
        var recorded = terminal.path("run");
        assertEquals("COMPLETED", recorded.path("status").asText());
        assertEquals("MODEL_SOURCE_ACCESS_CHALLENGE", terminal.path("task").path("stopCode").asText());
        assertTrue(terminal.path("calls").isEmpty());
        assertTrue(terminal.path("candidates").isEmpty());
        assertEquals("current", recorded.path("freshness").asText());
        when(run.artifactSha256()).thenReturn(recorded.path("artifactSha256").asText());
        when(run.profileSha256()).thenReturn(recorded.path("profileSha256").asText());
        when(run.runtimeSha256()).thenReturn(recorded.path("runtimeSha256").asText());
        result = (ObjectNode) recorded.path("result");
        when(run.result()).thenReturn(result);
        String before = result.toString();
        service.poll();
        verify(store).finishMaintenance("run", "STOPPED", null, "LIVE_INSPECTION_SOURCE_ACCESS_CHALLENGE");
        verifyNoInteractions(repairs, automatic, automation, client, models, probes);
        assertEquals(before, result.toString());
    }

    @Test
    void observedBlockedPlayerDefectsUseTheExistingAutomaticRepairFlow() {
        for (String reason : List.of("PLAYER_URL_EMPTY", "PLAYER_URL_INVALID", "PLAYER_HEADERS_INVALID",
                "PLAYER_OUTPUT_INVALID", "PLAYER_EXTERNAL_PARSER_REQUIRED", "LOADER_METHOD_FAILED")) {
            row.setMaintenanceStatus("CHECKING");
            row.setMaintenanceRepairId(null);
            ((ObjectNode) result.path("nodes").get(0)).put("id", "R01")
                    .put("status", "blocked").put("reason", reason);
            service.poll();
            assertEquals("REPAIRING", row.getMaintenanceStatus(), reason);
        }
        verify(repairs, times(6)).create(eq("source"), any());
        verifyNoInteractions(probes);
    }

    @Test
    void missingHostCapabilitiesAndOptionalBlockedNodesDoNotSpendModelCalls() {
        for (String reason : List.of("EGRESS_BYTE_BUDGET", "LOADER_INIT_FAILED",
                "USER_CONFIGURATION_CONTRACT_NOT_ESTABLISHED", "HLS_KEY_OR_INIT_REQUIRES_ADDITIONAL_ADAPTER")) {
            row.setMaintenanceStatus("CHECKING");
            ((ObjectNode) result.path("nodes").get(0)).put("id", "R01")
                    .put("status", "blocked").put("reason", reason);
            service.poll();
            assertEquals("LIVE_INSPECTION_INCOMPLETE", row.getMaintenanceStopCode());
        }
        row.setMaintenanceStatus("CHECKING");
        ((ObjectNode) result.path("nodes").get(0)).put("reason", "PLAYER_URL_EMPTY").put("requirement", "optional");
        service.poll();
        verifyNoInteractions(repairs, automation, client);
    }

    private ObjectNode metadataDeclaration() {
        String sha = "a".repeat(64);
        result.put("artifactSha256", sha);
        ((ObjectNode) result.path("managedSourceEvidence")).put("inputSourceSha256", sha);
        ((ObjectNode) result.path("nodes").get(0)).put("id", "R01").put("requirement", "required")
                .put("status", "blocked").put("reason", "PLAYER_URL_EMPTY");
        var inspection = result.withArray("nodes").addObject().put("id", "S03").put("status", "pass")
                .put("evidenceClass", "static").putObject("evidence").put("sha256", sha).put("parseOk", true);
        inspection.putArray("issues");
        inspection.putObject("playbackDeclaration").put("kind", "metadata-only")
                .put("authority", "static_literal_player_declaration");
        return inspection;
    }

    @Test
    void liveMetadataOnlySourceStopsWithoutRepairAndKeepsItsFailedPlaybackRequirement() {
        metadataDeclaration();
        service.poll();
        verify(store).finishMaintenance("run", "STOPPED", null, "LIVE_INSPECTION_METADATA_ONLY");
        verifyNoInteractions(repairs, automation, client);
        assertEquals("required", result.path("nodes").get(0).path("requirement").asText());
        assertEquals("blocked", result.path("nodes").get(0).path("status").asText());
        assertEquals(0, result.path("requiredCoverage").path("pass").asInt());
    }

    @Test
    void metadataClassificationRequiresMatchingImmutableSourceAndObservedEmptyPlayer() {
        var inspection = metadataDeclaration();
        assertTrue(RepairReport.playbackNotProvided(result));
        inspection.put("sha256", "b".repeat(64));
        assertFalse(RepairReport.playbackNotProvided(result));
        inspection.put("sha256", "a".repeat(64));
        var player = (ObjectNode) result.path("nodes").get(0);
        for (String field : List.of("requirement", "evidenceClass", "reason", "status")) {
            var original = player.get(field);
            player.put(field, "unverified");
            assertFalse(RepairReport.playbackNotProvided(result), field);
            player.set(field, original);
        }
        var observation = (ObjectNode) result.path("managedSourceEvidence").path("httpObservations").get(0);
        observation.put("responseComplete", false);
        assertFalse(RepairReport.playbackNotProvided(result));
        observation.put("responseComplete", true).put("responseStatus", 403);
        assertFalse(RepairReport.playbackNotProvided(result));
        observation.put("responseStatus", 200);
        ((ObjectNode) result.path("managedSourceEvidence").path("cleanup")).put("confirmed", false);
        assertFalse(RepairReport.playbackNotProvided(result));
    }

    @Test
    void scheduledFaultUsesTheExistingInspectionAndOnlyCreatesOneLinkedRepair() {
        row.setTriggerKind("scheduled");
        when(run.triggerKind()).thenReturn("scheduled");
        service.poll();
        when(automatic.get("repair")).thenReturn(new AutomaticRepairSummary("repair", "ACTIVE", "config", null));
        service.poll();
        verify(repairs, times(1)).create("source", new StartRepairRequest("run", "sha"));
        verify(automation, times(1)).start(eq("repair"), any());
        verifyNoInteractions(probes);
    }

    @Test
    void revokedScheduledPolicyStopsCheckingBeforeCreatingRepairOrCallingModel() {
        row.setTriggerKind("scheduled");
        when(run.triggerKind()).thenReturn("scheduled");
        when(schedules.executionBlockedReason(row)).thenReturn("MANAGED_SCHEDULE_APPROVAL_CHANGED");
        service.poll();
        verify(store).finishMaintenance("run", "STOPPED", null, "MANAGED_SCHEDULE_APPROVAL_CHANGED");
        verify(probes).cancel("run");
        verifyNoInteractions(repairs, automation, client);
    }

    @Test
    void revokedPolicyDuringScheduledRepairCancelsItsLinkedAutomation() {
        row.setTriggerKind("scheduled");
        row.setMaintenanceStatus("REPAIRING");
        row.setMaintenanceRepairId("repair");
        when(run.triggerKind()).thenReturn("scheduled");
        when(schedules.executionBlockedReason(row)).thenReturn("MODEL_SOURCE_NOT_AUTHORIZED");
        service.poll();
        verify(probes).cancel("run");
        verify(automation).cancel("repair");
        verify(automation, never()).start(anyString(), any());
        verifyNoInteractions(repairs, client);
    }

    @Test
    void fullyCoveredLiveSelectionDoesNotCallModel() {
        ((ObjectNode) result.path("nodes").get(0)).put("status", "pass");
        ((ObjectNode) result.path("requiredCoverage")).put("pass", 1);
        service.poll();
        verify(store).finishMaintenance("run", "CHECKED", null, null);
        verifyNoInteractions(repairs, automation, client);
    }

    @Test
    void offlineResolverDependencyStopsWithoutTryingToRewriteTheSpider() {
        ((ObjectNode) result.path("nodes").get(0)).put("id", "C07").put("status", "pass");
        result.withArray("nodes").addObject().put("id", "R01").put("status", "blocked")
                .put("reason", "PLAYER_OFFLINE_RESOLVER_REQUIRED")
                .put("evidenceClass", "host_loader_contract_observed");
        service.poll();
        verify(store).finishMaintenance("run", "STOPPED", null, "LIVE_INSPECTION_OFFLINE_RESOLVER_REQUIRED");
        verifyNoInteractions(repairs, automation, client);
    }

    @Test
    void unrelatedOfflineDependencyDoesNotHideAnActualDiscoveredSourceFault() {
        result.withArray("nodes").addObject().put("id", "R01").put("status", "blocked")
                .put("reason", "PLAYER_OFFLINE_RESOLVER_REQUIRED")
                .put("evidenceClass", "host_loader_contract_observed");
        service.poll();
        verify(automation).start(eq("repair"), any());
    }

    @Test
    void policyLimitationNeverBecomesAnAutomaticSourceRepair() {
        ((ObjectNode) result.path("managedSourceEvidence")).putArray("deniedCodes").add("EGRESS_URL_DENIED");
        service.poll();
        verify(store).finishMaintenance("run", "STOPPED", null, "LIVE_INSPECTION_POLICY_LIMITED");
        verifyNoInteractions(repairs, automation, client);
    }

    @Test
    void unconfirmedCleanupStopsBeforeModel() {
        ((ObjectNode) result.path("managedSourceEvidence").path("cleanup")).put("confirmed", false);
        service.poll();
        verify(store).finishMaintenance("run", "STOPPED", null, "MANAGED_CLEANUP_UNCONFIRMED");
        verifyNoInteractions(repairs, automation);
    }

    @Test
    void sourceOutputOrStaticPassWithoutCompleteHttpCannotTriggerRepair() {
        ((ObjectNode) result.path("managedSourceEvidence")).putArray("httpObservations");
        service.poll();
        verify(store).finishMaintenance("run", "STOPPED", null, "LIVE_INSPECTION_RESPONSE_MISSING");
        verifyNoInteractions(repairs, automation);
    }

    @Test
    void networkFailureIsNotPolicyDenialOrASourceFault() {
        ((ObjectNode) result.path("managedSourceEvidence")).putArray("deniedCodes").add("EGRESS_HTTP_FAILED");
        service.poll();
        verify(store).finishMaintenance("run", "STOPPED", null, "LIVE_INSPECTION_NETWORK_UNAVAILABLE");
        verifyNoInteractions(repairs, automation, client);
    }

    @Test
    void handledHomeChildFailureDoesNotHideAnIndependentLiveCategoryFault() {
        var evidence = (ObjectNode) result.path("managedSourceEvidence");
        evidence.withArray("deniedCodes").add("EGRESS_NONPUBLIC_DNS");
        evidence.putArray("networkFailureCalls").addObject().put("denialIndex", 0).put("method", "home");
        var loader = evidence.putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION);
        loader.putArray("nodes").addObject().put("id", "C01").put("status", "pass");
        service.poll();
        verify(automation).start(eq("repair"), any());
        assertEquals(1, evidence.path("deniedCodes").size());
    }

    @Test
    void networkFailureInAnUnpassedActionStillPausesBeforeTheModel() {
        var evidence = (ObjectNode) result.path("managedSourceEvidence");
        evidence.withArray("deniedCodes").add("EGRESS_NONPUBLIC_DNS");
        evidence.putArray("networkFailureCalls").addObject().put("denialIndex", 0).put("method", "category");
        var loader = evidence.putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION);
        loader.putArray("nodes").addObject().put("id", "C03").put("status", "fail");
        service.poll();
        verify(store).finishMaintenance("run", "STOPPED", null, "LIVE_INSPECTION_NETWORK_UNAVAILABLE");
        verifyNoInteractions(repairs, automation, client);
    }

    @Test
    void recordedComplete403StopsBeforeCreatingRepairOrCallingModel() {
        ((ObjectNode) result.path("managedSourceEvidence").path("httpObservations").get(0))
                .put("responseStatus", 403).put("responseBytes", 6023);
        service.poll();
        verify(store).finishMaintenance("run", "STOPPED", null, "LIVE_INSPECTION_HTTP_ACCESS_DENIED");
        verifyNoInteractions(repairs, automation, client);
    }

    @Test
    void recordedRedirectThen403StopsBeforeCreatingRepairOrCallingModel() {
        var http = ((ObjectNode) result.path("managedSourceEvidence")).putArray("httpObservations");
        http.addObject().put("responseComplete", true).put("responseStatus", 301).put("responseBytes", 167);
        http.addObject().put("responseComplete", true).put("responseStatus", 403).put("responseBytes", 5657);
        service.poll();
        verify(store).finishMaintenance("run", "STOPPED", null, "LIVE_INSPECTION_HTTP_ACCESS_DENIED");
        verifyNoInteractions(repairs, automation, client);
    }

    @Test
    void redirectsDoNotHideSuccessServerFailuresIncompleteResponsesOrMissingFinalResponses() throws Exception {
        var mapper = new ObjectMapper();
        for (String responses : List.of(
                "[{\"responseComplete\":true,\"responseStatus\":301}]",
                "[{\"responseComplete\":true,\"responseStatus\":301},{\"responseComplete\":true,\"responseStatus\":500}]",
                "[{\"responseComplete\":true,\"responseStatus\":302},{\"responseComplete\":false,\"responseStatus\":403}]",
                "[{\"responseComplete\":true,\"responseStatus\":307},{\"responseComplete\":true,\"responseStatus\":200},"
                        + "{\"responseComplete\":true,\"responseStatus\":403}]",
                "[{\"responseComplete\":true,\"responseStatus\":304},{\"responseComplete\":true,\"responseStatus\":401}]"))
            assertFalse(RepairReport.httpAccessDenied(mapper.readTree("{\"httpObservations\":" + responses + "}")));
        for (int status : List.of(301, 302, 303, 307, 308)) {
            var evidence = mapper.createObjectNode();
            var http = evidence.putArray("httpObservations");
            http.addObject().put("responseComplete", true).put("responseStatus", status);
            http.addObject().put("responseComplete", true).put("responseStatus", 403);
            assertTrue(RepairReport.httpAccessDenied(evidence));
        }
    }

    @Test
    void mixedSuccessfulResponsesDoNotBlanketBlockSourceRepair() {
        ((ObjectNode) result.path("managedSourceEvidence")).withArray("httpObservations")
                .addObject().put("responseComplete", true).put("responseStatus", 403).put("responseBytes", 10);
        service.poll();
        verify(automation).start(eq("repair"), any());
    }

    @Test
    void accessClassificationRequiresCompleteDenialsAndDoesNotGuessWafFromOtherErrors() throws Exception {
        var mapper = new ObjectMapper();
        for (String responses : List.of("[]", "[{\"responseComplete\":false,\"responseStatus\":403}]",
                "[{\"responseComplete\":true,\"responseStatus\":404}]",
                "[{\"responseComplete\":true,\"responseStatus\":500}]",
                "[{\"responseComplete\":true,\"responseStatus\":\"403\"}]")) {
            assertFalse(RepairReport.httpAccessDenied(mapper.readTree("{\"httpObservations\":" + responses + "}")));
        }
        assertTrue(RepairReport.httpAccessDenied(mapper.readTree("""
                {"httpObservations":[{"responseComplete":true,"responseStatus":401},
                 {"responseComplete":true,"responseStatus":403}]}
                """)));
    }

    @Test
    void networkResumeValidatesExistingRepairBeforeRestartingAutomation() {
        row.setMaintenanceStatus("STOPPED"); row.setMaintenanceRepairId("repair");
        row.setMaintenanceStopCode("RUNTIME_NETWORK_UNAVAILABLE");
        when(run.freshness()).thenReturn("runtime_changed");
        var source = mock(SourceSummary.class);
        when(source.artifactSha256()).thenReturn("sha");
        when(sources.get("source")).thenReturn(source);
        when(repairs.resume("repair", "fault")).thenReturn(repair);
        var input = new StartMaintenanceRequest("sha", "config", "network_resume");
        when(store.resumeMaintenance("run", input, true)).thenReturn(
                new MaintenanceSummary("run", "source", "sha", "REPAIRING", "repair", null));
        assertEquals("REPAIRING", service.resume("run", input).status());
        var order = inOrder(repairs, store, automation);
        order.verify(repairs).resume("repair", "fault");
        order.verify(store).resumeMaintenance("run", input, true);
        order.verify(automation).start(eq("repair"), any());
        verifyNoInteractions(probes);
    }

    @Test
    void networkResumeCannotBypassCurrentRepairOrChangedSourceValidation() {
        row.setMaintenanceStatus("STOPPED"); row.setMaintenanceRepairId("repair");
        row.setMaintenanceStopCode("ROUND_LIMIT");
        when(run.freshness()).thenReturn("runtime_changed");
        var source = mock(SourceSummary.class);
        when(source.artifactSha256()).thenReturn("sha");
        when(sources.get("source")).thenReturn(source);
        var input = new StartMaintenanceRequest("sha", "config", "network_resume");
        var stale = new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "REPAIR_INPUTS_CHANGED");
        when(repairs.resume("repair", "fault")).thenThrow(stale);
        assertEquals("REPAIR_INPUTS_CHANGED", assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.resume("run", input)).getReason());
        when(source.artifactSha256()).thenReturn("changed");
        assertEquals("MAINTENANCE_INPUTS_CHANGED", assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.resume("run", input)).getReason());
        when(source.artifactSha256()).thenReturn("sha");
        row.setMaintenanceStopCode("MODEL_DAILY_BUDGET");
        assertEquals("MAINTENANCE_INPUTS_CHANGED", assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.resume("run", input)).getReason());
        verify(repairs, times(1)).resume("repair", "fault");
        verify(store, never()).resumeMaintenance(anyString(), any(), anyBoolean());
        verifyNoInteractions(automation, probes);
    }

    @Test
    void hardcodedCategoriesAndHttp200DoNotConcludeBusinessSuccess() {
        ((ObjectNode) result.path("nodes").get(0)).put("id", "C01").put("status", "pass");
        ((ObjectNode) result.path("requiredCoverage")).put("pass", 1);
        service.poll();
        verify(store).finishMaintenance("run", "STOPPED", null, "LIVE_INSPECTION_INCOMPLETE");
    }

    @Test
    void restartWithLinkedRepairNeverCreatesAnotherRepairOrProbe() {
        row.setMaintenanceStatus("REPAIRING"); row.setMaintenanceRepairId("repair");
        service.poll();
        verify(automation).start(eq("repair"), any());
        verify(repairs, never()).create(anyString(), any());
        verifyNoInteractions(probes);
    }

    @Test
    void existingAutomaticSuccessIsObservedWithoutCallingModelAgain() {
        row.setMaintenanceStatus("REPAIRING"); row.setMaintenanceRepairId("repair");
        when(automatic.get("repair")).thenReturn(new AutomaticRepairSummary("repair", "SUCCEEDED", "config", null));
        service.poll();
        verify(store).finishMaintenance("run", "SUCCEEDED", "repair", null);
        verifyNoInteractions(automation, repairs, client);
    }

    @Test
    void configurationChangeBeforeRepairStopsTheWorkflow() {
        when(client.connection().configurationSha256()).thenReturn("changed");
        service.poll();
        verify(store).finishMaintenance("run", "STOPPED", null, "MODEL_CONFIGURATION_CHANGED");
        verifyNoInteractions(repairs, automation);
    }

    @Test
    void cancellationStopsTheLinkedAutomaticTaskAndOriginalProbe() {
        row.setMaintenanceStatus("REPAIRING"); row.setMaintenanceRepairId("repair");
        assertEquals("CANCELLED", service.cancel("run").status());
        verify(probes).cancel("run");
        verify(automation).cancel("repair");
    }

    @Test
    void failureAfterLinkingRetainsTheExactRepairForDiagnosis() {
        doThrow(new IllegalStateException()).when(automation).start(anyString(), any());
        service.poll();
        verify(store).finishMaintenance("run", "STOPPED", "repair", "MAINTENANCE_INTERNAL_ERROR");
    }

    @Test
    void explicitBudgetResumeReusesOriginalReportAndRepairWithoutStartingProbe() {
        row.setMaintenanceStatus("STOPPED"); row.setMaintenanceRepairId("repair");
        var source = mock(SourceSummary.class);
        when(source.artifactSha256()).thenReturn("sha");
        when(sources.get("source")).thenReturn(source);
        var input = new StartMaintenanceRequest("sha", "config", "resume_key");
        when(store.resumeMaintenance("run", input)).thenReturn(
                new MaintenanceSummary("run", "source", "sha", "REPAIRING", "repair", null));
        assertEquals("REPAIRING", service.resume("run", input).status());
        verify(automation).start("repair", new StartModelCallRequest("fault", "config", "resume_key", true));
        verifyNoInteractions(probes);
    }

    @Test
    void newEvidenceContinuationUsesTheSameWorkflowAndCannotSkipCredentialChecks() {
        row.setMaintenanceStatus("STOPPED"); row.setMaintenanceRepairId("repair");
        row.setMaintenanceStopCode("REPEATED_FAILURE");
        var source = mock(SourceSummary.class);
        when(source.artifactSha256()).thenReturn("sha");
        when(sources.get("source")).thenReturn(source);
        when(repair.baselineArtifactSha256()).thenReturn("sha");
        when(repair.freshness()).thenReturn("current");
        when(repair.status()).thenReturn("AWAITING_PROPOSAL");
        when(repair.usedRounds()).thenReturn(3); when(repair.maxRounds()).thenReturn(4);
        var frozen = mapper.createObjectNode();
        frozen.putArray("evidenceContinuations").addObject().put("afterUsedRounds", 3);
        when(repair.frozen()).thenReturn(frozen);
        var input = new ContinueMaintenanceEvidenceRequest("sha", "config", "fault", "candidate", "material", "grant_key");
        when(repairs.continueWithEvidence("repair", input)).thenReturn(repair);
        var resumed = new MaintenanceSummary("run", "source", "sha", "REPAIRING", "repair", null);
        when(store.resumeMaintenance("run", new StartMaintenanceRequest("sha", "config", "grant_key"), true))
                .thenReturn(resumed);
        assertEquals(resumed, service.continueWithEvidence("run", input));
        var order = inOrder(repairs, store, automation);
        order.verify(repairs).continueWithEvidence("repair", input);
        order.verify(store).resumeMaintenance("run", new StartMaintenanceRequest("sha", "config", "grant_key"), true);
        order.verify(automation).start("repair", new StartModelCallRequest("fault", "config", "grant_key", true, true));
        verifyNoInteractions(probes);
        when(credentials.blockedReason("source")).thenReturn("SOURCE_CREDENTIALS_REQUIRED");
        assertEquals("SOURCE_CREDENTIALS_REQUIRED", assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.continueWithEvidence("run", input)).getReason());
        verify(repairs, times(1)).continueWithEvidence("repair", input);
    }

    @Test
    void repeatedWorkflowResumeKeepsThePersistedEnhancedMode() {
        row.setMaintenanceStatus("REPAIRING"); row.setMaintenanceRepairId("repair");
        var source = mock(SourceSummary.class);
        when(source.artifactSha256()).thenReturn("sha");
        when(sources.get("source")).thenReturn(source);
        when(automatic.get("repair")).thenReturn(new AutomaticRepairSummary("repair", "ACTIVE", "config", null, true));
        var input = new StartMaintenanceRequest("sha", "config", "resume_key");
        when(store.resumeMaintenance("run", input)).thenReturn(
                new MaintenanceSummary("run", "source", "sha", "REPAIRING", "repair", null));
        service.resume("run", input);
        service.resume("run", input);
        verify(automation, times(2)).start("repair", new StartModelCallRequest("fault", "config", "resume_key", true, true));
        verifyNoInteractions(probes);
    }
}
