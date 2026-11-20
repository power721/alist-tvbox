package cn.har01d.alist_tvbox.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Trusted in-process HTTP observation, not an import endpoint or a node-pass issuer.
 * The caller opens a step before HTTP; only the bound worker can supply its broker delivery.
 */
public final class ManagedT4HttpObservation implements AutoCloseable {
    private static final List<String> ACTIONS = List.of("home", "category", "category", "detail", "search", "play");
    private static final List<String> SHAPES = List.of("get", "form", "json");
    private static final List<String> MODES = List.of("t4", "t4-direct");
    private final ObjectMapper mapper;
    private final ObjectNode binding;
    private final com.fasterxml.jackson.databind.node.ArrayNode rows;
    private final boolean sessionMode;
    private final com.fasterxml.jackson.databind.node.ArrayNode sessionReceipts;
    private final Set<String> sessionJobs = new HashSet<>();
    private final Set<String> jobs = new HashSet<>();
    private final com.fasterxml.jackson.databind.node.ArrayNode activeActions;
    private final com.fasterxml.jackson.databind.node.ArrayNode proxyCalls;
    private JsonNode expectedCall, returned;
    private String callSha, childJob, resultSha;
    private String activeSessionJob;
    private String activeMode, activeDataSha;
    private boolean activeDataPresent;
    private int deliveredOrdinal;
    private JsonNode proxyResult;
    private String proxyCallSha, proxyResultSha, proxyTargetSha;
    private String playerTargetSha;
    private String proxyKind;
    private final Set<String> manifestTargetShas = new HashSet<>();
    private String rewrittenSha, selectedSegmentSha;
    private int rewrittenBytes;
    private int activeSessionOrdinal;
    private boolean dispatched, failed, closed;

    ManagedT4HttpObservation(ObjectMapper mapper, JsonNode configuration, String jobId, String profileJson)
            throws IOException {
        this(mapper, configuration, jobId, profileJson, false);
    }

    ManagedT4HttpObservation(ObjectMapper mapper, JsonNode configuration, String jobId, String profileJson,
                             boolean sessionMode)
            throws IOException {
        this.mapper = mapper.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
        this.sessionMode = sessionMode;
        require(uuid(jobId)
                && profileJson != null && profileJson.getBytes(StandardCharsets.UTF_8).length <= 65536,
                "T4_HTTP_OBSERVATION_INPUT_INVALID");
        JsonNode profile = this.mapper.readTree(profileJson);
        require(profile != null && profile.isObject() && profile.path("schemaVersion").isInt()
                && profile.path("schemaVersion").intValue() == 1
                && profile.path("runtime").asText().equals("t4")
                && profile.path("runtimeRevision").equals(configuration.path("runtimeSha256"))
                && profile.path("requiredNodes").isArray()
                && profile.path("requiredNodes").valueStream().anyMatch(node -> node.asText().equals("R04"))
                && (!profile.hasNonNull("extFingerprint")), "T4_HTTP_OBSERVATION_PROFILE_INVALID");
        binding = this.mapper.createObjectNode().put("schemaVersion", 1)
                .put("authority", "at_t4_http_owner_observed").put("scope", "t4-http-parameters-v1")
                .put("jobId", jobId).put("inputProfileSha256", sha(profileJson.getBytes(StandardCharsets.UTF_8)));
        for (String key : List.of("instanceId", "imageId", "bundleSha256", "runtimeSha256",
                "executionPolicySha256", "egressPolicySha256", "sourceSha256")) {
            require(configuration.path(key).isTextual(), "T4_HTTP_OBSERVATION_INPUT_INVALID");
            binding.set(key, configuration.path(key).deepCopy());
        }
        rows = this.mapper.createArrayNode();
        sessionReceipts = this.mapper.createArrayNode();
        activeActions = this.mapper.createArrayNode();
        proxyCalls = this.mapper.createArrayNode();
    }

