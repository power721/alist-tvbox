package cn.har01d.atmaintenance.web;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.service.UpstreamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/plugin-maintenance/sources/{id}/upstream")
@RequiredArgsConstructor
public class UpstreamController {
    private final UpstreamService service;

    @GetMapping
    public ResponseEntity<UpstreamSummary> get(@PathVariable String id) {
        var state = service.get(id);
        return state == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(state);
    }

    @GetMapping("/events")
    public List<UpstreamEventSummary> history(@PathVariable String id) { return service.history(id); }

    @PostMapping("/initialize")
    public UpstreamEventSummary initialize(@PathVariable String id, @Valid @RequestBody InitializeUpstreamRequest input) {
        return service.initialize(id, input);
    }

    @PostMapping("/observations")
    public UpstreamEventSummary observe(@PathVariable String id, @Valid @RequestBody ObserveUpstreamRequest input) {
        return service.observe(id, input);
    }

    @PostMapping("/at-observations")
    public UpstreamEventSummary fetchAt(@PathVariable String id, @Valid @RequestBody FetchAtUpstreamRequest input) {
        return service.fetchAt(id, input);
    }

    @PostMapping("/resolve")
    public UpstreamEventSummary resolve(@PathVariable String id, @Valid @RequestBody ResolveUpstreamRequest input) {
        return service.resolve(id, input);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@PathVariable String id) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"maintenance-upstream-comparison.zip\"")
                .body(service.export(id));
    }
}
