package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.*;
import cn.har01d.atmaintenance.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class UpstreamService {
    private final UpstreamRepository upstream;
    private final UpstreamEventRepository events;
    private final SourceRepository sources;
    private final ArtifactRepository artifacts;
    private final SourceService sourceService;
    private final ObjectMapper mapper;
    private final AtReadClient at;
    private final AtBindingService bindings;
    private final PluginInputService inputs;

    @Transactional
    public UpstreamSummary get(String sourceId) {
        var source = lockSource(sourceId);
        return upstream.findById(sourceId).map(state -> summary(state, source)).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<UpstreamEventSummary> history(String sourceId) {
        sourceService.requireSource(sourceId);
        return events.findTop20BySourceIdOrderByCreatedAtDesc(sourceId).stream().map(this::receipt).toList();
    }

    @Transactional
    public UpstreamEventSummary initialize(String sourceId, InitializeUpstreamRequest input) {
        var source = lockSource(sourceId);
        String requestSha = requestSha("INITIALIZE", input.expectedArtifactSha256(), input.baselineSha256());
        var replay = replay(sourceId, input.requestKey(), requestSha);
        if (replay != null) return replay;
        checkSource(source, input.expectedArtifactSha256());
        if (upstream.existsById(sourceId)) throw conflict("UPSTREAM_ALREADY_INITIALIZED");
        // The operator selects an existing baseline. An origin URL never proves provenance.
        sourceService.artifact(sourceId, input.baselineSha256());
        UpstreamState state = new UpstreamState();
        state.setSourceId(sourceId);
        state.setBaseSha256(input.baselineSha256());
        state.setHeadSha256(input.baselineSha256());
        state.setUpdatedAt(now());
        upstream.saveAndFlush(state);
        return saveEvent(sourceId, input.requestKey(), requestSha, "INITIALIZE", null, summary(state, source));
    }

    @Transactional
    public UpstreamEventSummary observe(String sourceId, ObserveUpstreamRequest input) {
        if (input.content().getBytes(StandardCharsets.UTF_8).length > 1_048_576)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "SOURCE_TOO_LARGE");
        var source = lockSource(sourceId);
        String sha = Hashes.sha256(input.content());
        String requestSha = requestSha("OBSERVE", input.expectedRevision(), input.expectedArtifactSha256(), sha);
        var replay = replay(sourceId, input.requestKey(), requestSha);
        if (replay != null) return replay;
        var state = checkedState(source, input.expectedRevision(), input.expectedArtifactSha256());
        var before = summary(state, source);
        retainObservation(source, state, input.content(), null);
        return saveEvent(sourceId, input.requestKey(), requestSha, "OBSERVE", before, summary(state, source));
    }

    @Transactional
    public UpstreamEventSummary fetchAt(String sourceId, FetchAtUpstreamRequest input) {
        sourceService.requireMaintenanceAllowed(sourceId);
        if (!input.authorized())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PLUGIN_PLAINTEXT_AUTHORIZATION_REQUIRED");
        var source = lockSource(sourceId);
        String requestSha = requestSha("FETCH_AT", input.expectedRevision(), input.expectedArtifactSha256(),
                input.expectedBindingSha256());
        var replay = replay(sourceId, input.requestKey(), requestSha);
        if (replay != null) return replay;
        checkSource(source, input.expectedArtifactSha256());
        var binding = checkedBinding(sourceId, input.expectedBindingSha256());
        int pluginId = binding.snapshot().path("plugin").path("id").asInt();
        at.requireMaintenanceAllowed(pluginId);
        var state = upstream.findById(sourceId).orElse(null);
        String baseline = null;
        if (state == null) {
            if (input.expectedRevision() != null) throw conflict("UPSTREAM_NOT_INITIALIZED");
            // Only a stored AT import can establish an automatic comparison base.
            baseline = inputs.list(sourceId).stream().filter(item -> {
                var origin = item.evidence().path("atSource");
                return origin.path("instanceKey").asText().equals(binding.snapshot().path("connection").path("instanceKey").asText())
                        && origin.path("pluginId").asInt() == pluginId
                        && origin.path("sourceSha256").asText().equals(item.artifactSha256());
            }).map(PluginInputSummary::artifactSha256).findFirst()
                    .orElseThrow(() -> conflict("UPSTREAM_AT_BASELINE_REQUIRED"));
            sourceService.artifact(sourceId, baseline);
        } else if (input.expectedRevision() == null || state.getRowVersion() != input.expectedRevision()) {
            throw conflict("UPSTREAM_CHANGED");
        }
        var observation = at.observe();
        var prepared = inputs.prepareAt(new PreviewAtPluginInputRequest(observation.sha256(), pluginId, true));
        checkedBinding(sourceId, input.expectedBindingSha256());
        if (!observation.connection().instanceKey().equals(at.connection().instanceKey()))
            throw conflict("AT_CONNECTION_CHANGED");
        var evidence = prepared.evidence().path("atSource").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) evidence)
                .put("bindingSha256", binding.sha256()).put("sourceExecuted", false)
                .put("runtimeApplied", false).put("publishEligible", false);
        var before = state == null ? null : summary(state, source);
        if (state == null) {
            state = new UpstreamState();
            state.setSourceId(sourceId);
            state.setBaseSha256(baseline);
            state.setHeadSha256(baseline);
            state.setUpdatedAt(now());
            state = upstream.saveAndFlush(state);
        }
        retainObservation(source, state, prepared.content(), evidence);
        return saveEvent(sourceId, input.requestKey(), requestSha, "FETCH_AT", before, summary(state, source));
    }

    private AtBindingSummary checkedBinding(String sourceId, String expected) {
        var binding = bindings.current(sourceId);
        if (binding == null) throw conflict("UPSTREAM_AT_BINDING_REQUIRED");
        if (!binding.sha256().equals(expected)) throw conflict("AT_BINDING_CHANGED");
        if (!binding.connectionMatches()) throw conflict("AT_CONNECTION_CHANGED");
        return binding;
    }

    private void retainObservation(MaintenanceSource source, UpstreamState state, String content, JsonNode evidence) {
        String sourceId = source.getId();
        String sha = Hashes.sha256(content);
        artifacts.findBySourceIdAndSha256(sourceId, sha).orElseGet(() -> {
            var artifact = new MaintenanceArtifact();
            artifact.setSourceId(sourceId);
            artifact.setSha256(sha);
            artifact.setParentSha256(state.getHeadSha256());
            artifact.setBody(content);
            return artifacts.saveAndFlush(artifact);
        });
        state.setHeadSha256(sha);
        state.setHeadObservationJson(evidence == null ? null : evidence.toString());
        if (sha.equals(sourceService.currentArtifact(source).getSha256())) state.setBaseSha256(sha);
        advance(state);
    }

    @Transactional
    public UpstreamEventSummary resolve(String sourceId, ResolveUpstreamRequest input) {
        if (!Set.of("FAST_FORWARD", "KEEP_LOCAL").contains(input.decision()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_UPSTREAM_DECISION");
        var source = lockSource(sourceId);
        String requestSha = requestSha(input.decision(), input.expectedRevision(), input.expectedArtifactSha256());
        var replay = replay(sourceId, input.requestKey(), requestSha);
        if (replay != null) return replay;
        var state = checkedState(source, input.expectedRevision(), input.expectedArtifactSha256());
        var before = summary(state, source);
        if (input.decision().equals("FAST_FORWARD")) {
            if (!before.effectiveSha256().equals(state.getBaseSha256())
                    && !before.effectiveSha256().equals(state.getHeadSha256()))
                throw conflict("UPSTREAM_LOCAL_CHANGES");
            source.setCurrentArtifactId(sourceService.artifact(sourceId, state.getHeadSha256()).getId());
            sources.flush();
        } else if (!before.status().equals("DIVERGED")) {
            throw conflict("UPSTREAM_NOT_DIVERGED");
        }
        // KEEP_LOCAL acknowledges the new comparison base; it does not claim integration or validation.
        state.setBaseSha256(state.getHeadSha256());
        advance(state);
        return saveEvent(sourceId, input.requestKey(), requestSha, input.decision(), before, summary(state, source));
    }

    /** Freeze all three roles under the same source lock used by every pointer writer. */
    @Transactional
    public byte[] export(String sourceId) throws IOException {
        var source = lockSource(sourceId);
        var state = upstream.findById(sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "UPSTREAM_NOT_INITIALIZED"));
        var current = summary(state, source);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("base.py", body(sourceId, current.baseSha256()));
        files.put("upstream.py", body(sourceId, current.headSha256()));
        files.put("effective.py", body(sourceId, current.effectiveSha256()));
        files.put("coordination.json", mapper.writeValueAsBytes(current));
        if (current.headObservation() != null)
            files.put("upstream-observation.json", mapper.writeValueAsBytes(current.headObservation()));
        Map<String, Object> hashes = new LinkedHashMap<>();
        files.forEach((name, bytes) -> hashes.put(name, Map.of("bytes", bytes.length, "sha256", Hashes.sha256(bytes))));
        var manifest = mapper.createObjectNode().put("schemaVersion", 1)
                .put("kind", "upstream_three_way_comparison").put("authority", current.authority())
                .put("sourceHealthEligible", false).put("publishEligible", false).put("runtimeApplied", false)
                .put("boundary", "上游与本地源码的三方比较包；未认证上游作者，未合并、执行、验证或部署。"
                        + "当前有效版本仅指独立台账指针；不是 AT 实际加载回执。此包不是单源恢复包或全库备份。");
        manifest.set("files", mapper.valueToTree(hashes));
        files.put("manifest.json", mapper.writeValueAsBytes(manifest));
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private byte[] body(String sourceId, String sha) {
        return sourceService.artifact(sourceId, sha).getBody().getBytes(StandardCharsets.UTF_8);
    }

    private MaintenanceSource lockSource(String sourceId) {
        return sources.findForUpdate(sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND"));
    }

    private void checkSource(MaintenanceSource source, String expected) {
        if (!sourceService.currentArtifact(source).getSha256().equals(expected)) throw conflict("SOURCE_CHANGED");
    }

    private UpstreamState checkedState(MaintenanceSource source, long revision, String expected) {
        checkSource(source, expected);
        var state = upstream.findById(source.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "UPSTREAM_NOT_INITIALIZED"));
        if (state.getRowVersion() != revision) throw conflict("UPSTREAM_CHANGED");
        return state;
    }

    private void advance(UpstreamState state) {
        // Even an identical observation consumes its expected revision and receives a distinct receipt.
        var observed = now();
        state.setUpdatedAt(observed.isAfter(state.getUpdatedAt()) ? observed
                : state.getUpdatedAt().plus(1, ChronoUnit.MICROS));
        upstream.flush();
    }

    private Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    private UpstreamSummary summary(UpstreamState state, MaintenanceSource source) {
        String effective = sourceService.currentArtifact(source).getSha256();
        String base = state.getBaseSha256(), head = state.getHeadSha256();
        String status = head.equals(effective) ? "IN_SYNC" : head.equals(base) ? "LOCAL_AHEAD"
                : effective.equals(base) ? "UPSTREAM_AHEAD" : "DIVERGED";
        JsonNode observation = null;
        if (state.getHeadObservationJson() != null) {
            try { observation = mapper.readTree(state.getHeadObservationJson()); }
            catch (JsonProcessingException error) { throw new IllegalStateException("Stored upstream observation is invalid", error); }
        }
        return new UpstreamSummary(source.getId(), state.getRowVersion(), base, head, effective,
                status, state.getUpdatedAt(), observation == null ? "operator_supplied_source" : "at_readonly_source",
                false, false, observation);
    }

    private String requestSha(Object... values) {
        return Hashes.sha256(mapper.valueToTree(values).toString());
    }

    private UpstreamEventSummary replay(String sourceId, String key, String requestSha) {
        var existing = events.findBySourceIdAndRequestKey(sourceId, key);
        if (existing.isEmpty()) return null;
        if (!existing.get().getRequestSha256().equals(requestSha)) throw conflict("UPSTREAM_REQUEST_KEY_REUSED");
        return receipt(existing.get());
    }

    private UpstreamEventSummary saveEvent(String sourceId, String key, String requestSha, String operation,
                                           UpstreamSummary before, UpstreamSummary after) {
        var event = new UpstreamEvent();
        event.setSourceId(sourceId);
        event.setRequestKey(key);
        event.setRequestSha256(requestSha);
        event.setCreatedAt(now());
        var result = new UpstreamEventSummary(event.getId(), sourceId, operation, requestSha,
                before, after, event.getCreatedAt());
        try {
            event.setReceiptJson(mapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode upstream receipt", e);
        }
        events.saveAndFlush(event);
        return result;
    }

    private UpstreamEventSummary receipt(UpstreamEvent event) {
        try {
            return mapper.readValue(event.getReceiptJson(), UpstreamEventSummary.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored upstream receipt is invalid", e);
        }
    }

    private ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
}
