package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.AtPublication;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AtPublicationService {
    private static final Pattern PYTHON_ID = Pattern.compile("(?m)^\\s*PLUGIN_ID\\s*=\\s*['\"]([^'\"]+)['\"]\\s*(?:#.*)?$");
    private static final Pattern HEADER_ID = Pattern.compile("(?m)^\\s*//@id:([^\\s]+)\\s*$");
    private final RepairStore repairs;
    private final RepairExportService exports;
    private final AtBindingService bindings;
    private final AtPluginPublishClient client;
    private final AtPublicationStore publications;
    private final SourceService sources;
    @Value("${maintenance.at-publish.default-mode:incremental}")
    private String defaultMode = "incremental";

    public AtPublicationTarget target(String candidateId) {
        var candidate = repairs.candidate(candidateId);
        var repair = repairs.get(candidate.repairId());
        sources.requireMaintenanceAllowed(repair.sourceId());
        var binding = bindings.get(repair.sourceId());
        boolean verified = candidate.result() != null && candidate.result().path("repairSuccessful").asBoolean();
        String blocked = !verified ? "AT_PUBLISH_REAL_VALIDATION_REQUIRED"
                : binding != null && !binding.connectionMatches() ? "AT_PUBLISH_CONNECTION_CHANGED" : null;
        String targetSha = binding == null ? null : binding.sha256();
        if (binding == null && blocked == null) {
            try { targetSha = newSourceTargetSha(client.instanceKey(), repair); }
            catch (ResponseStatusException error) { blocked = error.getReason(); }
        }
        return new AtPublicationTarget(blocked == null, blocked, binding == null ? "incremental" : mode(null), targetSha,
                binding == null ? null : binding.snapshot().path("plugin").path("id").asInt(),
                binding == null ? null : binding.snapshot().path("plugin").path("name").asText(),
                exports.delivery(candidateId), publications.list(candidateId));
    }

    public synchronized AtPublicationSummary publish(String candidateId, StartAtPublicationRequest input) {
        require(input.authorized(), "AT_PUBLISH_AUTHORIZATION_REQUIRED");
        var target = target(candidateId);
        require(target.available(), target.blockedReason());
        require(target.delivery().candidateSha256().equals(input.expectedCandidateSha256()), "AT_PUBLISH_CANDIDATE_CHANGED");
        require(Objects.equals(target.bindingSha256(), input.expectedBindingSha256()), "AT_PUBLISH_BINDING_CHANGED");
        String selectedMode = mode(input.mode() == null ? target.defaultMode() : input.mode());
        require(target.originalPluginId() != null || "incremental".equals(selectedMode),
                "AT_PUBLISH_NEW_SOURCE_INCREMENTAL_ONLY");
        var delivery = input.deliveryName() == null ? target.delivery()
                : SpiderDeliveryNames.renamed(target.delivery(), input.deliveryName());
        String requestIdentity = String.join("\n", candidateId, input.expectedCandidateSha256(),
                input.expectedBindingSha256(), selectedMode);
        if (input.deliveryName() != null) requestIdentity += "\n" + delivery.pluginName();
        String requestSha = Hashes.sha256(requestIdentity);
        AtPublication record = publications.previous(input.requestKey());
        if (record != null) {
            require(record.getRequestSha256().equals(requestSha), "AT_PUBLISH_REQUEST_KEY_REUSED");
            if ("PUBLISHED".equals(record.getState())) return publications.summary(record);
        }
        require(SpiderDeliveryNames.localized(delivery.pluginName()), "AT_PUBLISH_CHINESE_NAME_REQUIRED");
        var candidate = repairs.candidate(candidateId);
        var repair = repairs.get(candidate.repairId());
        var binding = bindings.get(repair.sourceId());
        byte[] content = repairs.content(candidateId).getBytes(StandardCharsets.UTF_8);
        require(Hashes.sha256(content).equals(input.expectedCandidateSha256()), "AT_PUBLISH_CANDIDATE_CHANGED");
        try (var session = client.open()) {
            if (binding == null) require(target.bindingSha256().equals(newSourceTargetSha(session.instanceKey(), repair)),
                    "AT_PUBLISH_CONNECTION_CHANGED");
            else require(session.instanceKey().equals(binding.snapshot().path("connection").path("instanceKey").asText()),
                    "AT_PUBLISH_CONNECTION_CHANGED");
            JsonNode observedPlugins = session.plugins();
            JsonNode original = target.originalPluginId() == null ? null : plugin(observedPlugins, target.originalPluginId());
            String originalPath = null;
            if (original != null) {
                require(Hashes.sha256(original.path("url").asText()).equals(
                        binding.snapshot().path("plugin").path("sourceLocatorSha256").asText()), "AT_PUBLISH_ORIGINAL_CHANGED");
                originalPath = "replace".equals(selectedMode)
                        ? session.customPath(original) : session.originalSourcePath(original);
                String currentSha = Hashes.sha256(session.source(originalPath, false));
                require(currentSha.equals(repair.baselineArtifactSha256()) || currentSha.equals(candidate.sha256()),
                        "AT_PUBLISH_ORIGINAL_CHANGED");
            }
            String path = "replace".equals(selectedMode) ? originalPath
                    : "/static/plugins/维护平台/" + repair.sourceId() + "/" + candidateId + "/" + delivery.filename();
            if ("incremental".equals(selectedMode)) {
                String fixedId = declaredId(new String(content, StandardCharsets.UTF_8));
                if (fixedId != null) for (JsonNode item : observedPlugins) {
                    require(!fixedId.equals(item.path("externalId").asText())
                                    || path.equals(item.path("url").asText()),
                            "AT_PUBLISH_INCREMENTAL_ID_CONFLICT");
                }
            }
            if (record == null) {
                record = new AtPublication();
                record.setRequestKey(input.requestKey());
                record.setRequestSha256(requestSha);
                record.setCandidateId(candidateId);
                record.setInstanceKey(session.instanceKey());
                record.setMode(selectedMode);
                record.setOriginalPluginId(target.originalPluginId());
                record.setTargetPath(path);
                record.setBeforePluginJson(original == null ? "null" : original.toString());
                record.setPluginName(delivery.pluginName() + "_v" + delivery.version());
                record.setDeliveryVersion(delivery.version());
                record.setCandidateSha256(candidate.sha256());
                record.setBaselineSha256(repair.baselineArtifactSha256());
                record = publications.save(record);
            }
            require(record.getInstanceKey().equals(session.instanceKey()) && record.getTargetPath().equals(path),
                    "AT_PUBLISH_CONNECTION_CHANGED");
            byte[] existing = session.source(path, true);
            require(existing == null || Hashes.sha256(existing).equals(candidate.sha256())
                    || "replace".equals(selectedMode) && Hashes.sha256(existing).equals(repair.baselineArtifactSha256()),
                    "AT_PUBLISH_TARGET_CHANGED");
            // The immutable path lets an explicit retry reconcile a lost upload response without creating another plugin.
            if (existing == null || !Hashes.sha256(existing).equals(candidate.sha256())) session.upload(path, content);
            record.setState("FILE_STORED");
            record.setErrorCode(null);
            record = publications.save(record);
            JsonNode published = pluginAtPath(session, path);
            require("incremental".equals(selectedMode)
                            ? !Objects.equals(published.path("id").asInt(), target.originalPluginId())
                            : Objects.equals(published.path("id").asInt(), target.originalPluginId()),
                    "AT_PUBLISH_PLUGIN_ID_CHANGED");
            if ("replace".equals(selectedMode)) session.refresh(published.path("id").asInt());
            session.rename(published, record.getPluginName(), original == null ? published : original);
            JsonNode confirmed = plugin(session.plugins(), published.path("id").asInt());
            require(record.getPluginName().equals(confirmed.path("name").asText())
                    && session.customPath(confirmed).equals(path)
                    && confirmed.path("lastError").asText("").isBlank()
                    && candidate.sha256().equals(Hashes.sha256(session.source(path, false))), "AT_PUBLISH_READBACK_FAILED");
            record.setPublishedPluginId(confirmed.path("id").asInt());
            if ("incremental".equals(selectedMode) && original != null) markOriginalDamaged(session, record, original);
            record.setState("PUBLISHED");
            record.setErrorCode(null);
            return publications.summary(publications.save(record));
        } catch (ResponseStatusException error) {
            if (record != null) {
                record.setState("NEEDS_CONFIRMATION");
                record.setErrorCode(error.getReason());
                publications.save(record);
            }
            throw error;
        }
    }

    String instanceKey() { return client.instanceKey(); }

    private String newSourceTargetSha(String instanceKey, RepairSummary repair) {
        return Hashes.sha256(String.join("\n", "new-source", instanceKey, repair.sourceId(), repair.baselineArtifactSha256()));
    }

    private void markOriginalDamaged(AtPluginPublishClient.Session session, AtPublication record, JsonNode original) {
        JsonNode current = plugin(session.plugins(), record.getOriginalPluginId());
        require(original.path("url").equals(current.path("url")), "AT_PUBLISH_ORIGINAL_CHANGED");
        String name = current.path("name").asText();
        String marked = name.startsWith("损坏_") || name.startsWith("损坏-") || name.startsWith("[损坏]")
                || name.startsWith("【损坏】") ? name : "损坏_" + name;
        // Preserve the live configuration, not an older binding snapshot, when changing only the name.
        if (!marked.equals(name)) session.rename(current, marked, current);
        JsonNode confirmed = plugin(session.plugins(), record.getOriginalPluginId());
        require(marked.equals(confirmed.path("name").asText())
                && current.path("url").equals(confirmed.path("url"))
                && current.path("extend").equals(confirmed.path("extend"))
                && current.path("enabled").equals(confirmed.path("enabled")), "AT_PUBLISH_ORIGINAL_MARK_FAILED");
    }

    private void requireMaintenanceAllowed(String candidateId) {
        sources.requireMaintenanceAllowed(repairs.get(repairs.candidate(candidateId).repairId()).sourceId());
    }

    private JsonNode pluginAtPath(AtPluginPublishClient.Session session, String path) {
        for (JsonNode item : session.plugins()) {
            try { if (path.equals(session.customPath(item))) return item; }
            catch (ResponseStatusException ignored) { /* Other plugin kinds are not publication targets. */ }
        }
        throw failure("AT_PUBLISH_REGISTRATION_PENDING");
    }

    public synchronized AtPublicationSummary rename(String candidateId, String publicationId,
                                                    RenameAtPublicationRequest input) {
        require(input.authorized(), "AT_PUBLISH_AUTHORIZATION_REQUIRED");
        requireMaintenanceAllowed(candidateId);
        var record = published(candidateId, publicationId);
        require(record.getCandidateSha256().equals(input.expectedCandidateSha256()), "AT_PUBLISH_CANDIDATE_CHANGED");
        String name = SpiderDeliveryNames.renamed(exports.delivery(candidateId), input.deliveryName()).pluginName()
                + "_v" + record.getDeliveryVersion();
        require(SpiderDeliveryNames.localized(input.deliveryName()), "AT_PUBLISH_CHINESE_NAME_REQUIRED");
        require(record.getPluginName().equals(input.expectedPluginName()) || record.getPluginName().equals(name),
                "AT_PUBLISH_NAME_CHANGED");
        try (var session = client.open()) {
            require(session.instanceKey().equals(record.getInstanceKey()), "AT_PUBLISH_CONNECTION_CHANGED");
            var current = plugin(session.plugins(), record.getPublishedPluginId());
            require(record.getTargetPath().equals(session.customPath(current)), "AT_PUBLISH_TARGET_CHANGED");
            require(record.getCandidateSha256().equals(Hashes.sha256(session.source(record.getTargetPath(), false))),
                    "AT_PUBLISH_TARGET_CHANGED");
            require(current.path("name").asText().equals(input.expectedPluginName())
                    || current.path("name").asText().equals(name), "AT_PUBLISH_NAME_CHANGED");
            if (!current.path("name").asText().equals(name)) session.rename(current, name, current);
            var confirmed = plugin(session.plugins(), record.getPublishedPluginId());
            require(name.equals(confirmed.path("name").asText())
                    && current.path("url").equals(confirmed.path("url"))
                    && current.path("extend").equals(confirmed.path("extend"))
                    && current.path("enabled").equals(confirmed.path("enabled")), "AT_PUBLISH_READBACK_FAILED");
            record.setPluginName(name);
            return publications.summary(publications.save(record));
        }
    }

    public AtPublicationRuntime runtime(String candidateId, String publicationId) {
        var record = published(candidateId, publicationId);
        try (var session = client.open()) { return runtime(record, session); }
    }

    public synchronized AtPublicationRuntime activate(String candidateId, String publicationId,
                                                        ActivateAtPublicationRequest input) {
        require(input.authorized(), "AT_PUBLISH_AUTHORIZATION_REQUIRED");
        requireMaintenanceAllowed(candidateId);
        var record = published(candidateId, publicationId);
        try (var session = client.open()) {
            var current = runtime(record, session);
            if ("ACTIVE".equals(current.state())) return current;
            require(current.activationAvailable(), current.reason() == null ? "AT_ACTIVATION_UNAVAILABLE" : current.reason());
            var target = current.target();
            boolean recordedRequest = input.activationId() == null && target.activations().stream()
                    .anyMatch(row -> row.requestKey().equals(input.requestKey()));
            // Reconcile a lost reply through AT's durable request before comparing the now-changed generation.
            if (!recordedRequest) {
                require(target.configurationSha256().equals(input.expectedConfigurationSha256()),
                        "AT_DEPLOYMENT_PLUGIN_CHANGED");
                require(Objects.equals(target.runtime().workerGeneration(), input.expectedWorkerGeneration()),
                        "AT_WORKER_GENERATION_CHANGED");
            }
            if (input.activationId() != null) require(target.activations().stream()
                    .anyMatch(row -> row.id().equals(input.activationId())
                            && Set.of("PREPARED", "RECOVERY_REQUIRED").contains(row.state())),
                    "AT_ACTIVATION_NOT_RESUMABLE");
            var result = session.activate(record.getPublishedPluginId(), new StartAtRuntimeActivationRequest(
                    record.getCandidateSha256(), input.expectedConfigurationSha256(), input.expectedWorkerGeneration(),
                    input.requestKey(), input.interruptIssuedPlayback()), input.activationId());
            var observed = runtime(record, session);
            return new AtPublicationRuntime(observed.state(), result.errorCode(), observed.activationAvailable(), observed.target());
        }
    }

    private AtPublication published(String candidateId, String id) {
        var record = publications.get(id);
        require(record.getCandidateId().equals(candidateId) && "PUBLISHED".equals(record.getState())
                && record.getPublishedPluginId() != null, "AT_PUBLICATION_NOT_PUBLISHED");
        return record;
    }

    private AtPublicationRuntime runtime(AtPublication record, AtPluginPublishClient.Session session) {
        require(session.instanceKey().equals(record.getInstanceKey()), "AT_PUBLISH_CONNECTION_CHANGED");
        var plugin = plugin(session.plugins(), record.getPublishedPluginId());
        require(record.getTargetPath().equals(session.customPath(plugin)), "AT_PUBLISH_TARGET_CHANGED");
        if (!record.getCandidateSha256().equals(Hashes.sha256(session.source(record.getTargetPath(), false))))
            return new AtPublicationRuntime("SAVED_VERSION_CHANGED", "AT_PUBLISH_TARGET_CHANGED", false, null);
        var target = session.savedRuntime(record.getPublishedPluginId());
        if (target == null) return new AtPublicationRuntime("UNOBSERVED", "AT_RUNTIME_INTERFACE_UNAVAILABLE", false, null);
        require(target.pluginId() == record.getPublishedPluginId() && target.runtime() != null,
                "AT_PUBLISH_RESPONSE_INVALID");
        boolean sameSaved = record.getCandidateSha256().equals(target.pluginSha256());
        if (!sameSaved) return new AtPublicationRuntime("SAVED_VERSION_CHANGED", "AT_PUBLISH_TARGET_CHANGED", false, target);
        var loaded = target.runtime();
        String state = "LOADED".equals(loaded.loadState())
                ? record.getCandidateSha256().equals(loaded.sourceSha256()) ? "ACTIVE" : "RELOAD_REQUIRED"
                : "BLOCKED".equals(loaded.loadState()) ? "RECOVERY_REQUIRED" : "NOT_LOADED";
        return new AtPublicationRuntime(state, target.enabled() ? null : "AT_DEPLOYMENT_DISABLED",
                target.enabled() && !"ACTIVE".equals(state), target);
    }

    private JsonNode plugin(JsonNode items, int id) {
        for (JsonNode item : items) if (item.path("id").asInt() == id) return item;
        throw failure("AT_PUBLISH_PLUGIN_NOT_FOUND");
    }

    private String mode(String requested) {
        String value = requested == null ? defaultMode : requested;
        require(Set.of("incremental", "replace").contains(value), "AT_PUBLISH_MODE_INVALID");
        return value;
    }

    private String declaredId(String content) {
        var python = PYTHON_ID.matcher(content);
        if (python.find()) return python.group(1);
        var header = HEADER_ID.matcher(content);
        return header.find() ? header.group(1) : null;
    }

    private static void require(boolean condition, String code) { if (!condition) throw failure(code); }
    private static ResponseStatusException failure(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
}
