package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.entity.MaintenanceAutomationTask;
import cn.har01d.atmaintenance.repository.MaintenanceAutomationTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MaintenanceAutomationStore {
    private final MaintenanceAutomationTaskRepository tasks;
    private final ObjectMapper mapper;

    @Transactional
    public MaintenanceAutomationTask enqueue(String kind, String owner, String source, ObjectNode context) {
        String id = kind + ":" + owner;
        return tasks.findById(id).orElseGet(() -> {
            var task = new MaintenanceAutomationTask();
            task.setId(id);
            task.setKind(kind);
            task.setOwnerId(owner);
            task.setSourceId(source);
            task.setContextJson(context.toString());
            return tasks.saveAndFlush(task);
        });
    }

    public MaintenanceAutomationTask get(String kind, String owner) {
        return tasks.findById(kind + ":" + owner).orElse(null);
    }

    public List<MaintenanceAutomationTask> due(String kind) {
        return tasks.findByKindAndStateInAndNextAttemptAtLessThanEqualOrderByCreatedAt(kind,
                Set.of("QUEUED", "SEARCHED", "RUNNING", "WAITING_MODEL", "VERIFYING", "RETRY_WAIT"), Instant.now());
    }

    @Transactional
    public MaintenanceAutomationTask save(MaintenanceAutomationTask task, String state, ObjectNode context, String code) {
        task.setState(state);
        task.setContextJson(context.toString());
        task.setErrorCode(code);
        task.setUpdatedAt(Instant.now());
        return tasks.saveAndFlush(task);
    }

    public ObjectNode context(MaintenanceAutomationTask task) {
        try { return (ObjectNode) mapper.readTree(task.getContextJson()); }
        catch (java.io.IOException error) { throw new IllegalStateException("Invalid automation context", error); }
    }

    public ObjectNode summary(MaintenanceAutomationTask task) {
        if (task == null) return mapper.createObjectNode().put("state", "NOT_REQUESTED");
        var result = mapper.createObjectNode().put("id", task.getId()).put("kind", task.getKind())
                .put("ownerId", task.getOwnerId()).put("sourceId", task.getSourceId()).put("state", task.getState())
                .put("attempts", task.getAttempts()).put("errorCode", task.getErrorCode())
                .put("createdAt", task.getCreatedAt().toString()).put("updatedAt", task.getUpdatedAt().toString());
        result.set("context", context(task));
        return result;
    }

    public List<ObjectNode> list() { return tasks.findTop100ByOrderByCreatedAtDesc().stream().map(this::summary).toList(); }
}
