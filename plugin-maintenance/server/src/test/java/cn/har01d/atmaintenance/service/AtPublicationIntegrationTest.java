package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.repository.AtPublicationRepository;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:at-publication;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=admin", "maintenance.at.origin=",
        "maintenance.model.enabled=false", "maintenance.scheduling.enabled=false"
})
class AtPublicationIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path DIRECTORY = temporary();
    static final String BASELINE = "class Spider:\n    pass\n";
    static final String CANDIDATE = "class Spider:\n    name = 'repaired'\n";
    static final String BASE_SHA = Hashes.sha256(BASELINE);
    static final String CANDIDATE_SHA = Hashes.sha256(CANDIDATE);
    static final String BINDING_SHA = "b".repeat(64);
    @Autowired AtPublicationService service;
    @Autowired AtPublicationRepository repository;
    @Autowired AtPublicationStore publicationStore;
    @Autowired MaintenanceAutomationStore taskStore;
    @Autowired cn.har01d.atmaintenance.repository.MaintenanceAutomationTaskRepository taskRows;
    @Autowired ConnectionSettingsStore connections;
    @MockitoBean RepairStore repairs;
    @MockitoBean RepairExportService exports;
    @MockitoBean AtBindingService bindings;
    @MockitoSpyBean SourceService sources;
    @LocalServerPort int port;
    HttpServer at;
    String origin;
    final Map<String, byte[]> files = new LinkedHashMap<>();
    final Map<Integer, ObjectNode> plugins = new LinkedHashMap<>();
    int writes, uploads, leaked;
    boolean loseUploadReply, loseRenameReply, redirectRead, loseOriginalRenameReply, rejectPublishedRename;
    String loadedSha = BASE_SHA;
    String generation = "1".repeat(32);
    boolean nativeRuntime = true;
    int activations;
    boolean loseActivationReply, failActivation;
    JsonNode recordedActivation;

    static Path temporary() {
        try { return Files.createTempDirectory("at-publication-"); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("maintenance.connections-file", () -> DIRECTORY.resolve("connections.json").toString());
    }

    @BeforeEach
    void prepare() throws Exception {
        repository.deleteAll();
        taskRows.deleteAll();
        at = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        at.createContext("/", this::handle);
        at.start();
        origin = "http://127.0.0.1:" + at.getAddress().getPort();
        connections.saveAt(new AtConnectionSettings(origin, "fixture", "test", "session",
                "fixture-admin", true, ""));
        files.put("/static/plugins/原件.py", BASELINE.getBytes(StandardCharsets.UTF_8));
        plugins.put(7, JSON.createObjectNode().put("id", 7).put("name", "测试源")
                .put("url", "/static/plugins/原件.py").put("enabled", true).put("t4Visible", true)
                .put("extend", "{\"keep\":\"unchanged\"}").put("lastError", ""));
        String instanceKey = Hashes.sha256("fixture\n" + Hashes.sha256(origin + "/"));
        var snapshot = JSON.createObjectNode();
        snapshot.putObject("connection").put("instanceKey", instanceKey);
        snapshot.putObject("plugin").put("id", 7).put("name", "测试源")
                .put("sourceLocatorSha256", Hashes.sha256("/static/plugins/原件.py"));
        when(bindings.get("source")).thenReturn(new AtBindingSummary("source", BINDING_SHA, snapshot, true));
        when(repairs.get("repair")).thenReturn(new RepairSummary("repair", "source", "run", BASE_SHA, "fault",
                "SUCCEEDED", null, 1, 3, "candidate", Instant.now(), JSON.createObjectNode(), "current", List.of()));
        verified(true);
        when(repairs.content("candidate")).thenReturn(CANDIDATE);
        when(exports.delivery("candidate")).thenReturn(SpiderDeliveryNames.describe(
                "测试源", "original.py", CANDIDATE_SHA, 1, true));
    }

    @AfterEach void stop() { at.stop(0); }

    void verified(boolean success) {
        when(repairs.candidate("candidate")).thenReturn(new RepairCandidateSummary("candidate", "repair", 1,
                CANDIDATE_SHA, "proposal", "RUNTIME_OBSERVED", null, Instant.now(), Instant.now(),
                JSON.createObjectNode(), JSON.createObjectNode().put("repairSuccessful", success), null, null, null));
    }

    StartAtPublicationRequest input(String mode, String key) {
        return new StartAtPublicationRequest(CANDIDATE_SHA, BINDING_SHA, mode, key, true, null);
    }

    AutomaticPublicationService unattended() {
        var sessions = mock(cn.har01d.atmaintenance.repository.RepairRepository.class);
        var candidates = mock(cn.har01d.atmaintenance.repository.RepairCandidateRepository.class);
        var owner = new cn.har01d.atmaintenance.entity.RepairSession();
        owner.setId("repair"); owner.setSourceId("source");
        var candidate = new cn.har01d.atmaintenance.entity.RepairCandidate();
        candidate.setId("candidate"); candidate.setRepairId("repair");
        candidate.setResultJson("{\"repairSuccessful\":true}");
        when(sessions.findAll()).thenReturn(List.of(owner));
        when(candidates.findAll()).thenReturn(List.of(candidate));
        var worker = new AutomaticPublicationService(taskStore, sessions, candidates, repairs, service,
                publicationStore, bindings, JSON);
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "instanceKey",
                Hashes.sha256("fixture\n" + Hashes.sha256(origin + "/")));
        return worker;
    }

    @Test
    void newSourcePublishesIncrementallyWithoutBindingOrChangingAnyExistingPlugin() {
        when(bindings.get("source")).thenReturn(null);
        var before = plugins.get(7).deepCopy();
        var target = service.target("candidate");
        assertTrue(target.available());
        assertNull(target.originalPluginId());
        assertEquals("incremental", target.defaultMode());
        assertNotNull(target.bindingSha256());
        var request = new StartAtPublicationRequest(CANDIDATE_SHA, target.bindingSha256(), null, "new-source", true, null);
        var result = service.publish("candidate", request);
        assertEquals("PUBLISHED", result.state());
        assertNull(result.originalPluginId());
        assertEquals(8, result.publishedPluginId());
        assertEquals(before, plugins.get(7));
        assertEquals(BASELINE, new String(files.get("/static/plugins/原件.py"), StandardCharsets.UTF_8));
        assertEquals("null", repository.findById(result.id()).orElseThrow().getBeforePluginJson());
        assertNull(repository.findById(result.id()).orElseThrow().getOriginalPluginId());
        int completedWrites = writes;
        var repeated = service.publish("candidate", request);
        assertEquals(result.id(), repeated.id());
        assertEquals("PUBLISHED", repeated.state());
        assertNull(repeated.originalPluginId());
        assertEquals(completedWrites, writes);
        assertEquals(1, uploads);
    }

    @Test
    void newSourceCannotReplaceAndUnverifiedSourceCannotPublish() {
        when(bindings.get("source")).thenReturn(null);
        var target = service.target("candidate");
        assertEquals("AT_PUBLISH_NEW_SOURCE_INCREMENTAL_ONLY", assertThrows(ResponseStatusException.class,
                () -> service.publish("candidate", new StartAtPublicationRequest(CANDIDATE_SHA,
                        target.bindingSha256(), "replace", "invalid-new-replace", true, null))).getReason());
        assertEquals("AT_PUBLISH_NEW_SOURCE_INCREMENTAL_ONLY", assertThrows(ResponseStatusException.class,
                () -> unattended().queue("candidate", new PublicationQueueRequest(CANDIDATE_SHA,
                        target.bindingSha256(), "replace", true))).getReason());
        verified(false);
        assertEquals("AT_PUBLISH_REAL_VALIDATION_REQUIRED", service.target("candidate").blockedReason());
        assertThrows(ResponseStatusException.class, () -> service.publish("candidate", new StartAtPublicationRequest(
                CANDIDATE_SHA, target.bindingSha256(), "incremental", "invalid-new-result", true, null)));
        assertEquals(0, writes);
        assertEquals(0, repository.count());
        assertEquals(0, taskRows.count());
    }

    @Test
    void newSourceFreshAutomaticSuccessQueuesPersistentlyAndPublishesOnceWithoutManualQueue() {
        when(bindings.get("source")).thenReturn(null);
        freshAutomaticSuccess();
        var worker = unattended();
        var before = plugins.get(7).deepCopy();
        worker.repairSucceeded("repair");
        var task = taskStore.get("publication", "candidate");
        assertEquals("QUEUED", task.getState());
        assertTrue(taskStore.context(task).path("newSource").asBoolean());
        assertEquals("automatic_repair_succeeded", taskStore.context(task).path("trigger").asText());
        var nextAttempt = task.getNextAttemptAt();
        worker.repairSucceeded("repair");
        assertEquals(nextAttempt, taskStore.get("publication", "candidate").getNextAttemptAt());
        assertEquals(0, uploads);
        makeDue();
        unattended().poll();
        assertEquals("PUBLISHED", taskStore.get("publication", "candidate").getState());
        assertNull(repository.findAll().getFirst().getOriginalPluginId());
        assertEquals(before, plugins.get(7));
        worker.repairSucceeded("repair");
        unattended().poll();
        assertEquals(1, uploads);
        assertEquals(1, repository.count());
    }

    void freshAutomaticSuccess() {
        when(repairs.get("repair")).thenReturn(new RepairSummary("repair", "source", "run", BASE_SHA, "fault",
                "RUNTIME_OBSERVED", null, 1, 3, "candidate", Instant.now(), JSON.createObjectNode(), "current", List.of()));
        var latest = repairs.candidate("candidate");
        when(repairs.candidates("repair")).thenReturn(List.of(latest));
    }

    @Test
    void automaticPublicationHandoffRetainsBlockedTargetAsASeparateQueueFailure() {
        when(bindings.get("source")).thenReturn(null);
        freshAutomaticSuccess();
        var worker = unattended();
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "instanceKey", "different-instance");
        assertDoesNotThrow(() -> worker.repairSucceeded("repair"));
        var task = taskStore.get("publication", "candidate");
        assertEquals("BLOCKED", task.getState());
        assertEquals("AT_PUBLISH_CONNECTION_CHANGED", task.getErrorCode());
        assertEquals("automatic_repair_succeeded", taskStore.context(task).path("trigger").asText());
        assertEquals(0, writes);
        assertEquals(0, repository.count());
    }

    @Test
    void automaticPublicationHandoffDoesNotPublishHistoricalFailedOrDisabledResults() {
        when(bindings.get("source")).thenReturn(null);
        var worker = unattended();
        worker.repairSucceeded("repair");
        assertEquals(0, taskRows.count());
        verified(false);
        freshAutomaticSuccess();
        worker.repairSucceeded("repair");
        assertEquals(0, taskRows.count());
        verified(true);
        freshAutomaticSuccess();
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "enabled", false);
        worker.repairSucceeded("repair");
        assertEquals(0, taskRows.count());
        assertEquals(0, writes);
    }

    @Test
    void newSourceNeedsExplicitQueueAndReconcilesLostUploadAfterWorkerRestart() {
        when(bindings.get("source")).thenReturn(null);
        unattended().poll();
        assertEquals(0, taskRows.count());
        var target = service.target("candidate");
        var request = new PublicationQueueRequest(CANDIDATE_SHA, target.bindingSha256(), "incremental", true);
        var queued = unattended().queue("candidate", request);
        assertTrue(queued.path("context").path("newSource").asBoolean());
        assertEquals("测试源", queued.path("context").path("sourceName").asText());
        var before = plugins.get(7).deepCopy();
        makeDue();
        loseUploadReply = true;
        unattended().poll();
        assertEquals("RETRY_WAIT", taskStore.get("publication", "candidate").getState());
        makeDue();
        unattended().poll();
        assertEquals("PUBLISHED", taskStore.get("publication", "candidate").getState());
        assertEquals(1, uploads);
        assertEquals(2, plugins.size());
        assertEquals(before, plugins.get(7));
        assertNull(repository.findAll().getFirst().getOriginalPluginId());
        unattended().poll();
        assertEquals(1, uploads);
    }

    @Test
    void newSourceTargetAndQueuedRequestCannotFollowAChangedAtConnection() throws Exception {
        when(bindings.get("source")).thenReturn(null);
        var target = service.target("candidate");
        unattended().queue("candidate", new PublicationQueueRequest(CANDIDATE_SHA,
                target.bindingSha256(), "incremental", true));
        connections.saveAt(new AtConnectionSettings(origin, "different-instance", "test", "session",
                "fixture-admin", true, ""));
        assertNotEquals(target.bindingSha256(), service.target("candidate").bindingSha256());
        assertEquals("AT_PUBLISH_BINDING_CHANGED", assertThrows(ResponseStatusException.class,
                () -> service.publish("candidate", new StartAtPublicationRequest(CANDIDATE_SHA,
                        target.bindingSha256(), "incremental", "changed-new-target", true, null))).getReason());
        makeDue();
        unattended().poll();
        assertEquals("BLOCKED", taskStore.get("publication", "candidate").getState());
        assertEquals("AT_PUBLISH_BINDING_CHANGED", taskStore.get("publication", "candidate").getErrorCode());
        assertEquals(0, writes);
        assertEquals(0, repository.count());
    }

    void makeDue() {
        var task = taskStore.get("publication", "candidate");
        task.setNextAttemptAt(Instant.now().minusSeconds(1));
        taskStore.save(task, task.getState(), taskStore.context(task), task.getErrorCode());
    }

    @Test
    void unattendedQueueSurvivesWorkerRestartAndReconcilesLostUploadWithoutDuplicatePlugin() {
        var first = unattended();
        first.poll();
        var queued = taskStore.get("publication", "candidate");
        assertEquals("QUEUED", queued.getState());
        assertEquals("incremental", taskStore.context(queued).path("mode").asText());
        assertEquals(0, uploads);
        makeDue();
        loseUploadReply = true;
        unattended().poll();
        assertEquals("RETRY_WAIT", taskStore.get("publication", "candidate").getState());
        assertEquals(1, uploads);
        makeDue();
        unattended().poll();
        assertEquals("PUBLISHED", taskStore.get("publication", "candidate").getState());
        assertEquals(1, uploads);
        assertEquals(2, plugins.size());
        assertArrayEquals(BASELINE.getBytes(StandardCharsets.UTF_8), files.get("/static/plugins/原件.py"));
        assertEquals(1, repository.count());
        unattended().poll();
        assertEquals(1, uploads);
    }

    @Test
    void unattendedQueueAllowsExplicitReplacementBeforeFirstAttemptAndLocksModeAfterwards() {
        var worker = unattended();
        worker.poll();
        var input = new PublicationQueueRequest(CANDIDATE_SHA, BINDING_SHA, "replace", true);
        assertEquals("replace", worker.queue("candidate", input).path("context").path("mode").asText());
        var task = taskStore.get("publication", "candidate");
        task.setAttempts(1);
        taskStore.save(task, "RUNNING", taskStore.context(task), null);
        assertEquals("AT_PUBLISH_QUEUE_MODE_LOCKED", assertThrows(ResponseStatusException.class,
                () -> worker.queue("candidate", new PublicationQueueRequest(CANDIDATE_SHA, BINDING_SHA, "incremental", true))).getReason());
        task.setAttempts(0);
        taskStore.save(task, "QUEUED", taskStore.context(task), null);
        makeDue();
        worker.poll();
        assertEquals("PUBLISHED", taskStore.get("publication", "candidate").getState());
        assertEquals("replace", repository.findAll().getFirst().getMode());
        assertEquals(7, repository.findAll().getFirst().getPublishedPluginId());
        assertEquals(1, plugins.size());
    }

    @Test
    void excludedSourceCannotPublishRenameOrActivateEvenWithVerifiedHistoricalCandidate() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                "AT_PLUGIN_MAINTENANCE_EXCLUDED")).when(sources).requireMaintenanceAllowed("source");
        assertEquals("AT_PLUGIN_MAINTENANCE_EXCLUDED", assertThrows(ResponseStatusException.class,
                () -> service.target("candidate")).getReason());
        assertEquals("AT_PLUGIN_MAINTENANCE_EXCLUDED", assertThrows(ResponseStatusException.class,
                () -> service.publish("candidate", input(null, "excluded"))).getReason());
        assertEquals("AT_PLUGIN_MAINTENANCE_EXCLUDED", assertThrows(ResponseStatusException.class,
                () -> service.rename("candidate", "old-publication", new RenameAtPublicationRequest(
                        CANDIDATE_SHA, "旧名称", "新名称", true))).getReason());
        assertEquals("AT_PLUGIN_MAINTENANCE_EXCLUDED", assertThrows(ResponseStatusException.class,
                () -> service.activate("candidate", "old-publication", new ActivateAtPublicationRequest(
                        "a".repeat(64), "b".repeat(32), "excluded", null, false, true))).getReason());
        assertEquals(0, writes);
        assertEquals(0, repository.count());
    }

    @Test
    void defaultIncrementalMarksOriginalAfterReadbackAndPersistsIdempotency() {
        assertEquals("incremental", service.target("candidate").defaultMode());
        var published = service.publish("candidate", input(null, "incremental"));
        assertEquals("PUBLISHED", published.state());
        assertEquals(8, published.publishedPluginId());
        assertEquals("测试源_v1.0.1", published.pluginName());
        assertEquals("incremental", published.mode());
        assertEquals(BASELINE, new String(files.get("/static/plugins/原件.py"), StandardCharsets.UTF_8));
        assertEquals("损坏_测试源", plugins.get(7).path("name").asText());
        assertTrue(plugins.get(7).path("enabled").asBoolean());
        assertEquals("{\"keep\":\"unchanged\"}", plugins.get(7).path("extend").asText());
        assertEquals("{\"keep\":\"unchanged\"}", plugins.get(8).path("extend").asText());
        var journal = repository.findById(published.id()).orElseThrow();
        assertTrue(journal.getTargetPath().endsWith("/测试源_v1.0.1.py"));
        assertArrayEquals(CANDIDATE.getBytes(StandardCharsets.UTF_8), files.get(journal.getTargetPath()));
        assertEquals(BASE_SHA, journal.getBaselineSha256());
        assertTrue(journal.getBeforePluginJson().contains("unchanged"));
        int completedWrites = writes;
        assertEquals(published.id(), service.publish("candidate", input(null, "incremental")).id());
        assertEquals(completedWrites, writes);
        assertEquals(1, service.target("candidate").publications().size());
    }

    @Test
    void incrementalReadsBoundLegacyStaticSourceAndWritesOnlyStandardDeliveryPath() {
        String path = "/static/自写脚本/原件.py";
        String locator = "/static/%E8%87%AA%E5%86%99%E8%84%9A%E6%9C%AC/%E5%8E%9F%E4%BB%B6.py";
        files.remove("/static/plugins/原件.py");
        files.put(path, BASELINE.getBytes(StandardCharsets.UTF_8));
        plugins.get(7).put("url", locator);
        ((ObjectNode) bindings.get("source").snapshot().path("plugin"))
                .put("sourceLocatorSha256", Hashes.sha256(locator));
        var published = service.publish("candidate", input("incremental", "legacy-static"));
        assertEquals("PUBLISHED", published.state());
        assertEquals(8, published.publishedPluginId());
        assertArrayEquals(BASELINE.getBytes(StandardCharsets.UTF_8), files.get(path));
        assertEquals(locator, plugins.get(7).path("url").asText());
        assertEquals("损坏_测试源", plugins.get(7).path("name").asText());
        assertEquals("{\"keep\":\"unchanged\"}", plugins.get(8).path("extend").asText());
        String target = repository.findById(published.id()).orElseThrow().getTargetPath();
        assertTrue(target.startsWith("/static/plugins/维护平台/"));
        assertArrayEquals(CANDIDATE.getBytes(StandardCharsets.UTF_8), files.get(target));
        int completedWrites = writes;
        assertEquals("AT_PUBLISH_CUSTOM_PLUGIN_REQUIRED", assertThrows(ResponseStatusException.class,
                () -> service.publish("candidate", input("replace", "legacy-replace"))).getReason());
        assertEquals(completedWrites, writes);
    }

    @Test
    void legacySourceReadStillRejectsForeignOriginsTraversalQueriesAndNonPythonFiles() {
        for (String locator : List.of("https://foreign.invalid/static/source.py",
                "/static/%2e%2e/source.py", "/static/source.py?token=not-a-credential",
                "/static/source.txt", "/data/source.py")) {
            plugins.get(7).put("url", locator);
            ((ObjectNode) bindings.get("source").snapshot().path("plugin"))
                    .put("sourceLocatorSha256", Hashes.sha256(locator));
            assertEquals("AT_PUBLISH_CUSTOM_PLUGIN_REQUIRED", assertThrows(ResponseStatusException.class,
                    () -> service.publish("candidate", input("incremental", "invalid-legacy"))).getReason());
        }
        assertEquals(0, writes);
        assertEquals(0, repository.count());
    }

    @Test
    void replacementKeepsOriginalPluginIdPathAndConfiguration() {
        var result = service.publish("candidate", input("replace", "replacement"));
        assertEquals("PUBLISHED", result.state());
        assertEquals(7, result.publishedPluginId());
        assertEquals(1, plugins.size());
        assertEquals("/static/plugins/原件.py", plugins.get(7).path("url").asText());
        assertEquals("{\"keep\":\"unchanged\"}", plugins.get(7).path("extend").asText());
        assertArrayEquals(CANDIDATE.getBytes(StandardCharsets.UTF_8), files.get("/static/plugins/原件.py"));
        assertEquals(BASE_SHA, result.baselineSha256());
    }

    @Test
    void failedDeliveryDoesNotMarkOriginalAndLostOriginalRenameReplyReconcilesWithoutDuplicatePrefix() {
        rejectPublishedRename = true;
        assertThrows(ResponseStatusException.class, () -> service.publish("candidate", input(null, "mark-retry")));
        assertEquals("测试源", plugins.get(7).path("name").asText());
        rejectPublishedRename = false;
        loseOriginalRenameReply = true;
        assertThrows(ResponseStatusException.class, () -> service.publish("candidate", input(null, "mark-retry")));
        assertEquals("损坏_测试源", plugins.get(7).path("name").asText());
        assertEquals("NEEDS_CONFIRMATION", repository.findAll().getFirst().getState());
        var result = service.publish("candidate", input(null, "mark-retry"));
        assertEquals("PUBLISHED", result.state());
        assertEquals("损坏_测试源", plugins.get(7).path("name").asText());
        assertEquals(1, uploads);
        assertEquals(2, plugins.size());
        assertEquals(BASELINE, new String(files.get("/static/plugins/原件.py"), StandardCharsets.UTF_8));
    }

    @Test
    void existingDamagePrefixIsNotDuplicatedAndOriginalDisabledConfigurationIsPreserved() {
        plugins.get(7).put("name", "【损坏】测试源").put("enabled", false);
        var result = service.publish("candidate", input(null, "already-marked"));
        assertEquals("PUBLISHED", result.state());
        assertEquals("【损坏】测试源", plugins.get(7).path("name").asText());
        assertFalse(plugins.get(7).path("enabled").asBoolean());
        assertEquals("{\"keep\":\"unchanged\"}", plugins.get(7).path("extend").asText());
    }

    @Test
    void savedPublicationRequiresExplicitNativeActivationAndConfirmsActualLoadedIdentity() {
        var publication = service.publish("candidate", input("replace", "runtime"));
        int publishedWrites = writes;
        var runtime = service.runtime("candidate", publication.id());
        assertEquals("RELOAD_REQUIRED", runtime.state());
        assertTrue(runtime.activationAvailable());
        assertEquals(publishedWrites, writes);
        assertEquals(0, activations);
        var activation = new ActivateAtPublicationRequest("c".repeat(64), generation, "activate-runtime",
                null, false, true);
        var active = service.activate("candidate", publication.id(), activation);
        assertEquals("ACTIVE", active.state());
        assertEquals(CANDIDATE_SHA, active.target().runtime().sourceSha256());
        assertNotEquals("1".repeat(32), active.target().runtime().workerGeneration());
        assertEquals(1, activations);
        assertEquals("ACTIVE", service.activate("candidate", publication.id(), activation).state());
        assertEquals(1, activations);
    }

    @Test
    void unsupportedAtStaysUnobservedAndChangedSavedSourceCannotBeActivated() {
        var publication = service.publish("candidate", input("replace", "unsupported"));
        nativeRuntime = false;
        var runtime = service.runtime("candidate", publication.id());
        assertEquals("UNOBSERVED", runtime.state());
        assertFalse(runtime.activationAvailable());
        nativeRuntime = true;
        files.put("/static/plugins/原件.py", BASELINE.getBytes(StandardCharsets.UTF_8));
        assertEquals("SAVED_VERSION_CHANGED", service.runtime("candidate", publication.id()).state());
        assertThrows(ResponseStatusException.class, () -> service.activate("candidate", publication.id(),
                new ActivateAtPublicationRequest("c".repeat(64), generation, "changed-activate", null, false, true)));
        assertEquals(0, activations);
    }

    @Test
    void lostFailedActivationReplyReconcilesDurableRequestDespiteChangedGeneration() {
        var publication = service.publish("candidate", input("replace", "lost-activation"));
        failActivation = loseActivationReply = true;
        var request = new ActivateAtPublicationRequest("c".repeat(64), generation, "lost-native-reply",
                null, false, true);
        assertThrows(ResponseStatusException.class, () -> service.activate("candidate", publication.id(), request));
        assertEquals(1, activations);
        assertNull(generation);
        var reconciled = service.activate("candidate", publication.id(), request);
        assertEquals("RECOVERY_REQUIRED", reconciled.state());
        assertEquals("AT_DEPLOYMENT_EXECUTION_FAILED", reconciled.reason());
        assertEquals(1, activations);
        assertEquals("lost-native-reply", reconciled.target().activations().getFirst().requestKey());
    }

    @Test
    void missingRealValidationMissingConsentAndChangedOriginalNeverWriteAt() {
        verified(false);
        assertFalse(service.target("candidate").available());
        assertThrows(ResponseStatusException.class, () -> service.publish("candidate", input(null, "unverified")));
        verified(true);
        assertThrows(ResponseStatusException.class, () -> service.publish("candidate",
                new StartAtPublicationRequest(CANDIDATE_SHA, BINDING_SHA, null, "no-consent", false, null)));
        files.put("/static/plugins/原件.py", "changed by owner".getBytes(StandardCharsets.UTF_8));
        var error = assertThrows(ResponseStatusException.class,
                () -> service.publish("candidate", input("replace", "changed")));
        assertEquals("AT_PUBLISH_ORIGINAL_CHANGED", error.getReason());
        assertEquals(0, writes);
        assertEquals(0, repository.count());
    }

    @Test
    void lostUploadReplyResumesSamePathWithoutAnotherUploadOrDuplicatePlugin() {
        loseUploadReply = true;
        assertThrows(ResponseStatusException.class, () -> service.publish("candidate", input(null, "lost-reply")));
        assertEquals("NEEDS_CONFIRMATION", repository.findByRequestKey("lost-reply").orElseThrow().getState());
        var result = service.publish("candidate", input(null, "lost-reply"));
        assertEquals("PUBLISHED", result.state());
        assertEquals(1, uploads);
        assertEquals(2, plugins.size());
        assertEquals(1, repository.count());
        assertThrows(ResponseStatusException.class, () -> service.publish("candidate", input("replace", "lost-reply")));
    }

    @Test
    void incrementalFixedIdentityCollisionIsRejectedBeforeAnyWrite() {
        String code = "PLUGIN_ID = 'fixed-origin'\n" + CANDIDATE;
        String sha = Hashes.sha256(code);
        plugins.get(7).put("externalId", "fixed-origin");
        when(repairs.content("candidate")).thenReturn(code);
        when(repairs.candidate("candidate")).thenReturn(new RepairCandidateSummary("candidate", "repair", 1,
                sha, "proposal", "RUNTIME_OBSERVED", null, Instant.now(), Instant.now(),
                JSON.createObjectNode(), JSON.createObjectNode().put("repairSuccessful", true), null, null, null));
        when(exports.delivery("candidate")).thenReturn(SpiderDeliveryNames.describe("测试源", "original.py", sha, 1, true));
        var error = assertThrows(ResponseStatusException.class, () -> service.publish("candidate",
                new StartAtPublicationRequest(sha, BINDING_SHA, "incremental", "fixed-id", true, null)));
        assertEquals("AT_PUBLISH_INCREMENTAL_ID_CONFLICT", error.getReason());
        assertEquals(0, writes);
        assertEquals(0, repository.count());
    }

    @Test
    void redirectsDoNotReceiveCredentialsAndPublishingApiRequiresAdministrator() throws Exception {
        redirectRead = true;
        assertThrows(ResponseStatusException.class, () -> service.publish("candidate", input(null, "redirect")));
        assertEquals(0, leaked);
        assertEquals(0, writes);
        try (var http = HttpClient.newHttpClient()) {
            var reply = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                    + "/api/plugin-maintenance/repair-candidates/candidate/at-publications")).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(401, reply.statusCode());
        }
    }

    @Test
    void chineseSiteNameControlsNewFileAndPublishedNameWithoutChangingSourceBytes() {
        var result = service.publish("candidate", new StartAtPublicationRequest(CANDIDATE_SHA, BINDING_SHA,
                "incremental", "chinese-site", true, "艾克斯视频"));
        assertEquals("艾克斯视频_v1.0.1", result.pluginName());
        var path = repository.findById(result.id()).orElseThrow().getTargetPath();
        assertTrue(path.endsWith("/艾克斯视频_v1.0.1.py"));
        assertArrayEquals(CANDIDATE.getBytes(StandardCharsets.UTF_8), files.get(path));
        assertEquals("损坏_测试源", plugins.get(7).path("name").asText());
    }

    @Test
    void chinesePrefixAloneDoesNotTurnAnEnglishSourceIntoAChineseSiteName() {
        var error = assertThrows(ResponseStatusException.class, () -> service.publish("candidate",
                new StartAtPublicationRequest(CANDIDATE_SHA, BINDING_SHA,
                        "incremental", "prefix-only", true, "影视源_Xvideos_TuT")));
        assertEquals("AT_PUBLISH_CHINESE_NAME_REQUIRED", error.getReason());
        assertEquals(0, writes);
    }

    @Test
    void publishedDisplayNameCanBeLocalizedWithoutAnotherUploadIdOrConfigurationChange() {
        var publication = service.publish("candidate", input(null, "localized"));
        int completedUploads = uploads;
        var request = new RenameAtPublicationRequest(CANDIDATE_SHA, publication.pluginName(), "艾克斯视频", true);
        loseRenameReply = true;
        assertThrows(ResponseStatusException.class, () -> service.rename("candidate", publication.id(), request));
        assertEquals("艾克斯视频_v1.0.1", plugins.get(8).path("name").asText());
        var renamed = service.rename("candidate", publication.id(), request);
        assertEquals("艾克斯视频_v1.0.1", renamed.pluginName());
        assertEquals(publication.id(), renamed.id());
        assertEquals(publication.publishedPluginId(), renamed.publishedPluginId());
        assertEquals(completedUploads, uploads);
        assertEquals(2, plugins.size());
        assertEquals("{\"keep\":\"unchanged\"}", plugins.get(8).path("extend").asText());
        assertEquals("损坏_测试源", plugins.get(7).path("name").asText());
        assertEquals("PUBLISHED", renamed.state());
        int completedWrites = writes;
        assertEquals(renamed, service.rename("candidate", publication.id(), request));
        assertEquals(completedWrites, writes);
    }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        try {
            assertEquals("fixture-admin", exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/leak")) { leaked++; reply(exchange, 500, new byte[0]); return; }
            if (redirectRead && path.startsWith("/static/")) {
                exchange.getResponseHeaders().set("Location", origin + "/leak");
                reply(exchange, 302, new byte[0]); return;
            }
            if (path.startsWith("/static/") && method.equals("GET")) {
                byte[] body = files.get(path);
                reply(exchange, body == null ? 404 : 200, body == null ? new byte[0] : body);
                return;
            }
            if (path.equals("/api/plugins") && method.equals("GET")) {
                reply(exchange, 200, JSON.writeValueAsBytes(plugins.values())); return;
            }
            if (path.endsWith("/saved-runtime")) {
                if (!nativeRuntime) { reply(exchange, 404, new byte[0]); return; }
                int id = Integer.parseInt(path.split("/")[5]);
                var target = JSON.createObjectNode().put("enabled", true).put("pluginId", id)
                        .put("pluginSha256", CANDIDATE_SHA).put("configurationSha256", "c".repeat(64));
                target.putObject("runtime").put("pluginId", id).put("siteKey", "plugin-" + id)
                        .put("sourceSha256", loadedSha).put("loadState", failActivation && activations > 0 ? "BLOCKED" : "LOADED")
                        .put("loaded", !failActivation || activations == 0)
                        .put("workerGeneration", generation);
                var history = target.putArray("activations");
                if (recordedActivation != null) history.add(recordedActivation);
                reply(exchange, 200, JSON.writeValueAsBytes(target)); return;
            }
            if (path.equals("/api/plugin-maintenance/csrf")) {
                reply(exchange, 200, JSON.writeValueAsBytes(JSON.createObjectNode()
                        .put("headerName", "X-CSRF-TOKEN").put("token", "native-csrf"))); return;
            }
            writes++;
            if (path.endsWith("/activations")) {
                assertEquals("native-csrf", exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN"));
                var input = JSON.readTree(exchange.getRequestBody());
                if (recordedActivation != null && recordedActivation.path("requestKey").asText()
                        .equals(input.path("requestKey").asText())) {
                    reply(exchange, 200, JSON.writeValueAsBytes(recordedActivation)); return;
                }
                assertEquals(generation, input.path("expectedWorkerGeneration").asText());
                assertEquals(CANDIDATE_SHA, input.path("expectedPluginSha256").asText());
                assertFalse(input.path("interruptIssuedPlayback").asBoolean());
                activations++;
                loadedSha = failActivation ? null : CANDIDATE_SHA;
                generation = failActivation ? null : "2".repeat(32);
                recordedActivation = JSON.createObjectNode().put("id", UUID.randomUUID().toString())
                        .put("requestKey", input.path("requestKey").asText())
                        .put("state", failActivation ? "RECOVERY_REQUIRED" : "DEPLOYED")
                        .put("loadedGeneration", generation)
                        .put("errorCode", failActivation ? "AT_DEPLOYMENT_EXECUTION_FAILED" : null);
                if (loseActivationReply) {
                    loseActivationReply = false; reply(exchange, 503, new byte[0]); return;
                }
                reply(exchange, 200, JSON.writeValueAsBytes(recordedActivation)); return;
            }
            if (path.equals("/api/static-files/upload")) {
                uploads++;
                String query = exchange.getRequestURI().getRawQuery();
                String dir = URLDecoder.decode(query.substring(query.indexOf("dir=") + 4), StandardCharsets.UTF_8);
                byte[] body = exchange.getRequestBody().readAllBytes();
                String multipart = new String(body, StandardCharsets.UTF_8);
                var name = Pattern.compile("filename=\"([^\"]+)\"").matcher(multipart);
                assertTrue(name.find());
                int headerEnd = multipart.indexOf("\r\n\r\n") + 4;
                int end = multipart.lastIndexOf("\r\n--");
                String target = "/static/" + dir + "/" + name.group(1);
                files.put(target, multipart.substring(headerEnd, end).getBytes(StandardCharsets.UTF_8));
                if (plugins.values().stream().noneMatch(item -> item.path("url").asText().equals(target))) {
                    plugins.put(8, JSON.createObjectNode().put("id", 8).put("url", target)
                            .put("name", name.group(1)).put("enabled", true).put("lastError", ""));
                }
                if (loseUploadReply) { loseUploadReply = false; reply(exchange, 503, new byte[0]); return; }
                reply(exchange, 200, new byte[0]); return;
            }
            if (path.matches("/api/plugins/\\d+/refresh")) {
                reply(exchange, 200, JSON.writeValueAsBytes(plugins.get(Integer.parseInt(path.split("/")[3])))); return;
            }
            if (method.equals("PUT") && path.matches("/api/plugins/\\d+")) {
                if (rejectPublishedRename && !path.endsWith("/7")) { reply(exchange, 503, new byte[0]); return; }
                var saved = plugins.get(Integer.parseInt(path.split("/")[3]));
                saved.setAll((ObjectNode) JSON.readTree(exchange.getRequestBody()));
                if (loseOriginalRenameReply && path.endsWith("/7")) {
                    loseOriginalRenameReply = false; reply(exchange, 503, new byte[0]); return;
                }
                if (loseRenameReply) { loseRenameReply = false; reply(exchange, 503, new byte[0]); return; }
                reply(exchange, 200, JSON.writeValueAsBytes(saved)); return;
            }
            reply(exchange, 404, new byte[0]);
        } catch (Throwable error) {
            reply(exchange, 500, new byte[0]);
        } finally { exchange.close(); }
    }

    private void reply(HttpExchange exchange, int status, byte[] body) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) exchange.getResponseBody().write(body);
    }
}
