package cn.har01d.atmaintenance.web;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/plugin-maintenance")
@RequiredArgsConstructor
public class ReleasePlanController {
    private final ReleasePlanService plans;
    private final ReleasePlanExportService exports;

    @PostMapping("/sources/{id}/release-plans")
    public ReleasePlanSummary create(@PathVariable String id, @Valid @RequestBody StartReleasePlanRequest input) {
        return plans.create(id, input);
    }

    @GetMapping("/sources/{id}/release-plans")
    public List<ReleasePlanSummary> list(@PathVariable String id) { return plans.list(id); }

    @GetMapping("/release-plans/{id}")
    public ReleasePlanSummary get(@PathVariable String id) { return plans.get(id); }

    @GetMapping("/release-plans/{id}/frozen")
    public JsonNode frozen(@PathVariable String id) { return plans.frozen(id); }

    @PostMapping("/release-plans/{id}/cancel")
    public ReleasePlanSummary cancel(@PathVariable String id) { return plans.cancel(id); }

    @PostMapping("/release-plans/{id}/local-apply")
    public ReleasePlanSummary applyLocal(@PathVariable String id, @Valid @RequestBody LocalReleaseRequest input) {
        return plans.applyLocal(id, input);
    }

    @PostMapping("/release-plans/{id}/local-rollback")
    public ReleasePlanSummary rollbackLocal(@PathVariable String id, @Valid @RequestBody LocalReleaseRequest input) {
        return plans.rollbackLocal(id, input);
    }

    @GetMapping("/release-plans/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"maintenance-release-preparation.zip\"")
                .body(exports.export(id));
    }
}
