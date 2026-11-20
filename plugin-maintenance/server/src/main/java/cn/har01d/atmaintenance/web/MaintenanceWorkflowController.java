package cn.har01d.atmaintenance.web;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/plugin-maintenance")
@RequiredArgsConstructor
public class MaintenanceWorkflowController {
    private final MaintenanceWorkflowService service;
    private final ProbeStore store;

    @PostMapping("/sources/{id}/maintenance")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MaintenanceSummary start(@PathVariable String id, @Valid @RequestBody StartMaintenanceRequest input) {
        return service.start(id, input);
    }

    @GetMapping("/sources/{id}/maintenance")
    public List<MaintenanceSummary> list(@PathVariable String id) { return store.maintenanceList(id); }

    @GetMapping("/probe-runs/{id}/maintenance")
    public MaintenanceSummary get(@PathVariable String id) { return store.maintenance(id); }

    @PostMapping("/probe-runs/{id}/maintenance/cancel")
    public MaintenanceSummary cancel(@PathVariable String id) { return service.cancel(id); }

    @PostMapping("/probe-runs/{id}/maintenance/resume")
    public MaintenanceSummary resume(@PathVariable String id, @Valid @RequestBody StartMaintenanceRequest input) {
        return service.resume(id, input);
    }

    @PostMapping("/probe-runs/{id}/maintenance/evidence-continuation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MaintenanceSummary continueWithEvidence(@PathVariable String id,
            @Valid @RequestBody ContinueMaintenanceEvidenceRequest input) {
        return service.continueWithEvidence(id, input);
    }
}