    public synchronized void begin(String shape, JsonNode call) throws IOException {
        try {
            require(!closed && !failed && expectedCall == null && proxyResult == null
                    && proxyCalls.size() % 2 == 0 && rows.size() < 36,
                    "T4_HTTP_OBSERVATION_ORDER_INVALID");
            int index = rows.size();
            require(!sessionMode || index % 18 != 0 || activeSessionJob == null,
                    "T4_HTTP_OBSERVATION_SESSION_MISMATCH");
            require(SHAPES.get((index / 6) % 3).equals(shape) && call != null && call.isObject()
                    && ACTIONS.get(index % 6).equals(call.path("method").asText())
                    && MODES.get(index / 18).equals(call.path("playType").asText()),
                    "T4_HTTP_OBSERVATION_PLAN_MISMATCH");
            expectedCall = call.deepCopy();
            if (call.path("method").asText().equals("play")) playerTargetSha = null;
            callSha = sha(ManagedT4BrokerClient.canonical(expectedCall).getBytes(StandardCharsets.US_ASCII));
            dispatched = false;
        } catch (IOException | RuntimeException error) {
            fail();
            throw new IOException("T4_HTTP_OBSERVATION_PLAN_MISMATCH");
        }
    }

    String parentJobId() { return binding.path("jobId").asText(); }
    String profileSha256() { return binding.path("inputProfileSha256").asText(); }
    boolean sessionMode() { return sessionMode; }

    synchronized void dispatch(String httpMethod, String contentType, JsonNode call) throws IOException {
        require(!closed && !failed && expectedCall != null && !dispatched,
                "T4_HTTP_OBSERVATION_ORDER_INVALID");
        String shape = "";
        if ("GET".equals(httpMethod)) shape = "get";
        else if ("POST".equals(httpMethod) && contentType != null) {
            try {
                MediaType type = MediaType.parseMediaType(contentType);
                if (type.getType().equals("application") && type.getSubtype().equals("json")) shape = "json";
                else if (type.getType().equals("application") && type.getSubtype().equals("x-www-form-urlencoded"))
                    shape = "form";
            } catch (IllegalArgumentException ignored) { }
        }
        if (!SHAPES.get((rows.size() / 6) % 3).equals(shape) || !expectedCall.equals(call)) {
            fail();
            throw new IOException("T4_HTTP_OBSERVATION_DISPATCH_MISMATCH");
        }
        dispatched = true;
    }

    synchronized void receive(JsonNode delivery) throws IOException {
        try {
            require(!closed && !failed && dispatched && returned == null
                    && delivery.path("schemaVersion").isInt() && delivery.path("schemaVersion").intValue() == 1
                    && Set.of("host_t4_rpc_transport_observed", "host_t4_session_observed")
                        .contains(delivery.path("authority").asText())
                    && (!sessionMode || delivery.path("authority").asText().equals("host_t4_session_observed"))
                    && (sessionMode || delivery.path("authority").asText().equals("host_t4_rpc_transport_observed"))
                    && delivery.path("sourceSha256").equals(binding.path("sourceSha256"))
                    && delivery.path("runtimeSha256").equals(binding.path("runtimeSha256"))
                    && delivery.path("parentJobId").equals(binding.path("jobId"))
                    && delivery.path("parentProfileSha256").equals(binding.path("inputProfileSha256"))
                    && delivery.path("t4CallSha256").asText().equals(callSha)
                    && delivery.path("resultSha256").asText().matches("[a-f0-9]{64}")
                    && delivery.path("result").isObject()
                    && delivery.path("businessChainVerified").isBoolean()
                    && !delivery.path("businessChainVerified").booleanValue()
                    && delivery.path("publishEligible").isBoolean() && !delivery.path("publishEligible").booleanValue(),
                    "T4_HTTP_OBSERVATION_DELIVERY_MISMATCH");
            childJob = delivery.path("jobId").asText();
            require(uuid(childJob) && !childJob.equals(binding.path("jobId").asText()),
                    "T4_HTTP_OBSERVATION_DELIVERY_MISMATCH");
            if (sessionMode) {
                receiveSessionAction(delivery, expectedCall);
                deliveredOrdinal = activeSessionOrdinal;
            } else {
                require(jobs.add(childJob), "T4_HTTP_OBSERVATION_DELIVERY_MISMATCH");
            }
            returned = delivery.path("result").deepCopy();
            resultSha = delivery.path("resultSha256").asText();
        } catch (IOException | RuntimeException error) {
            fail();
            throw new IOException("T4_HTTP_OBSERVATION_DELIVERY_MISMATCH");
        }
    }

