package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.*;
import cn.har01d.atmaintenance.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Single control process; one global row serializes all budgets, consent and active ownership. */
@Service
@RequiredArgsConstructor
public class ModelStore {
    private static final Set<String> ACTIVE = Set.of("QUEUED", "RUNNING", "CANCELLING");
    private final ModelBudgetRepository budgets;
    private final ModelSourceBudgetRepository sourceBudgets;
    private final ModelNamedBudgetRepository namedBudgets;
    private final ModelCallRepository calls;
    private final RepairCandidateRepository candidates;
    private final ModelConsentRepository consents;
    private final RepairStore repairs;
    private final SourceService sources;
    private final ModelClient client;
    private final RepairPatch patches;
    private final RepairDiagnostics diagnostics;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ModelBudgetSummary budget() {
        var value = budgets.findById(1).orElseThrow();
        return new ModelBudgetSummary(value.getBudgetDay(), value.getUsedCalls(), value.getReservedInputBytes(),
                value.getReservedOutputTokens(), value.getReservedCostUsd(), value.getActiveCallId(),
                client.connection().dailyCostUsd(), value.getCostBreachDay());
    }

    @Transactional(readOnly = true)
    public ModelSourceBudgetSummary sourceBudget(String sourceId) {
        sources.requireSource(sourceId);
        var value = sourceBudgets.findById(sourceId).orElseGet(ModelSourceBudget::new);
        var config = client.connection();
        return new ModelSourceBudgetSummary(sourceId, value.getBudgetDay(), value.getUsedCalls(),
                value.getReservedInputBytes(), value.getReservedOutputTokens(), value.getReservedCostUsd(),
                config.sourceDailyCallLimit(), config.sourceDailyInputBytes(), config.sourceDailyOutputTokens(),
                config.sourceDailyCostUsd());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ModelNamedBudgetSummary namedBudget() {
        var config = client.connection();
        var value = namedBudgets.findById(config.model()).orElseGet(ModelNamedBudget::new);
        int usageCalls = 0, pricedCalls = 0;
        Long promptTokens = null, completionTokens = null, totalTokens = null;
        BigDecimal cost = null;
        if (value.getBudgetDay() != null) {
            Instant start = value.getBudgetDay().atStartOfDay(ZoneOffset.UTC).toInstant();
            // Read the immutable call ledger so legacy usage needs no invented price or rewritten snapshot.
            for (var call : calls.findByModelNameAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                    config.model(), start, start.plus(Duration.ofDays(1)))) {
                var usage = decode(call.getUsageJson());
                if (usage == null || usage.isNull()) continue;
                usageCalls++;
                promptTokens = (promptTokens == null ? 0 : promptTokens) + usage.path("prompt_tokens").asLong();
                completionTokens = (completionTokens == null ? 0 : completionTokens) + usage.path("completion_tokens").asLong();
                totalTokens = (totalTokens == null ? 0 : totalTokens) + usage.path("total_tokens").asLong();
                var estimate = decode(call.getCostJson());
                if (estimate != null) {
                    pricedCalls++;
                    cost = (cost == null ? BigDecimal.ZERO : cost)
                            .add(new BigDecimal(estimate.path("totalUsd").asText()));
                }
            }
        }
        return new ModelNamedBudgetSummary(config.model(), value.getBudgetDay(), value.getUsedCalls(),
                value.getReservedInputBytes(), value.getReservedOutputTokens(), config.modelDailyCallLimit(),
                config.modelDailyInputBytes(), config.modelDailyOutputTokens(), usageCalls, pricedCalls,
                value.getUsedCalls() - usageCalls, usageCalls - pricedCalls,
                promptTokens, completionTokens, totalTokens, cost == null ? null : cost.stripTrailingZeros().toPlainString(),
                value.getReservedCostUsd(), config.modelDailyCostUsd());
    }

    @Transactional(readOnly = true)
    public ModelConsent consent(String sourceId) {
        sources.requireSource(sourceId);
        return new ModelConsent(sourceId, consents.findById(sourceId).map(ModelSourceConsent::getArtifactSha256).orElse(null));
    }

    @Transactional
    public ModelConsent consent(String sourceId, UpdateModelConsentRequest input) {
        budgets.lock().orElseThrow();
        var current = consent(sourceId);
        if (!Objects.equals(current.artifactSha256(), input.expectedArtifactSha256())) throw conflict("MODEL_CONSENT_CHANGED");
        if (input.artifactSha256() != null && !sources.get(sourceId).artifactSha256().equals(input.artifactSha256()))
            throw conflict("MODEL_CONSENT_ARTIFACT_CHANGED");
        var row = consents.findById(sourceId).orElseGet(ModelSourceConsent::new);
        row.setSourceId(sourceId);
        row.setArtifactSha256(input.artifactSha256());
        consents.saveAndFlush(row);
        return new ModelConsent(sourceId, row.getArtifactSha256());
    }

