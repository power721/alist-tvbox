package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.MaintenanceAutomationTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OriginDiscoveryServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final MaintenanceAutomationStore tasks = mock(MaintenanceAutomationStore.class);
    final RepairStore repairs = mock(RepairStore.class);
    final ReverseAnalysis reverse = mock(ReverseAnalysis.class);
    final ModelStore models = mock(ModelStore.class);
    final ModelService generation = mock(ModelService.class);
    final ModelClient client = mock(ModelClient.class);
    final AutomaticRepairStore automatic = mock(AutomaticRepairStore.class);
    final SourceCredentialStore credentials = mock(SourceCredentialStore.class);
    final OriginDiscoveryService service = new OriginDiscoveryService(tasks, repairs, reverse, models,
            generation, client, automatic, credentials, mapper);

    MaintenanceAutomationTask blockedSearch() {
        var repair = mock(RepairSummary.class);
        when(repair.id()).thenReturn("repair");
        when(repair.sourceId()).thenReturn("source");
        when(repair.baselineArtifactSha256()).thenReturn("artifact");
        when(repair.faultSha256()).thenReturn("fault");
        when(repair.status()).thenReturn("AWAITING_PROPOSAL");
        when(repair.freshness()).thenReturn("current");
        when(repair.staleInputs()).thenReturn(List.of());
        when(repairs.get("repair")).thenReturn(repair);
        var connection = mock(ModelConnection.class);
        when(client.connection()).thenReturn(connection);
        when(connection.configurationSha256()).thenReturn("config");
        when(models.consent("source")).thenReturn(new ModelConsent("source", "artifact"));
        when(automatic.get("repair")).thenReturn(new AutomaticRepairSummary("repair", "STOPPED", "config", null));
        var task = new MaintenanceAutomationTask();
        task.setId("origin_discovery:repair"); task.setOwnerId("repair");
        task.setState("BLOCKED"); task.setErrorCode("ORIGIN_SEARCH_RESULTS_UNAVAILABLE");
        var context = mapper.createObjectNode().put("expectedFaultSha256", "fault")
                .put("configurationSha256", "config").put("maxDiscoveryCalls", 1).put("searchTaskId", "search");
        context.putObject("searchEvidence").put("status", "unavailable").put("httpStatus", 302);
        when(tasks.get("origin_discovery", "repair")).thenReturn(task);
        when(tasks.context(task)).thenReturn(context);
        when(tasks.save(eq(task), anyString(), eq(context), nullable(String.class))).thenAnswer(call -> {
            task.setState(call.getArgument(1)); task.setErrorCode(call.getArgument(3)); return task;
        });
        when(tasks.summary(task)).thenAnswer(call -> mapper.createObjectNode().put("state", task.getState()));
        return task;
    }

    @Test void failedSearchRecoveryPreservesHistoryAndReplaysOnlyTheSameRecovery() {
        var task = blockedSearch();
        var request = new ResumeOriginSearchRequest("fault", "search");
        assertEquals("QUEUED", service.resumeSearch("repair", request).path("state").asText());
        var context = tasks.context(task);
        assertEquals(302, context.path("searchHistory").get(0).path("searchEvidence").path("httpStatus").asInt());
        assertEquals("search", context.path("searchHistory").get(0).path("taskId").asText());
        assertEquals(1, context.path("maxDiscoveryCalls").asInt());
        assertEquals(1, context.path("searchRecoveries").asInt());
        assertFalse(context.has("searchEvidence"));
        assertEquals(0, task.getAttempts());
        task.setState("BLOCKED");
        assertEquals("BLOCKED", service.resumeSearch("repair", request).path("state").asText());
        verify(tasks, times(1)).save(task, "QUEUED", context, null);
        verifyNoInteractions(generation, reverse);
    }

    @Test void newlyAvailableOriginInfrastructureRequeuesSameUnchargedTaskAndRetainsFailure() {
        var task = blockedSearch();
        task.setErrorCode("REVERSE_ENGINE_NOT_READY");
        task.setUpdatedAt(java.time.Instant.now());
        when(reverse.originReady()).thenReturn(true);
        var context = tasks.context(task);
        assertEquals("QUEUED", service.start("repair", "fault").path("state").asText());
        assertEquals("REVERSE_ENGINE_NOT_READY", context.path("infrastructureFailures").get(0).path("code").asText());
        assertEquals(0, task.getAttempts());
        assertFalse(context.has("modelCallId"));
        service.start("repair", "fault");
        verify(tasks, times(1)).save(task, "QUEUED", context, null);
        verifyNoInteractions(generation);
        task.setState("BLOCKED"); task.setErrorCode("REVERSE_ENGINE_NOT_READY");
        when(automatic.get("repair")).thenReturn(new AutomaticRepairSummary("repair", "CANCELLED", "config", "USER_CANCELLED"));
        assertEquals("USER_CANCELLED", assertThrows(ResponseStatusException.class,
                () -> service.start("repair", "fault")).getReason());
    }

    @Test void recoveryCannotGrantAnotherModelCallOrRetryAnEmptySuccessfulSearch() {
        var task = blockedSearch();
        var context = tasks.context(task);
        context.put("modelCallId", "issued");
        assertEquals("ORIGIN_SEARCH_RECOVERY_NOT_AVAILABLE", assertThrows(ResponseStatusException.class,
                () -> service.resumeSearch("repair", new ResumeOriginSearchRequest("fault", "search"))).getReason());
        context.remove("modelCallId");
        ((com.fasterxml.jackson.databind.node.ObjectNode) context.path("searchEvidence")).put("status", "no_results");
        assertEquals("ORIGIN_SEARCH_RECOVERY_NOT_AVAILABLE", assertThrows(ResponseStatusException.class,
                () -> service.resumeSearch("repair", new ResumeOriginSearchRequest("fault", "search"))).getReason());
        verify(tasks, never()).save(any(), anyString(), any(), any());
    }

    @Test void recoveryRejectsChangedFaultSearchOrConfigurationAndDoesNotReviveCancellation() {
        var task = blockedSearch();
        assertEquals("REPAIR_FAULT_CHANGED", assertThrows(ResponseStatusException.class,
                () -> service.resumeSearch("repair", new ResumeOriginSearchRequest("changed", "search"))).getReason());
        assertEquals("ORIGIN_SEARCH_RECOVERY_NOT_AVAILABLE", assertThrows(ResponseStatusException.class,
                () -> service.resumeSearch("repair", new ResumeOriginSearchRequest("fault", "other"))).getReason());
        when(client.connection().configurationSha256()).thenReturn("changed");
        assertEquals("MODEL_CONFIGURATION_CHANGED", assertThrows(ResponseStatusException.class,
                () -> service.resumeSearch("repair", new ResumeOriginSearchRequest("fault", "search"))).getReason());
        when(client.connection().configurationSha256()).thenReturn("config");
        when(automatic.get("repair")).thenReturn(new AutomaticRepairSummary("repair", "CANCELLED", "config", null));
        assertEquals("USER_CANCELLED", assertThrows(ResponseStatusException.class,
                () -> service.resumeSearch("repair", new ResumeOriginSearchRequest("fault", "search"))).getReason());
        assertEquals("BLOCKED", task.getState());
        verify(tasks, never()).save(any(), anyString(), any(), any());
    }

    @Test void restartUsesSavedSearchAndRejectsModelDomainsOutsideActualResults() {
        var repair = mock(RepairSummary.class);
        when(repair.id()).thenReturn("repair");
        when(repair.faultSha256()).thenReturn("fault");
        when(repair.status()).thenReturn("AWAITING_PROPOSAL");
        when(repair.freshness()).thenReturn("current");
        when(repair.staleInputs()).thenReturn(List.of());
        when(repairs.get("repair")).thenReturn(repair);
        var connection = mock(ModelConnection.class);
        when(client.connection()).thenReturn(connection);
        when(connection.configurationSha256()).thenReturn("config");
        when(automatic.get("repair")).thenReturn(new AutomaticRepairSummary("repair", "STOPPED", "config", null));
        var task = new MaintenanceAutomationTask();
        task.setOwnerId("repair"); task.setState("WAITING_MODEL"); task.setAttempts(1);
        var context = mapper.createObjectNode().put("expectedFaultSha256", "fault")
                .put("configurationSha256", "config").put("modelCallId", "call");
        var search = context.putObject("searchEvidence").put("status", "results_observed")
                .put("originalOrigin", "https://primary.example");
        search.putArray("results").addObject().put("url", "https://real.example/").put("origin", "https://real.example");
        when(tasks.due("origin_discovery")).thenReturn(List.of(task));
        when(tasks.context(task)).thenReturn(context);
        var call = mock(ModelCallSummary.class);
        when(models.get("call")).thenReturn(call);
        when(call.status()).thenReturn("DISCOVERED");
        var diagnostic = mapper.createObjectNode();
        diagnostic.putArray("originCandidates").addObject().put("origin", "https://invented.example")
                .put("evidenceUrl", "https://real.example/");
        when(call.responseDiagnostics()).thenReturn(diagnostic);
        service.poll();
        verify(tasks).save(task, "BLOCKED", context, "ORIGIN_MODEL_NO_MATCHING_CANDIDATES");
        verifyNoInteractions(reverse, generation);
        verify(automatic, never()).start(anyString(), anyString(), anyBoolean());
    }

    @Test void alreadyVerifiedDeclaredOriginResumesSameRepairWithoutSearchingOrSpendingCalls() {
        var task = blockedSearch();
        var context = tasks.context(task);
        task.setState("QUEUED");
        var original = repairs.get("repair");
        var frozen = mapper.createObjectNode();
        when(original.baselineRunId()).thenReturn("run");
        var report = RepairEvidenceMaterialTest.originReceipt(mapper, original);
        frozen.set("reverseAnalysis", report);
        when(original.frozen()).thenReturn(frozen);
        when(original.maxRounds()).thenReturn(3);
        when(client.connection().autoSubmitEnabled()).thenReturn(true);
        when(tasks.due("origin_discovery")).thenReturn(List.of(task));
        service.poll();
        verify(automatic).start("repair", "config", true);
        verify(tasks).save(task, "RESUMED", context, null);
        assertEquals("https://alternate.example.com", context.path("verifiedRoute").path("candidateOrigin").asText());
        verifyNoInteractions(reverse, generation);
        when(original.usedRounds()).thenReturn(3);
        task.setState("QUEUED");
        service.poll();
        verify(tasks).save(task, "VERIFIED", context, "ROUND_LIMIT");
        verify(automatic, times(1)).start(anyString(), anyString(), anyBoolean());
    }
}
