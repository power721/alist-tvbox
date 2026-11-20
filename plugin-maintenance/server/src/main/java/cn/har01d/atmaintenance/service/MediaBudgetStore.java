package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.MediaBudgetSummary;
import cn.har01d.atmaintenance.entity.*;
import cn.har01d.atmaintenance.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.util.Locale;

/** Lock order: global budget, run. Host/source rows are protected by the global lock. */
@Service
@RequiredArgsConstructor
public class MediaBudgetStore {
    private final MediaBudgetRepository budgets;
    private final MediaHostBudgetRepository hosts;
    private final MediaProbeRepository runs;
    private final MediaBudgetPolicy policy;
    private final SourceService sources;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public MediaBudgetSummary global() {
        return summary(budgets.findById("global").orElseThrow(), "global");
    }

    @Transactional(readOnly = true)
    public MediaBudgetSummary source(String sourceId) {
        sources.requireSource(sourceId);
        return summary(budgets.findById("source:" + sourceId).orElseGet(MediaBudget::new), "source");
    }

    /** Joins run creation's transaction. No terminal state refunds accepted reservations. */
    @Transactional
    public ObjectNode reserve(String sourceId) {
        MediaBudget global = budgets.lockGlobal();
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (rollback(global, now)) throw refused("MEDIA_CLOCK_ROLLBACK");
        MediaBudget source = budgets.findById("source:" + sourceId).orElseGet(() -> {
            MediaBudget row = new MediaBudget();
            row.setId("source:" + sourceId);
            return row;
        });
        if (source.getBudgetDay() != null && day.isBefore(source.getBudgetDay()))
            throw refused("MEDIA_CLOCK_ROLLBACK");
        if (source.getNextDueAt() != null && now.isBefore(source.getNextDueAt()))
            throw refused("MEDIA_SOURCE_COOLDOWN");
        rollDay(global, day);
        rollDay(source, day);
        requireCapacity(global, policy.globalProbes, policy.globalRequests, policy.globalBytes, "GLOBAL");
        requireCapacity(source, policy.sourceProbes, policy.sourceRequests, policy.sourceBytes, "SOURCE");
        charge(global); charge(source);
        global.setLastObservedAt(now);
        source.setLastObservedAt(now);
        source.setNextDueAt(now.plusSeconds(policy.sourceInterval));
        budgets.save(source);
        ObjectNode result = mapper.createObjectNode().put("reserved", true).put("budgetDay", day.toString())
                .put("globalOrdinal", global.getUsedProbes()).put("sourceOrdinal", source.getUsedProbes())
                .put("reservedRequests", MediaNetworkPolicy.MAX_REQUESTS)
                .put("reservedBodyBytes", MediaNetworkPolicy.MAX_TOTAL_BYTES)
                .put("policySha256", policy.sha256()).put("refundable", false);
        result.set("policy", policy.snapshot());
        return result;
    }

    /** Commits the permit before the caller opens the HTTP request, including redirect/segment hosts. */
    @Transactional(rollbackFor = IOException.class)
    public void authorize(String runId, URI uri) throws IOException {
        MediaBudget global = budgets.lockGlobal();
        Instant now = clock.instant();
        if (rollback(global, now)) throw new IOException("MEDIA_CLOCK_ROLLBACK");
        MediaProbeRun run = runs.findForUpdate(runId).orElseThrow(() -> new IOException("MEDIA_RUN_NOT_ACTIVE"));
        if (!run.getStatus().equals("RUNNING")) throw new IOException("MEDIA_RUN_NOT_ACTIVE");
        JsonNode reservation = decode(run.getBudgetJson());
        if (reservation == null || !reservation.path("reserved").asBoolean())
            throw new IOException("MEDIA_RESERVATION_REQUIRED");
        if (!LocalDate.ofInstant(now, ZoneOffset.UTC).toString().equals(reservation.path("budgetDay").asText()))
            throw new IOException("MEDIA_RESERVATION_DAY_EXPIRED");
        if (run.getIssuedRequests() >= reservation.path("reservedRequests").asInt())
            throw new IOException("MEDIA_PERSISTENT_REQUEST_LIMIT");
        String hostSha = Hashes.sha256(uri.getHost().toLowerCase(Locale.ROOT));
        MediaHostBudget host = hosts.findById(hostSha).orElse(null);
        if (host != null && !host.getLastRunId().equals(runId) && now.isBefore(host.getNextDueAt()))
            throw new IOException("MEDIA_HOST_COOLDOWN");
        if (host == null) {
            host = new MediaHostBudget();
            host.setHostSha256(hostSha);
        }
        if (!runId.equals(host.getLastRunId())) {
            host.setLastRunId(runId);
            host.setNextDueAt(now.plusSeconds(reservation.path("policy").path("hostIntervalSeconds").asInt()));
            hosts.save(host);
        }
        global.setLastObservedAt(now);
        run.setIssuedRequests(run.getIssuedRequests() + 1);
    }

    public boolean stale(JsonNode reservation) {
        return reservation != null && reservation.path("reserved").asBoolean()
                && !policy.sha256().equals(reservation.path("policySha256").asText());
    }

    private boolean rollback(MediaBudget row, Instant now) {
        return row.getLastObservedAt() != null && now.isBefore(row.getLastObservedAt())
                || row.getBudgetDay() != null && LocalDate.ofInstant(now, ZoneOffset.UTC).isBefore(row.getBudgetDay());
    }

    private void rollDay(MediaBudget row, LocalDate day) {
        if (!day.equals(row.getBudgetDay())) {
            row.setBudgetDay(day); row.setUsedProbes(0);
            row.setReservedRequests(0); row.setReservedBodyBytes(0);
        }
    }

    private void requireCapacity(MediaBudget row, int probes, long requests, long bytes, String scope) {
        if (row.getUsedProbes() >= probes) throw refused("MEDIA_" + scope + "_DAILY_PROBE_LIMIT");
        if (requests - row.getReservedRequests() < MediaNetworkPolicy.MAX_REQUESTS)
            throw refused("MEDIA_" + scope + "_DAILY_REQUEST_LIMIT");
        if (bytes - row.getReservedBodyBytes() < MediaNetworkPolicy.MAX_TOTAL_BYTES)
            throw refused("MEDIA_" + scope + "_DAILY_BODY_LIMIT");
    }

    private void charge(MediaBudget row) {
        row.setUsedProbes(row.getUsedProbes() + 1);
        row.setReservedRequests(row.getReservedRequests() + MediaNetworkPolicy.MAX_REQUESTS);
        row.setReservedBodyBytes(row.getReservedBodyBytes() + MediaNetworkPolicy.MAX_TOTAL_BYTES);
    }

    private MediaBudgetSummary summary(MediaBudget row, String scope) {
        return new MediaBudgetSummary(scope, row.getBudgetDay(), row.getUsedProbes(), row.getReservedRequests(),
                row.getReservedBodyBytes(), row.getNextDueAt(), row.getLastObservedAt());
    }

    private JsonNode decode(String value) {
        if (value == null) return null;
        try { return mapper.readTree(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Stored media reservation is invalid", error); }
    }

    private ResponseStatusException refused(String code) {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, code);
    }
}
