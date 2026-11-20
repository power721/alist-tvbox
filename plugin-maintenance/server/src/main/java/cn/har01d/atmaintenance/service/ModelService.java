package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.ModelCall;
import jakarta.annotation.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
public class ModelService {
    private final ModelStore store;
    private final ModelClient client;
    private final ModelSubmissionService submissions;
    private final ReverseAnalysis reverse;
    private final ConcurrentMap<String, FutureTask<Void>> tasks = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().name("maintenance-model").factory(),
            new ThreadPoolExecutor.AbortPolicy());

    @PostConstruct
    void recover() { store.recover(); }

    public ModelCallSummary propose(String repairId, StartModelCallRequest input) {
        if (input.enhancedDiagnostics()) {
            var repair = store.reverseInputs(repairId, input);
            if (repair != null && RepairEvidenceMaterial.activeGrant(repair).isMissingNode()) reverse.prepare(repair);
        }
        return enqueue(repairId, input);
    }

    public synchronized ModelCallSummary discoverOrigins(String repairId, String faultSha,
                                                        com.fasterxml.jackson.databind.JsonNode searchEvidence) {
        var call = store.reserveOriginDiscovery(repairId, faultSha, searchEvidence);
        enqueue(call);
        return store.get(call.getId());
    }

    private synchronized ModelCallSummary enqueue(String repairId, StartModelCallRequest input) {
        // Budget and immutable request commit before any transport or executor work.
        ModelCall call = store.reserve(repairId, input);
        enqueue(call);
        return store.get(call.getId());
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public synchronized void retryDue() {
        if (!executor.isShutdown()) store.dueRetries().forEach(this::enqueue);
    }

    private void enqueue(ModelCall call) {
        if (!call.getStatus().equals("QUEUED") || tasks.containsKey(call.getId())) return;
        FutureTask<Void> task = new FutureTask<>(() -> {
            execute(call);
            return null;
        }) {
            @Override protected void done() { tasks.remove(call.getId(), this); }
        };
        tasks.put(call.getId(), task);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            tasks.remove(call.getId(), task);
            store.finish(call.getId(), null, "MODEL_QUEUE_FULL");
        }
    }

    public synchronized ModelCallSummary cancel(String id) {
        var result = store.cancel(id);
        var task = tasks.get(id);
        if (task != null && Set.of("CANCELLING", "CANCELLED").contains(result.status())) {
            task.cancel(true);
            executor.remove(task);
        }
        return store.get(id);
    }

    private void execute(ModelCall call) {
        if (!store.start(call.getId())) return;
        try {
            ModelReply reply = client.generate(call.getRequestJson());
            store.finish(call.getId(), reply, reply.errorCode());
        } catch (InterruptedException e) {
            store.finish(call.getId(), null, "MODEL_OUTCOME_UNKNOWN");
            Thread.currentThread().interrupt();
        } catch (ResponseStatusException e) {
            store.finish(call.getId(), null, e.getReason());
        } catch (RuntimeException e) {
            store.finish(call.getId(), null, "MODEL_INTERNAL_ERROR");
            throw e;
        }
        if (!ModelClient.discoveryRequest(call.getRequestJson())) submissions.autoSubmit(call.getId());
    }

    @PreDestroy
    synchronized void close() throws InterruptedException {
        var queued = executor.shutdownNow();
        for (var entry : tasks.entrySet()) {
            if (queued.contains(entry.getValue())) {
                store.finish(entry.getKey(), null, "MODEL_SERVICE_STOPPED_BEFORE_SEND");
                entry.getValue().cancel(false);
            }
        }
        executor.awaitTermination(15, TimeUnit.SECONDS);
    }
}
