package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.RepairCandidateSummary;
import cn.har01d.atmaintenance.dto.RepairSummary;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepairCredentialGateTest {
    @Test
    void sourcePreparationFailureDoesNotReserveOrOverwriteCandidateHistory() throws Exception {
        var store = mock(RepairStore.class);
        var inspector = mock(StaticInspector.class);
        var runtime = mock(ManagedSourceRuntime.class);
        var credentials = mock(SourceCredentialStore.class);
        var setup = mock(ManagedSourceSetup.class);
        var sources = mock(SourceService.class);
        var candidate = mock(RepairCandidateSummary.class);
        var repair = mock(RepairSummary.class);
        when(store.candidate("candidate")).thenReturn(candidate);
        when(candidate.repairId()).thenReturn("repair");
        when(candidate.sha256()).thenReturn("sha");
        when(store.get("repair")).thenReturn(repair);
        when(store.content("candidate")).thenReturn("candidate-code");
        when(repair.sourceId()).thenReturn("source");
        when(repair.faultSha256()).thenReturn("fault");
        when(repair.baselineArtifactSha256()).thenReturn("baseline");
        var original = new cn.har01d.atmaintenance.entity.MaintenanceArtifact();
        original.setBody("original");
        when(sources.artifact("source", "baseline")).thenReturn(original);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "SOURCE_SETUP_UNAVAILABLE")).when(setup).ensure("source", "baseline");
        var service = new RepairService(store, inspector, mock(RepairReport.class), runtime, credentials,
                mock(ProbeStore.class), mock(ExtractionReplay.class), sources, setup);
        try {
            assertEquals("SOURCE_SETUP_UNAVAILABLE", assertThrows(ResponseStatusException.class,
                    () -> service.revalidateRuntime("candidate", "sha", "fault")).getReason());
            verify(store, never()).reserveRevalidation(anyString(), anyString(), anyString(), any(), any());
            verifyNoInteractions(runtime);
        } finally { service.close(); }
    }

    @Test
    void missingCredentialsStopEveryRuntimeEntryBeforeInspectionReservationOrHistoryMutation() throws Exception {
        var store = mock(RepairStore.class);
        var inspector = mock(StaticInspector.class);
        var runtime = mock(ManagedSourceRuntime.class);
        var credentials = mock(SourceCredentialStore.class);
        var setup = mock(ManagedSourceSetup.class);
        var candidate = mock(RepairCandidateSummary.class);
        var repair = mock(RepairSummary.class);
        when(store.candidate("candidate")).thenReturn(candidate);
        when(candidate.repairId()).thenReturn("repair");
        when(candidate.sha256()).thenReturn("sha");
        when(store.get("repair")).thenReturn(repair);
        when(repair.sourceId()).thenReturn("source");
        when(repair.faultSha256()).thenReturn("fault");
        var service = new RepairService(store, inspector, mock(RepairReport.class), runtime, credentials,
                mock(ProbeStore.class), mock(ExtractionReplay.class), mock(SourceService.class), setup);
        try {
            for (String reason : new String[]{"SOURCE_CREDENTIALS_REQUIRED", "SOURCE_AUTHENTICATION_REJECTED"}) {
                when(credentials.blockedReason("source")).thenReturn(reason);
                assertEquals(reason, assertThrows(ResponseStatusException.class,
                        () -> service.validateRuntime("candidate", "sha")).getReason());
                assertEquals(reason, assertThrows(ResponseStatusException.class,
                        () -> service.revalidateRuntime("candidate", "sha", "fault")).getReason());
                assertEquals(reason, assertThrows(ResponseStatusException.class,
                        () -> service.retryAutomaticRuntime("repair")).getReason());
            }
            verify(store, never()).reserveRuntime(anyString(), anyString());
            verify(store, never()).reserveRevalidation(anyString(), anyString(), anyString(), any(), any());
            verify(store, never()).reserveAutomaticRuntimeRetry(anyString());
            verifyNoInteractions(inspector, runtime, setup);
        } finally { service.close(); }
    }
}
