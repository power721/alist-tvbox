package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.PageDiagnosticsRequest;
import cn.har01d.atmaintenance.dto.ProbeSummary;
import cn.har01d.atmaintenance.entity.MaintenanceArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PageDiagnosticsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String PAGE = "https://source.example/";
    private static final String SOURCE = "host = 'https://source.example'";

    private ObjectNode runData() {
        var run = mapper.createObjectNode().put("id", "run").put("sourceId", "source")
                .put("artifactSha256", "a".repeat(64)).put("profileSha256", "p")
                .put("status", "COMPLETED").put("freshness", "current");
        var evidence = run.putObject("result").putObject("managedSourceEvidence")
                .put("authority", "managed_source_host_observed").put("jobId", "run")
                .put("inputSourceSha256", "a".repeat(64));
        evidence.putObject("resourceEvidence").put("artifactSha256", "a".repeat(64)).putArray("resources");
        evidence.putArray("httpObservations").addObject().put("method", "GET")
                .put("requestUrlSha256", Hashes.sha256(PAGE)).put("responseStatus", 444)
                .put("responseBytes", 0).put("responseComplete", true);
        run.putObject("context");
        return run;
    }

    private ProbeSummary run(ObjectNode value) { return mapper.convertValue(value, ProbeSummary.class); }

    @Test void emptyBodyHttpFailureStillLocatesTheExactObservedPageWithoutExecutingSource() {
        assertEquals(PAGE, ReverseAnalysis.runPage(run(runData()), SOURCE, ""));
        assertEquals(PAGE, ReverseAnalysis.runPage(run(runData()), SOURCE, PAGE));
        assertEquals("PAGE_DIAGNOSTICS_OBSERVED_URL_REQUIRED", assertThrows(ResponseStatusException.class,
                () -> ReverseAnalysis.runPage(run(runData()), SOURCE, "https://unobserved.example/")).getReason());
        var foreign = runData();
        ((ObjectNode) foreign.path("result").path("managedSourceEvidence")).put("jobId", "foreign");
        assertEquals("PAGE_DIAGNOSTICS_HOST_EVIDENCE_REQUIRED", assertThrows(ResponseStatusException.class,
                () -> ReverseAnalysis.runPage(run(foreign), SOURCE, "")).getReason());
    }

    @Test void standaloneDiagnosisNeverCreatesRepairOrUsesModelAndReusesItsFrozenReceipt() throws Exception {
        var repairs = mock(RepairStore.class);
        var sources = mock(SourceService.class);
        var artifact = new MaintenanceArtifact();
        artifact.setBody(SOURCE);
        when(sources.artifact("source", "a".repeat(64))).thenReturn(artifact);
        var service = spy(new ReverseAnalysis(mapper, repairs, sources, mock(ConnectionSettingsStore.class), "", "node", "", "python", "", ""));
        doReturn("b".repeat(64)).when(service).workerSha();
        doAnswer(call -> {
            ObjectNode payload = call.getArgument(0);
            assertEquals("", payload.path("repairId").asText());
            assertEquals("run", payload.path("runId").asText());
            assertFalse(payload.path("recoverScripts").asBoolean());
            assertEquals(Hashes.sha256(PAGE), payload.path("observedPageUrlSha256").asText());
            return mapper.createObjectNode().put("artifactSha256", "a".repeat(64))
                    .put("pageUrlSha256", Hashes.sha256(PAGE)).put("cleanupConfirmed", true);
        }).when(service).observe(any());
        var input = new PageDiagnosticsRequest("a".repeat(64), "");
        ObjectNode report = service.captureRun(run(runData()), input);
        assertFalse(report.path("acceptanceChanged").asBoolean());
        assertFalse(report.path("failureCauseEstablished").asBoolean());
        assertFalse(report.path("sourceCodeExecuted").asBoolean());
        var saved = runData();
        ((ObjectNode) saved.path("context")).putObject("auxiliaryEvidence").set("pageDiagnostics", report);
        assertEquals(report, service.captureRun(run(saved), input));
        verify(service, times(1)).observe(any());
        verifyNoInteractions(repairs);
        report.put("workerSha256", "c".repeat(64));
        assertEquals(report, service.captureRun(run(saved), input));
        verify(service, times(1)).observe(any());
    }

    @Test void changedSourceOrNonterminalRunCannotTriggerNetwork() {
        var sources = mock(SourceService.class);
        var service = new ReverseAnalysis(mapper, mock(RepairStore.class), sources, mock(ConnectionSettingsStore.class), "", "node", "", "python", "", "");
        for (String field : List.of("artifactSha256", "status", "freshness")) {
            var value = runData().put(field, "changed");
            assertEquals("AUXILIARY_EVIDENCE_RUN_MISMATCH", assertThrows(ResponseStatusException.class,
                    () -> service.captureRun(run(value), new PageDiagnosticsRequest("a".repeat(64), PAGE))).getReason());
        }
        verifyNoInteractions(sources);
    }
}