    private void receiveSessionAction(JsonNode delivery, JsonNode call) throws IOException {
        String job = delivery.path("jobId").asText();
        String dataSha = sha(ManagedT4BrokerClient.canonical(call.has("sourceData")
                ? call.get("sourceData") : mapper.createObjectNode()).getBytes(StandardCharsets.US_ASCII));
        require(uuid(job) && !job.equals(parentJobId()) && delivery.path("ordinal").isInt()
                && delivery.path("ordinal").intValue() == activeSessionOrdinal + 1
                && activeSessionOrdinal < 24
                && delivery.path("method").equals(call.path("method"))
                && delivery.path("workerCallId").isInt()
                && delivery.path("workerCallId").intValue() == activeSessionOrdinal + 2
                && delivery.path("cleanupPending").isBoolean() && delivery.path("cleanupPending").booleanValue()
                && delivery.path("paramsSha256").asText().equals(sha(ManagedT4BrokerClient.canonical(
                    call.path("params")).getBytes(StandardCharsets.US_ASCII)))
                && (activeSessionJob == null ? !sessionJobs.contains(job)
                    : activeSessionJob.equals(job) && activeMode.equals(call.path("playType").asText())
                        && activeDataPresent == call.has("sourceData") && activeDataSha.equals(dataSha)),
                "T4_HTTP_OBSERVATION_SESSION_MISMATCH");
        activeSessionJob = job;
        activeMode = call.path("playType").asText();
        activeDataSha = dataSha;
        activeDataPresent = call.has("sourceData");
        activeSessionOrdinal++;
        sessionJobs.add(job);
        var action = activeActions.addObject();
        for (String key : List.of("ordinal", "method", "t4CallSha256", "paramsSha256",
                "resultSha256", "workerCallId")) action.set(key, delivery.path(key).deepCopy());
    }

    synchronized void rewriteProxyResult(JsonNode httpResult, String target) throws IOException {
        require(sessionMode && returned != null && expectedCall.path("method").asText().equals("play")
                && returned.path("url").asText().equals("http://managed.invalid" + target)
                && httpResult.isObject(), "T4_HTTP_OBSERVATION_PROXY_MISMATCH");
        var original = ((ObjectNode) returned).deepCopy();
        original.set("url", httpResult.path("url"));
        require(original.equals(httpResult), "T4_HTTP_OBSERVATION_PROXY_MISMATCH");
        returned = httpResult.deepCopy();
        playerTargetSha = sha(target.getBytes(StandardCharsets.UTF_8));
    }