    @Transactional(readOnly = true)
    public RepairSummary reverseInputs(String repairId, StartModelCallRequest input) {
        if (calls.findByRepairIdAndRequestKey(repairId, input.requestKey()).isPresent()) return null;
        var repair = repairs.get(repairId);
        if (!repair.faultSha256().equals(input.expectedFaultSha256())) throw conflict("REPAIR_FAULT_CHANGED");
        client.requireEnabled();
        if (!client.connection().configurationSha256().equals(input.expectedConfigurationSha256()))
            throw conflict("MODEL_CONFIGURATION_CHANGED");
        requireInputs(repair);
        requireAccessibleInput(repair);
        requireFaultCall(repair, input, calls.findByRepairIdOrderByAttemptNumberAsc(repairId));
        if (budget().activeCallId() != null) throw conflict("MODEL_BUSY");
        return repair;
    }

    @Transactional
    public ModelCall reserve(String repairId, StartModelCallRequest input) {
        return reserve(repairId, input, null);
    }

    @Transactional
    public ModelCall reserveOriginDiscovery(String repairId, String expectedFaultSha, JsonNode searchEvidence) {
        return reserve(repairId, new StartModelCallRequest(expectedFaultSha, client.connection().configurationSha256(),
                "alternate_origins_once", false, false), client.originDiscoveryRequest(repairs.get(repairId), searchEvidence));
    }

