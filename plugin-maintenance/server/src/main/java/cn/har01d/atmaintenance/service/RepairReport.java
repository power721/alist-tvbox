package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RepairReport {
    private final ObjectMapper mapper;

    public static JsonNode runtimeInputs(JsonNode frozen) {
        JsonNode context = frozen.path("runtimeValidationContext");
        return context.isObject() ? context : frozen.path("baseline");
    }

    static boolean dnsPreparationFailure(cn.har01d.atmaintenance.dto.ProbeSummary run) {
        return dnsPreparationFailure(run.status(), run.executionMode(), run.errorCode(), run.result());
    }

    static boolean dnsPreparationFailure(JsonNode baseline) {
        return dnsPreparationFailure(baseline.path("status").asText(), baseline.path("executionMode").asText(),
                baseline.path("errorCode").asText(), baseline.path("result"));
    }

    private static boolean dnsPreparationFailure(String status, String mode, String code, JsonNode result) {
        JsonNode preparation = result == null ? null : result.path("sourcePreparation");
        return "managed_source".equals(mode) && "BLOCKED".equals(status)
                && "SOURCE_SETUP_DNS_NO_ADDRESS".equals(code) && preparation != null
                && "FAILED".equals(preparation.path("status").asText())
                && code.equals(preparation.path("code").asText())
                && preparation.path("sourceExecuted").isBoolean() && !preparation.path("sourceExecuted").asBoolean()
                && preparation.path("sourceHttpObserved").isBoolean() && !preparation.path("sourceHttpObserved").asBoolean()
                && !result.hasNonNull("managedSourceEvidence");
    }

    public ObjectNode build(JsonNode frozen, String candidateSha, JsonNode inspection) {
        JsonNode baseline = frozen.path("baseline");
        JsonNode context = runtimeInputs(frozen);
        var original = context.has("originalInspection") ? context.path("originalInspection")
                : frozen.has("preparationInspection") ? frozen.path("preparationInspection") : staticEvidence(baseline);
        boolean staticPassed = inspection.path("parseOk").asBoolean() && inspection.path("issues").isEmpty();
        var report = mapper.createObjectNode();
        report.put("schemaVersion", 1);
        report.put("evidenceClass", "static_candidate");
        report.put("artifactSha256", candidateSha);
        report.put("baselineArtifactSha256", baseline.path("artifactSha256").asText());
        report.put("profileSha256", baseline.path("profileSha256").asText());
        report.put("inspectorSha256", context.path("inspectorSha256").asText());
        if (frozen.has("runtimeValidationContext")) {
            ObjectNode binding = context.deepCopy();
            binding.remove("previousContexts");
            report.set("runtimeValidationContext", binding);
        }
        report.set("inspection", inspection);
        var violations = report.putArray("contractViolations");
        if (!original.path("pythonVersion").equals(inspection.path("pythonVersion")))
            violations.add("INSPECTOR_PYTHON_CHANGED");
        if (staticPassed) {
            if (!original.path("classContractSha256").equals(inspection.path("classContractSha256")))
                violations.add("SPIDER_CLASS_CONTRACT_CHANGED");
            if (!original.path("importContractSha256").equals(inspection.path("importContractSha256"))
                    && !additiveImports(frozen, original, inspection))
                violations.add("IMPORT_CONTRACT_CHANGED");
            original.path("methodContracts").properties().forEach(entry -> {
                if (!entry.getValue().equals(inspection.path("methodContracts").path(entry.getKey()))
                        && !searchPageUpgrade(frozen, entry.getKey(), entry.getValue(), inspection))
                    violations.add("METHOD_CONTRACT_CHANGED:" + entry.getKey());
            });
            if (inspection.path("methodsTruncated").asBoolean() || inspection.path("importsTruncated").asBoolean())
                violations.add("CANDIDATE_CONTRACT_INCOMPLETE");
        }
        var nodes = report.putArray("nodes");
        var regressions = report.putArray("regressions");
        var pending = report.putArray("pendingRegressionNodes");
        int total = 0, passed = 0, failed = 0, untested = 0;
        for (JsonNode before : baseline.path("result").path("nodes")) {
            String id = before.path("id").asText();
            String state = switch (id) {
                case "S01", "S02" -> "pass";
                case "S03" -> staticPassed ? "pass" : "fail";
                default -> "not_tested";
            };
            var node = nodes.addObject();
            node.put("id", id).put("title", before.path("title").asText())
                    .put("requirement", before.path("requirement").asText()).put("status", state);
            node.put("reason", state.equals("not_tested") ? "CANDIDATE_RUNTIME_NOT_EXECUTED" : "CANDIDATE_STATIC_CHECK");
            if (before.path("status").asText().equals("pass")) {
                if (state.equals("fail")) regressions.add(id);
                if (state.equals("not_tested")) pending.add(id);
            }
            if (before.path("requirement").asText().equals("required")) {
                total++;
                if (state.equals("pass")) passed++;
                else if (state.equals("fail")) failed++;
                else untested++;
            }
        }
        boolean accepted = staticPassed && violations.isEmpty() && regressions.isEmpty();
        report.put("staticValidationPassed", accepted);
        report.put("targetRecovered", staticPassed
                && frozen.path("policy").path("scope").asText().equals("static_candidate_validator"));
        report.put("verdict", accepted ? "static_validated" : "candidate_rejected");
        report.put("publishEligible", false);
        report.put("repairSuccessful", false);
        report.put("nextRequired", "isolated_runtime_and_AT_validation");
        report.putObject("requiredCoverage").put("total", total).put("pass", passed).put("fail", failed)
                .put("notTested", untested).put("conclusion", failed > 0 ? "required_failed"
                        : untested > 0 ? "required_incomplete" : "static_required_checks_passed");
        report.put("boundary", "只用可信 AST 分析器读取候选；未导入或执行候选。"
                + "签名、类头与导入 AST 对照不证明方法语义不变。未复用基线运行时绿项；"
                + "未测能力仍须重测。静态通过不晋级为 VERIFIED，不切换有效源码；本静态验证器不调用模型或发布。");
        return report;
    }

    private boolean additiveImports(JsonNode frozen, JsonNode original, JsonNode inspection) {
        if (!java.util.Set.of("managed_loader_candidate", "managed_origin_candidate")
                    .contains(frozen.path("policy").path("scope").asText())
                || original.path("importsTruncated").asBoolean() || inspection.path("importsTruncated").asBoolean()
                || !original.path("importEntries").isArray() || !inspection.path("importEntries").isArray())
            return false;
        var before = original.path("importEntries").valueStream().toList();
        var after = inspection.path("importEntries").valueStream().toList();
        var fields = java.util.Set.of("scopeSha256", "bindingSha256", "targetSha256", "importSha256");
        if (before.size() > 256 || after.size() > 256 || !after.containsAll(before)
                || java.util.stream.Stream.concat(before.stream(), after.stream()).anyMatch(entry ->
                    !entry.isObject() || entry.size() != fields.size() || fields.stream().anyMatch(key ->
                        !entry.path(key).isTextual() || !entry.path(key).asText().matches("[a-f0-9]{64}"))))
            return false;
        return after.stream().filter(entry -> !before.contains(entry)).allMatch(added ->
                !added.path("bindingSha256").asText().equals(Hashes.sha256("*"))
                && after.stream().noneMatch(other -> other.path("scopeSha256").equals(added.path("scopeSha256"))
                    && other.path("bindingSha256").equals(added.path("bindingSha256"))
                    && !other.path("targetSha256").equals(added.path("targetSha256"))));
    }

    private boolean searchPageUpgrade(JsonNode frozen, String method, JsonNode original, JsonNode inspection) {
        JsonNode compatibility = inspection.path("searchPageCompatibility");
        return method.equals("searchContent")
                && frozen.path("policy").path("scope").asText().equals("managed_loader_candidate")
                && frozen.path("baseline").path("profile").path("runtime").asText().equals("t4")
                && frozen.path("baseline").path("profile").path("requiredNodes").valueStream()
                    .anyMatch(node -> node.asText().equals("R04"))
                && sameSha(compatibility.path("priorMethodContractSha256"), original)
                && compatibility.path("preservesTwoArgumentCall").isBoolean()
                && compatibility.path("preservesTwoArgumentCall").booleanValue();
    }

    public void applySelectorBinding(ObjectNode report, JsonNode evidence) {
        if (evidence == null || evidence.isNull()) return;
        var binding = evidence.path("sourceSelectorBinding");
        var inspection = report.path("inspection");
        long matches = inspection.path("selectorCalls").valueStream().filter(call ->
                java.util.List.of("method", "call", "selectorType", "ordinal", "callContractSha256").stream()
                        .allMatch(key -> call.has(key) && call.path(key).equals(binding.path(key)))
                && call.path("selectorType").equals(evidence.path("selectorType"))
                && call.path("selectorSha256").equals(evidence.path("newSelectorSha256"))
                && call.path("literalSha256").equals(evidence.path("newLiteralSha256"))).count();
        boolean verified = evidence.path("sourceSelectorCallVerified").asBoolean()
                && !inspection.path("selectorCallsTruncated").asBoolean(true) && matches == 1;
        report.put("sourceSelectorCallVerified", verified);
        if (!verified) {
            report.withArray("contractViolations").add("SELECTOR_CANDIDATE_CALL_MISMATCH");
            report.put("staticValidationPassed", false).put("targetRecovered", false)
                    .put("verdict", "candidate_rejected");
        }
    }

    public ObjectNode runtime(JsonNode frozen, JsonNode staticReport, JsonNode evidence) {
        ObjectNode report = staticReport.deepCopy();
        report.put("evidenceClass", "managed_runtime_candidate").put("businessVerdictTrusted", false)
                .put("targetRecovered", false).put("publishEligible", false);
        report.set("runtimeEvidence", evidence.deepCopy());
        boolean networkPending = networkRetryable(frozen, report);
        boolean outputOnlyNetwork = networkPending && !networkUnavailable(evidence);
        report.put("runtimeValidationPaused", networkPending);
        JsonNode before = frozen.path("baseline").path("result").path("managedSourceEvidence")
                .path("untrustedRuntimeSummary");
        JsonNode after = evidence.path("untrustedRuntimeSummary");
        ObjectNode observed = report.putObject("runtimeObservation");
        observed.put("status", after.path("status").asText());
        var regressions = report.putArray("observedRegressions");
        var comparison = report.putArray("runtimeComparison");
        boolean singlePageRecovery = verifiedSinglePageRecovery(frozen, report, evidence);
        for (String field : OUTPUT_FIELDS) {
            observed.set(field, after.path(field).deepCopy());
            var row = comparison.addObject().put("field", field);
            row.set("before", before.path(field).deepCopy());
            row.set("after", after.path(field).deepCopy());
            boolean networkAffected = networkPending && (!outputOnlyNetwork
                    || networkFailureForMethod(evidence, outputMethod(field)));
            boolean comparable = !networkAffected && !(field.equals("page2Items") && singlePageRecovery);
            row.put("comparable", comparable);
            if (networkAffected) row.put("reason", "RUNTIME_NETWORK_UNAVAILABLE");
            if (field.equals("page2Items") && singlePageRecovery)
                row.put("reason", "FAILED_PAGINATION_RECOVERED_AS_VERIFIED_SINGLE_PAGE");
            if (comparable && before.path(field).asInt() > 0 && after.path(field).asInt() == 0)
                regressions.add(field);
        }
        var stages = report.putArray("runtimeStageComparison");
        for (String field : java.util.List.of("playerStatus", "mediaStatus")) {
            var previous = before.path(field);
            var current = after.path(field);
            observed.set(field, current.isMissingNode() ? mapper.nullNode() : current.deepCopy());
            var row = stages.addObject().put("field", field);
            row.set("before", previous.isMissingNode() ? mapper.nullNode() : previous.deepCopy());
            row.set("after", current.isMissingNode() ? mapper.nullNode() : current.deepCopy());
            String success = field.equals("playerStatus") ? "returned" : "prefix_returned";
            boolean comparable = !networkPending || outputOnlyNetwork
                    && !networkFailureForMethod(evidence, outputMethod(field));
            row.put("comparable", comparable);
            if (!comparable) row.put("reason", "RUNTIME_NETWORK_UNAVAILABLE");
            if (comparable && previous.asText().equals(success) && !current.asText().equals(success))
                regressions.add(field);
        }
        boolean homeCardsRequired = frozen.path("baseline").path("profile").path("requiredNodes").valueStream()
                .anyMatch(node -> node.asText().equals("C02"));
        boolean recovered = !networkPending && after.path("homeClasses").asInt() > 0
                && (!homeCardsRequired || after.path("homeItems").asInt() > 0)
                && java.util.Set.of("category_returned", "detail_returned").contains(after.path("status").asText());
        report.put("targetObservedRecovered", recovered)
                .put("verdict", recovered && regressions.isEmpty() ? "runtime_target_observed" : "runtime_target_unresolved")
                .put("nextRequired", "loader_required_node_validation")
                .put("boundary", "候选经同一受管 T4 执行器及冻结出站策略复测，控制与清理由宿主确认。"
                        + "首页恢复和退化比较只针对源自报输出，不能证明业务正确、媒体可播、"
                        + "客户端兼容或发布资格；必需节点尚未全部由装载器验证，不能宣布爬虫修复成功。"
                        + "有效源码未切换，没有模型调用或生产发布。");
        attachRuntimeAcceptance(frozen, report);
        if (networkPending) report.put("verdict", "runtime_network_paused")
                .put("targetObservedRecovered", false).put("runtimeProgressObserved", false)
                .put("nextRequired", "retry_existing_candidate_runtime")
                .put("boundary", "源站网络请求未完成，联网验证暂停；原始失败回执保留。"
                        + "不能据此判定源码退化或修复成功，也不继续调用模型。恢复后复测同一候选，不增加修复轮次。");
        return report;
    }

    private boolean verifiedSinglePageRecovery(JsonNode frozen, JsonNode report, JsonNode evidence) {
        JsonNode baseline = frozen.path("baseline");
        JsonNode before = baseline.path("result").path("managedSourceEvidence").path("loaderValidation");
        JsonNode after = evidence.path("loaderValidation");
        if (!frozen.path("policy").path("scope").asText().equals("managed_loader_candidate")
                || !verifiedLoader(before, baseline.path("artifactSha256"), baseline)
                || !verifiedLoader(after, report.path("artifactSha256"), baseline)) return false;
        JsonNode previous = node(before.path("nodes"), "C03");
        JsonNode current = node(after.path("nodes"), "C03");
        return previous.path("status").asText().equals("fail")
                && previous.path("reason").asText().equals("PAGINATION_REPLAY_OR_PAGE_MISMATCH")
                && current.path("status").asText().equals("pass")
                && current.path("reason").asText().equals("FIRST_SECOND_PAGE_WINDOW_CONTRACT")
                && current.path("paginationMode").asText().equals("page-window")
                && current.path("firstItems").asInt() > 0 && current.path("secondItems").isIntegralNumber()
                && current.path("secondItems").asInt(-1) == 0 && current.path("pagesObserved").asInt() == 1
                && current.path("pageNumbersVerified").asBoolean();
    }

    static final java.util.Set<String> NETWORK_CODES = java.util.Set.of(
            "EGRESS_HTTP_FAILED", "EGRESS_HTTP_DEADLINE", "EGRESS_DNS_FAILED",
            "EGRESS_DNS_NO_ADDRESS", "EGRESS_NONPUBLIC_DNS");
    static final java.util.Map<String, String> NETWORK_METHOD_NODES = java.util.Map.of(
            "init", "S06", "home", "C01", "category", "C03", "search", "C05", "detail", "C07", "play", "R01");
    static final java.util.List<String> OUTPUT_FIELDS = java.util.List.of("homeClasses", "homeItems", "homeCovers",
            "categoryItems", "categoryCovers", "page2Items", "detailItems", "detailCovers", "playGroups", "playEpisodes");

    private static String outputMethod(String field) {
        return switch (field) {
            case "homeClasses", "homeItems", "homeCovers" -> "home";
            case "categoryItems", "categoryCovers", "page2Items" -> "category";
            case "detailItems", "detailCovers", "playGroups", "playEpisodes" -> "detail";
            case "playerStatus" -> "play";
            case "mediaStatus" -> "media";
            default -> "";
        };
    }

    static boolean networkFailureForMethod(JsonNode evidence, String method) {
        if (method.isEmpty()) return false;
        JsonNode codes = evidence.path("deniedCodes");
        return codes.isArray() && evidence.path("networkFailureCalls").valueStream().anyMatch(call -> {
            JsonNode index = call.path("denialIndex");
            return method.equals(call.path("method").asText()) && index.isIntegralNumber()
                    && index.canConvertToInt() && index.intValue() >= 0 && index.intValue() < codes.size()
                    && NETWORK_CODES.contains(codes.path(index.intValue()).asText());
        });
    }

    static boolean networkAffectedOutput(JsonNode before, JsonNode evidence, String field) {
        if (before == null || !OUTPUT_FIELDS.contains(field)) return false;
        JsonNode previous = before.path(field), current = evidence.path("untrustedRuntimeSummary").path(field);
        return previous.isIntegralNumber() && previous.canConvertToInt() && previous.asInt() > 0
                && current.isIntegralNumber() && current.canConvertToInt() && current.asInt() == 0
                && networkFailureForMethod(evidence, outputMethod(field));
    }

    static boolean repairableLoaderNode(JsonNode node) {
        if ("optional".equals(node.path("requirement").asText())) return false;
        String status = node.path("status").asText();
        if ("fail".equals(status)) return true;
        // These are observed output defects, not missing credentials, host adapters or network access.
        return "blocked".equals(status) && java.util.Set.of(
                "PLAYER_URL_EMPTY", "PLAYER_URL_INVALID", "PLAYER_HEADERS_INVALID",
                "PLAYER_OUTPUT_INVALID", "PLAYER_EXTERNAL_PARSER_REQUIRED", "LOADER_METHOD_FAILED")
                .contains(node.path("reason").asText());
    }

    static java.util.List<JsonNode> unresolvedEgressCodes(JsonNode evidence) {
        return unresolvedEgressCodes(evidence, null);
    }

    static java.util.List<JsonNode> unresolvedEgressCodes(JsonNode evidence, JsonNode before) {
        var codes = evidence.path("deniedCodes");
        if (!codes.isArray()) return java.util.List.of();
        var unresolved = new java.util.ArrayList<JsonNode>();
        for (int index = 0; index < codes.size(); index++) {
            var code = codes.get(index);
            int denialIndex = index;
            var loader = evidence.path("loaderValidation");
            // A completed host-validated action may have handled an unavailable optional child source.
            boolean handled = NETWORK_CODES.contains(code.asText())
                    && loader.path("projection").asText().equals(ManagedLoaderReceipt.PROJECTION)
                    && evidence.path("networkFailureCalls").valueStream().anyMatch(call -> {
                        String nodeId = NETWORK_METHOD_NODES.get(call.path("method").asText());
                        return call.path("denialIndex").isIntegralNumber()
                                && call.path("denialIndex").asInt(-1) == denialIndex && nodeId != null
                                && OUTPUT_FIELDS.stream().noneMatch(field ->
                                    outputMethod(field).equals(call.path("method").asText())
                                        && networkAffectedOutput(before, evidence, field))
                                && loader.path("nodes").valueStream().anyMatch(node ->
                                    node.path("id").asText().equals(nodeId)
                                            && node.path("status").asText().equals("pass"));
                    });
            if (!handled) unresolved.add(code);
        }
        return unresolved;
    }

    static boolean egressUnresolved(JsonNode evidence) {
        return !evidence.path("deniedCodes").isArray() || !unresolvedEgressCodes(evidence).isEmpty();
    }

    static boolean networkUnavailable(JsonNode evidence) {
        return networkUnavailable(evidence, null);
    }

    static boolean networkUnavailable(JsonNode evidence, JsonNode before) {
        var unresolved = unresolvedEgressCodes(evidence, before);
        return !unresolved.isEmpty() && unresolved.stream().allMatch(code -> NETWORK_CODES.contains(code.asText()));
    }

    static boolean httpAccessDenied(JsonNode evidence) {
        var responses = nonRedirectResponses(evidence);
        return !responses.isEmpty()
                && responses.stream().allMatch(response ->
                    response.path("responseComplete").asBoolean()
                        && response.path("responseStatus").isIntegralNumber()
                        && java.util.Set.of(401, 403).contains(response.path("responseStatus").asInt()));
    }

    static boolean playbackNotProvided(JsonNode result) {
        JsonNode evidence = result.path("managedSourceEvidence");
        if (!sameSha(result.path("artifactSha256"), evidence.path("inputSourceSha256"))
                || !evidence.path("cleanup").path("confirmed").asBoolean()
                || !evidence.path("cleanup").path("containerAbsent").asBoolean()
                || evidence.path("httpObservations").valueStream().noneMatch(response ->
                    response.path("responseComplete").asBoolean()
                        && response.path("responseStatus").isIntegralNumber()
                        && response.path("responseStatus").asInt() >= 200
                        && response.path("responseStatus").asInt() < 300)) return false;
        boolean declared = result.path("nodes").valueStream().anyMatch(node -> {
            JsonNode inspection = node.path("evidence");
            JsonNode declaration = inspection.path("playbackDeclaration");
            return "S03".equals(node.path("id").asText()) && "pass".equals(node.path("status").asText())
                    && "static".equals(node.path("evidenceClass").asText())
                    && sameSha(result.path("artifactSha256"), inspection.path("sha256"))
                    && inspection.path("parseOk").asBoolean() && inspection.path("issues").isArray()
                    && inspection.path("issues").isEmpty()
                    && "metadata-only".equals(declaration.path("kind").asText())
                    && "static_literal_player_declaration".equals(declaration.path("authority").asText());
        });
        return declared && result.path("nodes").valueStream().anyMatch(node ->
                "R01".equals(node.path("id").asText()) && "required".equals(node.path("requirement").asText())
                    && "blocked".equals(node.path("status").asText())
                    && "host_loader_contract_observed".equals(node.path("evidenceClass").asText())
                    && "PLAYER_URL_EMPTY".equals(node.path("reason").asText()));
    }

    static java.util.List<JsonNode> nonRedirectResponses(JsonNode evidence) {
        var responses = evidence.path("httpObservations");
        // A completed redirect is an intermediate step, not an accessible source response.
        return responses.isArray() ? responses.valueStream().filter(response ->
                !(response.path("responseComplete").asBoolean()
                    && response.path("responseStatus").isIntegralNumber()
                    && java.util.Set.of(301, 302, 303, 307, 308)
                        .contains(response.path("responseStatus").asInt()))).toList() : java.util.List.of();
    }

    public boolean networkRetryable(JsonNode frozen, JsonNode report) {
        if (report == null) return false;
        JsonNode evidence = report.path("runtimeEvidence");
        JsonNode before = frozen.path("baseline").path("result").path("managedSourceEvidence")
                .path("untrustedRuntimeSummary");
        return report.path("staticValidationPassed").asBoolean() && networkUnavailable(evidence, before)
                && sameSha(evidence.path("inputSourceSha256"), report.path("artifactSha256"))
                && sameSha(evidence.path("bundleSha256"), runtimeInputs(frozen).path("runtimeSha256"))
                && evidence.path("cleanup").path("confirmed").asBoolean()
                && evidence.path("cleanup").path("containerAbsent").asBoolean();
    }

    private void attachRuntimeAcceptance(JsonNode frozen, ObjectNode report) {
        var acceptance = report.putObject("runtimeAcceptance").put("policy", "loader_required_nodes_v1")
                .put("status", "required_incomplete").put("successful", false);
        // Repair targets describe the patch, not the source's complete acceptance contract.
        var required = new java.util.LinkedHashSet<String>();
        required.addAll(java.util.List.of("S01", "S02", "S03", "S06"));
        JsonNode declared = frozen.path("baseline").path("profile").path("requiredNodes");
        if (declared.isArray()) declared.forEach(node -> required.add(node.asText()));
        var blockers = acceptance.putArray("blockers");
        if (!declared.isArray() || declared.isEmpty()) blockers.add("REPAIR_REQUIREMENTS_UNAVAILABLE");
        JsonNode evidence = report.path("runtimeEvidence");
        boolean networkPending = report.path("runtimeValidationPaused").asBoolean();
        boolean outputOnlyNetwork = networkPending && !networkUnavailable(evidence);
        JsonNode loader = evidence.path("loaderValidation");
        boolean loaderVerified = verifiedLoader(loader, report.path("artifactSha256"), frozen.path("baseline"));
        if (!loaderVerified) blockers.add("LOADER_REQUIRED_RESULTS_UNVERIFIED");
        if (!sameSha(evidence.path("inputSourceSha256"), report.path("artifactSha256"))
                || !sameSha(evidence.path("bundleSha256"), runtimeInputs(frozen).path("runtimeSha256")))
            blockers.add("RUNTIME_INPUT_MISMATCH");
        if (!report.path("staticValidationPassed").asBoolean()) blockers.add("CANDIDATE_STATIC_VALIDATION_REQUIRED");
        if (!evidence.path("cleanup").path("confirmed").asBoolean()
                || !evidence.path("cleanup").path("containerAbsent").asBoolean())
            blockers.add("MANAGED_CLEANUP_UNCONFIRMED");
        JsonNode beforeOutput = frozen.path("baseline").path("result").path("managedSourceEvidence")
                .path("untrustedRuntimeSummary");
        if (!evidence.path("deniedCodes").isArray() || !unresolvedEgressCodes(evidence, beforeOutput).isEmpty())
            blockers.add("RUNTIME_EGRESS_UNRESOLVED");
        var nodes = acceptance.putArray("nodes");
        int passed = 0, failed = 0, blocked = 0, untested = 0;
        String firstUnresolved = null;
        for (String id : required) {
            JsonNode original = mapper.missingNode();
            for (JsonNode node : report.path("nodes")) {
                if (node.path("id").asText().equals(id)) { original = node; break; }
            }
            boolean staticNode = java.util.Set.of("S01", "S02", "S03").contains(id);
            JsonNode loaded = mapper.missingNode();
            if (loaderVerified) {
                for (JsonNode node : loader.path("nodes")) {
                    if (node.path("id").asText().equals(id)) { loaded = node; break; }
                }
            }
            String status = staticNode ? original.path("status").asText("not_tested")
                    : !loaded.isMissingNode() ? loaded.path("status").asText("not_tested")
                    : id.equals("S06") ? "blocked" : "not_tested";
            String reason = staticNode ? original.path("reason").asText("CANDIDATE_STATIC_RESULT_MISSING")
                    : !loaded.isMissingNode() ? loaded.path("reason").asText("LOADER_NODE_UNRESOLVED")
                    : id.equals("S06") ? "LOADER_RESULT_UNVERIFIED" : "LOADER_NODE_NOT_VALIDATED";
            if (networkPending && !outputOnlyNetwork && !staticNode && !status.equals("pass")) {
                status = "blocked";
                reason = "RUNTIME_NETWORK_UNAVAILABLE";
            }
            var node = nodes.addObject().put("id", id).put("title", original.path("title").asText(id))
                    .put("status", status).put("reason", reason);
            node.put("evidenceClass", staticNode ? "static" : !loaded.isMissingNode()
                    ? "host_loader_contract_observed" : "none");
            if (!staticNode && !loaded.isMissingNode()) {
                node.set("evidence", loaded.deepCopy());
                node.put("evidenceReference", "runtimeEvidence.loaderValidation.nodes");
            }
            switch (status) {
                case "pass" -> passed++;
                case "fail" -> failed++;
                case "blocked" -> blocked++;
                default -> untested++;
            }
            if (!status.equals("pass") && firstUnresolved == null) firstUnresolved = id;
        }
        acceptance.put("firstUnresolvedNode", firstUnresolved);
        acceptance.putObject("requiredCoverage").put("total", required.size()).put("pass", passed)
                .put("fail", failed).put("blocked", blocked).put("notTested", untested);
        boolean originMode = frozen.path("policy").path("scope").asText().equals("managed_origin_candidate");
        boolean loaderMode = originMode || frozen.path("policy").path("scope").asText().equals("managed_loader_candidate");
        if (originMode && RepairEvidenceMaterial.originRoute(mapper, frozen.path("originRecoveryRepairId").asText(),
                frozen.path("baseline").path("id").asText(), frozen.path("baseline").path("artifactSha256").asText(),
                frozen.path("reverseAnalysis")).isMissingNode()) blockers.add("VERIFIED_ORIGIN_ROUTE_REQUIRED");
        if (loaderMode) {
            JsonNode previous = frozen.path("baseline").path("result").path("managedSourceEvidence")
                    .path("loaderValidation");
            boolean baselineVerified = verifiedLoader(previous, frozen.path("baseline").path("artifactSha256"),
                    frozen.path("baseline"));
            if (!baselineVerified && !originMode) blockers.add("LOADER_BASELINE_RESULTS_UNVERIFIED");
            var progress = report.putObject("runtimeNodeProgress").put("policy", "frozen_required_nodes_v1")
                    .put("baselineVerified", baselineVerified).put("candidateVerified", loaderVerified);
            var recovered = progress.putArray("recovered");
            var regressed = progress.putArray("regressed");
            var comparison = progress.putArray("comparison");
            for (JsonNode current : nodes) {
                String id = current.path("id").asText();
                if (java.util.Set.of("S01", "S02", "S03").contains(id)) continue;
                JsonNode beforeNode = baselineVerified ? node(previous.path("nodes"), id) : mapper.missingNode();
                String beforeStatus = beforeNode.path("status").asText("not_tested");
                String afterStatus = current.path("status").asText();
                boolean comparable = !networkPending || outputOnlyNetwork;
                comparison.addObject().put("id", id).put("before", beforeStatus).put("after", afterStatus)
                        .put("comparable", comparable)
                        .put("beforeReason", beforeNode.path("reason").asText("LOADER_NODE_NOT_VALIDATED"))
                        .put("afterReason", current.path("reason").asText());
                if (comparable && (baselineVerified || originMode) && loaderVerified
                        && !beforeStatus.equals("pass") && afterStatus.equals("pass"))
                    recovered.add(id);
                if (comparable && baselineVerified && beforeStatus.equals("pass") && !afterStatus.equals("pass")) {
                    regressed.add(id);
                    report.withArray("observedRegressions").add(id);
                }
            }
            JsonNode targets = frozen.path("policy").path("targetNodes");
            report.put("targetObservedRecovered", (baselineVerified || originMode) && loaderVerified && targets.isArray()
                    && !targets.isEmpty() && targets.valueStream().allMatch(target ->
                        node(nodes, target.asText()).path("status").asText().equals("pass")));
        }
        if (!report.path("observedRegressions").isEmpty()) blockers.add("RUNTIME_REGRESSION");
        if (loaderMode) {
            boolean progress = !report.path("runtimeNodeProgress").path("recovered").isEmpty() && blockers.isEmpty();
            report.put("runtimeProgressObserved", progress)
                    .put("verdict", progress ? "runtime_nodes_progress_observed" : "runtime_target_unresolved");
            var failure = report.putObject("runtimeFailureSignature");
            var remaining = failure.putArray("nodes");
            for (JsonNode current : nodes) {
                var signature = remaining.addObject().put("id", current.path("id").asText())
                        .put("status", current.path("status").asText()).put("reason", current.path("reason").asText());
                if (loaderVerified && java.util.Set.of("fail", "blocked").contains(current.path("status").asText())) {
                    JsonNode observed = node(loader.path("nodes"), current.path("id").asText());
                    String type = ManagedLoaderReceipt.safeFailureType(observed.path("failureType").asText());
                    if (type != null) signature.put("failureType", type);
                    // Stable contract distinctions matter; changing counts, IDs or timings do not.
                    var details = ManagedLoaderReceipt.contractObservations(mapper, observed);
                    details.retain("identityEvidence", "paginationMode", "exactTotalVerified", "lastPageVerified",
                            "pageNumbersVerified", "playerRouteKind", "offlineResolverRequired", "magnetInfoHashValid",
                            "playbackContract");
                    if (details.path("playbackContract") instanceof ObjectNode playback)
                        playback.retain("groupsAligned", "namePresent", "separatorPresent", "identifierPresent");
                    if (!details.isEmpty()) signature.set("contractObservations", details);
                }
            }
            failure.set("blockers", blockers.deepCopy());
            failure.set("regressions", report.path("observedRegressions").deepCopy());
        }
        boolean successful = firstUnresolved == null && blockers.isEmpty();
        acceptance.put("successful", successful).put("status", successful ? "required_passed" : "required_incomplete");
        report.put("repairSuccessful", successful);
        if (successful) report.put("targetRecovered", true).put("verdict", "loader_required_nodes_passed")
                .put("nextRequired", "release_preparation_and_client_validation")
                .put("boundary", "冻结画像全部必需节点均有受管运行器校验的宿主合同回执，且静态检查、"
                        + "输入身份、清理、出站与退化门禁通过。仅在该画像范围内认定装载合同恢复；"
                        + "未切换有效源码，不代表生产 AT、客户端长播或发布已通过。");
        acceptance.put("boundary", "按冻结画像全部必需项验收，初始化 S06 不可省略。"
                + "仅采纳经受管运行器核对源码、运行时和冻结画像的宿主节点回执；"
                + "源摘要、HTTP 200、非空 URL、私有聚合成功均不能代替。缺项继续修复；"
                + "节点全过只代表该冻结画像的装载合同，不自动认证客户端长播或授予发布资格。");
    }

    private static boolean sameSha(JsonNode actual, JsonNode expected) {
        return actual.isTextual() && actual.asText().matches("[a-f0-9]{64}") && actual.equals(expected);
    }

    private boolean verifiedLoader(JsonNode loader, JsonNode sourceSha, JsonNode baseline) {
        if (!loader.path("authority").asText().equals("host_loader_contract_observed")
                || !loader.path("projection").asText().equals(ManagedLoaderReceipt.PROJECTION)
                || !loader.path("scope").asText().equals(ManagedLoaderReceipt.SCOPE)
                || !loader.path("requirementsFrozen").isBoolean() || !loader.path("requirementsFrozen").booleanValue()
                || !sameSha(loader.path("inputProfileSha256"), baseline.path("profileSha256"))
                || !loader.path("inputProfileSha256").asText().equals(Hashes.sha256(baseline.path("profile").toString()))
                || !sameSha(loader.path("inputSourceSha256"), sourceSha)
                || !loader.path("nodes").isArray() || loader.path("nodes").size() > 44) return false;
        var ids = new java.util.HashSet<String>();
        for (JsonNode row : loader.path("nodes"))
            if (!row.path("id").isTextual() || !row.path("id").asText().matches("[SCRMP][0-9]{2}")
                    || !ids.add(row.path("id").asText())
                    || !java.util.Set.of("pass", "fail", "blocked", "not_tested").contains(row.path("status").asText()))
                return false;
        return true;
    }

    private JsonNode node(JsonNode nodes, String id) {
        return nodes.valueStream().filter(row -> row.path("id").asText().equals(id)).findFirst()
                .orElse(mapper.missingNode());
    }

    public JsonNode staticEvidence(JsonNode probe) {
        for (JsonNode node : probe.path("result").path("nodes")) {
            if (node.path("id").asText().equals("S03")) return node.path("evidence");
        }
        return mapper.createObjectNode();
    }
}
