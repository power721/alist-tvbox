package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.StartAtPublicationRequest;
import cn.har01d.atmaintenance.dto.PublicationQueueRequest;
import cn.har01d.atmaintenance.repository.RepairCandidateRepository;
import cn.har01d.atmaintenance.repository.RepairRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutomaticPublicationService {
    private final MaintenanceAutomationStore tasks;
    private final RepairRepository sessions;
    private final RepairCandidateRepository candidates;
    private final RepairStore repairs;
    private final AtPublicationService publisher;
    private final AtPublicationStore publications;
    private final AtBindingService bindings;
    private final ObjectMapper mapper;
    @Value("${maintenance.at-publish.automatic-enabled:false}")
    private boolean enabled;
    @Value("${maintenance.at-publish.automatic-instance-key:}")
    private String instanceKey = "";

    public boolean enabled() { return enabled && !instanceKey.isBlank(); }

    public com.fasterxml.jackson.databind.node.ObjectNode status(String candidateId) {
        repairs.candidate(candidateId);
        return tasks.summary(tasks.get("publication", candidateId)).put("enabled", enabled())
                .put("defaultMode", "incremental");
    }

    public synchronized void repairSucceeded(String repairId) {
        if (!enabled()) return;
        var repair = repairs.get(repairId);
        if (!"RUNTIME_OBSERVED".equals(repair.status()) || !"current".equals(repair.freshness())) return;
        var history = repairs.candidates(repairId);
        if (history.isEmpty()) return;
        var candidate = history.getLast();
        if (candidate.result() == null || !candidate.result().path("repairSuccessful").asBoolean()
                || tasks.get("publication", candidate.id()) != null
                || publications.list(candidate.id()).stream().anyMatch(row -> "PUBLISHED".equals(row.state()))) return;
        var context = mapper.createObjectNode().put("candidateSha256", candidate.sha256())
                .put("instanceKey", instanceKey).put("mode", "incremental")
                .put("trigger", "automatic_repair_succeeded").put("repairId", repairId);
        try {
            var target = publisher.target(candidate.id());
            queue(candidate.id(), new PublicationQueueRequest(candidate.sha256(), target.bindingSha256(), "incremental", true));
            var task = tasks.get("publication", candidate.id());
            var queued = tasks.context(task);
            queued.put("trigger", "automatic_repair_succeeded").put("repairId", repairId);
            tasks.save(task, task.getState(), queued, task.getErrorCode());
        } catch (ResponseStatusException error) {
            var task = tasks.enqueue("publication", candidate.id(), repair.sourceId(), context);
            tasks.save(task, "BLOCKED", context, error.getReason());
        } catch (RuntimeException error) {
            var task = tasks.enqueue("publication", candidate.id(), repair.sourceId(), context);
            tasks.save(task, "BLOCKED", context, "AUTOMATIC_PUBLICATION_INTERNAL_ERROR");
            log.error("Automatic publication enqueue failed: candidate={}, exception={}", candidate.id(), error.getClass().getName());
        }
    }

    public synchronized com.fasterxml.jackson.databind.node.ObjectNode queue(String candidateId, PublicationQueueRequest input) {
        if (!input.authorized()) throw conflict("AT_PUBLISH_AUTHORIZATION_REQUIRED");
        if (!enabled()) throw conflict("AUTOMATIC_PUBLICATION_DISABLED");
        var target = publisher.target(candidateId);
        if (!target.available()) throw conflict(target.blockedReason());
        if (!Objects.equals(input.expectedCandidateSha256(), target.delivery().candidateSha256()))
            throw conflict("AT_PUBLISH_CANDIDATE_CHANGED");
        if (!Objects.equals(input.expectedBindingSha256(), target.bindingSha256())) throw conflict("AT_PUBLISH_BINDING_CHANGED");
        if (!Set.of("incremental", "replace").contains(input.mode())) throw conflict("AT_PUBLISH_MODE_INVALID");
        var repair = repairs.get(repairs.candidate(candidateId).repairId());
        var binding = bindings.get(repair.sourceId());
        if (binding == null && !"incremental".equals(input.mode()))
            throw conflict("AT_PUBLISH_NEW_SOURCE_INCREMENTAL_ONLY");
        if (!instanceKey.equals(binding == null ? publisher.instanceKey()
                : binding.snapshot().path("connection").path("instanceKey").asText()))
            throw conflict("AT_PUBLISH_CONNECTION_CHANGED");
        if (target.publications().stream().anyMatch(row -> "PUBLISHED".equals(row.state())))
            throw conflict("AT_PUBLISH_ALREADY_PUBLISHED");
        var task = tasks.get("publication", candidateId);
        if (task != null && (task.getAttempts() > 0 || !"QUEUED".equals(task.getState()))) {
            if (input.mode().equals(tasks.context(task).path("mode").asText())) return status(candidateId);
            throw conflict("AT_PUBLISH_QUEUE_MODE_LOCKED");
        }
        var context = mapper.createObjectNode().put("candidateSha256", input.expectedCandidateSha256())
                .put("bindingSha256", input.expectedBindingSha256()).put("instanceKey", instanceKey).put("mode", input.mode())
                .put("sourceName", binding == null ? target.delivery().pluginName() : target.originalPluginName())
                .put("newSource", binding == null);
        if (task == null) task = tasks.enqueue("publication", candidateId, repair.sourceId(), context);
        task.setNextAttemptAt(Instant.now().plusSeconds(30));
        tasks.save(task, "QUEUED", context, null);
        return status(candidateId);
    }

    @Scheduled(fixedDelayString = "${maintenance.at-publish.poll-millis:15000}", initialDelay = 15000)
    public synchronized void poll() {
        if (!enabled()) return;
        // Only the latest successful delivery per source is eligible; historical successes stay historical.
        var latest = new LinkedHashMap<String, String>();
        var owners = new HashMap<String, String>();
        sessions.findAll().forEach(row -> owners.put(row.getId(), row.getSourceId()));
        candidates.findAll().stream().sorted(Comparator.comparing(c -> c.getCreatedAt()))
                .filter(c -> c.getResultJson() != null).forEach(c -> {
                    try {
                        if (mapper.readTree(c.getResultJson()).path("repairSuccessful").asBoolean()
                                && owners.containsKey(c.getRepairId()))
                            latest.put(owners.get(c.getRepairId()), c.getId());
                    } catch (java.io.IOException error) { log.warn("Invalid saved candidate result: {}", c.getId()); }
                });
        for (var entry : latest.entrySet()) {
            String source = entry.getKey(), candidate = entry.getValue();
            if (tasks.get("publication", candidate) != null) continue;
            if (publications.list(candidate).stream().anyMatch(row -> "PUBLISHED".equals(row.state()))) continue;
            var binding = bindings.get(source);
            if (binding == null || !binding.connectionMatches()
                    || !instanceKey.equals(binding.snapshot().path("connection").path("instanceKey").asText())) continue;
            var context = mapper.createObjectNode().put("candidateSha256", repairs.candidate(candidate).sha256())
                    .put("bindingSha256", binding.sha256()).put("instanceKey", instanceKey).put("mode", "incremental")
                    .put("sourceName", binding.snapshot().path("plugin").path("name").asText());
            var task = tasks.enqueue("publication", candidate, source, context);
            task.setNextAttemptAt(Instant.now().plusSeconds(30));
            tasks.save(task, "QUEUED", context, null);
        }
        for (var task : tasks.due("publication")) {
            var context = tasks.context(task);
            try {
                if (!instanceKey.equals(context.path("instanceKey").asText())) {
                    tasks.save(task, "BLOCKED", context, "AT_PUBLISH_CONNECTION_CHANGED");
                    continue;
                }
                if (task.getAttempts() == 0 && !task.getOwnerId().equals(latest.get(task.getSourceId()))) {
                    tasks.save(task, "SUPERSEDED", context, null);
                    continue;
                }
                var existing = publications.list(task.getOwnerId()).stream()
                        .filter(row -> "PUBLISHED".equals(row.state())).findFirst();
                if (existing.isPresent()) {
                    context.put("publicationId", existing.get().id());
                    tasks.save(task, "PUBLISHED", context, null);
                    continue;
                }
                task.setAttempts(task.getAttempts() + 1);
                tasks.save(task, "RUNNING", context, null);
                var result = publisher.publish(task.getOwnerId(), new StartAtPublicationRequest(
                        context.path("candidateSha256").asText(), context.path("bindingSha256").asText(),
                        context.path("mode").asText("incremental"), "unattended_" + task.getOwnerId(), true, null));
                context.put("publicationId", result.id()).put("publishedPluginId", result.publishedPluginId());
                tasks.save(task, "PUBLISHED", context, null);
            } catch (ResponseStatusException error) {
                // The publication service reconciles the same immutable path and request after a lost response.
                boolean retry = task.getAttempts() < 3 && (error.getStatusCode().is5xxServerError()
                        || Set.of("AT_PUBLISH_REGISTRATION_PENDING", "AT_PUBLISH_READBACK_FAILED", "AT_PUBLISH_HTTP_ERROR", "AT_PUBLISH_IO_ERROR",
                                "AT_PUBLISH_ORIGINAL_MARK_FAILED")
                            .contains(Objects.toString(error.getReason(), "")));
                task.setNextAttemptAt(Instant.now().plusSeconds(30L * Math.max(1, task.getAttempts())));
                tasks.save(task, retry ? "RETRY_WAIT" : "BLOCKED", context, error.getReason());
            } catch (RuntimeException error) {
                tasks.save(task, "BLOCKED", context, "AUTOMATIC_PUBLICATION_INTERNAL_ERROR");
                log.error("Automatic publication stopped: candidate={}, exception={}", task.getOwnerId(), error.getClass().getName());
            }
        }
    }

    private ResponseStatusException conflict(String code) {
        return new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, code);
    }
}
