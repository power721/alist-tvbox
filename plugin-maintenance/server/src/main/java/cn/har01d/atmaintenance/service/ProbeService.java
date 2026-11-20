package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.ProbeSummary;
import cn.har01d.atmaintenance.dto.StartMaintenanceRequest;
import cn.har01d.atmaintenance.entity.ProbeRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
public class ProbeService {
    private final ProbeStore store;
    private final SourceService sources;
    private final StaticInspector inspector;
    private final KnowledgeService knowledge;
    private final FixtureRuntime runtime;
    private final ContainerRuntime container;
    private final ManagedSourceRuntime managed;
    private final ManagedSourceSetup setup;
    private final ProfileService profiles;
    private final ProbeScheduleStore schedules;
    private final ObjectMapper mapper;
    private final SourceCredentialStore credentials;
    private final ConcurrentMap<String, FutureTask<Void>> tasks = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().name("maintenance-probe").factory(),
            new ThreadPoolExecutor.AbortPolicy());

    @PostConstruct
    void recover() {
        for (ProbeRun run : store.unfinishedContainerRuns()) {
            try {
                container.cleanup(run.getId(), decodePolicy(run));
            } catch (IOException | InterruptedException e) {
                store.finish(run.getId(), "FAILED", null, null, "CONTAINER_CLEANUP_UNCONFIRMED");
            }
        }
        for (ProbeRun run : store.unfinishedManagedRuns()) {
            try {
                managed.cancelAcceptance(run.getId(), decodePolicy(run));
            } catch (IOException e) {
                store.finish(run.getId(), "FAILED", null, null, "MANAGED_CLEANUP_UNCONFIRMED");
            }
        }
        store.interruptUnfinished();
    }

    public synchronized ProbeSummary start(String sourceId) {
        return start(sourceId, "static");
    }

    public synchronized ProbeSummary start(String sourceId, String mode) {
        sources.requireMaintenanceAllowed(sourceId);
        if (!Set.of("static", "offline_fixture", "container_fixture", "managed_source").contains(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PROBE_MODE");
        }
        if (mode.equals("managed_source")) {
            requireCredentials(sourceId);
            try {
                setup.ensure(sourceId, sources.get(sourceId).artifactSha256());
            } catch (ResponseStatusException error) {
                if (!preparationFailure(error)) throw error;
                return store.get(store.recordPreparationFailure(sourceId, inspector.sha256(), knowledge.sha256(),
                        null, error.getReason()).getId());
            }
            profiles.prepareLiveDefault(sourceId, managed.forSource(sourceId).manifest().path("runtimeSha256").asText());
        }
        // create() commits the snapshot before the worker can observe it.
        ProbeRun run = store.create(sourceId, inspector.sha256(), knowledge.sha256(), mode);
        return submit(run);
    }

    public synchronized ProbeSummary startMaintenance(String sourceId, StartMaintenanceRequest input) {
        sources.requireMaintenanceAllowed(sourceId);
        requireCredentials(sourceId);
        var run = store.existingMaintenance(sourceId, input);
        if (run == null) {
            try {
                setup.ensure(sourceId, input.expectedArtifactSha256());
            } catch (ResponseStatusException error) {
                if (!preparationFailure(error)) throw error;
                return store.get(store.recordPreparationFailure(sourceId, inspector.sha256(), knowledge.sha256(),
                        input, error.getReason()).getId());
            }
            run = store.createMaintenance(sourceId, inspector.sha256(), knowledge.sha256(), input);
        }
        return run.getStatus().equals("QUEUED") && !tasks.containsKey(run.getId()) ? submit(run) : store.get(run.getId());
    }

    private static boolean preparationFailure(ResponseStatusException error) {
        return error.getStatusCode().value() == 503 && error.getReason() != null
                && error.getReason().startsWith("SOURCE_SETUP_");
    }

    private void requireCredentials(String sourceId) {
        String blocked = credentials.blockedReason(sourceId);
        if (blocked != null) throw new ResponseStatusException(HttpStatus.CONFLICT, blocked);
    }

    synchronized ProbeSummary submit(ProbeRun run) {
        FutureTask<Void> task = new FutureTask<>(() -> {
            execute(run);
            return null;
        }) {
            @Override
            protected void done() {
                tasks.remove(run.getId(), this);
            }
        };
        tasks.put(run.getId(), task);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            tasks.remove(run.getId(), task);
            store.finish(run.getId(), "REJECTED", null, null, "QUEUE_FULL");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "QUEUE_FULL");
        }
        return store.get(run.getId());
    }

    public synchronized ProbeSummary cancel(String id) {
        var requested = store.requestCancel(id);
        FutureTask<Void> task = tasks.get(id);
        if (task != null && Set.of("CANCELLING", "CANCELLED").contains(requested.status())) {
            task.cancel(true);
            executor.remove(task);
        }
        return store.get(id);
    }

    private void execute(ProbeRun run) {
        if (!store.start(run.getId())) {
            return;
        }
        JsonNode inspection = null;
        try {
            var artifact = sources.artifact(run.getSourceId(), run.getArtifactSha256());
            inspection = inspector.inspect(artifact.getBody());
            boolean passed = inspection.path("parseOk").asBoolean()
                    && inspection.path("issues").isEmpty();
            if (run.getExecutionMode().equals("managed_source")) {
                var selectedRuntime = managed.forSource(run.getSourceId());
                String blocked = schedules.executionBlockedReason(run);
                if (blocked == null)
                    blocked = passed ? selectedRuntime.blockedReason(profile(run)) : "STATIC_PRECHECK_FAILED";
                JsonNode evidence = blocked == null ? selectedRuntime.inspect(run.getId(), artifact.getBody(), profile(run),
                        run.getRuntimeSha256(), run.getExecutionPolicySha256(),
                        credentials.execution(run.getSourceId(), run.getCredentialRevision())) : null;
                credentials.observe(run.getSourceId(), run.getCredentialRevision(), run.getArtifactSha256(), evidence);
                finishManaged(run, inspection, passed, evidence, blocked);
                return;
            }
            if (fixture(run)) {
                boolean inContainer = run.getExecutionMode().equals("container_fixture");
                String blocked = passed ? inContainer
                        ? container.blockedReason(run.getArtifactSha256(), profile(run))
                        : runtime.blockedReason(run.getArtifactSha256(), profile(run))
                        : "STATIC_PRECHECK_FAILED";
                JsonNode evidence = blocked != null ? null : inContainer
                        ? container.inspect(run.getId(), run.getArtifactSha256(), profile(run),
                            run.getRuntimeSha256(), run.getExecutionPolicySha256())
                        : runtime.inspect(run.getArtifactSha256(), profile(run), run.getRuntimeSha256());
                ObjectNode report = report(run, inspection, passed ? "pass" : "fail", "STATIC_ANALYSIS",
                        evidence, blocked == null ? "pass" : "blocked", blocked);
                ObjectNode context = knowledge.context("S06", blocked == null ? "" : blocked,
                        profile(run).path("runtime").asText(), 8);
                context.put("artifactSha256", run.getArtifactSha256());
                context.put("profileSha256", run.getProfileSha256());
                context.put("runtimeSha256", run.getRuntimeSha256());
                if (inContainer) context.put("executionPolicySha256", run.getExecutionPolicySha256());
                store.finish(run.getId(), blocked == null ? "COMPLETED" : "BLOCKED", report, context, blocked);
                return;
            }
            ObjectNode report = report(run, inspection, passed ? "pass" : "fail", "STATIC_ANALYSIS");
            String failure = inspection.path("issues").isEmpty()
                    ? "" : inspection.path("issues").get(0).path("code").asText();
            ObjectNode context = knowledge.context("S03", failure, profile(run).path("runtime").asText(), 8);
            context.put("artifactSha256", run.getArtifactSha256());
            context.put("profileSha256", run.getProfileSha256());
            store.finish(run.getId(), "COMPLETED", report, context, null);
        } catch (IOException e) {
            if (e instanceof ManagedExecutionFailure failure && inspection != null
                    && run.getExecutionMode().equals("managed_source")) {
                finishManaged(run, inspection, true, null, managed.errorCode(e.getMessage()), failure.diagnostic());
            } else if (Set.of("INSPECTOR_CLEANUP_FAILED", "CONTAINER_CLEANUP_UNCONFIRMED")
                    .contains(e.getMessage() == null ? "" : e.getMessage())
                    || "MANAGED_CLEANUP_UNCONFIRMED".equals(e.getMessage())) {
                store.finish(run.getId(), "FAILED", null, null, e.getMessage());
            } else if (run.getExecutionMode().equals("managed_source") && inspection != null) {
                finishManaged(run, inspection, true, null, managed.errorCode(e.getMessage()));
            } else if (fixture(run) && inspection != null) {
                String code = run.getExecutionMode().equals("container_fixture")
                        ? container.errorCode(e) : "PYTHON_TIMEOUT".equals(e.getMessage())
                        ? "FIXTURE_TIMEOUT" : "FIXTURE_EXECUTION_ERROR";
                ObjectNode context = knowledge.context("S06", code, profile(run).path("runtime").asText(), 8);
                context.put("artifactSha256", run.getArtifactSha256());
                context.put("profileSha256", run.getProfileSha256());
                context.put("runtimeSha256", run.getRuntimeSha256());
                if (run.getExecutionPolicySha256() != null)
                    context.put("executionPolicySha256", run.getExecutionPolicySha256());
                store.finish(run.getId(), "BLOCKED",
                        report(run, inspection, "pass", "STATIC_ANALYSIS", null, "blocked", code), context, code);
            } else {
                finishBlocked(run, "INSPECTOR_IO_ERROR");
            }
        } catch (InterruptedException e) {
            // StaticInspector has finished process cleanup before unwinding.
            store.finish(run.getId(), "INTERRUPTED", null, null, "EXECUTION_INTERRUPTED");
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            store.finish(run.getId(), "FAILED", null, null, "INTERNAL_ERROR");
            throw e;
        }
    }

    private void finishManaged(ProbeRun run, JsonNode inspection, boolean passed, JsonNode evidence, String blocked) {
        finishManaged(run, inspection, passed, evidence, blocked, null);
    }

    private void finishManaged(ProbeRun run, JsonNode inspection, boolean passed, JsonNode evidence, String blocked,
                               JsonNode diagnostic) {
        ObjectNode result = report(run, inspection, passed ? "pass" : "fail", "STATIC_ANALYSIS");
        result.put("evidenceClass", evidence == null ? "static" : "managed_source_host_observed")
                .put("verdict", evidence == null ? "managed_source_blocked" : "managed_source_observed")
                .put("runtimeSha256", run.getRuntimeSha256())
                .put("executionPolicySha256", run.getExecutionPolicySha256())
                .put("sourceHealthEligible", false).put("publishEligible", false)
                .put("boundary", "仅观测受管源释放前的容器控制与归属清理。源码进入后输出不可信，"
                        + "不认证初始化/实际装载、AT/T4业务、站点、媒体或客户端；没有模型调用或发布。");
        if (evidence != null) result.set("managedSourceEvidence", evidence);
        boolean businessSample = evidence != null
                && evidence.path("executionKind").asText().equals("t4_business_sample");
        if (businessSample) {
            result.put("verdict", "t4_business_sample_observed").put("businessVerdictTrusted", false)
                    .put("boundary", "固定只读出站策略下调用真实 T4 worker；源返回值仅作运行观察。"
                            + "HTTP 响应与源码计数分开展示，尚未验证取链、封面媒体、客户端、修复晋级或发布。");
            attachBusinessNodes(result, evidence);
        }
        if (diagnostic != null) result.set("managedExecutionDiagnostic", diagnostic);
        for (JsonNode value : result.path("nodes")) {
            if (!value.path("id").asText().equals("S06")) continue;
            ObjectNode node = (ObjectNode) value;
            node.put("status", "blocked").put("evidenceClass", evidence == null ? "none" : "managed_source_host_observed")
                    .put("reason", blocked == null ? "MANAGED_RUNTIME_UNATTESTED" : blocked);
            if (evidence != null) node.putObject("evidence").put("reference", "result.managedSourceEvidence")
                    .put("scope", "pre_source_controls_and_cleanup_only")
                    .put("runtimeVerdictTrusted", false);
            else if (diagnostic != null) node.putObject("evidence")
                    .put("reference", "result.managedExecutionDiagnostic").put("scope", "failure_diagnostic_only");
        }
        if (profile(run).path("requiredNodes").valueStream().anyMatch(value -> value.asText().equals("S06"))) {
            ObjectNode coverage = (ObjectNode) result.path("requiredCoverage");
            coverage.put("notTested", coverage.path("notTested").asInt() - 1);
            coverage.put("blocked", coverage.path("blocked").asInt() + 1);
            coverage.put("conclusion", passed ? "required_blocked" : "required_failed");
        }
        if (evidence != null && evidence.has("loaderValidation")) attachLoaderNodes(result, evidence);
        String contextNode = "S06";
        String contextFailure = blocked == null ? "MANAGED_RUNTIME_UNATTESTED" : blocked;
        if (blocked == null && evidence != null && evidence.has("loaderValidation")) {
            // Use the first observed required failure; initialization may already have passed.
            for (JsonNode node : evidence.path("loaderValidation").path("nodes")) {
                if (profile(run).path("requiredNodes").valueStream()
                        .anyMatch(id -> id.asText().equals(node.path("id").asText()))
                        && Set.of("fail", "blocked").contains(node.path("status").asText())) {
                    contextNode = node.path("id").asText();
                    contextFailure = node.path("reason").asText();
                    break;
                }
            }
        }
        ObjectNode context = knowledge.context(contextNode, contextFailure, profile(run).path("runtime").asText(), 8);
        context.put("node", contextNode).put("failure", contextFailure);
        context.put("artifactSha256", run.getArtifactSha256()).put("profileSha256", run.getProfileSha256())
                .put("runtimeSha256", run.getRuntimeSha256())
                .put("executionPolicySha256", run.getExecutionPolicySha256());
        String status = "MANAGED_CLEANUP_UNCONFIRMED".equals(blocked) ? "FAILED"
                : evidence == null ? "BLOCKED" : "COMPLETED";
        store.finish(run.getId(), status, result, context, blocked);
    }

    private void attachLoaderNodes(ObjectNode result, JsonNode evidence) {
        JsonNode loader = evidence.path("loaderValidation");
        for (JsonNode value : result.path("nodes")) {
            for (JsonNode observed : loader.path("nodes")) {
                if (!value.path("id").equals(observed.path("id"))
                        || Set.of("S01", "S02", "S03").contains(value.path("id").asText())) continue;
                ObjectNode node = (ObjectNode) value;
                node.set("status", observed.path("status").deepCopy());
                node.set("reason", observed.path("reason").deepCopy());
                node.put("evidenceClass", "host_loader_contract_observed");
                node.set("evidence", observed.deepCopy());
                node.put("evidenceReference", "managedSourceEvidence.loaderValidation.nodes");
            }
        }
        int pass = 0, fail = 0, blocked = 0, untested = 0;
        for (JsonNode node : result.path("nodes")) {
            if (!node.path("requirement").asText().equals("required")) continue;
            switch (node.path("status").asText()) {
                case "pass" -> pass++;
                case "fail" -> fail++;
                case "blocked" -> blocked++;
                default -> untested++;
            }
        }
        ((ObjectNode) result.path("requiredCoverage")).put("pass", pass).put("fail", fail)
                .put("blocked", blocked).put("notTested", untested)
                .put("conclusion", fail > 0 ? "required_failed" : blocked > 0 ? "required_blocked"
                        : untested > 0 ? "required_incomplete" : "loader_required_contracts_passed");
        result.put("boundary", "节点结果来自按本次冻结画像执行、经运行器身份校验的宿主装载器。"
                + "未支持节点仍为未测，返回数据不作源自报成功；不证明生产 AT 认证、客户端解码或长播，未发布。");
    }

    private void attachBusinessNodes(ObjectNode result, JsonNode evidence) {
        var fields = java.util.Map.of("C01", java.util.List.of("homeClasses"),
                "C02", java.util.List.of("homeItems", "homeCovers"),
                "C03", java.util.List.of("categoryItems", "categoryCovers", "page2Items"),
                "C07", java.util.List.of("detailItems", "detailCovers"),
                "C10", java.util.List.of("playGroups", "playEpisodes"),
                "R01", java.util.List.of("playerStatus", "playerUrlKind", "playerParse"),
                "M01", java.util.List.of("mediaStatus"));
        for (JsonNode value : result.path("nodes")) {
            String id = value.path("id").asText();
            if (!fields.containsKey(id)) continue;
            String playerStatus = evidence.path("untrustedRuntimeSummary").path("playerStatus").asText("not_sampled");
            if (Set.of("R01", "M01").contains(id) && playerStatus.equals("not_sampled")) continue;
            ObjectNode node = (ObjectNode) value;
            node.put("status", "blocked").put("evidenceClass", "untrusted_source_output")
                    .put("reason", "T4_SAMPLE_OUTPUT_UNVERIFIED");
            if (id.equals("R01") && playerStatus.equals("policy_denied"))
                node.put("reason", evidence.path("deniedCodes").valueStream()
                        .anyMatch(code -> code.asText().equals("EGRESS_NONPUBLIC_TARGET_DENIED"))
                        ? "T4_PLAYER_PRIVATE_DEPENDENCY_DENIED" : "T4_PLAYER_POLICY_DENIED");
            if (id.equals("M01") && evidence.path("untrustedRuntimeSummary").path("mediaStatus").asText()
                    .equals("not_requested")) node.put("reason", "T4_MEDIA_PLAYER_PREREQUISITE");
            if (id.equals("M01") && evidence.path("untrustedRuntimeSummary").path("mediaStatus").asText()
                    .equals("policy_denied")) node.put("reason", "T4_MEDIA_POLICY_DENIED");
            ObjectNode observed = node.putObject("evidence").put("reference", "result.managedSourceEvidence")
                    .put("sourceStatus", evidence.path("untrustedRuntimeSummary").path("status").asText())
                    .put("businessVerdictTrusted", false);
            ObjectNode counts = observed.putObject("counts");
            for (String field : fields.get(id))
                counts.set(field, evidence.path("untrustedRuntimeSummary").path(field).deepCopy());
            if (node.path("requirement").asText().equals("required")) {
                ObjectNode coverage = (ObjectNode) result.path("requiredCoverage");
                coverage.put("notTested", coverage.path("notTested").intValue() - 1)
                        .put("blocked", coverage.path("blocked").intValue() + 1)
                        .put("conclusion", "required_blocked");
            }
        }
    }

    private void finishBlocked(ProbeRun run, String code) {
        ObjectNode context = knowledge.context("S03", code, profile(run).path("runtime").asText(), 8);
        context.put("artifactSha256", run.getArtifactSha256());
        context.put("profileSha256", run.getProfileSha256());
        store.finish(run.getId(), "BLOCKED", report(run, null, "blocked", code), context, code);
    }

    private ObjectNode report(ProbeRun run, JsonNode inspection, String staticState, String reason) {
        return report(run, inspection, staticState, reason, null, "blocked", reason);
    }

    private ObjectNode report(ProbeRun run, JsonNode inspection, String staticState, String reason,
                              JsonNode runtimeEvidence, String runtimeState, String runtimeReason) {
        ObjectNode report = mapper.createObjectNode();
        report.put("artifactSha256", run.getArtifactSha256());
        report.put("profileSha256", run.getProfileSha256());
        report.put("nodeCatalogSha256", knowledge.nodeCatalogSha256());
        report.put("evidenceClass", "static");
        report.put("verdict", staticState.equals("pass") ? "static_checked" : "static_" + staticState);
        report.put("boundary", "未导入或执行爬虫；未验证依赖、网站、媒体与客户端；运行器为 AST 静态分析器");
        boolean fixture = fixture(run);
        if (fixture) {
            report.put("evidenceClass", runtimeEvidence == null ? "static" : run.getExecutionMode());
            report.put("verdict", run.getExecutionMode() + (runtimeEvidence == null ? "_blocked" : "_checked"));
            report.put("runtimeSha256", run.getRuntimeSha256());
            report.put("boundary", "只允许内置可信示例；装载身份由独立启动器观察，"
                    + "不是 AT 原生回执。未验证隔离容器、T4 HTTP、真实站点、媒体或客户端。");
            if (run.getExecutionMode().equals("container_fixture")) {
                report.put("executionPolicySha256", run.getExecutionPolicySha256());
                report.put("boundary", runtimeEvidence == null
                        ? "容器可信示例未执行成功；冻结配置仅是预期声明，不证明 Linux 隔离。"
                        : "仅固定可信示例的容器回执；包含 Engine 配置、入口内控制值和删除确认。"
                          + "不证明未知源隔离安全、AT 原生装载、T4 HTTP、真实站点、媒体或客户端可用。");
            }
            if (runtimeEvidence != null) report.set("runtimeEvidence", runtimeEvidence);
        }
        report.put("executionMode", run.getExecutionMode());
        JsonNode profile = profile(run);
        Set<String> required = new HashSet<>();
        Set<String> optional = new HashSet<>();
        profile.path("requiredNodes").forEach(id -> required.add(id.asText()));
        profile.path("optionalNodes").forEach(id -> optional.add(id.asText()));
        var nodes = report.putArray("nodes");
        for (JsonNode definition : knowledge.nodeCatalog().path("nodes")) {
            String id = definition.path("id").asText();
            ObjectNode node = nodes.addObject();
            node.put("id", id);
            node.put("title", definition.path("title").asText());
            node.put("requirement", required.contains(id) ? "required" : optional.contains(id) ? "optional" : "undeclared");
            node.put("status", switch (id) {
                case "S01", "S02" -> "pass";
                case "S03" -> staticState;
                default -> "not_tested";
            });
            node.put("evidenceClass", id.matches("S0[123]") ? "static" : "none");
            node.put("reason", switch (id) {
                case "S01" -> "LOCAL_SOURCE_BINDING";
                case "S02" -> "IMMUTABLE_UTF8_ARTIFACT";
                case "S03" -> reason;
                default -> "RUNTIME_NOT_EXECUTED";
            });
            if (profile.path("sourcePurpose").asText().equals("metadata-navigation") && ProfileService.playbackNode(id)) {
                node.put("requirement", "not_applicable");
                node.put("reason", "SOURCE_PURPOSE_NOT_APPLICABLE");
            }
            if (id.equals("S01")) {
                node.putObject("evidence").put("sourceId", run.getSourceId())
                        .put("scope", "local_registry")
                        .put("boundary", "仅独立台账身份；尚未核对 AT Plugin ID 与 runtime siteKey");
                if (run.getBindingJson() != null) {
                    var evidence = (ObjectNode) node.path("evidence");
                    evidence.put("bindingSha256", run.getBindingSha256());
                    evidence.set("atBinding", decodeBinding(run));
                    evidence.put("boundary", "含管理 API 观察的绑定；siteKey 为本地基线推导，不是实际加载身份。");
                }
            } else if (id.equals("S02")) {
                node.putObject("evidence").put("sha256", run.getArtifactSha256());
            } else if (id.equals("S03") && inspection != null) {
                node.set("evidence", inspection);
            }
            if (fixture && !node.path("requirement").asText().equals("not_applicable")
                    && Set.of("S06", "C01", "C07", "C10", "R01").contains(id)) {
                node.put("status", runtimeState);
                node.put("evidenceClass", runtimeEvidence == null ? "none" : run.getExecutionMode());
                node.put("reason", runtimeEvidence == null ? runtimeReason : "TRUSTED_FIXTURE_ASSERTIONS");
                if (runtimeEvidence != null) {
                    node.putObject("evidence").put("reference", "result.runtimeEvidence")
                            .put("scope", "bundled_fixture_only");
                }
            }
        }
        ObjectNode coverage = report.putObject("requiredCoverage");
        int passed = 0, failed = 0, blocked = 0, untested = 0;
        for (JsonNode node : nodes) {
            if (node.path("requirement").asText().equals("required")) {
                switch (node.path("status").asText()) {
                    case "pass" -> passed++;
                    case "fail" -> failed++;
                    case "blocked" -> blocked++;
                    default -> untested++;
                }
            }
        }
        coverage.put("total", required.size());
        coverage.put("pass", passed);
        coverage.put("fail", failed);
        coverage.put("blocked", blocked);
        coverage.put("notTested", untested);
        coverage.put("conclusion", failed > 0 ? "required_failed" : blocked > 0 ? "required_blocked"
                : untested > 0 ? "required_incomplete"
                : fixture ? "fixture_required_checks_passed" : "static_required_checks_passed");
        coverage.put("boundary", "按该次声明统计；静态或离线夹具必需项通过不代表真实源可用或播放成功");
        return report;
    }

    private JsonNode profile(ProbeRun run) {
        try {
            return mapper.readTree(run.getProfileJson());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored profile JSON is invalid", e);
        }
    }

    private boolean fixture(ProbeRun run) {
        return Set.of("offline_fixture", "container_fixture").contains(run.getExecutionMode());
    }

    private JsonNode decodePolicy(ProbeRun run) {
        try {
            return run.getExecutionPolicyJson() == null ? null : mapper.readTree(run.getExecutionPolicyJson());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored execution policy JSON is invalid", e);
        }
    }

    private JsonNode decodeBinding(ProbeRun run) {
        try {
            return mapper.readTree(run.getBindingJson());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored AT binding JSON is invalid", e);
        }
    }

    @PreDestroy
    synchronized void close() throws InterruptedException {
        var queued = executor.shutdownNow();
        for (var entry : tasks.entrySet()) {
            if (queued.contains(entry.getValue())) {
                store.finish(entry.getKey(), "INTERRUPTED", null, null, "SERVICE_STOPPED");
                entry.getValue().cancel(false);
            }
        }
        executor.awaitTermination(15, TimeUnit.SECONDS);
    }
}
