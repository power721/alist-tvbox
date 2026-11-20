package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.AtBinding;
import cn.har01d.atmaintenance.repository.AtBindingRepository;
import cn.har01d.atmaintenance.repository.SourceRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AtBindingService {
    private final AtBindingRepository bindings;
    private final SourceRepository sources;
    private final AtReadClient client;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true)
    public AtBindingSummary get(String sourceId) {
        if (!sources.existsById(sourceId)) throw error("SOURCE_NOT_FOUND", HttpStatus.NOT_FOUND);
        return current(sourceId);
    }

    public AtBindingSummary current(String sourceId) {
        return bindings.findById(sourceId).map(this::summary).orElse(null);
    }

    @Transactional
    public AtBindingSummary bind(String sourceId, BindAtRequest input) {
        client.requireMaintenanceAllowed(input.pluginId());
        var source = sources.findById(sourceId).orElseThrow(() -> error("SOURCE_NOT_FOUND", HttpStatus.NOT_FOUND));
        var old = bindings.findById(sourceId).orElse(null);
        if (!Objects.equals(old == null ? null : old.getSha256(), input.expectedSha256())) {
            throw error("AT_BINDING_CHANGED", HttpStatus.CONFLICT);
        }
        var observation = client.requireObservation(input.observationSha256());
        var plugin = observation.plugins().stream().filter(p -> p.id() == input.pluginId()).findFirst()
                .orElseThrow(() -> error("AT_PLUGIN_NOT_OBSERVED", HttpStatus.CONFLICT));
        if (!java.util.Set.of("python", "secspider").contains(plugin.kind())) {
            throw error("AT_PLUGIN_TYPE_UNSUPPORTED", HttpStatus.CONFLICT);
        }
        if (source.getExternalId() != null && !source.getExternalId().equals(plugin.externalId())) {
            throw error("AT_EXTERNAL_ID_CONFLICT", HttpStatus.CONFLICT);
        }
        bindings.findByInstanceKeyAndPluginId(observation.connection().instanceKey(), plugin.id())
                .filter(binding -> !binding.getSourceId().equals(sourceId)).ifPresent(binding -> {
                    throw error("AT_PLUGIN_ALREADY_BOUND", HttpStatus.CONFLICT);
                });
        var snapshot = mapper.createObjectNode();
        snapshot.put("schemaVersion", 1);
        snapshot.put("sourceId", sourceId);
        snapshot.set("connection", mapper.valueToTree(observation.connection()));
        snapshot.put("observationSha256", observation.sha256());
        snapshot.put("observedAt", observation.observedAt().toString());
        snapshot.set("plugin", mapper.valueToTree(plugin));
        snapshot.put("authority", "metadata_observed");
        snapshot.put("siteKeyAuthority", "derived_from_baseline");
        snapshot.put("boundary", "管理 API 身份绑定；未取得插件正文、实际源码 SHA、订阅覆盖后的 siteKey 或运行时装载回执。");
        String json = snapshot.toString();
        AtBinding saved = old == null ? new AtBinding() : old;
        saved.setSourceId(sourceId);
        saved.setInstanceKey(observation.connection().instanceKey());
        saved.setPluginId(plugin.id());
        saved.setSnapshotJson(json);
        saved.setSha256(Hashes.sha256(json));
        return summary(bindings.saveAndFlush(saved));
    }

    @Transactional
    public AtBindingSummary observeRuntimeIdentity(String sourceId, String expectedSha) {
        if (!sources.existsById(sourceId)) throw error("SOURCE_NOT_FOUND", HttpStatus.NOT_FOUND);
        AtBinding binding = bindings.findById(sourceId)
                .orElseThrow(() -> error("AT_BINDING_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (!binding.getSha256().equals(expectedSha)) throw error("AT_BINDING_CHANGED", HttpStatus.CONFLICT);
        JsonNode original;
        try {
            original = mapper.readTree(binding.getSnapshotJson());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored AT binding is invalid", e);
        }
        int pluginId = original.path("plugin").path("id").asInt(0);
        String siteKey = original.path("plugin").path("derivedSiteKey").asText("");
        AtRuntimeIdentity runtime = client.observeRuntimeIdentity(pluginId, siteKey);
        var snapshot = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
        snapshot.put("schemaVersion", 2);
        snapshot.set("runtimeIdentity", mapper.valueToTree(runtime));
        snapshot.put("runtimeIdentitySha256", Hashes.sha256(mapper.valueToTree(runtime).toString()));
        snapshot.put("runtimeIdentityAuthority", runtime.authority());
        snapshot.put("runtimeIdentityState", runtime.loadState());
        snapshot.put("siteKeyAuthority", "runtime_observed");
        snapshot.put("boundary", runtime.boundary());
        String json = snapshot.toString();
        binding.setSnapshotJson(json);
        binding.setSha256(Hashes.sha256(json));
        return summary(bindings.saveAndFlush(binding));
    }

    @Transactional
    public void unbind(String sourceId, String expectedSha) {
        AtBinding old = bindings.findById(sourceId).orElseThrow(() -> error("AT_BINDING_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (!old.getSha256().equals(expectedSha)) throw error("AT_BINDING_CHANGED", HttpStatus.CONFLICT);
        bindings.delete(old);
        bindings.flush();
    }

    private AtBindingSummary summary(AtBinding binding) {
        try {
            return new AtBindingSummary(binding.getSourceId(), binding.getSha256(),
                    mapper.readTree(binding.getSnapshotJson()),
                    binding.getInstanceKey().equals(client.connection().instanceKey()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored AT binding is invalid", e);
        }
    }

    private ResponseStatusException error(String code, HttpStatus status) {
        return new ResponseStatusException(status, code);
    }
}
