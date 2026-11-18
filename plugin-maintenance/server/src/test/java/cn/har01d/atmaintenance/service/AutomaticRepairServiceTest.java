package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AutomaticRepairServiceTest {
    private final AutomaticRepairStore store = mock(AutomaticRepairStore.class);
    private final RepairStore repairs = mock(RepairStore.class);
    private final RepairService validation = mock(RepairService.class);
    private final ModelStore models = mock(ModelStore.class);
    private final ModelService generation = mock(ModelService.class);
    private final ModelSubmissionService submissions = mock(ModelSubmissionService.class);
    private final ModelClient client = mock(ModelClient.class);
    private final SourceCredentialStore credentials = mock(SourceCredentialStore.class);
    private final RepairDiagnostics diagnostics = mock(RepairDiagnostics.class);
    private final OriginDiscoveryService originDiscovery = mock(OriginDiscoveryService.class);
    private final AutomaticPublicationService publications = mock(AutomaticPublicationService.class);
    private final AutomaticRepairService service = new AutomaticRepairService(
            store, repairs, validation, models, generation, submissions, client, credentials, diagnostics, originDiscovery, publications);
    private final RepairSummary repair = mock(RepairSummary.class);
    private final RepairCandidateSummary candidate = mock(RepairCandidateSummary.class);

    @BeforeEach
    void prepare() {
        when(store.active()).thenReturn(List.of("repair"));
        when(store.get("repair")).thenReturn(new AutomaticRepairSummary("repair", "ACTIVE", "config", null));
        when(repairs.get("repair")).thenReturn(repair);
        when(repair.status()).thenReturn("AWAITING_PROPOSAL");
        when(repair.freshness()).thenReturn("current");
        when(repair.sourceId()).thenReturn("source");
        when(repair.baselineArtifactSha256()).thenReturn("sha");
        when(repair.faultSha256()).thenReturn("fault");
        when(repair.frozen()).thenReturn(new ObjectMapper().createObjectNode());
        var connection = mock(ModelConnection.class);
        when(client.connection()).thenReturn(connection);
        when(connection.configurationSha256()).thenReturn("config");
        when(connection.autoSubmitEnabled()).thenReturn(true);
        when(models.consent("source")).thenReturn(new ModelConsent("source", "sha"));
        when(models.repairCalls("repair")).thenReturn(List.of());
        when(repairs.candidates("repair")).thenReturn(List.of(candidate));
        when(candidate.id()).thenReturn("candidate");
        when(candidate.sha256()).thenReturn("candidate-sha");
    }

    @Test
    void successfulAutomaticRepairHandsOffToPersistentPublicationBeforeFinishing() {
        when(repair.status()).thenReturn("RUNTIME_OBSERVED");
        when(candidate.result()).thenReturn(new ObjectMapper().createObjectNode().put("repairSuccessful", true));
        service.poll();
        var order = inOrder(publications, store);
        order.verify(publications).repairSucceeded("repair");
        order.verify(store).finish("repair", "SUCCEEDED", null);
        verifyNoInteractions(generation, validation, submissions);
        when(candidate.result()).thenReturn(new ObjectMapper().createObjectNode().put("repairSuccessful", false));
        clearInvocations(publications);
        service.poll();
        verifyNoInteractions(publications);
    }

    @Test
    void dnsRecoveryWaitsForPersistentOriginEvidenceBeforeRepairCalls() {
        var frozen = new ObjectMapper().createObjectNode();
        frozen.putObject("policy").put("scope", "managed_origin_candidate");
        when(repair.frozen()).thenReturn(frozen);
        when(originDiscovery.start("repair", "fault"))
                .thenReturn(new ObjectMapper().createObjectNode().put("state", "WAITING_MODEL"));
        service.poll();
        service.poll();
        verify(originDiscovery, times(2)).start("repair", "fault");
        verifyNoInteractions(generation, validation);
        verify(store, never()).finish(anyString(), anyString(), any());
        when(originDiscovery.start("repair", "fault"))
                .thenReturn(new ObjectMapper().createObjectNode().put("state", "BLOCKED")
                        .put("errorCode", "ORIGIN_SITE_IDENTITY_OR_PATH_UNCONFIRMED"));
        service.poll();
        verify(store).finish("repair", "STOPPED", "ORIGIN_SITE_IDENTITY_OR_PATH_UNCONFIRMED");
        when(originDiscovery.hasVerifiedRoute(repair)).thenReturn(true);
        service.poll();
        verify(generation).propose("repair", new StartModelCallRequest("fault", "config", "automatic_0_0", true));
    }

    @Test
    void authenticationFailureStopsBeforeSpendingAnotherModelCall() {
        when(credentials.blockedReason("source")).thenReturn("SOURCE_AUTHENTICATION_REJECTED");
        service.poll();
        verify(store).finish("repair", "STOPPED", "SOURCE_AUTHENTICATION_REJECTED");
        verifyNoInteractions(generation, validation);
    }

    @Test
    void accessDenialAutomaticallyQueuesOriginDiscoveryInsteadOfRepeatingRepairModel() {
        when(generation.propose(anyString(), any())).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "MODEL_SOURCE_ACCESS_DENIED"));
        service.poll();
        verify(originDiscovery).start("repair", "fault");
        verify(store).finish("repair", "STOPPED", "ORIGIN_DISCOVERY_QUEUED");
        verifyNoInteractions(validation);
    }

    @Test
    void staticCompletionAutomaticallyStartsFrozenRuntime() throws Exception {
        when(repair.status()).thenReturn("AWAITING_RUNTIME_VALIDATION");
        when(repair.frozen()).thenReturn(new ObjectMapper().readTree(
                "{\"policy\":{\"scope\":\"managed_loader_candidate\"}}"));
        service.poll();
        verify(validation).validateRuntime("candidate", "candidate-sha");
        verifyNoInteractions(generation);
    }

    @Test
    void failedRuntimeRequestsNextModelUsingPersistentIdentity() {
        when(repair.usedRounds()).thenReturn(1);
        service.poll();
        service.poll();
        verify(generation, times(2)).propose("repair",
                new StartModelCallRequest("fault", "config", "automatic_0_1", true));
    }

    @Test
    void completedRuntimeRequiresBusinessSuccess() throws Exception {
        when(repair.status()).thenReturn("RUNTIME_OBSERVED");
        when(candidate.result()).thenReturn(new ObjectMapper().readTree("{\"repairSuccessful\":true}"));
        service.poll();
        verify(store).finish("repair", "SUCCEEDED", null);
        verifyNoInteractions(generation, validation);
    }

    @Test
    void interruptedModelIsNeverSentAgain() {
        var call = mock(ModelCallSummary.class);
        when(call.status()).thenReturn("INTERRUPTED");
        when(call.errorCode()).thenReturn("MODEL_RESTARTED_OUTCOME_UNKNOWN");
        when(models.repairCalls("repair")).thenReturn(List.of(call));
        service.poll();
        verify(store).finish("repair", "STOPPED", "MODEL_RESTARTED_OUTCOME_UNKNOWN");
        verifyNoInteractions(generation, validation);
    }

    @Test
    void explicitResponseRecoveryUsesRequestIdentityAndDoesNotResetHistory() {
        var call = mock(ModelCallSummary.class);
        when(call.status()).thenReturn("FAILED");
        when(call.errorCode()).thenReturn("MODEL_RESPONSE_INVALID");
        when(models.repairCalls("repair")).thenReturn(List.of(call));
        when(store.get("repair")).thenReturn(
                new AutomaticRepairSummary("repair", "STOPPED", "config", "MODEL_RESPONSE_INVALID"));
        var input = new StartModelCallRequest("fault", "config", "resume_response", true);
        service.start("repair", input);
        service.start("repair", input);
        verify(generation, times(2)).propose("repair", input);
        verify(store, times(2)).start("repair", "config", false);
        verifyNoInteractions(validation, submissions);
    }

    @Test
    void explicitHttpRecoveryPreservesEnhancedModeButPollingDoesNotReviveTheFailure() {
        var call = mock(ModelCallSummary.class);
        when(call.status()).thenReturn("FAILED");
        when(call.errorCode()).thenReturn("MODEL_HTTP_ERROR");
        when(call.enhancedDiagnostics()).thenReturn(true);
        when(models.repairCalls("repair")).thenReturn(List.of(call));
        service.poll();
        verify(store).finish("repair", "STOPPED", "MODEL_HTTP_ERROR");
        verifyNoInteractions(generation);
        when(store.get("repair")).thenReturn(new AutomaticRepairSummary(
                "repair", "STOPPED", "config", "MODEL_HTTP_ERROR"));
        service.start("repair", new StartModelCallRequest("fault", "config", "resume_http", true));
        verify(generation).propose("repair", new StartModelCallRequest("fault", "config", "resume_http", true, true));
        verify(store).start("repair", "config", true);
        verifyNoInteractions(validation, submissions);
    }

    @Test
    void failedResponseDoesNotAutomaticallyRetryAndActiveResumeDoesNotReserveAgain() {
        var call = mock(ModelCallSummary.class);
        when(call.status()).thenReturn("FAILED");
        when(call.errorCode()).thenReturn("MODEL_RESPONSE_PROPOSAL_SCHEMA_INVALID");
        when(models.repairCalls("repair")).thenReturn(List.of(call));
        service.poll();
        verify(store).finish("repair", "STOPPED", "MODEL_RESPONSE_PROPOSAL_SCHEMA_INVALID");
        service.start("repair", new StartModelCallRequest("fault", "config", "resume_response", true));
        verifyNoInteractions(generation, validation);
    }

    @Test
    void responseRecoveryStillUsesExistingCallBudget() {
        var call = mock(ModelCallSummary.class);
        when(call.status()).thenReturn("FAILED");
        when(call.errorCode()).thenReturn("MODEL_RESPONSE_OUTPUT_TRUNCATED");
        when(models.repairCalls("repair")).thenReturn(List.of(call));
        when(store.get("repair")).thenReturn(
                new AutomaticRepairSummary("repair", "STOPPED", "config", "MODEL_RESPONSE_OUTPUT_TRUNCATED"));
        when(generation.propose(eq("repair"), any())).thenThrow(
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "MODEL_FAULT_CALL_LIMIT"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.start("repair", new StartModelCallRequest("fault", "config", "resume_response", true)));
        verify(store, never()).start(anyString(), anyString(), anyBoolean());
    }

    @Test
    void changedConfigurationStopsBeforeRuntimeOrModel() {
        when(client.connection().configurationSha256()).thenReturn("changed");
        service.poll();
        verify(store).finish("repair", "STOPPED", "MODEL_CONFIGURATION_CHANGED");
        verifyNoInteractions(generation, validation);
    }

    @Test
    void committedSubmissionWinningSnapshotRaceIsReconciledNextPoll() {
        when(generation.propose(eq("repair"), any())).thenThrow(
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "REPAIR_NOT_ACCEPTING_PROPOSALS"));
        service.poll();
        verify(store, never()).finish(anyString(), anyString(), any());
    }

    @Test
    void enhancedModeSurvivesPollingAndKeepsTheOriginalRoundIdentity() {
        when(store.get("repair")).thenReturn(new AutomaticRepairSummary("repair", "ACTIVE", "config", null, true));
        when(repair.usedRounds()).thenReturn(2);
        service.poll();
        verify(generation).propose("repair",
                new StartModelCallRequest("fault", "config", "automatic_0_2", true, true));
    }

    @Test
    void networkPauseUsesSameCandidateRecoveryWithoutAnotherModelCall() {
        when(repair.status()).thenReturn("BLOCKED");
        when(repair.stopCode()).thenReturn("RUNTIME_NETWORK_UNAVAILABLE");
        service.poll();
        verify(validation).retryAutomaticRuntime("repair");
        verifyNoInteractions(generation, submissions);
        verify(store, never()).finish(anyString(), anyString(), any());
    }

    @Test
    void exhaustedNetworkRecoveryStopsAndNeverRestartsCancelledRepairs() {
        when(repair.status()).thenReturn("BLOCKED");
        when(repair.stopCode()).thenReturn("RUNTIME_NETWORK_UNAVAILABLE");
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "RUNTIME_NETWORK_UNAVAILABLE"))
                .when(validation).retryAutomaticRuntime("repair");
        service.poll();
        verify(store).finish("repair", "STOPPED", "RUNTIME_NETWORK_UNAVAILABLE");
        when(repair.status()).thenReturn("CANCELLED");
        when(repair.stopCode()).thenReturn("USER_CANCELLED");
        service.poll();
        verify(validation, times(1)).retryAutomaticRuntime("repair");
        verify(store).finish("repair", "STOPPED", "USER_CANCELLED");
        verifyNoInteractions(generation);
    }

    @Test
    void observedPlayerFailureAutomaticallyEnhancesTheNextExistingRound() {
        when(repair.usedRounds()).thenReturn(1);
        when(diagnostics.automaticEnhancement(repair, List.of(candidate))).thenReturn(true);
        service.poll();
        service.poll();
        verify(generation, times(2)).propose("repair",
                new StartModelCallRequest("fault", "config", "automatic_0_1", true, true));
        verify(store, never()).start(anyString(), anyString(), anyBoolean());
    }

    @Test
    void unexpectedPreparationFailureStopsWithoutStartingAnotherRound() {
        when(diagnostics.automaticEnhancement(repair, List.of(candidate)))
                .thenThrow(new IllegalStateException("private-provider-marker"));
        service.poll();
        verify(store).finish("repair", "STOPPED", "AUTOMATIC_REPAIR_INTERNAL_ERROR");
        verifyNoInteractions(generation, validation);
    }

    @Test
    void observedDetailFailureSelectsEnhancedEvidenceThroughTheActualDiagnosticDecision() throws Exception {
        var mapper = new ObjectMapper();
        String sha = "a".repeat(64), next = "d".repeat(64), profile = "b".repeat(64), runtime = "c".repeat(64);
        when(repair.id()).thenReturn("repair");
        when(repair.baselineArtifactSha256()).thenReturn(sha);
        when(models.consent("source")).thenReturn(new ModelConsent("source", sha));
        when(repair.maxRounds()).thenReturn(3);
        when(repair.usedRounds()).thenReturn(1);
        var frozen = mapper.createObjectNode();
        frozen.putObject("baseline").put("runtimeSha256", runtime).put("profileSha256", profile);
        when(repair.frozen()).thenReturn(frozen);
        when(candidate.repairId()).thenReturn("repair");
        when(candidate.roundNumber()).thenReturn(1);
        when(candidate.sha256()).thenReturn(next);
        var result = mapper.createObjectNode().put("artifactSha256", next).put("runtimeRequested", true);
        var evidence = result.putObject("runtimeEvidence").put("inputSourceSha256", next)
                .put("bundleSha256", runtime);
        evidence.putArray("deniedCodes");
        evidence.putArray("httpObservations").addObject().put("responseComplete", true).put("responseStatus", 200);
        var loader = evidence.putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION)
                .put("inputSourceSha256", next).put("inputProfileSha256", profile);
        loader.putArray("nodes").addObject().put("id", "C07").put("status", "fail")
                .put("reason", "DETAIL_IDENTITY_MISMATCH");
        when(candidate.result()).thenReturn(result);
        var actualDiagnostics = new RepairDiagnostics(mapper);
        assertTrue(actualDiagnostics.forRepair(repair, List.of(candidate))
                .path("automaticEnhancementRecommended").asBoolean());
        new AutomaticRepairService(store, repairs, validation, models, generation, submissions,
                client, credentials, actualDiagnostics, originDiscovery, publications).poll();
        verify(generation).propose("repair",
                new StartModelCallRequest("fault", "config", "automatic_0_1", true, true));
        verifyNoInteractions(validation, submissions);
    }

    @Test
    void enhancedStartPersistsModeWithoutResettingBudgetsOrCreatingAnotherRepair() {
        when(store.get("repair")).thenReturn(new AutomaticRepairSummary("repair", "DISABLED", null, null));
        service.start("repair", new StartModelCallRequest("fault", "config", "enhanced-start", true, true));
        verify(store).start("repair", "config", true);
        verifyNoInteractions(generation, validation, submissions);
    }
}
