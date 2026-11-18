package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:maintenance-media-budget-tests;DB_CLOSE_DELAY=-1",
        "spring.security.user.password=integration-test-only", "maintenance.at.origin=",
        "maintenance.scheduling.enabled=false", "maintenance.model.enabled=false",
        "maintenance.media.enabled=true", "maintenance.media.allow-http=true",
        "maintenance.media.daily-probe-limit=3", "maintenance.media.daily-request-limit=36",
        "maintenance.media.daily-retained-bytes=1572864", "maintenance.media.source-daily-probe-limit=2",
        "maintenance.media.source-daily-request-limit=24", "maintenance.media.source-daily-retained-bytes=1048576",
        "maintenance.media.source-interval-seconds=1800"
})
class MediaBudgetIntegrationTest {
    static final MutableClock CLOCK = new MutableClock();
    static final AtomicInteger REQUESTS = new AtomicInteger();
    static final HttpServer HTTP = server();
    static final String ORIGIN = "http://127.0.0.1:" + HTTP.getAddress().getPort();
    static final String ALIAS = "http://localhost:" + HTTP.getAddress().getPort();

    @DynamicPropertySource
    static void origins(DynamicPropertyRegistry values) {
        values.add("maintenance.media.allowed-origins", () -> ORIGIN + "," + ALIAS + ",http://127.0.0.1:1");
    }
    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary Clock testMediaClock() { return CLOCK; }
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> value = new AtomicReference<>(Instant.parse("2030-01-01T10:00:00Z"));
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        public Instant instant() { return value.get(); }
        void at(String value) { this.value.set(Instant.parse(value)); }
        void advance(long seconds) { value.updateAndGet(now -> now.plusSeconds(seconds)); }
    }
    @Autowired SourceService sources;
    @Autowired MediaProbeService service;
    @Autowired MediaProbeStore store;
    @Autowired MediaBudgetStore budgets;
    @Autowired MediaBudgetPolicy policy;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        store.recover();
        CLOCK.at("2030-01-01T10:00:00Z");
        jdbc.update("delete from maintenance_media_host_budget");
        jdbc.update("delete from maintenance_media_budget where id<>'global'");
        jdbc.update("update maintenance_media_budget set budget_day=null, used_probes=0, reserved_requests=0,"
                + "reserved_body_bytes=0, next_due_at=null, last_observed_at=null where id='global'");
        REQUESTS.set(0);
    }

    @AfterAll
    static void closeServer() { HTTP.stop(0); }

    @Test
    void realHttpSamplesShareOneHostPermitAndFreezeTheNonRefundableReservation() throws Exception {
        var source = source();
        var run = await(service.start(source.id(), input(source, ORIGIN + "/list")).id());
        assertEquals("sample_checked", run.result().path("verdict").asText());
        assertEquals(2, REQUESTS.get());
        assertEquals(2, run.issuedRequests());
        assertEquals(1, budgets.global().usedProbes());
        assertEquals(12, budgets.global().reservedRequests());
        assertEquals(524288, budgets.global().reservedBodyBytes());
        assertEquals(1, budgets.source(source.id()).usedProbes());
        assertEquals(policy.sha256(), run.budget().path("policySha256").asText());
        assertFalse(run.budget().path("refundable").asBoolean());
        assertEquals(1, jdbc.queryForObject("select count(*) from maintenance_media_host_budget", Integer.class));
        assertFalse(run.budget().toString().contains("127.0.0.1"));
        assertFalse(run.budget().toString().contains("secret"));
    }

    @Test
    void sourceCooldownSurvivesArtifactChangesWithoutChargingARejectedAdmission() throws Exception {
        var source = source();
        await(service.start(source.id(), input(source, ORIGIN + "/list")).id());
        sources.addArtifact(source.id(), new AddArtifactRequest(source.artifactSha256(), "class Spider:\n    changed=True\n"));
        var updated = sources.get(source.id());
        assertEquals("MEDIA_SOURCE_COOLDOWN", assertThrows(ResponseStatusException.class,
                () -> service.start(source.id(), input(updated, ORIGIN + "/list"))).getReason());
        assertEquals(1, budgets.global().usedProbes());
        assertEquals(1, store.list(source.id()).size());
    }

    @Test
    void anotherSourceAndAnotherPortCannotBypassTheSameHostCooldown() throws Exception {
        var first = source();
        await(service.start(first.id(), input(first, ORIGIN + "/list")).id());
        var second = source();
        var run = await(service.start(second.id(), input(second, "http://127.0.0.1:1/no-connect")).id());
        assertEquals("sample_blocked", run.result().path("verdict").asText());
        assertEquals("MEDIA_HOST_COOLDOWN", run.result().path("nodes").get(0).path("reason").asText());
        assertEquals(0, run.issuedRequests());
        assertEquals(0, run.result().path("usage").path("requests").asInt());
        assertEquals(2, REQUESTS.get());
        assertEquals(2, budgets.global().usedProbes());
        assertEquals(12, budgets.source(second.id()).reservedRequests());
    }

    @Test
    void aRedirectMustAcquireTheDerivedHostsPermitBeforeContactingIt() throws Exception {
        var previous = source();
        var claimed = store.create(previous.id(), previous.artifactSha256());
        store.start(claimed.getId());
        budgets.authorize(claimed.getId(), URI.create(ALIAS + "/fixture"));
        store.finish(claimed.getId(), "INTERRUPTED", null, "TEST_FIXTURE");
        var current = source();
        var run = await(service.start(current.id(), input(current, ORIGIN + "/redirect")).id());
        assertEquals("sample_blocked", run.result().path("verdict").asText());
        assertEquals("MEDIA_HOST_COOLDOWN", run.result().path("nodes").get(0).path("reason").asText());
        assertEquals(1, REQUESTS.get());
        assertEquals(1, run.issuedRequests());
        assertEquals(302, run.result().path("resources").get(0).path("httpStatus").asInt());
    }

    @Test
    void concurrentReservationsCannotOverspendTheGlobalLimit() throws Exception {
        List<SourceSummary> inputs = List.of(source(), source(), source(), source());
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (var source : inputs) attempts.add(executor.submit(() -> {
                try { store.create(source.id(), source.artifactSha256()); return true; }
                catch (ResponseStatusException error) {
                    assertEquals("MEDIA_GLOBAL_DAILY_PROBE_LIMIT", error.getReason());
                    return false;
                }
            }));
            int accepted = 0;
            for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(3, accepted);
        }
        assertEquals(3, budgets.global().usedProbes());
        assertEquals(36, budgets.global().reservedRequests());
        assertEquals(1572864, budgets.global().reservedBodyBytes());
        store.recover();
        assertEquals(3, budgets.global().usedProbes());
    }

    @Test
    void sourceDailyLimitRemainsAcrossCooldownsAndCancelledRuns() {
        var source = source();
        for (int index = 0; index < 2; index++) {
            var run = store.create(source.id(), source.artifactSha256());
            store.cancel(run.getId());
            CLOCK.advance(1800);
        }
        assertEquals("MEDIA_SOURCE_DAILY_PROBE_LIMIT", assertThrows(ResponseStatusException.class,
                () -> store.create(source.id(), source.artifactSha256())).getReason());
        assertEquals(2, budgets.global().usedProbes());
        assertEquals(2, budgets.source(source.id()).usedProbes());
    }

    @Test
    void utcRolloverDoesNotEraseHistoricalReservationsAndClockRollbackCannotGrantNewBudget() {
        var source = source();
        var first = store.create(source.id(), source.artifactSha256());
        String frozen = store.get(first.getId()).budget().toString();
        store.cancel(first.getId());
        CLOCK.advance(-1);
        var other = source();
        assertEquals("MEDIA_CLOCK_ROLLBACK", assertThrows(ResponseStatusException.class,
                () -> store.create(other.id(), other.artifactSha256())).getReason());
        CLOCK.at("2030-01-02T10:00:00Z");
        store.create(source.id(), source.artifactSha256());
        assertEquals(LocalDate.of(2030, 1, 2), budgets.global().budgetDay());
        assertEquals(1, budgets.global().usedProbes());
        assertEquals(frozen, store.get(first.getId()).budget().toString());
        CLOCK.at("2030-01-01T12:00:00Z");
        assertEquals("MEDIA_CLOCK_ROLLBACK", assertThrows(ResponseStatusException.class,
                () -> store.create(other.id(), other.artifactSha256())).getReason());
        assertEquals(1, budgets.global().usedProbes());
    }

    @Test
    void anOldDaysTaskCannotSpendItsReservationAfterMidnight() throws Exception {
        CLOCK.at("2030-01-01T23:59:59Z");
        var source = source();
        var run = store.create(source.id(), source.artifactSha256());
        store.start(run.getId());
        CLOCK.advance(2);
        assertEquals("MEDIA_RESERVATION_DAY_EXPIRED", assertThrows(IOException.class,
                () -> budgets.authorize(run.getId(), URI.create(ORIGIN))).getMessage());
        assertEquals(0, store.get(run.getId()).issuedRequests());
        assertEquals(LocalDate.of(2030, 1, 1), budgets.global().budgetDay());
        var next = source();
        store.create(next.id(), next.artifactSha256());
        assertEquals(LocalDate.of(2030, 1, 2), budgets.global().budgetDay());
        assertEquals(1, budgets.global().usedProbes());
    }

    @Test
    void cancellationQueueRejectionAndRecoveryNeverRefundAcceptedReservations() {
        var a = source(); var b = source(); var c = source();
        var cancelled = store.create(a.id(), a.artifactSha256());
        store.cancel(cancelled.getId());
        var rejected = store.create(b.id(), b.artifactSha256());
        store.finish(rejected.getId(), "REJECTED", null, "MEDIA_QUEUE_FULL");
        var interrupted = store.create(c.id(), c.artifactSha256());
        store.start(interrupted.getId());
        store.recover();
        assertEquals("INTERRUPTED", store.get(interrupted.getId()).status());
        assertEquals(3, budgets.global().usedProbes());
        var fourth = source();
        assertEquals("MEDIA_GLOBAL_DAILY_PROBE_LIMIT", assertThrows(ResponseStatusException.class,
                () -> store.create(fourth.id(), fourth.artifactSha256())).getReason());
    }

    @Test
    void everyRequestPermitIsPersistedAndTheThirteenthIsRefused() throws Exception {
        var source = source();
        var run = store.create(source.id(), source.artifactSha256());
        store.start(run.getId());
        for (int index = 0; index < 12; index++) budgets.authorize(run.getId(), URI.create(ORIGIN));
        assertEquals("MEDIA_PERSISTENT_REQUEST_LIMIT", assertThrows(IOException.class,
                () -> budgets.authorize(run.getId(), URI.create(ORIGIN))).getMessage());
        assertEquals(12, store.get(run.getId()).issuedRequests());
        store.cancel(run.getId());
        assertEquals("MEDIA_RUN_NOT_ACTIVE", assertThrows(IOException.class,
                () -> budgets.authorize(run.getId(), URI.create(ORIGIN))).getMessage());
        store.finish(run.getId(), "INTERRUPTED", null, "TEST_FIXTURE");
        assertEquals(0, REQUESTS.get());
    }

    @Test
    void requestAndBodyQuotasRejectBeforeCreatingOrChargingAnotherRun() {
        var source = source();
        jdbc.update("update maintenance_media_budget set budget_day='2030-01-01', reserved_requests=25 where id='global'");
        assertEquals("MEDIA_GLOBAL_DAILY_REQUEST_LIMIT", assertThrows(ResponseStatusException.class,
                () -> store.create(source.id(), source.artifactSha256())).getReason());
        jdbc.update("update maintenance_media_budget set reserved_requests=0, reserved_body_bytes=1048577 where id='global'");
        assertEquals("MEDIA_GLOBAL_DAILY_BODY_LIMIT", assertThrows(ResponseStatusException.class,
                () -> store.create(source.id(), source.artifactSha256())).getReason());
        jdbc.update("update maintenance_media_budget set reserved_body_bytes=0 where id='global'");
        var first = store.create(source.id(), source.artifactSha256());
        store.cancel(first.getId());
        CLOCK.advance(1800);
        jdbc.update("update maintenance_media_budget set reserved_requests=13 where id=?", "source:" + source.id());
        assertEquals("MEDIA_SOURCE_DAILY_REQUEST_LIMIT", assertThrows(ResponseStatusException.class,
                () -> store.create(source.id(), source.artifactSha256())).getReason());
        jdbc.update("update maintenance_media_budget set reserved_requests=12, reserved_body_bytes=524289 where id=?", "source:" + source.id());
        assertEquals("MEDIA_SOURCE_DAILY_BODY_LIMIT", assertThrows(ResponseStatusException.class,
                () -> store.create(source.id(), source.artifactSha256())).getReason());
        assertEquals(1, budgets.global().usedProbes());
        assertEquals(1, store.list(source.id()).size());
    }

    @Test
    void policyRejectsIntervalsBelowThirtyMinutesAndIdentifiesChangedLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new MediaBudgetPolicy(mapper, 24, 288, 12582912, 4, 48, 2097152, 1799, 1800));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaBudgetPolicy(mapper, 24, 288, 12582912, 4, 48, 2097152, 21600, 1799));
        var other = new MediaBudgetPolicy(mapper, 24, 288, 12582912, 4, 48, 2097152, 21600, 1800);
        assertNotEquals(policy.sha256(), other.sha256());
        assertFalse(budgets.stale(null));
    }

    private SourceSummary source() {
        return sources.create(new CreateSourceRequest("budget fixture", null, "source.py", null, "class Spider:\n    pass\n"));
    }
    private StartMediaProbeRequest input(SourceSummary source, String url) {
        return new StartMediaProbeRequest(source.artifactSha256(), mapper.createObjectNode()
                .put("url", url + "?signature=secret"));
    }
    private MediaProbeSummary await(String id) throws InterruptedException {
        Instant end = Instant.now().plusSeconds(8);
        do {
            var run = store.get(id);
            if (!Set.of("QUEUED", "RUNNING", "CANCELLING").contains(run.status())) return run;
            Thread.sleep(20);
        } while (Instant.now().isBefore(end));
        throw new AssertionError("Media task not finished: " + id);
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                REQUESTS.incrementAndGet();
                try {
                    if (exchange.getRequestURI().getPath().equals("/redirect")) {
                        exchange.getResponseHeaders().set("Location", "http://localhost:" + server.getAddress().getPort() + "/not-contacted");
                        exchange.sendResponseHeaders(302, -1);
                        return;
                    }
                    byte[] bytes;
                    if (exchange.getRequestURI().getPath().equals("/list"))
                        bytes = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\ns0\n#EXT-X-ENDLIST\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    else {
                        bytes = TsFixtures.stream();
                    }
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            });
            server.start();
            return server;
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }
}
