package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Projects only the pinned host loader's contracts, never source-provided node verdicts. */
final class ManagedLoaderReceipt {
    static final String SCOPE = "t4-host-loader-observation-v1";
    static final String PROJECTION = "managed-runtime-validated-v1";
    private static final Map<String, Set<String>> PASSES = Map.ofEntries(
            Map.entry("S06", Set.of("PRE_EXEC_SOURCE_SHA_AND_INIT_RETURNED")),
            Map.entry("S04", Set.of("UNCONDITIONAL_IMPORTS_LOADED_IN_PINNED_IMAGE")),
            Map.entry("S05", Set.of("NO_USER_CONFIGURATION_REQUIRED_INIT_OBSERVED")),
            Map.entry("S07", Set.of("JSONL_ERRORS_RECOVERY_TIMEOUT_EXIT_OBSERVED")),
            Map.entry("S08", Set.of("FRESH_TMPFS_WORKERS_MATCHED_HOT_HOME")),
            Map.entry("R09", Set.of("NORMAL_CANCEL_TIMEOUT_EXIT_CLEANUP_OBSERVED")),
            Map.entry("C01", Set.of("HOME_CATEGORY_CONTRACT")),
            Map.entry("C02", Set.of("HOME_RECOMMENDATION_TO_DETAIL_CONTRACT")),
            Map.entry("C03", Set.of("FIRST_SECOND_LAST_PAGE_CONTRACT", "FIRST_SECOND_PAGE_WINDOW_CONTRACT")),
            Map.entry("C05", Set.of("SEARCH_FIRST_PAGE_TO_DETAIL_CONTRACT")),
            Map.entry("C07", Set.of("LIST_ID_TO_DETAIL_AND_INVALID_ID_REJECTED")),
            Map.entry("C09", Set.of("COVER_BYTES_DECODED")),
            Map.entry("C10", Set.of("ALL_GROUPS_AND_EPISODES_PARSED")),
            Map.entry("R01", Set.of("SELECTED_PLAY_ID_TO_DIRECT_PLAYER_CONTRACT",
                    "SELECTED_PLAY_ID_TO_AT_OFFLINE_CONTRACT")),
            Map.entry("R06", Set.of("PLAYER_HEADERS_USED_FOR_MEDIA_REQUEST")),
            Map.entry("M01", Set.of("HOST_FETCHED_PLAYER_RESOURCE")),
            Map.entry("M02", Set.of("MASTER_TO_SELECTED_VARIANT_FETCHED", "DIRECT_MEDIA_PLAYLIST_NO_MASTER")),
            Map.entry("M03", Set.of("MEDIA_PLAYLIST_PARSED")),
            Map.entry("M04", Set.of("NO_KEY_OR_INIT_RESOURCE_REQUIRED", "HLS_KEY_AND_INIT_RESOURCES_VERIFIED")),
            Map.entry("M05", Set.of("FIRST_MIDDLE_LAST_SEGMENT_PREFIXES_VERIFIED", "HLS_SEGMENT_SAMPLES_VERIFIED")),
            Map.entry("M06", Set.of("MP4_COHERENT_RANGE_SAMPLES")),
            Map.entry("M07", Set.of("BOUNDED_CONSECUTIVE_SEGMENT_PREFIX_WINDOW")));
    private static final Set<String> REASONS = Set.of(
            "LOADER_INIT_FAILED", "LOADER_METHOD_FAILED", "RETURN_OBJECT_REQUIRED",
            "HOME_CATEGORIES_INVALID", "HOME_CATEGORY_IDS_DUPLICATED", "HOME_OUTPUT_INVALID",
            "HOT_HOME_CATEGORY_IDENTITY_CHANGED", "CONTENT_LIST_EMPTY_OR_INVALID", "CONTENT_CARD_INVALID",
            "CONTENT_IDS_DUPLICATED", "PAGINATION_METADATA_INVALID", "PAGINATION_REPLAY_OR_PAGE_MISMATCH",
            "PAGINATION_LAST_PAGE_INVALID", "CATEGORY_OUTPUT_INVALID", "DETAIL_SELECTION_INVALID",
            "SEARCH_KEYWORD_REQUIRED", "SEARCH_OUTPUT_INVALID",
            "DETAIL_IDENTITY_MISMATCH", "INVALID_DETAIL_ID_RETURNED_CONTENT", "DETAIL_OUTPUT_INVALID",
            "COVER_URL_EMPTY", "COVER_DECODER_UNAVAILABLE", "COVER_DIMENSIONS_INVALID",
            "COVER_DECODER_IDENTITY_INVALID", "COVER_MIME_INVALID", "COVER_DECODE_UNAVAILABLE_OR_INVALID",
            "COVER_BYTES_INVALID", "COVER_PROXY_RESULT_INVALID",
            "PLAY_GROUPS_INVALID", "PLAY_EPISODE_INVALID", "PLAYER_URL_EMPTY",
            "PLAYER_EXTERNAL_PARSER_REQUIRED", "PLAYER_OFFLINE_RESOLVER_REQUIRED",
            "AT_OFFLINE_DOWNLOAD_NOT_VALIDATED",
            "PLAYER_URL_INVALID", "PLAYER_HEADERS_INVALID", "PLAYER_OUTPUT_INVALID",
            "RESOURCE_HTTP_FAILED", "MEDIA_PREFIX_NOT_RECOGNIZED", "HLS_COMPLETE_DOCUMENT_NOT_CONFIRMED",
            "MP4_RANGE_NOT_SUPPORTED", "MP4_CONTENT_RANGE_INVALID", "MP4_RANGE_MISMATCH",
            "MP4_TOTAL_CHANGED", "MP4_RANGE_FETCH_FAILED", "EGRESS_RANGE_DENIED", "EGRESS_RANGE_INVALID",
            "MEDIA_FETCH_FAILED", "HLS_MASTER_EMPTY", "HLS_VARIANT_NOT_PLAYLIST", "HLS_NESTED_MASTER_NOT_SUPPORTED",
            "HLS_SEGMENTS_INVALID", "HLS_KEY_OR_INIT_REQUIRES_ADDITIONAL_ADAPTER",
            "HLS_BYTERANGE_REQUIRES_ADDITIONAL_ADAPTER", "HLS_SEGMENT_NOT_MPEG_TS",
            "HLS_IFRAMES_ONLY_UNSUPPORTED", "HLS_BYTERANGE_INVALID", "HLS_RANGE_RESPONSE_INVALID",
            "HLS_ENCRYPTION_UNSUPPORTED", "HLS_KEY_RESPONSE_INVALID", "HLS_INIT_IV_REQUIRED", "HLS_IV_INVALID",
            "HLS_CIPHER_INVALID", "HLS_DECRYPT_FAILED", "HLS_FMP4_INVALID", "HLS_INIT_INVALID", "HLS_INIT_INCOMPLETE",
            "CONTINUOUS_WINDOW_DEADLINE", "CONTINUOUS_SEGMENT_NOT_MPEG_TS", "CONTINUOUS_WINDOW_INCOMPLETE",
            "HLS_PARSER_OR_RESOURCE_UNAVAILABLE", "JSONL_INVALID_REQUEST_NOT_REJECTED", "TOP_LEVEL_JSONL_NOT_CONFIRMED",
            "JSONL_ERRORS_OBSERVED_TIMEOUT_EXIT_PENDING", "DESTROY_RETURNED_PENDING_CONTAINER_CLEANUP",
            "NORMAL_CLEANUP_CONFIRMED_FAILURE_CASES_NOT_TESTED", "DESTROY_UNCONFIRMED",
            "TOP_LEVEL_JSONL_ENTRYPOINT_NOT_IN_SAMPLE_RUNNER", "FRESH_WORKER_CACHE_COMPARISON_NOT_IN_SAMPLE_RUNNER",
            "NODE_NOT_VALIDATED_BY_THIS_RUNNER", "EGRESS_URL_DENIED", "EGRESS_NONPUBLIC_TARGET_DENIED",
            "EGRESS_HEADER_ORIGIN_DENIED", "EGRESS_HEADERS_DENIED", "EGRESS_METHOD_DENIED",
            "EGRESS_REDIRECT_DENIED", "EGRESS_SESSION_TOKEN_DENIED", "SOURCE_REGISTERED_QUERY_SEPARATOR_MISSING",
            "DEPENDENCY_COVERAGE_INCOMPLETE", "USER_CONFIGURATION_CONTRACT_NOT_ESTABLISHED",
            "EGRESS_REQUEST_BUDGET", "EGRESS_BYTE_BUDGET", "EGRESS_HTTP_FAILED", "EGRESS_HTTP_DEADLINE",
            "EGRESS_RATE_LIMITED", "EGRESS_DNS_FAILED", "EGRESS_DNS_NO_ADDRESS", "EGRESS_NONPUBLIC_DNS");
    private final ObjectMapper mapper;
    private static final Set<String> FAILURE_TYPES = Set.of(
            "ModuleNotFoundError", "ImportError", "NameError", "AttributeError", "TypeError",
            "ValueError", "KeyError", "IndexError", "ZeroDivisionError", "RuntimeError",
            "NotImplementedError", "RecursionError", "UnicodeDecodeError", "JSONDecodeError",
            "TimeoutError", "ConnectionError", "OSError", "SyntaxError");
    private static final Set<String> PLAYER_URL_SHAPES = Set.of("not_string", "empty", "absolute_http",
            "magnet", "protocol_relative", "relative_path", "json_like", "html_like",
            "percent_encoded_url", "base64_encoded_url", "custom_scheme", "opaque", "malformed");

