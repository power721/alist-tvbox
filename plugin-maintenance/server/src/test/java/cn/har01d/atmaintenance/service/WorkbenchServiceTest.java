package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.ProbeSummary;
import cn.har01d.atmaintenance.repository.*;
import cn.har01d.atmaintenance.entity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkbenchServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path directory;

    @Test
    void healthRequiresFreshLiveResponsesCleanupAndBusinessCoverage() throws Exception {
        var value = mapper.readTree("""
                {"freshness":"current","status":"COMPLETED","executionMode":"managed_source","result":{
                "nodes":[{"id":"C03","status":"pass"}],"requiredCoverage":{"total":1,"pass":1},
                "managedSourceEvidence":{"cleanup":{"confirmed":true,"containerAbsent":true},"deniedCodes":[],
                "httpObservations":[{"responseComplete":true,"responseStatus":200,"responseBytes":12}]}}}
                """);
        assertEquals("verified", WorkbenchService.health(mapper.treeToValue(value, ProbeSummary.class)));
        var object = (com.fasterxml.jackson.databind.node.ObjectNode) value;
        object.put("freshness", "profile_changed");
        assertEquals("stale", WorkbenchService.health(mapper.treeToValue(value, ProbeSummary.class)));
        object.put("freshness", "current");
        ((com.fasterxml.jackson.databind.node.ObjectNode) value.at("/result/managedSourceEvidence/cleanup")).put("confirmed", false);
        assertEquals("blocked", WorkbenchService.health(mapper.treeToValue(value, ProbeSummary.class)));
        ((com.fasterxml.jackson.databind.node.ObjectNode) value.at("/result/managedSourceEvidence/cleanup")).put("confirmed", true);
        ((com.fasterxml.jackson.databind.node.ObjectNode) value.at("/result/managedSourceEvidence/httpObservations/0")).put("responseComplete", false);
        assertEquals("blocked", WorkbenchService.health(mapper.treeToValue(value, ProbeSummary.class)));
        ((com.fasterxml.jackson.databind.node.ObjectNode) value.at("/result/managedSourceEvidence/httpObservations/0")).put("responseComplete", true);
        ((com.fasterxml.jackson.databind.node.ObjectNode) value.at("/result/nodes/0")).put("status", "fail");
        assertEquals("needs_repair", WorkbenchService.health(mapper.treeToValue(value, ProbeSummary.class)));
    }

    @Test
    void overviewFlagsOnlyComparableLiveRegressionsAndThreeValidatedUnsuccessfulCandidates() throws Exception {
        var probes = mock(ProbeStore.class);
        var runs = mock(ProbeRunRepository.class);
        var repairs = mock(RepairRepository.class);
        var candidates = mock(RepairCandidateRepository.class);
        var service = new WorkbenchService(mock(SourceService.class), probes, runs, repairs, candidates,
                mock(ModelCallRepository.class), mapper);
        var latest = new ProbeRun(); latest.setId("latest"); latest.setSourceId("source");
        latest.setArtifactSha256("sha"); latest.setExecutionMode("managed_source");
        latest.setCreatedAt(Instant.parse("2026-11-18T10:00:00Z"));
        var previous = new ProbeRun(); previous.setId("previous"); previous.setSourceId("source");
        previous.setArtifactSha256("sha"); previous.setExecutionMode("managed_source");
        previous.setCreatedAt(latest.getCreatedAt().minusSeconds(60));
        String evidence = """
                {"cleanup":{"confirmed":true,"containerAbsent":true},"deniedCodes":[],
                "httpObservations":[{"responseComplete":true,"responseStatus":200,"responseBytes":12}]}
                """;
        var failed = mapper.readTree("""
                {"freshness":"current","status":"COMPLETED","executionMode":"managed_source","result":{
                "nodes":[{"id":"C03","status":"fail"}],"requiredCoverage":{"total":1,"pass":0}}}
                """);
        ((com.fasterxml.jackson.databind.node.ObjectNode) failed.path("result")).set("managedSourceEvidence", mapper.readTree(evidence));
        var healthy = failed.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) healthy.at("/result/nodes/0")).put("status", "pass");
        ((com.fasterxml.jackson.databind.node.ObjectNode) healthy.at("/result/requiredCoverage")).put("pass", 1);
        var brokenSummary = mapper.treeToValue(failed, ProbeSummary.class);
        when(runs.latestLiveRuns()).thenReturn(List.of(latest));
        when(runs.findTop30BySourceIdOrderByCreatedAtDesc("source")).thenReturn(List.of(latest, previous));
        when(probes.get("latest")).thenReturn(brokenSummary);
        when(probes.get("previous")).thenReturn(mapper.treeToValue(healthy, ProbeSummary.class));
        var repair = new RepairSession(); repair.setId("repair"); repair.setBaselineArtifactSha256("sha");
        repair.setBaselineRunId("latest"); repair.setUsedRounds(3);
        when(repairs.findTop20BySourceIdOrderByCreatedAtDesc("source")).thenReturn(List.of(repair));
        var attempts = new ArrayList<RepairCandidate>();
        for (int round = 1; round <= 3; round++) {
            var candidate = new RepairCandidate(); candidate.setRoundNumber(round); candidate.setStatus("RUNTIME_OBSERVED");
            candidate.setResultJson("{\"repairSuccessful\":false,\"runtimeAcceptance\":{\"successful\":false},\"runtimeEvidence\":" + evidence + "}");
            attempts.add(candidate);
        }
        when(candidates.findByRepairIdOrderByRoundNumberAsc("repair")).thenReturn(attempts);
        var row = service.overview().get(0);
        assertTrue(row.path("degraded").asBoolean());
        assertEquals("C03", row.path("regressedNodes").get(0).asText());
        assertTrue(row.path("repairExhausted").asBoolean());
        assertEquals(3, row.path("failedRepairRounds").asInt());
        previous.setProfileSha256("different-profile");
        assertFalse(service.overview().get(0).path("degraded").asBoolean());
        attempts.getLast().setStatus("BLOCKED");
        attempts.getLast().setErrorCode("RUNTIME_NETWORK_UNAVAILABLE");
        row = service.overview().get(0);
        assertFalse(row.path("repairExhausted").asBoolean());
        assertEquals(2, row.path("failedRepairRounds").asInt());
        attempts.forEach(candidate -> candidate.setStatus("FAILED"));
        assertEquals(0, service.overview().get(0).path("failedRepairRounds").asInt());
        when(probes.get("latest")).thenReturn(mapper.treeToValue(healthy, ProbeSummary.class));
        row = service.overview().get(0);
        assertFalse(row.path("degraded").asBoolean());
        assertFalse(row.path("repairExhausted").asBoolean());
    }

    @Test
    void importedLogPersistsAsUntrustedDataAndNeverInvokesAnyWork() throws Exception {
        var sources = mock(SourceService.class);
        var probes = mock(ProbeStore.class);
        var runs = mock(ProbeRunRepository.class);
        var repairs = mock(RepairRepository.class);
        var candidates = mock(RepairCandidateRepository.class);
        var calls = mock(ModelCallRepository.class);
        var service = new WorkbenchService(sources, probes, runs, repairs, candidates, calls, mapper);
        ReflectionTestUtils.setField(service, "importDirectory", directory.toString());
        var input = mapper.readTree("""
                {"format":"at-maintenance-log","schemaVersion":1,"authority":"trusted","active":true,
                "execute":"must never run","events":[{"id":"1","time":"2026-11-18T00:00:00Z",
                "stage":"model","status":"FAILED","code":"MODEL_RATE_LIMITED","message":"request paused",
                "requestJson":"private-body"}]}
                """);
        var accepted = service.importLog(input);
        assertEquals("imported_untrusted_log", accepted.path("authority").asText());
        assertFalse(accepted.path("active").asBoolean());
        assertEquals(1, accepted.path("analysis").path("problemCount").asInt());
        assertFalse(accepted.toString().contains("private-body"));
        assertFalse(accepted.has("execute"));
        assertEquals(accepted, service.importedLog(accepted.path("importId").asText()));
        verifyNoInteractions(sources, probes, runs, repairs, candidates, calls);
        assertThrows(ResponseStatusException.class, () -> service.importedLog("../../connections"));
        assertThrows(ResponseStatusException.class, () -> service.importLog(mapper.valueToTree(Map.of("events", "bad"))));
    }
}