    synchronized void receiveProxy(JsonNode delivery, JsonNode call) throws IOException {
        try {
            boolean first = proxyCalls.size() % 2 == 0;
            String target = call.path("params").path("target").asText();
            String targetSha = sha(target.getBytes(StandardCharsets.UTF_8));
            String mime = delivery.path("result").path("mime").asText();
            String mediaType = mime.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
            boolean manifest = Set.of("application/vnd.apple.mpegurl", "application/x-mpegurl")
                    .contains(mediaType);
            boolean segment = "video/mp2t".equals(mediaType);
            require(sessionMode && !closed && !failed && expectedCall == null && proxyResult == null
                    && rows.size() > 0 && rows.size() % 6 == 0
                    && delivery.path("authority").asText().equals("host_t4_session_observed")
                    && delivery.path("parentJobId").equals(binding.path("jobId"))
                    && delivery.path("parentProfileSha256").equals(binding.path("inputProfileSha256"))
                    && delivery.path("sourceSha256").equals(binding.path("sourceSha256"))
                    && delivery.path("runtimeSha256").equals(binding.path("runtimeSha256"))
                    && delivery.path("businessChainVerified").isBoolean()
                    && !delivery.path("businessChainVerified").booleanValue()
                    && delivery.path("publishEligible").isBoolean() && !delivery.path("publishEligible").booleanValue()
                    && delivery.path("result").isObject()
                    && delivery.path("resultSha256").asText().matches("[a-f0-9]{64}")
                    && call.path("method").asText().equals("proxy")
                    && delivery.path("t4CallSha256").asText().equals(sha(ManagedT4BrokerClient.canonical(call)
                        .getBytes(StandardCharsets.US_ASCII)))
                    && call.path("params").path("head").isBoolean()
                    && (first
                        ? targetSha.equals(playerTargetSha) && !call.path("params").path("head").booleanValue()
                            && ((manifest && Set.of("", "bytes=0-32767")
                                    .contains(call.path("params").path("range").asText()))
                                || (segment && call.path("params").path("range").asText()
                                    .equals("bytes=0-32767")))
                        : ("manifest".equals(proxyKind)
                            ? !call.path("params").path("head").booleanValue() && segment
                                && call.path("params").path("range").asText().equals("bytes=0-32767")
                                && targetSha.equals(selectedSegmentSha)
                            : "direct".equals(proxyKind)
                                && call.path("params").path("head").booleanValue() && segment
                                && call.path("params").path("range").asText().equals("bytes=0-32767")
                                && targetSha.equals(proxyCalls.get(proxyCalls.size() - 1)
                                    .path("targetSha256").asText())))
                    && (proxyCalls.isEmpty() || (first
                        ? proxyCalls.get(proxyCalls.size() - 1).path("playerOrdinal").intValue() < rows.size()
                        : proxyCalls.get(proxyCalls.size() - 1).path("playerOrdinal").intValue() == rows.size()))
                    && proxyCalls.size() / 2 < rows.size() / 6,
                    "T4_HTTP_OBSERVATION_PROXY_MISMATCH");
            receiveSessionAction(delivery, call);
            proxyResult = delivery.path("result").deepCopy();
            proxyCallSha = delivery.path("t4CallSha256").asText();
            proxyResultSha = delivery.path("resultSha256").asText();
            proxyTargetSha = targetSha;
            if (first) {
                proxyKind = manifest ? "manifest" : "direct";
                if (manifest) {
                    manifestTargetShas.clear();
                    require(delivery.path("proxyManifestTargets").isArray()
                            && !delivery.path("proxyManifestTargets").isEmpty()
                            && delivery.path("proxyManifestTargets").size() <= 5,
                            "T4_HTTP_OBSERVATION_PROXY_MISMATCH");
                    for (JsonNode value : delivery.path("proxyManifestTargets")) {
                        require(value.isTextual(), "T4_HTTP_OBSERVATION_PROXY_MISMATCH");
                        require(manifestTargetShas.add(sha(value.textValue().getBytes(StandardCharsets.UTF_8))),
                                "T4_HTTP_OBSERVATION_PROXY_MISMATCH");
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            fail();
            throw new IOException("T4_HTTP_OBSERVATION_PROXY_MISMATCH");
        }
    }

    synchronized void rewriteProxyManifest(byte[] sourceBody, byte[] httpBody, String firstTarget) throws IOException {
        try {
            require(!closed && !failed && proxyResult != null && "manifest".equals(proxyKind)
                    && proxyCalls.size() % 2 == 0 && rewrittenSha == null
                    && sourceBody != null && sourceBody.length > 0
                    && proxyResult.path("bytes").isInt()
                    && sourceBody.length == proxyResult.path("bytes").intValue()
                    && sha(sourceBody).equals(proxyResult.path("sha256").asText())
                    && httpBody != null && httpBody.length > 0 && httpBody.length <= 524288
                    && firstTarget != null, "T4_HTTP_OBSERVATION_PROXY_MISMATCH");
            String selected = sha(firstTarget.getBytes(StandardCharsets.UTF_8));
            require(manifestTargetShas.contains(selected), "T4_HTTP_OBSERVATION_PROXY_MISMATCH");
            // Only the trusted worker calls this after validating and rewriting the complete manifest.
            selectedSegmentSha = selected;
            rewrittenSha = sha(httpBody);
            rewrittenBytes = httpBody.length;
        } catch (IOException | RuntimeException error) {
            fail();
            throw new IOException("T4_HTTP_OBSERVATION_PROXY_MISMATCH");
        }
    }

    public synchronized void completeProxy(int status, String mime, String range, long contentLength, byte[] body)
            throws IOException {
        try {
            boolean head = "direct".equals(proxyKind) && proxyCalls.size() % 2 == 1;
            boolean manifest = "manifest".equals(proxyKind) && proxyCalls.size() % 2 == 0;
            MediaType actualType = MediaType.parseMediaType(mime);
            MediaType sourceType = MediaType.parseMediaType(proxyResult == null ? "" : proxyResult.path("mime").asText());
            require(sessionMode && !closed && !failed && proxyResult != null
                    && Set.of(200, 206).contains(status)
                    && (!manifest || status == 200)
                    && proxyResult.path("status").asInt() == status
                    && actualType.getType().equals(sourceType.getType())
                    && actualType.getSubtype().equals(sourceType.getSubtype())
                    && sourceType.getParameters().entrySet().stream()
                        .allMatch(entry -> entry.getValue().equals(actualType.getParameter(entry.getKey())))
                    && actualType.getParameters().keySet().stream()
                        .allMatch(key -> key.equals("charset"))
                    && (actualType.getCharset() == null || actualType.getCharset().equals(StandardCharsets.UTF_8))
                    && proxyResult.path("headers").path("content-range").asText("").equals(range)
                    && body != null && body.length <= (manifest ? 524288 : 32768)
                    && (manifest
                        ? range.isEmpty() && body.length > 0 && body.length == contentLength
                            && contentLength == rewrittenBytes && sha(body).equals(rewrittenSha)
                        : contentLength == proxyResult.path("bytes").asInt()
                            && (head ? body.length == 0 : body.length > 0 && body.length == contentLength)
                            && sha(body).equals(proxyResult.path("sha256").asText())),
                    "T4_HTTP_OBSERVATION_PROXY_MISMATCH");
            if (!manifest && !head) {
                require(mime != null && mime.split(";", 2)[0].equalsIgnoreCase("video/mp2t")
                        && body.length >= 564 && body[0] == 0x47 && body[188] == 0x47 && body[376] == 0x47,
                        "T4_HTTP_OBSERVATION_MEDIA_INVALID");
            } else if (head) {
                var get = proxyCalls.get(proxyCalls.size() - 1);
                require(get.path("targetSha256").asText().equals(proxyTargetSha)
                        && get.path("httpStatus").asInt() == status
                        && get.path("contentLength").asLong() == contentLength
                        && get.path("mime").asText().equals(mime)
                        && get.path("contentRange").asText().equals(range),
                        "T4_HTTP_OBSERVATION_PROXY_MISMATCH");
            }
            int ordinal = proxyCalls.size() + 1;
            var row = proxyCalls.addObject().put("ordinal", ordinal).put("playerOrdinal", rows.size())
                    .put("brokerJobId", activeSessionJob).put("sessionOrdinal", activeSessionOrdinal)
                    .put("httpMethod", head ? "HEAD" : "GET").put("httpStatus", status)
                    .put("t4CallSha256", proxyCallSha).put("resultSha256", proxyResultSha)
                    .put("targetSha256", proxyTargetSha).put("responseSha256", sha(body))
                    .put("responseBytes", body.length).put("contentLength", contentLength)
                    .put("mime", mime).put("contentRange", range);
            if (manifest) row.put("sourceBodySha256", proxyResult.path("sha256").asText())
                    .put("sourceBytes", proxyResult.path("bytes").intValue())
                    .put("segmentTargetSha256", selectedSegmentSha);
            proxyResult = null;
            proxyCallSha = proxyResultSha = proxyTargetSha = null;
            rewrittenSha = null;
            rewrittenBytes = 0;
            if (proxyCalls.size() % 2 == 0) {
                proxyKind = null;
                manifestTargetShas.clear();
                selectedSegmentSha = null;
            }
        } catch (IOException | RuntimeException error) {
            fail();
            throw new IOException("T4_HTTP_OBSERVATION_PROXY_MISMATCH");
        }
    }

    synchronized void recordSessionReceipt(JsonNode row) throws IOException {
        try {
            require(sessionMode && !closed && !failed && expectedCall == null
                    && proxyResult == null && proxyKind == null && rows.size() % 18 == 0 && activeSessionJob != null
                    && row.path("jobId").asText().equals(activeSessionJob)
                    && row.path("status").asText().equals("COMPLETED")
                    && uuid(row.path("jobId").asText())
                    && row.path("parentJobId").equals(binding.path("jobId"))
                    && row.path("parentProfileSha256").equals(binding.path("inputProfileSha256"))
                    && row.path("sourceSha256").equals(binding.path("sourceSha256"))
                    && row.path("executionKind").asText().equals("t4_bounded_session")
                    && row.path("cleanup").path("confirmed").asBoolean()
                    && row.path("cleanup").path("containerAbsent").asBoolean()
                    && row.path("sessionActions").isArray()
                    && row.path("result").path("t4SessionValidation").isObject()
                    && sessionJobs.contains(row.path("jobId").asText()),
                    "T4_HTTP_OBSERVATION_SESSION_MISMATCH");
            JsonNode receipt = row.path("result").path("t4SessionValidation");
            require(row.path("sessionActions").equals(activeActions) && activeActions.size() >= 18
                    && activeActions.size() <= 24 && receipt.path("actions").equals(activeActions)
                    && receipt.path("runtimeSha256").equals(binding.path("runtimeSha256"))
                    && receipt.path("inputSourceSha256").equals(binding.path("sourceSha256"))
                    && receipt.path("playType").asText().equals(activeMode)
                    && receipt.path("sourceDataSha256").asText().equals(activeDataSha)
                    && receipt.path("sourceDataPresent").isBoolean()
                    && receipt.path("sourceDataPresent").booleanValue() == activeDataPresent
                    && receipt.path("initCount").isInt() && receipt.path("initCount").intValue() == 1
                    && receipt.path("destroyReturned").isBoolean() && receipt.path("destroyReturned").booleanValue(),
                    "T4_HTTP_OBSERVATION_SESSION_MISMATCH");
            var safe = this.mapper.createObjectNode().put("brokerJobId", activeSessionJob);
            for (String key : List.of("runtimeSha256", "loadedTextSha256", "playType", "sourceDataPresent",
                    "sourceDataSha256", "initCount", "destroyReturned", "actions", "cleanup",
                    "initializationKind", "fullGeneratedExtForwarded"))
                safe.set(key, receipt.path(key).deepCopy());
            if (receipt.has("sourceRuleSha256") || receipt.has("dependencySha256")) {
                require(receipt.path("sourceRuleSha256").asText().matches("[a-f0-9]{64}")
                        && receipt.path("dependencySha256").asText().matches("[a-f0-9]{64}"),
                        "T4_HTTP_OBSERVATION_SESSION_MISMATCH");
                safe.set("sourceRuleSha256", receipt.path("sourceRuleSha256").deepCopy());
                safe.set("dependencySha256", receipt.path("dependencySha256").deepCopy());
            }
            sessionReceipts.add(safe);
            activeSessionJob = null;
            activeSessionOrdinal = 0;
            activeActions.removeAll();
            playerTargetSha = null;
        } catch (IOException | RuntimeException error) {
            fail();
            throw new IOException("T4_HTTP_OBSERVATION_SESSION_MISMATCH");
        }
    }

    public synchronized JsonNode complete(int httpStatus, byte[] body) throws IOException {
        try {
            require(!closed && !failed && returned != null && httpStatus == 200
                    && body != null && body.length > 0 && body.length <= 65536,
                    "T4_HTTP_OBSERVATION_RESPONSE_INVALID");
            require(returned.equals(mapper.readTree(body)), "T4_HTTP_OBSERVATION_RESPONSE_MISMATCH");
            int index = rows.size();
            var row = rows.addObject().put("ordinal", index + 1).put("shape", SHAPES.get((index / 6) % 3))
                    .put("playType", MODES.get(index / 18)).put("method", ACTIONS.get(index % 6))
                    .put("httpStatus", httpStatus).put("t4CallSha256", callSha).put("brokerJobId", childJob)
                    .put("resultSha256", resultSha).put("httpResponseSha256", sha(body))
                    .put("httpResponseBytes", body.length).put("workerReturnedAfterCleanup", !sessionMode);
            if (sessionMode) row.put("sessionOrdinal", deliveredOrdinal).put("cleanupPending", true);
            expectedCall = returned = null;
            callSha = childJob = resultSha = null;
            dispatched = false;
            return row.deepCopy();
        } catch (IOException | RuntimeException error) {
            fail();
            throw new IOException("T4_HTTP_OBSERVATION_RESPONSE_MISMATCH");
        }
    }

    synchronized JsonNode finish() throws IOException {
        require(!closed && !failed && expectedCall == null && rows.size() == 36
                && ((sessionMode && !sessionJobs.isEmpty() && activeSessionJob == null
                        && sessionReceipts.size() == 2 && sessionReceipts.size() == sessionJobs.size()
                        && proxyResult == null && proxyCalls.size() % 2 == 0)
                    || (!sessionMode && jobs.size() == 36)),
                "T4_HTTP_OBSERVATION_INCOMPLETE");
        var result = binding.deepCopy().put("allPlannedCallsObserved", true)
                .put("rawOutputStored", false).put("formalAcceptanceIntegrated", false)
                .put("productNodePassesAdded", 0).put("publishEligible", false);
        if (sessionMode) {
            result.put("sessionMode", true);
            result.set("sessionReceipts", sessionReceipts.deepCopy());
            result.set("proxyCalls", proxyCalls.deepCopy());
        }
        result.set("calls", rows.deepCopy());
        closed = true;
        return result;
    }

    synchronized void fail() {
        failed = true;
        expectedCall = returned = null;
        callSha = childJob = resultSha = null;
        proxyResult = null;
        proxyCallSha = proxyResultSha = proxyTargetSha = playerTargetSha = null;
        proxyKind = null;
        manifestTargetShas.clear();
        rewrittenSha = selectedSegmentSha = null;
        rewrittenBytes = 0;
    }

    @Override public synchronized void close() {
        if (!closed) fail();
        closed = true;
    }

    private static String sha(byte[] body) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException("T4_HTTP_OBSERVATION_DIGEST_UNAVAILABLE");
        }
    }

    private static boolean uuid(String value) {
        return value != null && value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }

    private static void require(boolean value, String code) throws IOException {
        if (!value) throw new IOException(code);
    }
}