    ManagedLoaderReceipt(ObjectMapper mapper) { this.mapper = mapper; }

    static String safeReason(String reason) {
        return REASONS.contains(reason) ? reason : "LOADER_NODE_UNRESOLVED";
    }

    static String safeFailureType(String type) {
        return FAILURE_TYPES.contains(type) ? type : null;
    }

    static ObjectNode contractObservations(ObjectMapper mapper, JsonNode node) {
        var result = mapper.createObjectNode();
        for (String field : List.of("classes", "firstItems", "secondItems", "lastItems", "pages", "items",
                "groups", "episodes", "sampledPlayers", "sampledVariants", "segments", "width", "height",
                "bytes", "pagesObserved")) {
            JsonNode count = node.path(field);
            if (integer(count, 40_000_000)) result.set(field, count.deepCopy());
        }
        for (String field : List.of("exactTotalVerified", "lastPageVerified", "pageNumbersVerified",
                "offlineResolverRequired", "magnetInfoHashValid")) {
            if (node.path(field).isBoolean()) result.set(field, node.path(field));
        }
        if (Set.of("page-window", "exact-total").contains(node.path("paginationMode").asText()))
            result.put("paginationMode", node.path("paginationMode").asText());
        if (Set.of("absent", "http", "https", "magnet", "other").contains(node.path("playerRouteKind").asText()))
            result.put("playerRouteKind", node.path("playerRouteKind").asText());
        var identity = mapper.createObjectNode();
        for (String field : List.of("exactMatch", "guidCoverMatch", "atvpDetailPrefixMatch", "nameMatch", "coverMatch")) {
            JsonNode value = node.path("identityEvidence").path(field);
            if (value.isBoolean()) identity.set(field, value);
        }
        if (!identity.isEmpty()) result.set("identityEvidence", identity);
        if ("C10".equals(node.path("id").asText())) {
            var playback = mapper.createObjectNode();
            JsonNode contract = node.path("playbackContract");
            for (String field : List.of("detailCallId", "flagGroups", "urlGroups", "parsedEpisodes", "groupIndex", "episodeIndex"))
                if (integer(contract.path(field), 40_000_000)) playback.set(field, contract.path(field));
            for (String field : List.of("groupsAligned", "namePresent", "separatorPresent", "identifierPresent"))
                if (contract.path(field).isBoolean()) playback.set(field, contract.path(field));
            if (!playback.isEmpty()) result.set("playbackContract", playback);
        }
        if ("R01".equals(node.path("id").asText())) {
            var player = playerContractObservations(mapper, node.path("playerContract"));
            if (!player.isEmpty()) result.set("playerContract", player);
        }
        return result;
    }

    private static ObjectNode playerContractObservations(ObjectMapper mapper, JsonNode contract) {
        var player = mapper.createObjectNode();
        for (String field : List.of("detailCallId", "playCallId", "returnedUrlBytes"))
            if (integer(contract.path(field), 40_000_000)) player.set(field, contract.path(field));
        for (String field : List.of("selectedIdentifierShape", "returnedUrlShape"))
            if (PLAYER_URL_SHAPES.contains(contract.path(field).asText())) player.set(field, contract.path(field));
        if (Set.of("missing", "null", "string", "boolean", "number", "object", "array", "other")
                .contains(contract.path("returnedUrlType").asText()))
            player.set("returnedUrlType", contract.path("returnedUrlType"));
        if (Set.of("direct", "parse_required").contains(contract.path("parseMode").asText()))
            player.set("parseMode", contract.path("parseMode"));
        for (String field : List.of("returnedUrlMatchesIdentifier", "hasWhitespace", "hasFragment", "hasUserInfo", "hasHostname"))
            if (contract.path(field).isBoolean()) player.set(field, contract.path(field));
        return player;
    }

