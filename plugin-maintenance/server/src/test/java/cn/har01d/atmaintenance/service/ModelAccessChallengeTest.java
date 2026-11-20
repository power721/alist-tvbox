package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.*;
import cn.har01d.atmaintenance.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelAccessChallengeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RepairDiagnosticsTest fixtures = new RepairDiagnosticsTest();
    private final ModelBudgetRepository budgets = mock(ModelBudgetRepository.class);
    private final ModelCallRepository calls = mock(ModelCallRepository.class);
    private final RepairStore repairs = mock(RepairStore.class);
    private final SourceService sources = mock(SourceService.class);
    private final ModelClient client = mock(ModelClient.class);
    private final ModelConsentRepository consents = mock(ModelConsentRepository.class);
    private final ModelBudget budget = new ModelBudget();
    private final ModelStore store = new ModelStore(budgets, mock(ModelSourceBudgetRepository.class),
            mock(ModelNamedBudgetRepository.class), calls, mock(RepairCandidateRepository.class), consents,
            repairs, sources, client, mock(RepairPatch.class), new RepairDiagnostics(mapper), mapper, Clock.systemUTC());

    private RepairSummary prepare() {
        return prepare(AccessPageClassifierTest.CHALLENGE);
    }

    private RepairSummary prepare(String html) {
        String sha = "a".repeat(64);
        var evidence = fixtures.evidence(sha, "C03", "CONTENT_LIST_EMPTY_OR_INVALID");
        AccessPageClassifierTest.attach(mapper, evidence, sha, html);
        var repair = fixtures.repair(evidence);
        when(repair.status()).thenReturn("AWAITING_PROPOSAL");
        when(repair.faultSha256()).thenReturn("f".repeat(64));
        when(repair.sourceId()).thenReturn("source");
        when(repairs.get("repair")).thenReturn(repair);
        var consent = new ModelSourceConsent();
        consent.setArtifactSha256(sha);
        when(consents.findById("source")).thenReturn(Optional.of(consent));
        var config = mock(ModelConnection.class);
        when(config.configurationSha256()).thenReturn("config");
        when(client.connection()).thenReturn(config);
        when(budgets.lock()).thenReturn(Optional.of(budget));
        when(budgets.findById(1)).thenReturn(Optional.of(budget));
        return repair;
    }

    @Test
    void ordinaryAndEnhancedCallsStopBeforeReservationBrowserWorkAndTransport() throws Exception {
        var repair = prepare();
        var reverse = mock(ReverseAnalysis.class);
        var service = new ModelService(store, client, mock(ModelSubmissionService.class), reverse);
        try {
            for (boolean enhanced : List.of(false, true)) {
                var request = new StartModelCallRequest(repair.faultSha256(), "config", "challenge-" + enhanced,
                        false, enhanced);
                var error = assertThrows(ResponseStatusException.class, () -> service.propose("repair", request));
                assertEquals("MODEL_SOURCE_ACCESS_CHALLENGE", error.getReason());
            }
            assertEquals(0, budget.getUsedCalls());
            assertEquals(0, budget.getReservedInputBytes());
            assertEquals(0, budget.getReservedOutputTokens());
            assertNull(budget.getActiveCallId());
            verify(calls, never()).saveAndFlush(any());
            verifyNoInteractions(reverse);
            verify(client, never()).request(any(), any(), any(), anyBoolean(), any());
            verify(client, never()).generate(anyString());
        } finally { service.close(); }
    }

    @Test
    void cloudflareDenialStopsModelAndBrowserBeforeReservationButNotUnrelatedFaults() throws Exception {
        var repair = prepare(AccessPageClassifierTest.CF_DENIAL);
        var evidence = (com.fasterxml.jackson.databind.node.ObjectNode) repair.frozen()
                .path("baseline").path("result").path("managedSourceEvidence");
        AccessPageClassifierTest.denied(mapper, evidence, repair.baselineArtifactSha256());
        var reverse = mock(ReverseAnalysis.class);
        var service = new ModelService(store, client, mock(ModelSubmissionService.class), reverse);
        try {
            for (boolean enhanced : List.of(false, true)) {
                var request = new StartModelCallRequest(repair.faultSha256(), "config", "denial-" + enhanced, false, enhanced);
                assertEquals("MODEL_SOURCE_ACCESS_DENIED", assertThrows(ResponseStatusException.class,
                        () -> service.propose("repair", request)).getReason());
            }
            AccessPageClassifierTest.observeDuring(mapper, evidence, "detail");
            assertSame(repair, store.reverseInputs("repair",
                    new StartModelCallRequest(repair.faultSha256(), "config", "unrelated", false, true)));
            assertEquals(0, budget.getUsedCalls());
            assertNull(budget.getActiveCallId());
            verify(calls, never()).saveAndFlush(any());
            verifyNoInteractions(reverse);
            verify(client, never()).generate(anyString());
        } finally { service.close(); }
    }

    @Test
    void oldIdempotentCallIsReturnedInsteadOfBeingReclassifiedOrResent() {
        var repair = prepare();
        var previous = new ModelCall();
        previous.setConfigurationSha256("config");
        when(calls.findByRepairIdAndRequestKey("repair", "old-key")).thenReturn(Optional.of(previous));
        var request = new StartModelCallRequest(repair.faultSha256(), "config", "old-key", false, false);
        assertSame(previous, store.reserve("repair", request));
        assertNull(store.reverseInputs("repair", request));
        verify(calls, never()).saveAndFlush(any());
    }

    @Test
    void machineAuthenticationRejectsOrdinaryAndEnhancedCallsBeforeBudgetAndBrowserWork() throws Exception {
        var repair = prepare();
        var baseline = (com.fasterxml.jackson.databind.node.ObjectNode) repair.frozen().path("baseline");
        baseline.withObject("result").set("managedSourceEvidence",
                SourceAuthenticationEvidenceTest.jsonEvidence(mapper, repair.baselineArtifactSha256()));
        var reverse = mock(ReverseAnalysis.class);
        var service = new ModelService(store, client, mock(ModelSubmissionService.class), reverse);
        try {
            for (boolean enhanced : List.of(false, true)) {
                var request = new StartModelCallRequest(repair.faultSha256(), "config", "auth-" + enhanced,
                        false, enhanced);
                assertEquals("SOURCE_AUTHENTICATION_REJECTED", assertThrows(ResponseStatusException.class,
                        () -> service.propose("repair", request)).getReason());
            }
            assertEquals(0, budget.getUsedCalls());
            assertNull(budget.getActiveCallId());
            verify(calls, never()).saveAndFlush(any());
            verifyNoInteractions(reverse);
            verify(client, never()).generate(anyString());
        } finally { service.close(); }
    }

    @Test
    void missingLatestCandidateEvidenceDoesNotTurnAnOldChallengeIntoANewBlock() {
        var repair = prepare();
        var candidate = fixtures.candidate("d".repeat(64), 1,
                fixtures.evidence("e".repeat(64), "C03", "CONTENT_LIST_EMPTY_OR_INVALID"));
        when(repairs.candidates("repair")).thenReturn(List.of(candidate));
        var request = new StartModelCallRequest(repair.faultSha256(), "config", "current-key", false, true);
        assertSame(repair, store.reverseInputs("repair", request));
    }

    @Test
    void cloudflareClassificationStopsMatchedCallsButNotAnUnrelatedObservedAction() throws Exception {
        var repair = prepare(AccessPageClassifierTest.CF_CHALLENGE);
        var request = new StartModelCallRequest(repair.faultSha256(), "config", "cf-key", false, true);
        var error = assertThrows(ResponseStatusException.class, () -> store.reverseInputs("repair", request));
        assertEquals("MODEL_SOURCE_ACCESS_CHALLENGE", error.getReason());
        var evidence = (com.fasterxml.jackson.databind.node.ObjectNode) repair.frozen()
                .path("baseline").path("result").path("managedSourceEvidence");
        AccessPageClassifierTest.observeDuring(mapper, evidence, "detail");
        assertSame(repair, store.reverseInputs("repair", request));
        assertEquals(0, budget.getUsedCalls());
        verify(calls, never()).saveAndFlush(any());
        verify(client, never()).generate(anyString());
    }
}
