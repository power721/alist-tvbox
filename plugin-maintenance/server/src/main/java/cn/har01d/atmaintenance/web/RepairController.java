package cn.har01d.atmaintenance.web;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/plugin-maintenance")
@RequiredArgsConstructor
public class RepairController {
    private final RepairStore store;
    private final RepairService service;
    private final RepairExportService exports;
    private final ObjectMapper mapper;
    private final ExtractionReplay extraction;
    private final SourceService sources;
    private final BrowserEvidence browser;
    private final ProbeStore probes;
    private final RepairDiagnostics diagnostics;
    private final ReverseAnalysis reverse;
    private final OriginDiscoveryService originDiscovery;

    @GetMapping("/reverse-analysis/readiness")
    public ObjectNode reverseReadiness() { return reverse.readiness(); }

    @PostMapping("/sources/{id}/page-diagnostics")
    public ObjectNode pageDiagnostics(@PathVariable String id, @RequestParam String runId,
                                      @RequestBody PageDiagnosticsRequest input) {
        if (input == null || !sources.get(id).artifactSha256().equals(input.expectedArtifactSha256()))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "BROWSER_SOURCE_CHANGED");
        probes.requireEvidenceRun(id, runId, input.expectedArtifactSha256());
        return probes.attachEvidence(id, runId, input.expectedArtifactSha256(), "pageDiagnostics",
                reverse.captureRun(probes.get(runId), input));
    }

    @PostMapping("/repairs/{id}/reverse-analysis")
    public ObjectNode reverseAnalysis(@PathVariable String id, @RequestBody ReverseAnalysisRequest input) {
        return reverse.capture(id, input);
    }

    @PostMapping("/repairs/{id}/origin-inspection")
    public ObjectNode inspectOrigins(@PathVariable String id, @RequestBody OriginInspectionRequest input) {
        return reverse.inspectOrigins(id, input);
    }

    @GetMapping("/repairs/{id}/origin-discovery")
    public ObjectNode originDiscovery(@PathVariable String id) { return originDiscovery.status(id); }

    @PostMapping("/repairs/{id}/origin-discovery")
    public ObjectNode discoverOrigins(@PathVariable String id, @Valid @RequestBody OriginInspectionRequest input) {
        return originDiscovery.start(id, input.expectedFaultSha256());
    }

    @PostMapping("/repairs/{id}/origin-discovery/resume-search")
    public ObjectNode resumeOriginSearch(@PathVariable String id, @Valid @RequestBody ResumeOriginSearchRequest input) {
        return originDiscovery.resumeSearch(id, input);
    }

    @GetMapping("/runs/{id}/diagnostics")
    public ObjectNode runDiagnostics(@PathVariable String id) {
        return diagnostics.forRun(probes.get(id));
    }

    @GetMapping("/repairs/{id}/diagnostics")
    public ObjectNode repairDiagnostics(@PathVariable String id) {
        return diagnostics.forRepair(store.get(id), store.candidates(id));
    }

    @PostMapping("/sources/{id}/browser-evidence")
    public ObjectNode browserEvidence(@PathVariable String id, @RequestBody BrowserEvidenceRequest input) {
        if (input == null || !sources.get(id).artifactSha256().equals(input.expectedArtifactSha256()))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "BROWSER_SOURCE_CHANGED");
        probes.requireEvidenceRun(id, input.jobId(), input.expectedArtifactSha256());
        try {
            return probes.attachEvidence(id, input.jobId(), input.expectedArtifactSha256(), "browserEvidence", browser.capture(input));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "BROWSER_INTERRUPTED");
        } catch (IOException error) {
            String code = error.getMessage();
            if (code == null || !code.matches("BROWSER_[A-Z_]{1,80}")) code = "BROWSER_EVIDENCE_FAILED";
            throw new org.springframework.web.server.ResponseStatusException(
                    code.equals("BROWSER_EVIDENCE_DISABLED") ? HttpStatus.SERVICE_UNAVAILABLE
                            : code.equals("BROWSER_RESPONSE_ABORT_NOT_APPROVED") || code.contains("INPUT_INVALID")
                                || code.contains("URL_INVALID") ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY, code);
        }
    }

    @PostMapping("/sources/{id}/extraction/replay")
    public ObjectNode replay(@PathVariable String id, @RequestParam String runId, @RequestBody ExtractionReplayRequest input) {
        if (input == null || !sources.get(id).artifactSha256().equals(input.expectedArtifactSha256()))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "EXTRACTION_SOURCE_CHANGED");
        probes.requireEvidenceRun(id, runId, input.expectedArtifactSha256());
        try { return probes.attachEvidence(id, runId, input.expectedArtifactSha256(), "extractionReplay", extraction.replay(input)); }
        catch (IllegalArgumentException error) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }

    @PostMapping("/repairs/{id}/selector-proposals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RepairCandidateSummary selectorProposal(@PathVariable String id, @RequestBody SelectorLearningRequest input) {
        try { return service.proposeSelector(id, input); }
        catch (IllegalArgumentException error) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }

    @GetMapping("/fixtures/repair")
    public ObjectNode fixture() throws IOException {
        var value = (ObjectNode) mapper.readTree(new ClassPathResource("worker/repair-demo.json").getContentAsByteArray());
        String sha = Hashes.sha256(value.path("content").asText());
        value.put("artifactSha256", sha);
        ((ObjectNode) value.path("proposal")).put("expectedBaselineSha256", sha);
        return value;
    }

    @GetMapping("/sources/{id}/repairs")
    public List<RepairSummary> repairs(@PathVariable String id) { return store.list(id); }

    @PostMapping("/sources/{id}/repairs")
    public RepairSummary create(@PathVariable String id, @Valid @RequestBody StartRepairRequest input) {
        return store.create(id, input);
    }

    @GetMapping("/repairs/{id}")
    public RepairSummary repair(@PathVariable String id) { return store.get(id); }

    @GetMapping("/repairs/{id}/candidates")
    public List<RepairCandidateSummary> candidates(@PathVariable String id) { return store.candidates(id); }

    @GetMapping("/repairs/{id}/evidence-continuation")
    public ObjectNode evidenceContinuation(@PathVariable String id) { return store.evidenceContinuation(id); }

    @PostMapping("/repairs/{id}/proposals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RepairCandidateSummary propose(@PathVariable String id, @Valid @RequestBody RepairProposal input) {
        return service.propose(id, input);
    }

    @PostMapping("/repairs/{id}/imported-candidates")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RepairCandidateSummary proposeImported(@PathVariable String id,
                                                 @Valid @RequestBody ImportedCandidateRequest input) {
        return service.proposeImported(id, input);
    }

    @PostMapping("/repairs/{id}/cancel")
    public RepairSummary cancel(@PathVariable String id) { return service.cancel(id); }

    @PostMapping("/repairs/{id}/resume")
    public RepairSummary resume(@PathVariable String id, @Valid @RequestBody ResumeRepairRequest input) {
        return store.resume(id, input.expectedFaultSha256());
    }

    @GetMapping("/repair-candidates/{id}")
    public RepairCandidateSummary candidate(@PathVariable String id) { return store.candidate(id); }

    @PostMapping("/repair-candidates/{id}/runtime-validation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RepairCandidateSummary validateRuntime(@PathVariable String id,
                                                  @Valid @RequestBody RuntimeValidationRequest input) {
        return service.validateRuntime(id, input.expectedCandidateSha256());
    }

    @PostMapping("/repair-candidates/{id}/runtime-revalidation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RepairCandidateSummary revalidateRuntime(@PathVariable String id,
                                                    @Valid @RequestBody RuntimeRevalidationRequest input) {
        return service.revalidateRuntime(id, input.expectedCandidateSha256(), input.expectedFaultSha256());
    }

    @PostMapping("/repair-candidates/{id}/runtime-reconciliation")
    public RepairSummary reconcileRuntime(@PathVariable String id, @Valid @RequestBody RuntimeRevalidationRequest input) {
        return store.reconcileRuntimeSuccess(id, input.expectedCandidateSha256(), input.expectedFaultSha256());
    }

    @GetMapping(value = "/repair-candidates/{id}/content", produces = "text/plain;charset=UTF-8")
    public byte[] content(@PathVariable String id) { return store.content(id).getBytes(StandardCharsets.UTF_8); }

    @GetMapping("/repair-candidates/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(exports.delivery(id).archiveFilename(), StandardCharsets.UTF_8).build().toString())
                .body(exports.export(id));
    }
}