    void applyHttp(ObjectNode loader, JsonNode phase, JsonNode binding) throws IOException {
        require(loader.path("projection").asText().equals(PROJECTION)
                && loader.path("inputProfileSha256").equals(binding.path("inputProfileSha256"))
                && loader.path("inputSourceSha256").equals(binding.path("sourceSha256"))
                && loader.path("runtimeSha256").equals(binding.path("runtimeSha256"))
                && Set.of("COMPLETED", "FAILED").contains(phase.path("status").asText())
                && phase.path("cleanup").path("confirmed").isBoolean()
                && phase.path("cleanup").path("confirmed").booleanValue()
                && phase.path("cleanup").path("containerAbsent").isBoolean()
                && phase.path("cleanup").path("containerAbsent").booleanValue(),
                "T4_HTTP_RECEIPT_MISMATCH");
        ObjectNode node = null;
        for (JsonNode row : loader.path("nodes"))
            if (row.path("id").asText().equals("R04")) node = (ObjectNode) row;
        require(node != null, "T4_HTTP_RECEIPT_MISMATCH");
        if (phase.path("status").asText().equals("FAILED")) {
            node.put("status", "blocked").put("reason", "T4_HTTP_ACCEPTANCE_FAILED");
            JsonNode driver = phase.path("driverFailure");
            if (!driver.isMissingNode()) {
                require(phase.path("code").asText().equals("T4_HTTP_DRIVER_FAILED")
                        && fields(driver, Set.of("schemaVersion", "authority", "code", "completedActions",
                            "cleanupConfirmed"))
                        && driver.path("schemaVersion").isInt() && driver.path("schemaVersion").intValue() == 1
                        && driver.path("authority").asText().equals("at_t4_http_driver_observed")
                        && Set.of("HTTP_DRIVER_FAILED", "HTTP_CLEANUP_UNCONFIRMED", "HTTP_HOME_EMPTY",
                            "HTTP_CARDS_EMPTY", "HTTP_CARD_CONTRACT_FAILED", "HTTP_PAGINATION_FAILED",
                            "HTTP_DETAIL_EPISODE_MISSING", "HTTP_DETAIL_SAMPLE_MISMATCH", "HTTP_PLAYER_INVALID",
                            "HTTP_SEARCH_SHAPE_INVALID", "HTTP_PROXY_BODY_LIMIT", "HTTP_MANIFEST_URI_INVALID",
                            "HTTP_MANIFEST_EMPTY", "T4_BROKER_TIMEOUT", "T4_BROKER_REJECTED",
                            "T4_BROKER_RECEIPT_MISMATCH", "T4_BROKER_DELIVERY_MISMATCH",
                            "T4_BROKER_SESSION_CONTEXT_CHANGED", "T4_BROKER_UNIX_SOCKET_UNAVAILABLE",
                            "T4_BROKER_CLEANUP_UNCONFIRMED", "T4_HTTP_OBSERVATION_PLAN_MISMATCH",
                            "T4_HTTP_OBSERVATION_DISPATCH_MISMATCH", "T4_HTTP_OBSERVATION_DELIVERY_MISMATCH",
                            "T4_HTTP_OBSERVATION_RESPONSE_MISMATCH", "T4_HTTP_OBSERVATION_SESSION_MISMATCH",
                            "T4_HTTP_OBSERVATION_PROXY_MISMATCH").contains(driver.path("code").asText())
                        && integer(driver.path("completedActions"), 36)
                        && driver.path("cleanupConfirmed").isBoolean(), "T4_HTTP_RECEIPT_MISMATCH");
                node.set("driverFailure", driver.deepCopy());
                loader.set("httpDriverFailure", driver.deepCopy());
            }
            JsonNode failure = phase.path("sourceRequestFailure");
            if (!failure.isMissingNode()) {
                require(phase.path("code").asText().equals("T4_HTTP_DRIVER_FAILED")
                        && fields(failure, Set.of("schemaVersion", "authority", "jobId", "instanceId",
                            "imageId", "bundleSha256", "executionPolicySha256", "egressPolicySha256",
                            "sourceSha256", "inputProfileSha256", "childJobId", "childJournalSha256",
                            "currentChildJournalVerified", "httpId", "method", "requestUrlSha256",
                            "registeredUrlSha256", "registeredQuerySeparatorMissing", "cleanup"))
                        && failure.path("schemaVersion").isInt() && failure.path("schemaVersion").intValue() == 1
                        && failure.path("authority").asText().equals("host_registered_request_mismatch")
                        && List.of("jobId", "instanceId", "imageId", "bundleSha256", "executionPolicySha256",
                            "egressPolicySha256", "sourceSha256", "inputProfileSha256").stream()
                            .allMatch(key -> failure.path(key).equals(binding.path(key)))
                        && uuid(failure.path("childJobId"))
                        && !failure.path("childJobId").equals(binding.path("jobId"))
                        && sha(failure.path("childJournalSha256"))
                        && flag(failure, "currentChildJournalVerified", true)
                        && fields(failure.path("cleanup"), Set.of("confirmed", "containerAbsent"))
                        && flag(failure.path("cleanup"), "confirmed", true)
                        && flag(failure.path("cleanup"), "containerAbsent", true)
                        && integer(failure.path("httpId"), 64) && failure.path("httpId").intValue() > 0
                        && failure.path("method").asText().equals("GET")
                        && sha(failure.path("requestUrlSha256")) && sha(failure.path("registeredUrlSha256"))
                        && !failure.path("requestUrlSha256").equals(failure.path("registeredUrlSha256"))
                        && flag(failure, "registeredQuerySeparatorMissing", true), "T4_HTTP_RECEIPT_MISMATCH");
                node.put("status", "fail").put("reason", "SOURCE_REGISTERED_QUERY_SEPARATOR_MISSING");
                loader.set("sourceRequestFailure", failure.deepCopy());
            }
            loader.put("successful", false);
            return;
        }
        require(phase.path("currentChildJournalsVerified").isBoolean()
                && phase.path("currentChildJournalsVerified").booleanValue(), "T4_HTTP_RECEIPT_MISMATCH");
        JsonNode observed = phase.path("observation");
        var expected = ((ObjectNode) binding).deepCopy().put("schemaVersion", 1)
                .put("authority", "at_t4_http_owner_observed").put("scope", "t4-http-parameters-v1")
                .put("allPlannedCallsObserved", true).put("rawOutputStored", false)
                .put("formalAcceptanceIntegrated", false).put("productNodePassesAdded", 0)
                .put("publishEligible", false);
        if (observed.has("sessionMode")) {
            applyHttpSessions(loader, observed, expected, node);
            return;
        }
        require(observed.isObject() && observed.size() == expected.size() + 1
                && expected.properties().stream().allMatch(entry ->
                    entry.getValue().equals(observed.path(entry.getKey())))
                && observed.path("calls").isArray() && observed.path("calls").size() == 36,
                "T4_HTTP_RECEIPT_MISMATCH");
        var calls = mapper.createArrayNode();
        Set<String> jobs = new java.util.HashSet<>();
        List<String> methods = List.of("home", "category", "category", "detail", "search", "play");
        Set<String> fields = Set.of("ordinal", "shape", "playType", "method", "httpStatus",
                "t4CallSha256", "brokerJobId", "resultSha256", "httpResponseSha256",
                "httpResponseBytes", "workerReturnedAfterCleanup");
        int index = 0;
        for (JsonNode call : observed.path("calls")) {
            int ordinal = index++;
            require(call.isObject() && call.size() == fields.size()
                    && fields.stream().allMatch(call::has)
                    && integer(call.path("ordinal"), 36) && call.path("ordinal").intValue() == ordinal + 1
                    && call.path("shape").asText().equals(List.of("get", "form", "json").get((ordinal / 6) % 3))
                    && call.path("playType").asText().equals(ordinal < 18 ? "t4" : "t4-direct")
                    && call.path("method").asText().equals(methods.get(ordinal % 6))
                    && call.path("httpStatus").isInt() && call.path("httpStatus").intValue() == 200
                    && integer(call.path("httpResponseBytes"), 65536) && call.path("httpResponseBytes").intValue() > 0
                    && call.path("workerReturnedAfterCleanup").isBoolean()
                    && call.path("workerReturnedAfterCleanup").booleanValue()
                    && sha(call.path("t4CallSha256")) && sha(call.path("resultSha256"))
                    && sha(call.path("httpResponseSha256"))
                    && call.path("brokerJobId").isTextual()
                    && call.path("brokerJobId").textValue().matches(
                        "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                    && !call.path("brokerJobId").equals(binding.path("jobId"))
                    && jobs.add(call.path("brokerJobId").textValue()), "T4_HTTP_RECEIPT_MISMATCH");
            calls.add(call.deepCopy());
        }
        var safe = expected.deepCopy();
        safe.set("calls", calls);
        loader.set("t4HttpObservation", safe);
        node.put("status", "pass").put("reason", "T4_HTTP_FIVE_ACTION_PARAMETERS_OBSERVED")
                .put("httpCalls", 36).put("requestShapes", 3).put("playTypes", 2)
                .put("currentChildJournalsVerified", true);
        loader.put("successful", loader.path("nodes").valueStream()
                .allMatch(row -> row.path("status").asText().equals("pass")));
    }

    private void applyHttpSessions(ObjectNode loader, JsonNode observed, ObjectNode expected, ObjectNode r04)
            throws IOException {
        String failure = "T4_HTTP_RECEIPT_MISMATCH";
        require(observed.isObject() && observed.size() == expected.size() + 4
                && expected.properties().stream().allMatch(entry -> entry.getValue().equals(observed.path(entry.getKey())))
                && flag(observed, "sessionMode", true) && observed.path("calls").isArray()
                && observed.path("calls").size() == 36 && observed.path("sessionReceipts").isArray()
                && observed.path("sessionReceipts").size() == 2 && observed.path("proxyCalls").isArray()
                && observed.path("proxyCalls").size() <= 12 && observed.path("proxyCalls").size() % 2 == 0, failure);
        var actions = new java.util.HashMap<String, JsonNode>();
        var used = new java.util.HashSet<String>();
        var jobs = new java.util.HashSet<String>();
        List<String> modes = List.of("t4", "t4-direct");
        Set<String> sessionFields = new java.util.HashSet<>(Set.of(
                "brokerJobId", "runtimeSha256", "loadedTextSha256", "playType",
                "sourceDataPresent", "sourceDataSha256", "initCount", "destroyReturned", "actions", "cleanup",
                "initializationKind", "fullGeneratedExtForwarded"));
        boolean sourceIdentityPresent = loader.has("sourceRuleSha256") || loader.has("dependencySha256");
        if (sourceIdentityPresent) sessionFields.addAll(Set.of("sourceRuleSha256", "dependencySha256"));
        Set<String> actionFields = Set.of("ordinal", "method", "t4CallSha256", "paramsSha256",
                "resultSha256", "workerCallId");
        int index = 0;
        for (JsonNode session : observed.path("sessionReceipts")) {
            require(fields(session, sessionFields) && uuid(session.path("brokerJobId"))
                    && !session.path("brokerJobId").equals(expected.path("jobId"))
                    && jobs.add(session.path("brokerJobId").asText())
                    && session.path("runtimeSha256").equals(expected.path("runtimeSha256"))
                    && session.path("loadedTextSha256").equals(loader.path("loadedTextSha256"))
                    && session.path("playType").asText().equals(modes.get(index++))
                    && session.path("sourceDataPresent").isBoolean() && sha(session.path("sourceDataSha256"))
                    && session.path("sourceDataPresent").equals(loader.path("sourceDataPresent"))
                    && session.path("sourceDataSha256").equals(loader.path("sourceDataSha256"))
                    && (!sourceIdentityPresent
                        || session.path("sourceRuleSha256").equals(loader.path("sourceRuleSha256"))
                        && session.path("dependencySha256").equals(loader.path("dependencySha256")))
                    && session.path("initCount").isInt() && session.path("initCount").intValue() == 1
                    && flag(session, "destroyReturned", true)
                    && flag(session.path("cleanup"), "confirmed", true)
                    && flag(session.path("cleanup"), "containerAbsent", true)
                    && session.path("cleanup").size() == 2
                    && session.path("initializationKind").asText().equals("credential_free_local_source_projection")
                    && flag(session, "fullGeneratedExtForwarded", false)
                    && session.path("actions").isArray() && session.path("actions").size() >= 18
                    && session.path("actions").size() <= 24, failure);
            if (index == 2) {
                JsonNode first = observed.path("sessionReceipts").get(0);
                require(session.path("sourceDataPresent").equals(first.path("sourceDataPresent"))
                        && session.path("sourceDataSha256").equals(first.path("sourceDataSha256")), failure);
            }
            int ordinal = 0;
            for (JsonNode action : session.path("actions")) {
                ordinal++;
                require(fields(action, actionFields) && action.path("ordinal").isInt()
                        && action.path("ordinal").intValue() == ordinal && action.path("workerCallId").isInt()
                        && action.path("workerCallId").intValue() == ordinal + 1
                        && Set.of("home", "category", "detail", "search", "play", "proxy")
                            .contains(action.path("method").asText())
                        && sha(action.path("t4CallSha256")) && sha(action.path("paramsSha256"))
                        && sha(action.path("resultSha256")), failure);
                actions.put(session.path("brokerJobId").asText() + ":" + ordinal, action);
            }
        }
        Set<String> callFields = Set.of("ordinal", "shape", "playType", "method", "httpStatus",
                "t4CallSha256", "brokerJobId", "resultSha256", "httpResponseSha256", "httpResponseBytes",
                "workerReturnedAfterCleanup", "sessionOrdinal", "cleanupPending");
        index = 0;
        for (JsonNode call : observed.path("calls")) {
            int i = index++;
            JsonNode session = observed.path("sessionReceipts").get(i / 18);
            require(fields(call, callFields) && call.path("ordinal").isInt() && call.path("ordinal").intValue() == i + 1
                    && call.path("shape").asText().equals(List.of("get", "form", "json").get((i / 6) % 3))
                    && call.path("playType").equals(session.path("playType"))
                    && call.path("method").asText().equals(
                        List.of("home", "category", "category", "detail", "search", "play").get(i % 6))
                    && call.path("brokerJobId").equals(session.path("brokerJobId"))
                    && call.path("httpStatus").isInt() && call.path("httpStatus").intValue() == 200
                    && integer(call.path("httpResponseBytes"), 65536) && call.path("httpResponseBytes").intValue() > 0
                    && sha(call.path("httpResponseSha256")) && flag(call, "workerReturnedAfterCleanup", false)
                    && flag(call, "cleanupPending", true), failure);
            consumeSessionAction(call, call.path("method").asText(), actions, used);
        }
        Set<String> proxyFields = Set.of("ordinal", "playerOrdinal", "brokerJobId", "sessionOrdinal",
                "httpMethod", "httpStatus", "t4CallSha256", "resultSha256", "targetSha256", "responseSha256",
                "responseBytes", "contentLength", "mime", "contentRange");
        var manifestFields = new java.util.HashSet<>(proxyFields);
        manifestFields.addAll(Set.of("sourceBodySha256", "sourceBytes", "segmentTargetSha256"));
        JsonNode prior = null;
        index = 0;
        for (JsonNode proxy : observed.path("proxyCalls")) {
            boolean first = index % 2 == 0;
            boolean manifest = proxy.path("mime").asText().matches(
                    "(?i)(?:application/vnd\\.apple\\.mpegurl|application/x-mpegurl)(?:; *charset=UTF-8)?");
            boolean priorManifest = prior != null && prior.path("mime").asText().matches(
                    "(?i)(?:application/vnd\\.apple\\.mpegurl|application/x-mpegurl)(?:; *charset=UTF-8)?");
            boolean hlsSegment = !first && priorManifest;
            require(fields(proxy, manifest ? manifestFields : proxyFields) && proxy.path("ordinal").isInt()
                    && proxy.path("ordinal").intValue() == ++index
                    && integer(proxy.path("playerOrdinal"), 36) && proxy.path("playerOrdinal").intValue() > 0
                    && proxy.path("playerOrdinal").intValue() % 6 == 0
                    && proxy.path("httpStatus").isInt() && Set.of(200, 206).contains(proxy.path("httpStatus").intValue())
                    && integer(proxy.path("responseBytes"), manifest ? 524288 : 32768)
                    && integer(proxy.path("contentLength"), manifest ? 524288 : 32768)
                    && proxy.path("contentLength").intValue() > 0 && sha(proxy.path("responseSha256"))
                    && sha(proxy.path("targetSha256")) && proxy.path("contentRange").isTextual()
                    && proxy.path("mime").isTextual(), failure);
            JsonNode player = observed.path("calls").get(proxy.path("playerOrdinal").intValue() - 1);
            require(proxy.path("brokerJobId").equals(player.path("brokerJobId"))
                    && proxy.path("sessionOrdinal").isInt() && proxy.path("sessionOrdinal").intValue()
                        == player.path("sessionOrdinal").intValue() + (first ? 1 : 2), failure);
            boolean segment = proxy.path("mime").asText().matches("(?i)video/mp2t(?:; *charset=UTF-8)?");
            require(first ? prior == null
                    || proxy.path("playerOrdinal").intValue() > prior.path("playerOrdinal").intValue()
                    : proxy.path("playerOrdinal").equals(prior.path("playerOrdinal"))
                        && proxy.path("brokerJobId").equals(prior.path("brokerJobId")), failure);
            require(manifest
                    ? first && proxy.path("httpMethod").asText().equals("GET")
                        && proxy.path("httpStatus").intValue() == 200
                        && proxy.path("contentRange").asText().isEmpty()
                        && proxy.path("responseBytes").intValue() == proxy.path("contentLength").intValue()
                        && proxy.path("responseBytes").intValue() > 0
                        && integer(proxy.path("sourceBytes"), 524288) && proxy.path("sourceBytes").intValue() > 0
                        && sha(proxy.path("sourceBodySha256")) && sha(proxy.path("segmentTargetSha256"))
                    : segment && proxy.path("httpMethod").asText().equals(hlsSegment || first ? "GET" : "HEAD"),
                    failure);
            if (proxy.path("httpStatus").intValue() == 206) {
                var match = java.util.regex.Pattern.compile("bytes 0-([0-9]{1,5})/([0-9]{1,20})")
                        .matcher(proxy.path("contentRange").asText());
                require(match.matches() && Integer.parseInt(match.group(1)) + 1 == proxy.path("contentLength").intValue()
                        && new java.math.BigInteger(match.group(2)).compareTo(
                            java.math.BigInteger.valueOf(Long.parseLong(match.group(1)))) > 0, failure);
            } else require(proxy.path("contentRange").asText().isEmpty(), failure);
            if (hlsSegment) {
                require(proxy.path("httpMethod").asText().equals("GET")
                        && proxy.path("responseBytes").intValue() == proxy.path("contentLength").intValue()
                        && proxy.path("responseBytes").intValue() >= 564
                        && proxy.path("targetSha256").equals(prior.path("segmentTargetSha256")), failure);
            } else if (!first) {
                require(prior != null && proxy.path("responseBytes").intValue() == 0
                        && proxy.path("responseSha256").asText().equals(Hashes.sha256(new byte[0])), failure);
                for (String key : List.of("playerOrdinal", "brokerJobId", "targetSha256", "httpStatus",
                        "mime", "contentLength", "contentRange"))
                    require(proxy.path(key).equals(prior.path(key)), failure);
            } else if (!manifest) require(proxy.path("responseBytes").intValue() >= 564
                    && proxy.path("responseBytes").equals(proxy.path("contentLength"))
                    && (prior == null || proxy.path("playerOrdinal").intValue() > prior.path("playerOrdinal").intValue()),
                    failure);
            consumeSessionAction(proxy, "proxy", actions, used);
            prior = proxy;
        }
        require(used.equals(actions.keySet()), failure);
        for (int segment = 0; segment < 2; segment++) {
            int ordinal = 0;
            for (int i = segment * 18; i < (segment + 1) * 18; i++) {
                JsonNode call = observed.path("calls").get(i);
                require(call.path("sessionOrdinal").intValue() == ++ordinal, failure);
                for (JsonNode proxy : observed.path("proxyCalls"))
                    if (proxy.path("playerOrdinal").intValue() == i + 1)
                        require(proxy.path("sessionOrdinal").intValue() == ++ordinal, failure);
            }
        }
        loader.set("t4HttpObservation", observed.deepCopy());
        r04.put("status", "pass").put("reason", "T4_HTTP_FIVE_ACTION_PARAMETERS_OBSERVED")
                .put("httpCalls", 36).put("requestShapes", 3).put("playTypes", 2)
                .put("currentChildJournalsVerified", true);
        for (JsonNode row : loader.path("nodes")) {
            if (!row.path("id").asText().equals("R05")) continue;
            boolean proxyComplete = observed.path("proxyCalls").size() == 12;
            ((ObjectNode) row).put("status", proxyComplete ? "pass" : "not_tested")
                    .put("reason", proxyComplete ? "T4_GENERATED_EXT_SESSION_AND_PROXY_BYTES_OBSERVED"
                            : "T4_SESSION_OBSERVED_PROXY_NOT_EXERCISED")
                    .put("sessions", 2).put("proxyCalls", observed.path("proxyCalls").size())
                    .put("fullGeneratedExtForwarded", false).put("clientPlaybackVerified", false);
        }
        loader.put("successful", loader.path("nodes").valueStream()
                .allMatch(row -> row.path("status").asText().equals("pass")));
    }

    private void consumeSessionAction(JsonNode call, String method, Map<String, JsonNode> actions, Set<String> used)
            throws IOException {
        String key = call.path("brokerJobId").asText() + ":" + call.path("sessionOrdinal").asText();
        JsonNode action = actions.get(key);
        require(integer(call.path("sessionOrdinal"), 24) && call.path("sessionOrdinal").intValue() > 0
                && action != null && used.add(key) && action.path("method").asText().equals(method)
                && action.path("t4CallSha256").equals(call.path("t4CallSha256"))
                && action.path("resultSha256").equals(call.path("resultSha256")), "T4_HTTP_RECEIPT_MISMATCH");
    }

    private static boolean fields(JsonNode value, Set<String> names) {
        return value.isObject() && value.size() == names.size() && names.stream().allMatch(value::has);
    }
    private static boolean uuid(JsonNode value) {
        return value.isTextual() && value.asText().matches(
                "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }
    private static boolean flag(JsonNode value, String key, boolean expected) {
        return value.path(key).isBoolean() && value.path(key).booleanValue() == expected;
    }

    ObjectNode project(JsonNode input, JsonNode profile, String sourceSha, String textSha, String workerSha)
            throws IOException {
        JsonNode data = SourceConfig.read(profile);
        JsonNode staticData = PublicSourceData.read(profile);
        require(input.path("schemaVersion").isInt() && input.path("schemaVersion").intValue() == 1
                && input.path("authority").asText().equals("host_loader_contract_observed")
                && input.path("scope").asText().equals(SCOPE)
                && input.path("requirementsFrozen").isBoolean() && input.path("requirementsFrozen").booleanValue()
                && input.path("inputProfileSha256").asText().equals(Hashes.sha256(profile.toString()))
                && input.path("inputSourceSha256").asText().equals(sourceSha)
                && input.path("runtimeSha256").asText().equals(workerSha)
                && sha(input.path("profileSha256"))
                && flag(input, "sourceDataPresent", !data.isEmpty())
                && input.path("sourceDataSha256").asText().equals(SourceConfig.sha256(data))
                && profile.path("requiredNodes").isArray() && !profile.path("requiredNodes").isEmpty(),
                "MANAGED_LOADER_IDENTITY_MISMATCH");
        boolean sourceIdentityPresent = staticData.has("sourceRuleSha256") || staticData.has("dependencySha256");
        if (sourceIdentityPresent) {
            require(staticData.has("sourceRuleSha256") && staticData.has("dependencySha256")
                    && sha(staticData.path("sourceRuleSha256")) && sha(staticData.path("dependencySha256"))
                    && input.path("sourceRuleSha256").asText().equals(staticData.path("sourceRuleSha256").asText())
                    && input.path("dependencySha256").asText().equals(staticData.path("dependencySha256").asText()),
                    "MANAGED_LOADER_IDENTITY_MISMATCH");
        }
        var required = new LinkedHashSet<String>();
        required.add("S06");
        for (JsonNode node : profile.path("requiredNodes")) {
            require(node.isTextual() && node.textValue().matches("S0[1-8]|C0[1-9]|C1[01]|R0[1-9]|R10|M0[1-9]|M10|P0[1-5]"),
                    "MANAGED_LOADER_IDENTITY_MISMATCH");
            required.add(node.textValue());
        }
        require(input.path("requiredNodes").equals(mapper.valueToTree(required))
                && input.path("nodes").isArray() && input.path("nodes").size() == required.size(),
                "MANAGED_LOADER_IDENTITY_MISMATCH");
        var safe = mapper.createObjectNode().put("schemaVersion", 1).put("authority", "host_loader_contract_observed")
                .put("projection", PROJECTION).put("scope", SCOPE).put("requirementsFrozen", true)
                .put("inputSourceSha256", sourceSha).put("runtimeSha256", workerSha)
                .put("inputProfileSha256", Hashes.sha256(profile.toString()))
                .put("profileSha256", input.path("profileSha256").asText())
                .put("sourceDataPresent", !data.isEmpty()).put("sourceDataSha256", SourceConfig.sha256(data))
                .put("businessVerdictTrusted", false).put("publishEligible", false);
        safe.set("requiredNodes", input.path("requiredNodes").deepCopy());
        var nodes = safe.putArray("nodes");
        int index = 0;
        boolean allPassed = true, loaded = false;
        for (String id : required) {
            JsonNode row = input.path("nodes").get(index++);
            String status = row.path("status").asText(), reason = row.path("reason").asText();
            require(row.path("id").asText().equals(id)
                    && Set.of("pass", "fail", "blocked", "not_tested").contains(status),
                    "MANAGED_LOADER_RECEIPT_INVALID");
            boolean passed = status.equals("pass");
            require(!passed || PASSES.getOrDefault(id, Set.of()).contains(reason),
                    "MANAGED_LOADER_RECEIPT_INVALID");
            if (passed && reason.equals("SELECTED_PLAY_ID_TO_AT_OFFLINE_CONTRACT"))
                require(id.equals("R01") && row.path("playerRouteKind").asText().equals("magnet")
                        && flag(row, "magnetInfoHashValid", true)
                        && flag(row, "offlineResolverRequired", true)
                        && flag(row, "offlineDownloadVerified", false)
                        && flag(row, "clientPlaybackVerified", false) && sha(row.path("playerUrlSha256")),
                        "MANAGED_LOADER_RECEIPT_INVALID");
            if (passed && id.equals("C03")) {
                boolean window = profile.path("paginationMode").asText("exact-total").equals("page-window");
                require(reason.equals(window ? "FIRST_SECOND_PAGE_WINDOW_CONTRACT" : "FIRST_SECOND_LAST_PAGE_CONTRACT"),
                        "MANAGED_LOADER_RECEIPT_INVALID");
                if (window) require(row.path("paginationMode").asText().equals("page-window")
                        && flag(row, "exactTotalVerified", false) && flag(row, "lastPageVerified", false)
                        && integer(row.path("pagesObserved"), 2) && row.path("pagesObserved").intValue() >= 1,
                        "MANAGED_LOADER_RECEIPT_INVALID");
            }
            if (passed && Set.of("C02", "C05").contains(id)) {
                boolean recommendation = id.equals("C02");
                String callId = recommendation ? "homeCallId" : "searchCallId";
                require((recommendation || sha(row.path("keywordSha256"))) && row.path("items").asInt() > 0
                        && row.has("identityEvidence")
                        && integer(row.path(callId), 32) && row.path(callId).asInt() > 0
                        && integer(row.path("detailCallId"), 32)
                        && row.path("detailCallId").asInt() > row.path(callId).asInt()
                        && input.path("calls").valueStream().anyMatch(call ->
                            call.path("id").equals(row.path(callId))
                                && call.path("method").asText().equals(recommendation ? "home" : "search")
                                && flag(call, "ok", true))
                        && input.path("calls").valueStream().anyMatch(call ->
                            call.path("id").equals(row.path("detailCallId"))
                                && call.path("method").asText().equals("detail") && flag(call, "ok", true)),
                        "MANAGED_LOADER_RECEIPT_INVALID");
            }
            var node = nodes.addObject().put("id", id).put("status", status)
                    .put("reason", passed ? reason : safeReason(reason));
            if (row.has("failureType")) {
                require(!passed && Set.of("LOADER_INIT_FAILED", "LOADER_METHOD_FAILED").contains(reason)
                        && FAILURE_TYPES.contains(row.path("failureType").asText()),
                        "MANAGED_LOADER_RECEIPT_INVALID");
                node.put("failureType", row.path("failureType").asText());
            }
            if (row.has("playbackContract")) {
                JsonNode contract = row.path("playbackContract");
                require(id.equals("C10") && !passed
                        && Set.of("PLAY_GROUPS_INVALID", "PLAY_EPISODE_INVALID").contains(reason)
                        && contract.isObject() && integer(contract.path("detailCallId"), 32)
                        && contract.path("detailCallId").asInt() > 0
                        && input.path("calls").valueStream().anyMatch(call ->
                            call.path("id").equals(contract.path("detailCallId"))
                                && "detail".equals(call.path("method").asText()) && flag(call, "ok", true)),
                        "MANAGED_LOADER_RECEIPT_INVALID");
                node.set("playbackContract", contractObservations(mapper, row).path("playbackContract"));
            }
            if (row.has("playerContract")) {
                var contract = playerContractObservations(mapper, row.path("playerContract"));
                require(id.equals("R01") && row.path("playerContract").isObject() && contract.size() == 12
                        && integer(contract.path("detailCallId"), 32) && contract.path("detailCallId").asInt() > 0
                        && integer(contract.path("playCallId"), 32)
                        && contract.path("playCallId").asInt() > contract.path("detailCallId").asInt()
                        && input.path("calls").valueStream().anyMatch(call ->
                            call.path("id").equals(contract.path("detailCallId"))
                                && "detail".equals(call.path("method").asText()) && flag(call, "ok", true))
                        && input.path("calls").valueStream().anyMatch(call ->
                            call.path("id").equals(contract.path("playCallId"))
                                && "play".equals(call.path("method").asText()) && flag(call, "ok", true)),
                        "MANAGED_LOADER_RECEIPT_INVALID");
                node.set("playerContract", contract);
            }
            if (passed && reason.equals("FIRST_SECOND_PAGE_WINDOW_CONTRACT"))
                node.put("paginationMode", "page-window");
            allPassed &= passed;
            loaded |= id.equals("S06") && passed;
            for (String field : List.of("classes", "firstItems", "secondItems", "lastItems", "pages", "items",
                    "groups", "episodes", "sampledPlayers", "sampledVariants", "segments", "width", "height",
                    "bytes", "mediaSequence", "elapsedMillis", "byteLimit", "timeLimitMillis", "rejectedRequests",
                    "importStatements", "pagesObserved", "homeCallId", "searchCallId", "detailCallId")) {
                if (!row.has(field)) continue;
                require(integer(row.path(field), 40_000_000), "MANAGED_LOADER_RECEIPT_INVALID");
                node.set(field, row.path(field).deepCopy());
            }
            for (String field : List.of("normalCleanupConfirmed", "strictFrameParsing", "protocolChecksPassed",
                    "fullSegments", "clientPlaybackVerified", "endList", "dynamicImportCoverage",
                    "generatedRawExtObserved", "userExtRequired", "credentialUse", "persistentCacheConfigured",
                    "exactTotalVerified", "lastPageVerified", "pageNumbersVerified", "offlineDownloadVerified",
                    "keyBytesPersisted")) {
                if (!row.has(field)) continue;
                require(row.path(field).isBoolean(), "MANAGED_LOADER_RECEIPT_INVALID");
                node.set(field, row.path(field).deepCopy());
            }
            for (String field : List.of("responseSha256", "playlistSha256", "keywordSha256")) {
                if (!row.has(field)) continue;
                require(sha(row.path(field)), "MANAGED_LOADER_RECEIPT_INVALID");
                node.set(field, row.path(field).deepCopy());
            }
            if (row.has("identityEvidence")) {
                JsonNode identity = row.path("identityEvidence");
                require(Set.of("C02", "C05", "C07").contains(id) && fields(identity, Set.of("selectedIdSha256", "returnedIdSha256",
                        "exactMatch", "guidCoverMatch", "atvpDetailPrefixMatch",
                        "nameMatch", "coverMatch")) && sha(identity.path("selectedIdSha256"))
                        && sha(identity.path("returnedIdSha256")), "MANAGED_LOADER_RECEIPT_INVALID");
                for (String key : List.of("exactMatch", "guidCoverMatch", "atvpDetailPrefixMatch",
                        "nameMatch", "coverMatch"))
                    require(identity.path(key).isBoolean(), "MANAGED_LOADER_RECEIPT_INVALID");
                node.set("identityEvidence", identity.deepCopy());
            }
            if (row.has("playerRouteKind")) {
                require(id.equals("R01") && Set.of("absent", "http", "https", "magnet", "other")
                        .contains(row.path("playerRouteKind").asText())
                        && row.path("offlineResolverRequired").isBoolean()
                        && row.path("magnetInfoHashValid").isBoolean()
                        && (row.path("playerRouteKind").asText().equals("absent")
                            ? !row.has("playerUrlSha256") : sha(row.path("playerUrlSha256"))),
                        "MANAGED_LOADER_RECEIPT_INVALID");
                for (String key : List.of("playerRouteKind", "offlineResolverRequired", "magnetInfoHashValid",
                        "playerUrlSha256"))
                    if (row.has(key)) node.set(key, row.path(key).deepCopy());
            }
            if (row.has("proxyObservation")) {
                JsonNode proxy = row.path("proxyObservation");
                require(id.equals("C09") && passed
                        && fields(proxy, Set.of("workerCallId", "paramsSha256", "resultSha256", "targetSha256"))
                        && integer(proxy.path("workerCallId"), 32) && proxy.path("workerCallId").asInt() > 0
                        && sha(proxy.path("paramsSha256")) && sha(proxy.path("resultSha256"))
                        && sha(proxy.path("targetSha256"))
                        && input.path("calls").valueStream().anyMatch(call ->
                            call.path("id").equals(proxy.path("workerCallId"))
                            && call.path("method").asText().equals("proxy") && flag(call, "ok", true)
                            && call.path("inputSha256").equals(proxy.path("paramsSha256"))
                            && call.path("outputSha256").equals(proxy.path("resultSha256"))),
                        "MANAGED_LOADER_RECEIPT_INVALID");
                node.set("proxyObservation", proxy.deepCopy());
            }
            if (row.has("format")) {
                require(Set.of("hls", "mp4", "mpeg_ts").contains(row.path("format").asText()),
                        "MANAGED_LOADER_RECEIPT_INVALID");
                node.set("format", row.path("format").deepCopy());
            }
            if (row.has("samples")) {
                require(row.path("samples").isArray() && row.path("samples").size() <= 3,
                        "MANAGED_LOADER_RECEIPT_INVALID");
                var samples = node.putArray("samples");
                for (JsonNode sample : row.path("samples")) {
                    require(integer(sample.path("index"), 1000000) && integer(sample.path("bytes"), 65536)
                            && sample.path("bytes").intValue() > 0 && integer(sample.path("status"), 206)
                            && Set.of(200, 206).contains(sample.path("status").intValue())
                            && sha(sample.path("responseSha256")), "MANAGED_LOADER_RECEIPT_INVALID");
                    var item = samples.addObject();
                    for (String key : List.of("index", "bytes", "status", "responseSha256"))
                        item.set(key, sample.path(key).deepCopy());
                    if (id.equals("M06") || sample.has("start") || sample.has("end")) {
                        require(offset(sample.path("start")) && offset(sample.path("end"))
                                && sample.path("end").longValue() >= sample.path("start").longValue()
                                && sample.path("end").longValue() - sample.path("start").longValue() + 1
                                    == sample.path("bytes").intValue()
                                && sample.path("status").intValue() == 206,
                                "MANAGED_LOADER_RECEIPT_INVALID");
                        item.set("start", sample.path("start").deepCopy());
                        item.set("end", sample.path("end").deepCopy());
                    }
                    if (sample.has("container")) {
                        require(Set.of("M05", "M07").contains(id)
                                && Set.of("mpeg_ts", "fmp4").contains(sample.path("container").asText())
                                && integer(sample.path("clearBytes"), 65536) && sample.path("clearBytes").intValue() > 0
                                && sha(sample.path("clearSha256")), "MANAGED_LOADER_RECEIPT_INVALID");
                        for (String key : List.of("container", "clearBytes", "clearSha256"))
                            item.set(key, sample.path(key).deepCopy());
                    }
                    if (sample.has("encryption")) {
                        JsonNode encryption = sample.path("encryption");
                        require(Set.of("M05", "M07").contains(id)
                                && encryption.path("method").asText().equals("AES-128")
                                && integer(encryption.path("keyDeclaration"), 1000000)
                                && encryption.path("keyDeclaration").intValue() > 0
                                && Set.of("explicit", "media_sequence").contains(encryption.path("ivSource").asText())
                                && encryption.path("prefixOnly").isBoolean()
                                && encryption.path("paddingChecked").isBoolean()
                                && encryption.path("prefixOnly").booleanValue() != encryption.path("paddingChecked").booleanValue()
                                && flag(encryption, "authenticated", false), "MANAGED_LOADER_RECEIPT_INVALID");
                        var encryptionProjection = item.putObject("encryption");
                        for (String key : List.of("method", "keyDeclaration", "ivSource", "prefixOnly", "paddingChecked", "authenticated"))
                            encryptionProjection.set(key, encryption.path(key).deepCopy());
                    }
                }
            }
            if (row.has("resources")) {
                require(id.equals("M04") && row.path("resources").isArray() && row.path("resources").size() <= 15
                        && flag(row, "keyBytesPersisted", false), "MANAGED_LOADER_RECEIPT_INVALID");
                var resources = node.putArray("resources");
                for (JsonNode resource : row.path("resources")) {
                    String kind = resource.path("kind").asText();
                    require(Set.of("key", "init").contains(kind)
                            && integer(resource.path("declaration"), 1000000) && resource.path("declaration").intValue() > 0
                            && integer(resource.path("bytes"), 65536) && resource.path("bytes").intValue() > 0
                            && (!kind.equals("key") || resource.path("bytes").intValue() == 16)
                            && Set.of(200, 206).contains(resource.path("status").intValue())
                            && sha(resource.path("urlSha256")) && sha(resource.path("responseSha256")),
                            "MANAGED_LOADER_RECEIPT_INVALID");
                    var resourceProjection = resources.addObject();
                    for (String key : List.of("kind", "declaration", "bytes", "status", "urlSha256", "responseSha256"))
                        resourceProjection.set(key, resource.path(key).deepCopy());
                    if (resource.has("start") || resource.has("end")) {
                        require(kind.equals("init") && resource.path("status").intValue() == 206
                                && offset(resource.path("start")) && offset(resource.path("end"))
                                && resource.path("end").longValue() >= resource.path("start").longValue()
                                && resource.path("end").longValue() - resource.path("start").longValue() + 1
                                    == resource.path("bytes").intValue(), "MANAGED_LOADER_RECEIPT_INVALID");
                        resourceProjection.set("start", resource.path("start").deepCopy());
                        resourceProjection.set("end", resource.path("end").deepCopy());
                    }
                }
            }
            if (id.equals("M06")) {
                if (row.has("totalBytes")) {
                    require(offset(row.path("totalBytes")) && row.path("totalBytes").longValue() > 0,
                            "MANAGED_LOADER_RECEIPT_INVALID");
                    node.set("totalBytes", row.path("totalBytes").deepCopy());
                }
                if (passed) {
                    require(row.has("totalBytes") && row.path("format").asText().equals("mp4")
                            && flag(row, "clientPlaybackVerified", false), "MANAGED_LOADER_RECEIPT_INVALID");
                    long total = row.path("totalBytes").longValue();
                    var starts = new LinkedHashSet<Long>();
                    starts.add(0L);
                    starts.add(Math.max(0, (total - 65536) / 2));
                    starts.add(Math.max(0, total - 65536));
                    require(row.path("samples").size() == starts.size(), "MANAGED_LOADER_RECEIPT_INVALID");
                    int ordinal = 0;
                    for (long start : starts) {
                        JsonNode sample = row.path("samples").get(ordinal);
                        require(sample.path("index").intValue() == ordinal++
                                && sample.path("start").longValue() == start
                                && sample.path("end").longValue() == start + Math.min(65536, total - start) - 1,
                                "MANAGED_LOADER_RECEIPT_INVALID");
                    }
                }
            }
            if (passed && id.equals("S08")) {
                JsonNode workers = row.path("workerContainerIds");
                require(workers.isArray() && workers.size() == 2 && sha(workers.get(0)) && sha(workers.get(1))
                        && !workers.get(0).equals(workers.get(1)) && sha(row.path("hotHomeCategoriesSha256"))
                        && row.path("persistentCacheConfigured").isBoolean()
                        && !row.path("persistentCacheConfigured").booleanValue(), "MANAGED_LOADER_RECEIPT_INVALID");
                node.set("workerContainerIds", workers.deepCopy());
                node.set("hotHomeCategoriesSha256", row.path("hotHomeCategoriesSha256").deepCopy());
            }
            if (passed && Set.of("S07", "R09").contains(id)) {
                JsonNode scenarios = row.path("scenarios");
                require(scenarios.isArray() && scenarios.size() == 3, "MANAGED_LOADER_RECEIPT_INVALID");
                Set<String> identifiers = new java.util.HashSet<>();
                var values = node.putArray("scenarios");
                int scenarioIndex = 0;
                for (String scenario : List.of("cancel", "timeout", "exit")) {
                    JsonNode entry = scenarios.get(scenarioIndex++);
                    var codes = switch (scenario) {
                        case "cancel" -> Set.of("MANAGED_CANCELLED");
                        case "timeout" -> Set.of("ENGINE_TIMEOUT");
                        default -> Set.of("EGRESS_PROTOCOL_INVALID", "CONTAINER_EXECUTION_FAILED");
                    };
                    require(entry.path("scenario").asText().equals(scenario)
                            && codes.contains(entry.path("code").asText()) && sha(entry.path("containerId"))
                            && identifiers.add(entry.path("containerId").asText())
                            && integer(entry.path("httpCount"), 24) && entry.path("httpCount").intValue() > 0
                            && entry.path("cleanup").path("confirmed").isBoolean()
                            && entry.path("cleanup").path("confirmed").booleanValue()
                            && entry.path("cleanup").path("containerAbsent").isBoolean()
                            && entry.path("cleanup").path("containerAbsent").booleanValue(),
                            "MANAGED_LOADER_RECEIPT_INVALID");
                    var item = values.addObject();
                    for (String key : List.of("scenario", "code", "containerId", "httpCount", "cleanup"))
                        item.set(key, entry.path(key).deepCopy());
                }
                if (id.equals("S07"))
                    require(row.path("strictFrameParsing").asBoolean() && row.path("protocolChecksPassed").asBoolean()
                            && row.path("rejectedRequests").asInt() == 3, "MANAGED_LOADER_RECEIPT_INVALID");
                else require(row.path("normalCleanupConfirmed").asBoolean(), "MANAGED_LOADER_RECEIPT_INVALID");
            }
        }
        require(!loaded || input.path("loadedTextSha256").asText().equals(textSha),
                "MANAGED_LOADER_IDENTITY_MISMATCH");
        safe.put("loadedTextSha256", loaded ? textSha : null);
        if (sourceIdentityPresent) {
            safe.put("sourceRuleSha256", input.path("sourceRuleSha256").asText())
                    .put("dependencySha256", input.path("dependencySha256").asText());
        }
        JsonNode calls = input.path("calls");
        require(calls.isArray() && !calls.isEmpty() && calls.size() <= 32, "MANAGED_LOADER_RECEIPT_INVALID");
        var safeCalls = safe.putArray("calls");
        int ordinal = 0;
        for (JsonNode call : calls) {
            require(integer(call.path("id"), 32) && call.path("id").intValue() == ++ordinal
                    && Set.of("init", "home", "category", "search", "detail", "play", "proxy", "destroy", "ping", "",
                            "loader_unknown_method").contains(call.path("method").asText())
                    && call.path("ok").isBoolean() && sha(call.path("inputSha256")) && sha(call.path("outputSha256"))
                    && integer(call.path("elapsedMillis"), 120000), "MANAGED_LOADER_RECEIPT_INVALID");
            var value = safeCalls.addObject();
            for (String key : List.of("id", "method", "ok", "inputSha256", "outputSha256", "elapsedMillis"))
                value.set(key, call.path(key).deepCopy());
            if (call.has("failureType")) {
                require(!call.path("ok").asBoolean()
                        && FAILURE_TYPES.contains(call.path("failureType").asText()),
                        "MANAGED_LOADER_RECEIPT_INVALID");
                value.put("failureType", call.path("failureType").asText());
            }
        }
        String callsSha = Hashes.sha256(mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .without(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(mapper.convertValue(safeCalls, Object.class)));
        require(input.path("callsSha256").asText().equals(callsSha)
                && (!loaded || calls.get(0).path("method").asText().equals("init")
                        && calls.get(0).path("ok").booleanValue()), "MANAGED_LOADER_RECEIPT_INVALID");
        safe.put("callsSha256", callsSha).put("successful", allPassed);
        return safe;
    }

    private boolean sha(JsonNode value) { return value.isTextual() && value.textValue().matches("[a-f0-9]{64}"); }
    private boolean offset(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
    }
    private static boolean integer(JsonNode value, int max) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0 && value.intValue() <= max;
    }
    private static void require(boolean condition, String code) throws IOException {
        if (!condition) throw new IOException(code);
    }
}
