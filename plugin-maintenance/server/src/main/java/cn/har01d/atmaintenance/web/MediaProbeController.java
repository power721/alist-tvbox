package cn.har01d.atmaintenance.web;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/plugin-maintenance")
@RequiredArgsConstructor
public class MediaProbeController {
    private final MediaProbeService service;
    private final MediaProbeStore store;
    private final MediaNetworkPolicy policy;
    private final MediaProbeExportService exports;
    private final MediaBudgetStore budgets;
    private final MediaBudgetPolicy budgetPolicy;

    @GetMapping("/media-policy")
    public Map<String, Object> policy() {
        return Map.of("sha256", policy.sha256(), "snapshot", policy.snapshot(),
                "blockedReason", Objects.toString(policy.blockedReason(), ""),
                "budgetPolicySha256", budgetPolicy.sha256(), "budgetPolicy", budgetPolicy.snapshot());
    }

    @GetMapping("/media-budget")
    public MediaBudgetSummary budget() { return budgets.global(); }

    @GetMapping("/sources/{id}/media-budget")
    public MediaBudgetSummary sourceBudget(@PathVariable String id) { return budgets.source(id); }

    @PostMapping("/sources/{id}/media-probes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MediaProbeSummary start(@PathVariable String id, @Valid @RequestBody StartMediaProbeRequest input) {
        return service.start(id, input);
    }

    @GetMapping("/sources/{id}/media-probes")
    public List<MediaProbeSummary> list(@PathVariable String id) { return store.list(id); }

    @GetMapping("/media-probes/{id}")
    public MediaProbeSummary get(@PathVariable String id) { return store.get(id); }

    @PostMapping("/media-probes/{id}/cancel")
    public MediaProbeSummary cancel(@PathVariable String id) { return service.cancel(id); }

    @GetMapping("/media-probes/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"maintenance-media-evidence.zip\"")
                .body(exports.export(id));
    }
}
