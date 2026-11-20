package cn.har01d.atmaintenance.web;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plugin-maintenance")
@RequiredArgsConstructor
public class MaintenanceController {
    private final SourceService sources;
    private final ProbeService probes;
    private final ProbeStore store;
    private final KnowledgeService knowledge;
    private final ExportService exports;
    private final ProfileService profiles;
    private final FixtureRuntime runtime;
    private final ContainerRuntime container;
    private final ManagedSourceRuntime managed;
    private final ManagedSourceSetup sourceSetup;
    private final AtReadClient atClient;
    private final AtBindingService bindings;
    private final ProbeScheduleStore schedules;
    private final ScheduleObservationService scheduleObservations;

    @GetMapping("/scheduling")
    public SchedulingSummary scheduling() { return schedules.global(); }

    @GetMapping("/sources/{id}/schedule")
    public ScheduleSummary schedule(@PathVariable String id) { return schedules.get(id); }

    @GetMapping("/sources/{id}/schedule/observations")
    public JsonNode scheduleObservations(@PathVariable String id) { return scheduleObservations.get(id); }

    @PostMapping("/sources/{id}/schedule")
    public ScheduleSummary updateSchedule(@PathVariable String id, @Valid @RequestBody UpdateScheduleRequest input) {
        return schedules.update(id, input);
    }

    @PostMapping("/sources/{id}/schedule/resume")
    public ScheduleSummary resumeSchedule(@PathVariable String id, @Valid @RequestBody ResumeScheduleRequest input) {
        return schedules.resume(id, input.expectedSha256());
    }

    @GetMapping("/at/connection")
    public AtConnection atConnection() { return atClient.connection(); }

    @PostMapping("/at/observations")
    public AtObservation observeAt() { return atClient.observe(); }

    @GetMapping("/sources/{id}/at-binding")
    public ResponseEntity<AtBindingSummary> binding(@PathVariable String id) {
        var binding = bindings.get(id);
        return binding == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(binding);
    }

    @PostMapping("/sources/{id}/at-binding")
    public AtBindingSummary bind(@PathVariable String id, @Valid @RequestBody BindAtRequest input) {
        return bindings.bind(id, input);
    }

    @PostMapping("/sources/{id}/at-binding/runtime-identity")
    public AtBindingSummary observeRuntimeIdentity(@PathVariable String id,
                                                   @Valid @RequestBody ObserveAtRuntimeIdentityRequest input) {
        return bindings.observeRuntimeIdentity(id, input.expectedSha256());
    }

    @PostMapping("/sources/{id}/at-binding/unbind")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@PathVariable String id, @Valid @RequestBody UnbindAtRequest input) {
        bindings.unbind(id, input.expectedSha256());
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @GetMapping("/sources")
    public List<SourceSummary> list() {
        return sources.list();
    }

    @PostMapping("/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public SourceSummary create(@Valid @RequestBody CreateSourceRequest input) {
        return sources.create(input);
    }

    @GetMapping("/sources/{id}")
    public SourceSummary get(@PathVariable String id) {
        return sources.get(id);
    }

    @GetMapping("/sources/{id}/profile")
    public ProfileSummary profile(@PathVariable String id) {
        return profiles.get(id);
    }

    @GetMapping("/sources/{id}/profiles")
    public List<ProfileSummary> profiles(@PathVariable String id) {
        return profiles.history(id);
    }

    @PostMapping("/sources/{id}/profiles")
    public ProfileSummary updateProfile(@PathVariable String id, @Valid @RequestBody UpdateProfileRequest input) {
        return profiles.update(id, input);
    }

    @GetMapping("/sources/{id}/artifacts")
    public List<ArtifactSummary> versions(@PathVariable String id) {
        return sources.versions(id);
    }

    @PostMapping("/sources/{id}/artifacts")
    public SourceSummary addArtifact(@PathVariable String id, @Valid @RequestBody AddArtifactRequest input) {
        return sources.addArtifact(id, input);
    }

    @GetMapping(value = "/sources/{id}/artifacts/{sha}", produces = "text/plain;charset=UTF-8")
    public byte[] content(@PathVariable String id, @PathVariable String sha) {
        return sources.artifact(id, sha).getBody().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/sources/{id}/probe-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProbeSummary start(@PathVariable String id, @Valid @RequestBody(required = false) StartProbeRequest input) {
        return probes.start(id, input == null || input.mode() == null ? "static" : input.mode());
    }

    @GetMapping("/runtime")
    public Map<String, Object> runtime() {
        return Map.of("sha256", runtime.sha256(), "manifest", runtime.manifest());
    }

    @GetMapping("/container-runtime")
    public Map<String, Object> containerRuntime() {
        return Map.of("sha256", container.sha256(), "snapshot", container.snapshot(),
                "blockedReason", java.util.Objects.toString(container.configurationBlockedReason(), ""));
    }

    @GetMapping("/managed-source-runtime")
    public Map<String, Object> managedSourceRuntime(@RequestParam(required = false) String sourceId) {
        if (sourceId != null) sources.get(sourceId);
        var selected = managed.forSource(sourceId);
        return Map.of("sha256", selected.sha256(), "manifest", selected.manifest(),
                "policySha256", selected.policySha256(), "snapshot", selected.snapshot(),
                "routeLabel", sourceSetup.sameHost() && (sourceId == null || !managed.hasSourceRoute(sourceId))
                        ? "AT/T4 内部运行环境（按需准备）" : managed.routeLabel(sourceId),
                "backend", sourceSetup.sameHost() ? "same_host_t4" : "external_managed_t4",
                "setupAvailable", sourceSetup.availableFor(sourceId),
                "blockedReason", java.util.Objects.toString(selected.configurationBlockedReason(), ""));
    }

    @GetMapping("/managed-source-runtime/connection")
    public ResponseEntity<JsonNode> managedSourceConnection(@RequestParam(required = false) String sourceId)
            throws InterruptedException {
        if (sourceId != null) sources.get(sourceId);
        var selected = managed.forSource(sourceId);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(sourceSetup.sameHost() && selected.configurationBlockedReason() != null
                        ? sourceSetup.localConnection() : selected.checkConnection());
    }

    @GetMapping("/fixtures/demo")
    public Map<String, String> fixture() {
        return Map.of("name", "内置离线示例", "filename", "demo.py", "content", runtime.fixtureContent(),
                "sha256", Hashes.sha256(runtime.fixtureContent()));
    }

    @GetMapping("/sources/{id}/probe-runs")
    public List<ProbeSummary> runs(@PathVariable String id) {
        return store.list(id);
    }

    @GetMapping("/probe-runs/{runId}")
    public ProbeSummary run(@PathVariable String runId) {
        return store.get(runId);
    }

    @PostMapping("/probe-runs/{runId}/cancel")
    public ProbeSummary cancel(@PathVariable String runId) {
        return probes.cancel(runId);
    }

    @GetMapping("/knowledge")
    public JsonNode knowledge(@RequestParam(required = false) String node,
                              @RequestParam(defaultValue = "") String failure,
                              @RequestParam(defaultValue = "t4") String runtime,
                              @RequestParam(defaultValue = "8") int limit) {
        return knowledge.context(node, failure, runtime, limit);
    }

    @GetMapping("/capability-nodes")
    public JsonNode nodes() {
        return knowledge.nodeCatalog();
    }

    @GetMapping("/sources/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id,
                                         @RequestParam(required = false) String sha) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"maintenance-source.zip\"")
                .body(exports.export(id, sha));
    }
}