    private ModelCall reserve(String repairId, StartModelCallRequest input, String discoveryRequest) {
        var budget = budgets.lock().orElseThrow();
        var repair = repairs.get(repairId);
        if (!repair.faultSha256().equals(input.expectedFaultSha256())) throw conflict("REPAIR_FAULT_CHANGED");
        var previous = calls.findByRepairIdAndRequestKey(repairId, input.requestKey());
        if (previous.isPresent()) {
            if (!previous.get().getConfigurationSha256().equals(input.expectedConfigurationSha256())
                    || (discoveryRequest != null) != ModelClient.discoveryRequest(previous.get().getRequestJson())
                    || previous.get().isAutoSubmit() != input.autoSubmit()
                    || ModelClient.enhancedRequest(mapper, previous.get().getRequestJson()) != input.enhancedDiagnostics())
                throw conflict("MODEL_REQUEST_KEY_CONFLICT");
            return previous.get();
        }
        client.requireEnabled();
        var config = client.connection();
        if (!config.configurationSha256().equals(input.expectedConfigurationSha256())) throw conflict("MODEL_CONFIGURATION_CHANGED");
        if (input.autoSubmit() && !config.autoSubmitEnabled()) throw conflict("MODEL_AUTO_SUBMIT_DISABLED");
        if (discoveryRequest == null) {
            requireInputs(repair);
            requireAccessibleInput(repair);
        } else requireDiscoveryInputs(repair);
        var history = calls.findByRepairIdOrderByAttemptNumberAsc(repairId);
        if (discoveryRequest == null) requireFaultCall(repair, input, history);
        if (discoveryRequest == null && history.stream().anyMatch(call -> "REPEATED_MODEL_PROPOSAL".equals(call.getErrorCode())))
            throw conflict("MODEL_REPEATED_PROPOSAL_STOP");
        if (budget.getActiveCallId() != null) throw conflict("MODEL_BUSY");
        String request = discoveryRequest != null ? discoveryRequest : client.request(repair,
                sources.artifact(repair.sourceId(), repair.baselineArtifactSha256()).getBody(),
                repairs.candidates(repairId), input.enhancedDiagnostics(), history.stream()
                        .filter(call -> !ModelClient.discoveryRequest(call.getRequestJson())).map(this::summary).toList());
        int bytes = request.getBytes(StandardCharsets.UTF_8).length;
        BigDecimal maximumCost = maximumCost(config, bytes);
        LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (budget.getBudgetDay() != null && day.isBefore(budget.getBudgetDay())) throw conflict("MODEL_CLOCK_ROLLBACK");
        if (budget.getCostBreachDay() != null && !day.isAfter(budget.getCostBreachDay()))
            throw conflict("MODEL_COST_RESERVATION_EXCEEDED");
        requireKnownCostHistory(budget.getBudgetDay(), day, budget.getUsedCalls(), budget.getReservedCostUsd(),
                config.dailyCostUsd());
        if (!day.equals(budget.getBudgetDay())) {
            budget.setBudgetDay(day);
            budget.setUsedCalls(0);
            budget.setReservedInputBytes(0);
            budget.setReservedOutputTokens(0);
        }
        if (budget.getUsedCalls() == 0) budget.setReservedCostUsd(maximumCost == null ? null : BigDecimal.ZERO);
        if (exceeds(config.dailyCallLimit(), budget.getUsedCalls(), 1)
                || exceeds(config.dailyInputBytes(), budget.getReservedInputBytes(), bytes)
                || exceeds(config.dailyOutputTokens(), budget.getReservedOutputTokens(), config.maxCompletionTokens()))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "MODEL_DAILY_BUDGET");
        if (exceeds(config.dailyCostUsd(), budget.getReservedCostUsd(), maximumCost))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "MODEL_COST_DAILY_BUDGET");
        var sourceBudget = sourceBudgets.findById(repair.sourceId()).orElseGet(ModelSourceBudget::new);
        sourceBudget.setSourceId(repair.sourceId());
        if (sourceBudget.getBudgetDay() != null && day.isBefore(sourceBudget.getBudgetDay()))
            throw conflict("MODEL_CLOCK_ROLLBACK");
        requireKnownCostHistory(sourceBudget.getBudgetDay(), day, sourceBudget.getUsedCalls(),
                sourceBudget.getReservedCostUsd(),
                config.sourceDailyCostUsd());
        if (!day.equals(sourceBudget.getBudgetDay())) {
            sourceBudget.setBudgetDay(day);
            sourceBudget.setUsedCalls(0);
            sourceBudget.setReservedInputBytes(0);
            sourceBudget.setReservedOutputTokens(0);
        }
        if (sourceBudget.getUsedCalls() == 0) sourceBudget.setReservedCostUsd(maximumCost == null ? null : BigDecimal.ZERO);
        if (exceeds(config.sourceDailyCallLimit(), sourceBudget.getUsedCalls(), 1)
                || exceeds(config.sourceDailyInputBytes(), sourceBudget.getReservedInputBytes(), bytes)
                || exceeds(config.sourceDailyOutputTokens(), sourceBudget.getReservedOutputTokens(), config.maxCompletionTokens()))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "MODEL_SOURCE_DAILY_BUDGET");
        if (exceeds(config.sourceDailyCostUsd(), sourceBudget.getReservedCostUsd(), maximumCost))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "MODEL_SOURCE_COST_DAILY_BUDGET");
        var namedBudget = namedBudgets.findById(config.model()).orElseGet(ModelNamedBudget::new);
        namedBudget.setModelName(config.model());
        if (namedBudget.getBudgetDay() != null && day.isBefore(namedBudget.getBudgetDay()))
            throw conflict("MODEL_CLOCK_ROLLBACK");
        requireKnownCostHistory(namedBudget.getBudgetDay(), day, namedBudget.getUsedCalls(),
                namedBudget.getReservedCostUsd(),
                config.modelDailyCostUsd());
        if (!day.equals(namedBudget.getBudgetDay())) {
            namedBudget.setBudgetDay(day);
            namedBudget.setUsedCalls(0);
            namedBudget.setReservedInputBytes(0);
            namedBudget.setReservedOutputTokens(0);
        }
        if (namedBudget.getUsedCalls() == 0) namedBudget.setReservedCostUsd(maximumCost == null ? null : BigDecimal.ZERO);
        if (exceeds(config.modelDailyCallLimit(), namedBudget.getUsedCalls(), 1)
                || exceeds(config.modelDailyInputBytes(), namedBudget.getReservedInputBytes(), bytes)
                || exceeds(config.modelDailyOutputTokens(), namedBudget.getReservedOutputTokens(), config.maxCompletionTokens()))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "MODEL_NAME_DAILY_BUDGET");
        if (exceeds(config.modelDailyCostUsd(), namedBudget.getReservedCostUsd(), maximumCost))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "MODEL_NAME_COST_DAILY_BUDGET");
        var call = new ModelCall();
        call.setRepairId(repairId);
        call.setRequestKey(input.requestKey());
        call.setAttemptNumber(history.size() + 1);
        call.setConfigurationSha256(config.configurationSha256());
        call.setModelName(config.model());
        call.setRequestJson(request);
        call.setRequestSha256(Hashes.sha256(request));
        call.setRequestBytes(bytes);
        call.setReservedOutputTokens(config.maxCompletionTokens());
        var snapshot = mapper.createObjectNode().put("schemaVersion", 4).put("budgetDay", day.toString())
                .put("sourceId", repair.sourceId()).put("unit", "accepted_call_request_bytes_reserved_output_tokens");
        snapshot.put("modelName", config.model());
        snapshot.putObject("reservation").put("calls", 1).put("inputBytes", bytes)
                .put("outputTokens", config.maxCompletionTokens());
        snapshot.putObject("globalLimits").put("calls", config.dailyCallLimit())
                .put("inputBytes", config.dailyInputBytes()).put("outputTokens", config.dailyOutputTokens())
                .put("costUsd", decimal(config.dailyCostUsd()));
        snapshot.putObject("sourceLimits").put("calls", config.sourceDailyCallLimit())
                .put("inputBytes", config.sourceDailyInputBytes()).put("outputTokens", config.sourceDailyOutputTokens())
                .put("costUsd", decimal(config.sourceDailyCostUsd()));
        snapshot.putObject("modelLimits").put("calls", config.modelDailyCallLimit())
                .put("inputBytes", config.modelDailyInputBytes()).put("outputTokens", config.modelDailyOutputTokens())
                .put("costUsd", decimal(config.modelDailyCostUsd()));
        var pricing = snapshot.putObject("pricing").put("currency", "USD");
        if (config.inputPriceUsdPerMillion() == null) {
            pricing.putNull("inputPerMillion").putNull("outputPerMillion");
        } else {
            pricing.put("inputPerMillion", config.inputPriceUsdPerMillion().toPlainString())
                    .put("outputPerMillion", config.outputPriceUsdPerMillion().toPlainString());
        }
        var costReservation = snapshot.putObject("costReservation");
        costReservation.put("currency", "USD").put("inputTokenBound", config.inputTokenBound());
        if (maximumCost == null) {
            costReservation.put("status", "unavailable");
        } else {
            costReservation.put("status", "maximum_reserved")
                    .put("inputTokensUpperBound", bytes)
                    .put("outputTokens", config.maxCompletionTokens())
                    .put("maximumUsd", maximumCost.toPlainString());
        }
        call.setBudgetJson(snapshot.toString());
        call.setAutoSubmit(input.autoSubmit());
        if (input.autoSubmit()) call.setSubmissionStatus("AWAITING_MODEL");
        call.setCreatedAt(clock.instant());
        calls.saveAndFlush(call);
        budget.setUsedCalls(budget.getUsedCalls() + 1);
        budget.setReservedInputBytes(budget.getReservedInputBytes() + bytes);
        budget.setReservedOutputTokens(budget.getReservedOutputTokens() + config.maxCompletionTokens());
        budget.setReservedCostUsd(addKnownCost(budget.getReservedCostUsd(), maximumCost, budget.getUsedCalls() - 1));
        budget.setActiveCallId(call.getId());
        sourceBudget.setUsedCalls(sourceBudget.getUsedCalls() + 1);
        sourceBudget.setReservedInputBytes(sourceBudget.getReservedInputBytes() + bytes);
        sourceBudget.setReservedOutputTokens(sourceBudget.getReservedOutputTokens() + config.maxCompletionTokens());
        sourceBudget.setReservedCostUsd(addKnownCost(sourceBudget.getReservedCostUsd(), maximumCost,
                sourceBudget.getUsedCalls() - 1));
        sourceBudgets.saveAndFlush(sourceBudget);
        namedBudget.setUsedCalls(namedBudget.getUsedCalls() + 1);
        namedBudget.setReservedInputBytes(namedBudget.getReservedInputBytes() + bytes);
        namedBudget.setReservedOutputTokens(namedBudget.getReservedOutputTokens() + config.maxCompletionTokens());
        namedBudget.setReservedCostUsd(addKnownCost(namedBudget.getReservedCostUsd(), maximumCost,
                namedBudget.getUsedCalls() - 1));
        namedBudgets.saveAndFlush(namedBudget);
        return call;
    }

    private static boolean exceeds(long limit, long used, long additional) {
        return limit > 0 && additional > limit - used;
    }

    @Transactional
    public boolean start(String id) {
        var budget = budgets.lock().orElseThrow();
        var call = require(id);
        if (!id.equals(budget.getActiveCallId()) || !"QUEUED".equals(call.getStatus())) return false;
        var diagnostic = decode(call.getResponseDiagnosticsJson());
        if (diagnostic != null && diagnostic.path("retryScheduled").asBoolean()
                && clock.instant().isBefore(Instant.parse(diagnostic.path("nextRetryAt").asText()))) return false;
        try {
            if (ModelClient.discoveryRequest(call.getRequestJson())) requireDiscoveryInputs(repairs.get(call.getRepairId()));
            else requireInputs(repairs.get(call.getRepairId()));
            client.requireEnabled();
            if (!call.getConfigurationSha256().equals(client.connection().configurationSha256()))
                throw conflict("MODEL_CONFIGURATION_CHANGED");
        } catch (ResponseStatusException e) {
            terminal(call, "FAILED", e.getReason());
            budget.setActiveCallId(null);
            return false;
        }
        call.setStatus("RUNNING");
        call.setErrorCode(null);
        if (diagnostic != null && diagnostic.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) diagnostic).put("retryScheduled", false)
                    .put("action", "requesting").remove("nextRetryAt");
            call.setResponseDiagnosticsJson(diagnostic.toString());
        }
        return true;
    }

    @Transactional
    public void finish(String id, ModelReply reply, String code) {
        var budget = budgets.lock().orElseThrow();
        var call = require(id);
        if (!id.equals(budget.getActiveCallId()) || !ACTIVE.contains(call.getStatus())) return;
        var previousDiagnostic = decode(call.getResponseDiagnosticsJson());
        if (reply != null && "QUEUED".equals(call.getStatus()) && previousDiagnostic != null
                && previousDiagnostic.path("retryScheduled").asBoolean()) return;
        var diagnostic = responseAttempt(call, reply, code);
        if (!ModelClient.discoveryRequest(call.getRequestJson()) && "RUNNING".equals(call.getStatus()) && code != null && reply != null
                && reply.proposal() == null && reply.usage() == null
                && diagnostic.path("retryable").asBoolean() && !diagnostic.path("outcomeUnknown").asBoolean()) {
            int attempts = diagnostic.path("transportAttempts").size();
            if (attempts < 3) {
                long delay = Math.max(attempts == 1 ? 5 : 15, diagnostic.path("retryAfterSeconds").asLong());
                diagnostic.put("retryScheduled", true).put("action", "wait")
                        .put("nextRetryAt", clock.instant().plusSeconds(delay).toString());
                call.setResponseDiagnosticsJson(diagnostic.toString());
                call.setStatus("QUEUED");
                call.setErrorCode(code);
                return;
            }
            diagnostic.put("pauseReason", "MODEL_TRANSPORT_RETRIES_EXHAUSTED");
        }
        diagnostic.put("retryScheduled", false);
        if (code != null) diagnostic.put("action", "pause");
        call.setResponseDiagnosticsJson(diagnostic.toString());
        // Usage describes the received request, even when the proposal or authority is rejected.
        // The terminal-state guard and global lock make this a single durable completion.
        if (reply != null && reply.usage() != null) {
            call.setUsageJson(reply.usage().toString());
            var reservation = decode(call.getBudgetJson());
            var pricing = reservation == null ? null : reservation.get("pricing");
            if (pricing != null && pricing.path("inputPerMillion").isTextual()
                    && pricing.path("outputPerMillion").isTextual()) {
                BigDecimal input = new BigDecimal(pricing.path("inputPerMillion").asText())
                        .multiply(BigDecimal.valueOf(reply.usage().path("prompt_tokens").asLong())).movePointLeft(6);
                BigDecimal output = new BigDecimal(pricing.path("outputPerMillion").asText())
                        .multiply(BigDecimal.valueOf(reply.usage().path("completion_tokens").asLong())).movePointLeft(6);
                call.setCostJson(mapper.createObjectNode().put("schemaVersion", 1).put("currency", "USD")
                        .put("basis", "provider_reported_usage_configured_flat_rates")
                        .put("inputPriceUsdPerMillion", pricing.path("inputPerMillion").asText())
                        .put("outputPriceUsdPerMillion", pricing.path("outputPerMillion").asText())
                        .put("inputUsd", input.stripTrailingZeros().toPlainString())
                        .put("outputUsd", output.stripTrailingZeros().toPlainString())
                        .put("totalUsd", input.add(output).stripTrailingZeros().toPlainString()).toString());
            }
            if (reservation != null
                    && "maximum_reserved".equals(reservation.path("costReservation").path("status").asText())
                    && (reply.usage().path("prompt_tokens").asLong() > call.getRequestBytes()
                    || reply.usage().path("completion_tokens").asLong() > call.getReservedOutputTokens())) {
                LocalDate day = LocalDate.parse(reservation.path("budgetDay").asText());
                LocalDate receivedDay = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
                if (receivedDay.isAfter(day)) day = receivedDay;
                if (budget.getCostBreachDay() == null || day.isAfter(budget.getCostBreachDay()))
                    budget.setCostBreachDay(day);
                // A local reservation cannot undo provider charges. Stop further same-day sends.
                code = "MODEL_COST_RESERVATION_EXCEEDED";
            }
        }
        if (call.getStatus().equals("CANCELLING")) {
            terminal(call, "CANCELLED", "MODEL_CANCELLED_OUTCOME_UNKNOWN");
        } else if (code != null || reply != null && reply.errorCode() != null) {
            terminal(call, "FAILED", code != null ? code : reply.errorCode());
        } else if (ModelClient.discoveryRequest(call.getRequestJson())) {
            try {
                requireDiscoveryInputs(repairs.get(call.getRepairId()));
                if (reply == null || reply.responseDiagnostics() == null
                        || !reply.responseDiagnostics().path("originCandidates").isArray())
                    throw conflict("MODEL_ORIGIN_DISCOVERY_INVALID");
                terminal(call, "DISCOVERED", null);
            } catch (ResponseStatusException e) { terminal(call, "FAILED", e.getReason()); }
        } else {
            try {
                var repair = repairs.get(call.getRepairId());
                requireInputs(repair);
                if (!repair.baselineArtifactSha256().equals(reply.proposal().expectedBaselineSha256()))
                    throw conflict("MODEL_BASELINE_MISMATCH");
                var targets = new HashSet<String>();
                repair.frozen().path("policy").path("targetNodes").forEach(node -> targets.add(node.asText()));
                if (reply.proposal().validationTargets().size() != targets.size()
                        || !targets.equals(new HashSet<>(reply.proposal().validationTargets())))
                    throw conflict("PATCH_TARGET_MISMATCH");
                String proposal = patches.encode(reply.proposal());
                String baseline = sources.artifact(repair.sourceId(), repair.baselineArtifactSha256()).getBody();
                if (patches.apply(baseline, reply.proposal()).equals(baseline)) throw conflict("PATCH_NO_CHANGE");
                for (var prior : calls.findByRepairIdOrderByAttemptNumberAsc(repair.id())) {
                    if (!prior.getId().equals(id) && proposal.equals(prior.getProposalJson()))
                        throw conflict("REPEATED_MODEL_PROPOSAL");
                }
                call.setProposalJson(proposal);
                terminal(call, "PROPOSED", null);
                if (call.isAutoSubmit()) call.setSubmissionStatus("PENDING");
            } catch (ResponseStatusException e) {
                terminal(call, "FAILED", e.getReason());
            }
        }
        budget.setActiveCallId(null);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode responseAttempt(ModelCall call, ModelReply reply, String code) {
        var previous = decode(call.getResponseDiagnosticsJson());
        var result = reply != null && reply.responseDiagnostics() != null
                ? (com.fasterxml.jackson.databind.node.ObjectNode) reply.responseDiagnostics().deepCopy()
                : mapper.createObjectNode().put("stage", code == null ? "MODEL_RESPONSE_RECEIVED" : code);
        var attempts = mapper.createArrayNode();
        if (previous != null && previous.path("transportAttempts").isArray())
            previous.path("transportAttempts").forEach(item -> attempts.add(item.deepCopy()));
        if (!"QUEUED".equals(call.getStatus())) {
            var attempt = result.deepCopy();
            attempt.put("attempt", attempts.size() + 1).put("finishedAt", clock.instant().toString());
            attempts.add(attempt);
        }
        result.put("schemaVersion", 2).put("maxTransportAttempts", 3).set("transportAttempts", attempts);
        return result;
    }

    @Transactional(readOnly = true)
    public List<ModelCall> dueRetries() {
        return calls.findByStatusIn(Set.of("QUEUED")).stream().filter(call -> {
            var diagnostic = decode(call.getResponseDiagnosticsJson());
            return diagnostic != null && diagnostic.path("retryScheduled").asBoolean()
                    && !clock.instant().isBefore(Instant.parse(diagnostic.path("nextRetryAt").asText()));
        }).toList();
    }

    /** Model provenance, candidate bytes, round budget and ownership commit together. */
    @Transactional
    public RepairCandidate reserveSubmission(String id, String expectedProposalSha256, boolean automatic) {
        budgets.lock().orElseThrow();
        var call = require(id);
        if (!"PROPOSED".equals(call.getStatus())) throw conflict("MODEL_PROPOSAL_NOT_READY");
        String proposalSha256 = Hashes.sha256(call.getProposalJson());
        if (!proposalSha256.equals(expectedProposalSha256)) throw conflict("MODEL_PROPOSAL_CHANGED");
        var existing = candidates.findByModelCallId(id);
        if (existing.isPresent()) return existing.get();
        if (automatic && (!call.isAutoSubmit() || !"PENDING".equals(call.getSubmissionStatus()))) return null;
        if (automatic) {
            client.requireEnabled();
            if (!client.connection().autoSubmitEnabled()) throw conflict("MODEL_AUTO_SUBMIT_DISABLED");
            if (!call.getConfigurationSha256().equals(client.connection().configurationSha256()))
                throw conflict("MODEL_CONFIGURATION_CHANGED");
        }
        requireInputs(repairs.get(call.getRepairId()));
        RepairProposal proposal;
        try { proposal = mapper.readValue(call.getProposalJson(), RepairProposal.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored model proposal is invalid", e); }
        var candidate = repairs.reserveModel(call.getRepairId(), proposal, id);
        if (!candidate.getProposalSha256().equals(proposalSha256))
            throw new IllegalStateException("Model proposal identity changed during submission");
        call.setSubmissionStatus("SUBMITTED");
        call.setSubmissionErrorCode(null);
        call.setSubmissionMethod(automatic ? "AUTO" : "MANUAL");
        return candidate;
    }

    @Transactional
    public void failAutomaticSubmission(String id, String code) {
        budgets.lock().orElseThrow();
        var call = require(id);
        if (!"PENDING".equals(call.getSubmissionStatus())) return;
        call.setSubmissionStatus("FAILED");
        call.setSubmissionErrorCode(code);
    }

    @Transactional
    public ModelCallSummary cancel(String id) {
        var budget = budgets.lock().orElseThrow();
        var call = require(id);
        if ("PENDING".equals(call.getSubmissionStatus())) {
            call.setSubmissionStatus("CANCELLED");
            call.setSubmissionErrorCode("MODEL_SUBMISSION_CANCELLED");
        }
        if (!ACTIVE.contains(call.getStatus())) return summary(call);
        if (call.getStatus().equals("QUEUED")) {
            terminal(call, "CANCELLED", "MODEL_CANCELLED_BEFORE_SEND");
            if (id.equals(budget.getActiveCallId())) budget.setActiveCallId(null);
        } else {
            call.setStatus("CANCELLING");
        }
        return summary(call);
    }

    @Transactional
    public void recover() {
        var budget = budgets.lock().orElseThrow();
        String waiting = null;
        for (var call : calls.findByStatusIn(ACTIVE)) {
            var diagnostic = decode(call.getResponseDiagnosticsJson());
            if (call.getId().equals(budget.getActiveCallId()) && "QUEUED".equals(call.getStatus())
                    && diagnostic != null && diagnostic.path("retryScheduled").asBoolean()) {
                waiting = call.getId();
            } else terminal(call, "INTERRUPTED", "MODEL_RESTARTED_OUTCOME_UNKNOWN");
        }
        for (var call : calls.findBySubmissionStatus("PENDING")) {
            call.setSubmissionStatus("INTERRUPTED");
            call.setSubmissionErrorCode("MODEL_SUBMISSION_RESTARTED");
        }
        budget.setActiveCallId(waiting);
    }

    @Transactional(readOnly = true)
    public ModelCallSummary get(String id) { return summary(require(id)); }

    @Transactional(readOnly = true)
    public List<ModelCallSummary> list(String repairId) {
        repairs.get(repairId);
        return calls.findByRepairIdOrderByAttemptNumberAsc(repairId).stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public List<ModelCallSummary> repairCalls(String repairId) {
        return calls.findByRepairIdOrderByAttemptNumberAsc(repairId).stream()
                .filter(call -> !ModelClient.discoveryRequest(call.getRequestJson())).map(this::summary).toList();
    }

    private void requireDiscoveryInputs(RepairSummary repair) {
        sources.requireMaintenanceAllowed(repair.sourceId());
        ReverseAnalysis.requireCurrent(repair);
        if (!"current".equals(repair.freshness())) throw conflict("REPAIR_INPUTS_CHANGED");
        if (!repair.baselineArtifactSha256().equals(consent(repair.sourceId()).artifactSha256()))
            throw conflict("MODEL_SOURCE_NOT_AUTHORIZED");
    }

    private void requireInputs(RepairSummary repair) {
        sources.requireMaintenanceAllowed(repair.sourceId());
        if (!repair.status().equals("AWAITING_PROPOSAL") || repair.usedRounds() >= repair.maxRounds())
            throw conflict("REPAIR_NOT_ACCEPTING_PROPOSALS");
        if (!repair.freshness().equals("current")) throw conflict("REPAIR_INPUTS_CHANGED");
        if (!repair.baselineArtifactSha256().equals(consent(repair.sourceId()).artifactSha256()))
            throw conflict("MODEL_SOURCE_NOT_AUTHORIZED");
    }

    private void requireAccessibleInput(RepairSummary repair) {
        var triage = diagnostics.forRepair(repair, repairs.candidates(repair.id()));
        if (!triage.path("latestCandidateEvidenceUnavailable").asBoolean()
                && triage.path("authenticationEvidence").isObject())
            throw conflict("SOURCE_AUTHENTICATION_REJECTED");
        if (!RepairEvidenceMaterial.originRoute(mapper, repair, repair.frozen().path("reverseAnalysis")).isMissingNode()) return;
        if ("managed_origin_candidate".equals(repair.frozen().path("policy").path("scope").asText()))
            throw conflict("VERIFIED_ORIGIN_ROUTE_REQUIRED");
        if (!triage.path("latestCandidateEvidenceUnavailable").asBoolean()
                && triage.path("accessPage").path("repairBlocked").asBoolean())
            throw conflict("cloudflare_access_denied".equals(triage.path("accessPage").path("classification").asText())
                    ? "MODEL_SOURCE_ACCESS_DENIED" : "MODEL_SOURCE_ACCESS_CHALLENGE");
    }

    private ModelCall require(String id) {
        return calls.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MODEL_CALL_NOT_FOUND"));
    }
    private void terminal(ModelCall call, String status, String code) {
        call.setStatus(status);
        call.setErrorCode(code);
        call.setFinishedAt(clock.instant());
        var diagnostic = decode(call.getResponseDiagnosticsJson());
        if (diagnostic != null && diagnostic.isObject()) {
            var value = (com.fasterxml.jackson.databind.node.ObjectNode) diagnostic;
            value.put("retryScheduled", false).put("action", Set.of("PROPOSED", "DISCOVERED").contains(status) ? "complete" : "pause");
            value.remove("nextRetryAt");
            if (code != null && (!value.has("pauseReason") || !code.equals(value.path("stage").asText())))
                value.put("pauseReason", code);
            call.setResponseDiagnosticsJson(value.toString());
        }
        if ("AWAITING_MODEL".equals(call.getSubmissionStatus()) && !"PROPOSED".equals(status))
            call.setSubmissionStatus("SKIPPED");
    }
    private ModelCallSummary summary(ModelCall call) {
        return new ModelCallSummary(call.getId(), call.getRepairId(), call.getRequestKey(), call.getAttemptNumber(),
                call.getConfigurationSha256(), call.getModelName(), call.getRequestSha256(), call.getRequestBytes(), call.getReservedOutputTokens(),
                call.getStatus(), call.getErrorCode(), call.getCreatedAt(), call.getFinishedAt(),
                decode(call.getUsageJson()), decode(call.getProposalJson()),
                call.getProposalJson() == null ? null : Hashes.sha256(call.getProposalJson()),
                call.isAutoSubmit(), call.getSubmissionStatus(), call.getSubmissionErrorCode(), call.getSubmissionMethod(),
                candidates.findByModelCallId(call.getId()).map(RepairCandidate::getId).orElse(null),
                decode(call.getBudgetJson()), decode(call.getCostJson()),
                ModelClient.enhancedRequest(mapper, call.getRequestJson()), decode(call.getResponseDiagnosticsJson()));
    }
    private void requireFaultCall(RepairSummary repair, StartModelCallRequest input, List<ModelCall> history) {
        if (history.stream().filter(this::consumesFaultCall).count() < 3) return;
        JsonNode grant = RepairEvidenceMaterial.activeGrant(repair);
        if (input.enhancedDiagnostics() && !grant.isMissingNode()
                && history.stream().filter(this::consumesFaultCall).noneMatch(call -> grant.path("evidenceSha256").equals(
                    RepairEvidenceMaterial.requestData(mapper, call.getRequestJson())
                        .path("evidenceContinuation").path("evidenceSha256")))) return;
        JsonNode task = repair.frozen().path("reverseAnalysis").path("taskId");
        if (input.enhancedDiagnostics() && repairs.hasNewReverseEvidence(repair.id())
                && history.stream().filter(this::consumesFaultCall).noneMatch(call -> task.equals(
                    ReverseAnalysis.modelReport(mapper, call.getRequestJson()).path("taskId")))) return;
        throw conflict("MODEL_FAULT_CALL_LIMIT");
    }

    private boolean consumesFaultCall(ModelCall call) {
        if (ModelClient.discoveryRequest(call.getRequestJson())) return false;
        if (!"FAILED".equals(call.getStatus()) || call.getUsageJson() != null || call.getProposalJson() != null
                || !Set.of("MODEL_RATE_LIMITED", "MODEL_SERVICE_UNAVAILABLE", "MODEL_CONNECTION_FAILED")
                    .contains(call.getErrorCode() == null ? "" : call.getErrorCode())) return true;
        var diagnostic = decode(call.getResponseDiagnosticsJson());
        if (diagnostic == null || !"MODEL_TRANSPORT_RETRIES_EXHAUSTED".equals(diagnostic.path("pauseReason").asText())
                || !diagnostic.path("retryable").asBoolean() || diagnostic.path("outcomeUnknown").asBoolean(true)
                || diagnostic.path("providerUsageReported").asBoolean()
                || !diagnostic.path("transportAttempts").isArray() || diagnostic.path("transportAttempts").size() != 3)
            return true;
        for (var attempt : diagnostic.path("transportAttempts")) {
            if (!attempt.path("retryable").asBoolean() || attempt.path("outcomeUnknown").asBoolean(true)
                    || attempt.path("providerUsageReported").asBoolean()
                    || !Set.of("rate_limit", "temporary_service", "connection")
                        .contains(attempt.path("failureCategory").asText())) return true;
        }
        // Keep every request in daily ledgers; a bounded transient failure yielded no repair proposal.
        return false;
    }

    private JsonNode decode(String value) {
        if (value == null) return null;
        try { return mapper.readTree(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored model JSON is invalid", e); }
    }
    private ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }

    private BigDecimal maximumCost(ModelConnection config, int requestBytes) {
        if (config.inputPriceUsdPerMillion() == null
                || !config.inputTokenBound().equals("request_utf8_bytes_v1")) return null;
        return BigDecimal.valueOf(requestBytes).multiply(config.inputPriceUsdPerMillion())
                .add(BigDecimal.valueOf(config.maxCompletionTokens()).multiply(config.outputPriceUsdPerMillion()))
                .movePointLeft(6).stripTrailingZeros();
    }

    private void requireKnownCostHistory(LocalDate storedDay, LocalDate day, int usedCalls,
                                          BigDecimal reserved, BigDecimal limit) {
        if (limit != null && day.equals(storedDay) && usedCalls > 0 && reserved == null) {
            // A pre-V16 or no-pricing reservation cannot be safely compared with a hard amount cap.
            // Refuse the new call instead of treating unknown historical cost as zero.
            throw conflict("MODEL_COST_HISTORY_UNKNOWN");
        }
    }

    private boolean exceeds(BigDecimal limit, BigDecimal reserved, BigDecimal amount) {
        if (limit == null) return false;
        if (reserved == null || amount == null) return true;
        return reserved.add(amount).compareTo(limit) > 0;
    }

    private BigDecimal addKnownCost(BigDecimal current, BigDecimal amount, int previousCalls) {
        if (amount == null) return null;
        if (current == null && previousCalls > 0) return null;
        return (current == null ? BigDecimal.ZERO : current).add(amount).stripTrailingZeros();
    }

    private String decimal(BigDecimal value) { return value == null ? null : value.toPlainString(); }
}
