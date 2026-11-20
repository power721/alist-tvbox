package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import jakarta.annotation.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
public class MediaProbeService {
    private final MediaProbeStore store;
    private final MediaProbeEngine engine;
    private final MediaNetworkPolicy policy;
    private final ConcurrentMap<String, FutureTask<Void>> tasks = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().name("maintenance-media").factory(),
            new ThreadPoolExecutor.AbortPolicy());

    @PostConstruct
    void recover() { store.recover(); }

    public synchronized MediaProbeSummary start(String sourceId, StartMediaProbeRequest request) {
        MediaTarget target;
        try { target = policy.target(request.player()); }
        catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
        var run = store.create(sourceId, request.expectedArtifactSha256());
        FutureTask<Void> task = new FutureTask<>(() -> {
            if (!store.start(run.getId())) return null;
            try {
                store.finish(run.getId(), "COMPLETED", engine.probe(target, run.getId()), null);
            } catch (InterruptedException error) {
                store.finish(run.getId(), "INTERRUPTED", null, "MEDIA_INTERRUPTED");
                Thread.currentThread().interrupt();
            } catch (RuntimeException error) {
                store.finish(run.getId(), "FAILED", null, "MEDIA_INTERNAL_ERROR");
                throw error;
            }
            return null;
        }) {
            @Override protected void done() { tasks.remove(run.getId(), this); }
        };
        tasks.put(run.getId(), task);
        try { executor.execute(task); }
        catch (RejectedExecutionException error) {
            tasks.remove(run.getId(), task);
            store.finish(run.getId(), "REJECTED", null, "MEDIA_QUEUE_FULL");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "MEDIA_QUEUE_FULL");
        }
        return store.get(run.getId());
    }

    public synchronized MediaProbeSummary cancel(String id) {
        var run = store.cancel(id);
        var task = tasks.get(id);
        if (task != null && Set.of("CANCELLING", "CANCELLED").contains(run.status())) {
            task.cancel(true);
            executor.remove(task);
        }
        return store.get(id);
    }

    @PreDestroy
    synchronized void close() throws InterruptedException {
        var queued = executor.shutdownNow();
        for (var entry : tasks.entrySet()) {
            if (queued.contains(entry.getValue())) {
                store.finish(entry.getKey(), "INTERRUPTED", null, "SERVICE_STOPPED");
                entry.getValue().cancel(false);
            }
        }
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}
