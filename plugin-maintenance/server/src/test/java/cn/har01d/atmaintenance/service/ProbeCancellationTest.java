package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.CreateSourceRequest;
import cn.har01d.atmaintenance.dto.ProbeSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "maintenance.scheduling.enabled=false",
        "maintenance.at.origin=",
        "spring.datasource.url=jdbc:h2:mem:maintenance-cancel-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=integration-test-only"
})
class ProbeCancellationTest {
    @Autowired SourceService sources;
    @Autowired ProbeService probes;
    @Autowired ProbeStore store;
    @Autowired KnowledgeService knowledge;
    @MockitoSpyBean StaticInspector inspector;

    @Test
    void queuedCancellationFreesCapacityAndRunningCancellationDoesNotStopTheNextJob() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await();
            return invocation.callRealMethod();
        }).when(inspector).inspect(anyString());
        String sourceId = create();
        var running = probes.start(sourceId);
        var queued = new ArrayList<ProbeSummary>();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 32; i++) queued.add(probes.start(sourceId));
            assertEquals(429, assertThrows(ResponseStatusException.class,
                    () -> probes.start(sourceId)).getStatusCode().value());
            var cancelled = probes.cancel(queued.getFirst().id());
            assertEquals("CANCELLED", cancelled.status());
            assertNull(cancelled.result());
            assertNotNull(cancelled.finishedAt());
            assertEquals(cancelled, probes.cancel(cancelled.id()));
            queued.add(probes.start(sourceId));
            for (var run : queued) assertEquals("CANCELLED", probes.cancel(run.id()).status());
            probes.cancel(running.id());
            assertEquals("CANCELLED", await(running.id()).status());
            verify(inspector, times(1)).inspect(anyString());
        } finally {
            release.countDown();
            for (var run : queued) probes.cancel(run.id());
            probes.cancel(running.id());
            await(running.id());
        }
        doCallRealMethod().when(inspector).inspect(anyString());
        var next = await(probes.start(sourceId).id());
        assertEquals("COMPLETED", next.status(),
                () -> "Next probe status=" + next.status() + " error=" + next.errorCode());
    }

    @Test
    void acceptedCancellationWaitsForCleanupAndCannotBecomeALateSuccess() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch cleanup = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    cleanup.await();
                    released = true;
                } catch (InterruptedException e) {
                    interrupted.countDown();
                }
            }
            // Model a finished cleanup returning a late result. No source is executed.
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree("{\"parseOk\":true,\"issues\":[]}");
        }).when(inspector).inspect(anyString());
        var submitted = probes.start(create());
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var pending = probes.cancel(submitted.id());
            assertEquals("CANCELLING", pending.status());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertNull(pending.finishedAt());
            assertNull(pending.result());
            assertEquals("CANCELLING", probes.cancel(submitted.id()).status());
        } finally {
            cleanup.countDown();
        }
        var cancelled = await(submitted.id());
        assertEquals("CANCELLED", cancelled.status());
        assertEquals("USER_CANCELLED", cancelled.errorCode());
        assertNull(cancelled.result());
        assertEquals(submitted.profileSha256(), cancelled.profileSha256());
        store.finish(submitted.id(), "COMPLETED", null, null, null);
        assertEquals(cancelled, store.get(submitted.id()));
    }

    @Test
    void cleanupFailureCannotBePresentedAsSuccessfulCancellation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new IOException("INSPECTOR_CLEANUP_FAILED", e);
            }
            return invocation.callRealMethod();
        }).when(inspector).inspect(anyString());
        var submitted = probes.start(create());
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            probes.cancel(submitted.id());
            var failed = await(submitted.id());
            assertEquals("FAILED", failed.status());
            assertEquals("INSPECTOR_CLEANUP_FAILED", failed.errorCode());
            assertNull(failed.result());
        } finally {
            release.countDown();
            probes.cancel(submitted.id());
            await(submitted.id());
        }
    }

    @Test
    void terminalCancellationIsIdempotentAndRestartRecoversPendingCancellation() throws Exception {
        String sourceId = create();
        var completed = await(probes.start(sourceId).id());
        assertEquals("COMPLETED", completed.status());
        assertEquals(completed, probes.cancel(completed.id()));
        var abandoned = store.create(sourceId, inspector.sha256(), knowledge.sha256());
        assertTrue(store.start(abandoned.getId()));
        assertEquals("CANCELLING", store.requestCancel(abandoned.getId()).status());
        store.interruptUnfinished();
        var recovered = store.get(abandoned.getId());
        assertEquals("INTERRUPTED", recovered.status());
        assertEquals("SERVICE_RESTARTED", recovered.errorCode());
        assertEquals(recovered, probes.cancel(recovered.id()));
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> probes.cancel("missing-run")).getStatusCode().value());
    }

    private String create() {
        return sources.create(new CreateSourceRequest("取消夹具", null, "fixture.py", null,
                "raise RuntimeError('never execute')\nclass Spider: pass\n")).id();
    }

    private ProbeSummary await(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        ProbeSummary run;
        do {
            run = store.get(id);
            if (!Set.of("QUEUED", "RUNNING", "CANCELLING").contains(run.status())) return run;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("Cancellation did not finish within 10 seconds");
        return run;
    }
}
