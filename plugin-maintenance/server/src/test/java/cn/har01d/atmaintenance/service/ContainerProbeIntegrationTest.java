package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.CreateSourceRequest;
import cn.har01d.atmaintenance.dto.ProbeSummary;
import cn.har01d.atmaintenance.repository.ProbeRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Persistence and orchestration only; Engine calls are disabled or explicitly mocked. */
@SpringBootTest(properties = {
        "maintenance.scheduling.enabled=false", "maintenance.at.origin=", "maintenance.container.enabled=false",
        "maintenance.container.executable=", "maintenance.container.endpoint=", "maintenance.container.image-id=",
        "spring.datasource.url=jdbc:h2:mem:maintenance-container-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=integration-test-only"
})
class ContainerProbeIntegrationTest {
    @Autowired SourceService sources;
    @Autowired ProbeService probes;
    @Autowired ProbeStore store;
    @Autowired StaticInspector inspector;
    @Autowired KnowledgeService knowledge;
    @Autowired ExportService exports;
    @Autowired ProbeRunRepository runs;
    @MockitoSpyBean FixtureRuntime fixture;
    @MockitoSpyBean ContainerRuntime container;

    @Test
    void defaultDisabledModePersistsFrozenPolicyAndExportsItWithoutHostFallback() throws Exception {
        String source = create(fixture.fixtureContent());
        var done = await(probes.start(source, "container_fixture").id());
        assertEquals("BLOCKED", done.status());
        assertEquals("CONTAINER_EXECUTION_DISABLED", done.errorCode());
        assertEquals("container_fixture_blocked", done.result().path("verdict").asText());
        assertEquals("static", done.result().path("evidenceClass").asText());
        assertEquals(container.sha256(), done.executionPolicySha256());
        assertEquals(done.executionPolicySha256(), Hashes.sha256(done.executionPolicy().toString()));
        assertEquals(done.executionPolicySha256(), done.context().path("executionPolicySha256").asText());
        assertEquals(3, done.result().path("nodes").valueStream()
                .filter(n -> n.path("status").asText().equals("pass")).count());
        assertFalse(done.result().has("runtimeEvidence"));
        verify(fixture, never()).inspect(anyString(), any(), anyString());
        verify(container, never()).inspect(anyString(), anyString(), any(), anyString(), anyString());
        var files = new HashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(exports.export(source, null)))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) files.put(entry.getName(), zip.readAllBytes());
        }
        assertEquals(done.executionPolicySha256(), Hashes.sha256(files.get("probe-execution-policy.json")));
        var stored = runs.findById(done.id()).orElseThrow();
        stored.setExecutionPolicySha256("0".repeat(64));
        runs.saveAndFlush(stored);
        assertEquals("execution_policy_changed", store.get(done.id()).freshness());
        assertEquals(List.of("execution_policy"), store.get(done.id()).staleInputs());
        var regular = await(probes.start(source).id());
        assertNull(regular.executionPolicy());
        assertNull(regular.executionPolicySha256());
    }

    @Test
    void unknownSourceCannotReachEitherExecutorAndStaticRepairIsNotContainerAcceptance() throws Exception {
        var done = await(probes.start(create("raise RuntimeError('must never run')\nclass Spider: pass\n"),
                "container_fixture").id());
        assertEquals("TRUSTED_FIXTURE_REQUIRED", done.errorCode());
        verify(fixture, never()).inspect(anyString(), any(), anyString());
        verify(container, never()).inspect(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void engineFailureHasAnExplicitReportAndCannotFallBackToHostExecution() throws Exception {
        doReturn(null).when(container).blockedReason(anyString(), any());
        doThrow(new IOException("LINUX_CGROUP_V2_REQUIRED")).when(container)
                .inspect(anyString(), anyString(), any(), anyString(), anyString());
        var done = await(probes.start(create(fixture.fixtureContent()), "container_fixture").id());
        assertEquals("BLOCKED", done.status());
        assertEquals("LINUX_CGROUP_V2_REQUIRED", done.errorCode());
        assertFalse(done.result().has("runtimeEvidence"));
        verify(fixture, never()).inspect(anyString(), any(), anyString());
    }

    @Test
    void unconfirmedCleanupCannotBecomeSuccessfulCancellation() throws Exception {
        doReturn(null).when(container).blockedReason(anyString(), any());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException e) { throw new IOException("CONTAINER_CLEANUP_UNCONFIRMED", e); }
            throw new AssertionError("Expected cancellation");
        }).when(container).inspect(anyString(), anyString(), any(), anyString(), anyString());
        var run = probes.start(create(fixture.fixtureContent()), "container_fixture");
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            probes.cancel(run.id());
            var failed = await(run.id());
            assertEquals("FAILED", failed.status());
            assertEquals("CONTAINER_CLEANUP_UNCONFIRMED", failed.errorCode());
            store.finish(run.id(), "COMPLETED", null, null, null);
            assertEquals(failed, store.get(run.id()));
        } finally {
            release.countDown();
        }
    }

    @Test
    void lateContainerResultCannotOverwriteAcceptedCancellation() throws Exception {
        doReturn(null).when(container).blockedReason(anyString(), any());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch cleanupFinished = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            boolean finished = false;
            while (!finished) {
                try { cleanupFinished.await(); finished = true; }
                catch (InterruptedException e) { interrupted.countDown(); }
            }
            return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        }).when(container).inspect(anyString(), anyString(), any(), anyString(), anyString());
        var run = probes.start(create(fixture.fixtureContent()), "container_fixture");
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertEquals("CANCELLING", probes.cancel(run.id()).status());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertNull(store.get(run.id()).finishedAt());
        } finally {
            cleanupFinished.countDown();
        }
        var done = await(run.id());
        assertEquals("CANCELLED", done.status());
        assertNull(done.result(), "Mocked late results are not execution evidence");
    }

    @Test
    void recoveredJobsRequireEngineCleanupBeforeBeingMarkedInterrupted() throws Exception {
        String source = create(fixture.fixtureContent());
        var cleaned = store.create(source, inspector.sha256(), knowledge.sha256(), "container_fixture");
        var failed = store.create(source, inspector.sha256(), knowledge.sha256(), "container_fixture");
        var queued = store.create(source, inspector.sha256(), knowledge.sha256(), "container_fixture");
        assertTrue(store.start(cleaned.getId()));
        assertTrue(store.start(failed.getId()));
        store.requestCancel(failed.getId());
        doNothing().when(container).cleanup(eq(cleaned.getId()), any());
        doThrow(new IOException("CONTAINER_CLEANUP_UNCONFIRMED")).when(container).cleanup(eq(failed.getId()), any());
        probes.recover();
        assertEquals("INTERRUPTED", store.get(cleaned.getId()).status());
        assertEquals("SERVICE_RESTARTED", store.get(cleaned.getId()).errorCode());
        assertEquals("FAILED", store.get(failed.getId()).status());
        assertEquals("CONTAINER_CLEANUP_UNCONFIRMED", store.get(failed.getId()).errorCode());
        assertEquals("INTERRUPTED", store.get(queued.getId()).status());
        verify(container, never()).cleanup(eq(queued.getId()), any());
        var expected = new com.fasterxml.jackson.databind.ObjectMapper().readTree(cleaned.getExecutionPolicyJson());
        verify(container).cleanup(cleaned.getId(), expected);
    }

    private String create(String body) {
        return sources.create(new CreateSourceRequest("容器协议编排测试", null, "demo.py", null, body)).id();
    }
    private ProbeSummary await(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            var run = store.get(id);
            if (!Set.of("QUEUED", "RUNNING", "CANCELLING").contains(run.status())) return run;
            Thread.sleep(25);
        }
        throw new AssertionError("Container orchestration did not finish");
    }
}
