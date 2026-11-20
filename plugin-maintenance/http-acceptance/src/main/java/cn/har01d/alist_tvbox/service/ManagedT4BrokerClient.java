package cn.har01d.alist_tvbox.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * HTTP verifier consumer of the existing broker, not an EXT mapper or a source executor.
 * Results remain untrusted. No automatic retry of start or take is permitted.
 */
public final class ManagedT4BrokerClient implements AutoCloseable {
    private static final String UUID_PATTERN =
            "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED", "CLEANUP_UNCONFIRMED");
    private static final Map<String, Set<String>> REQUIRED = Map.of(
            "home", Set.of(), "category", Set.of("t", "pg"), "detail", Set.of("ids"),
            "search", Set.of("wd", "quick", "pg"), "play", Set.of("play", "flag"));
    private static final Map<String, Set<String>> OPTIONAL = Map.of(
            "home", Set.of("filter"), "category", Set.of("filter", "extend"));
    private final ObjectMapper mapper;
    private final Path endpoint;
    private final String instanceId, imageId, bundleSha, runtimeSha, policySha, egressSha;
    private final String sourceBase64, sourceSha, textSha;
    private final long timeoutNanos;
    private final Object lifecycle = new Object();
    private CountDownLatch completion = new CountDownLatch(0);
    private SocketChannel activeChannel;
    private boolean closed;
    private IOException cleanupFailure;
    private String sessionJob, sessionContext, sessionParent, sessionProfile, sessionInitialCall;
    private String sessionPlayType, sessionDataSha;
    private boolean sessionDataPresent;
    private com.fasterxml.jackson.databind.node.ArrayNode sessionActions;
    private int sessionOrdinal;

    public ManagedT4BrokerClient(ObjectMapper mapper, Path endpoint, String instanceId, String imageId,
                                 String bundleSha, String runtimeSha, String policySha, String egressSha,
                                 byte[] source, Duration timeout) throws IOException {
        require(endpoint != null && endpoint.isAbsolute() && endpoint.equals(endpoint.normalize())
                && endpoint.getFileName().toString().equals("executor.sock")
                && endpoint.toString().getBytes(StandardCharsets.UTF_8).length <= 100
                && instanceId != null && instanceId.matches(UUID_PATTERN)
                && imageId != null && imageId.matches("sha256:[a-f0-9]{64}")
                && hash(bundleSha) && hash(runtimeSha) && hash(policySha) && hash(egressSha)
                && source != null && source.length > 0 && source.length <= 1_048_576
                && timeout != null && timeout.toMillis() >= 100 && timeout.toMillis() <= 180_000,
                "T4_BROKER_CONFIGURATION_INVALID");
        this.mapper = mapper.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
        this.endpoint = endpoint;
        this.instanceId = instanceId;
        this.imageId = imageId;
        this.bundleSha = bundleSha;
        this.runtimeSha = runtimeSha;
        this.policySha = policySha;
        this.egressSha = egressSha;
        byte[] frozen = source.clone();
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(frozen)).toString();
        sourceSha = digest(frozen);
        textSha = digest((text.startsWith("\uFEFF") ? text.substring(1) : text).getBytes(StandardCharsets.UTF_8));
        sourceBase64 = Base64.getEncoder().encodeToString(frozen);
        timeoutNanos = timeout.toNanos();
    }

    public String sourceSha256() { return sourceSha; }

    JsonNode observationBinding() {
        return mapper.createObjectNode().put("instanceId", instanceId).put("imageId", imageId)
                .put("bundleSha256", bundleSha).put("runtimeSha256", runtimeSha)
                .put("executionPolicySha256", policySha).put("egressPolicySha256", egressSha)
                .put("sourceSha256", sourceSha);
    }

    public synchronized void verify() throws IOException, InterruptedException {
        synchronized (lifecycle) {
            require(!closed && cleanupFailure == null, "T4_BROKER_CLOSED");
        }
        validateIdentity(control(mapper.createObjectNode().put("schemaVersion", 1)
                .put("operation", "identity"), System.nanoTime() + timeoutNanos, false));
    }

    public synchronized JsonNode execute(JsonNode input) throws IOException, InterruptedException {
        return execute(input, null, null);
    }

    public synchronized JsonNode executeForAcceptance(JsonNode input, String parentJobId, String profileSha)
            throws IOException, InterruptedException {
        require(parentJobId != null && parentJobId.matches(UUID_PATTERN) && hash(profileSha),
                "T4_BROKER_PARENT_INVALID");
        return execute(input, parentJobId, profileSha);
    }

    public synchronized JsonNode executeSession(JsonNode input) throws IOException, InterruptedException {
        return executeSession(input, null, null);
    }

    public synchronized JsonNode executeSessionForAcceptance(JsonNode input, String parentJobId, String profileSha)
            throws IOException, InterruptedException {
        require(parentJobId != null && parentJobId.matches(UUID_PATTERN) && hash(profileSha),
                "T4_BROKER_PARENT_INVALID");
        return executeSession(input, parentJobId, profileSha);
    }

    private JsonNode executeSession(JsonNode input, String parentJobId, String profileSha)
            throws IOException, InterruptedException {
        JsonNode call;
        if (input != null && input.path("method").asText().equals("proxy")) {
            call = input.deepCopy();
            require(fields(call).equals(Set.of("method", "params", "playType"))
                    || fields(call).equals(Set.of("method", "params", "playType", "sourceData")),
                    "T4_BROKER_CALL_INVALID");
            JsonNode params = call.path("params");
            require(fields(params).equals(Set.of("target", "range", "head"))
                    && params.path("target").isTextual() && params.path("target").textValue().startsWith("/local?")
                    && params.path("target").textValue().length() <= 8192 && params.path("range").isTextual()
                    && Set.of("", "bytes=0-32767").contains(params.path("range").asText())
                    && params.path("head").isBoolean(), "T4_BROKER_CALL_INVALID");
        } else call = validateCall(input);
        var context = mapper.createObjectNode().set("playType", call.path("playType"));
        if (call.has("sourceData")) ((ObjectNode) context).set("sourceData", call.get("sourceData"));
        String contextSha = digest(canonical(context).getBytes(StandardCharsets.US_ASCII));
        String callJson = canonical(call), callSha = digest(callJson.getBytes(StandardCharsets.US_ASCII));
        CountDownLatch done;
        synchronized (lifecycle) {
            require(!closed && cleanupFailure == null, "T4_BROKER_CLOSED");
            done = completion = new CountDownLatch(1);
        }
        long deadline = System.nanoTime() + timeoutNanos;
        try {
            require(sessionJob == null || Objects.equals(sessionParent, parentJobId)
                    && Objects.equals(sessionProfile, profileSha), "T4_BROKER_SESSION_CONTEXT_CHANGED");
            validateIdentity(control(mapper.createObjectNode().put("schemaVersion", 1)
                    .put("operation", "identity"), deadline, false));
            if (sessionJob == null) {
                require(!call.path("method").asText().equals("proxy"), "T4_BROKER_SESSION_REQUIRED");
                sessionJob = UUID.randomUUID().toString();
                sessionContext = contextSha;
                sessionParent = parentJobId;
                sessionProfile = profileSha;
                sessionInitialCall = callSha;
                sessionPlayType = call.path("playType").asText();
                sessionDataPresent = call.has("sourceData");
                sessionDataSha = digest(canonical(sessionDataPresent ? call.get("sourceData")
                        : mapper.createObjectNode()).getBytes(StandardCharsets.US_ASCII));
                sessionActions = mapper.createArrayNode();
                sessionOrdinal = 1;
                var start = request("start_session", sessionJob).put("sourceBase64", sourceBase64)
                        .put("sourceSha256", sourceSha).put("t4CallJson", callJson);
                if (parentJobId != null) start.put("parentJobId", parentJobId);
                JsonNode row = control(start, deadline, false);
                validateRow(row, sessionJob);
                require(row.path("sourceSha256").asText().equals(sourceSha)
                        && row.path("t4CallSha256").asText().equals(callSha)
                        && parentMatches(row, parentJobId, profileSha)
                        && row.path("executionKind").asText().equals("t4_bounded_session"),
                        "T4_BROKER_RECEIPT_MISMATCH");
            } else {
                require(sessionContext.equals(contextSha) && sessionOrdinal < 24, "T4_BROKER_SESSION_CONTEXT_CHANGED");
                sessionOrdinal++;
                JsonNode accepted = control(request("session_call", sessionJob).put("ordinal", sessionOrdinal)
                        .put("t4CallJson", callJson), deadline, false);
                require(trueFlag(accepted, "accepted") && accepted.path("ordinal").isInt()
                        && accepted.path("ordinal").intValue() == sessionOrdinal, "T4_BROKER_RECEIPT_MISMATCH");
            }
            while (true) {
                JsonNode delivery = control(request("session_take", sessionJob).put("ordinal", sessionOrdinal)
                        .put("t4CallSha256", callSha), deadline, false);
                require(delivery.path("ready").isBoolean(), "T4_BROKER_DELIVERY_MISMATCH");
                if (!delivery.path("ready").booleanValue()) { pause(deadline); continue; }
                var deliveryFields = new HashSet<>(Set.of("ready", "schemaVersion", "authority", "jobId",
                        "sourceSha256", "runtimeSha256", "ordinal", "method", "t4CallSha256", "paramsSha256",
                        "resultSha256", "workerCallId", "cleanupPending", "businessChainVerified", "publishEligible", "resultJson"));
                if (parentJobId != null) deliveryFields.addAll(Set.of("parentJobId", "parentProfileSha256"));
                if (delivery.has("proxyManifestTargets")) deliveryFields.add("proxyManifestTargets");
                require(fields(delivery).equals(deliveryFields) && parentMatches(delivery, parentJobId, profileSha)
                        && delivery.path("schemaVersion").isInt() && delivery.path("schemaVersion").intValue() == 1
                        && delivery.path("authority").asText().equals("host_t4_session_observed")
                        && delivery.path("jobId").asText().equals(sessionJob)
                        && delivery.path("sourceSha256").asText().equals(sourceSha)
                        && delivery.path("runtimeSha256").asText().equals(runtimeSha)
                        && delivery.path("ordinal").isInt() && delivery.path("ordinal").intValue() == sessionOrdinal
                        && delivery.path("method").equals(call.path("method"))
                        && delivery.path("t4CallSha256").asText().equals(callSha)
                        && delivery.path("paramsSha256").asText().equals(digest(canonical(call.path("params"))
                            .getBytes(StandardCharsets.US_ASCII)))
                        && hash(delivery.path("resultSha256").asText())
                        && delivery.path("workerCallId").isInt() && delivery.path("workerCallId").intValue() == sessionOrdinal + 1
                        && trueFlag(delivery, "cleanupPending") && falseFlag(delivery, "businessChainVerified")
                        && falseFlag(delivery, "publishEligible") && delivery.path("resultJson").isTextual()
                        && delivery.path("resultJson").textValue().length() <= 786_432
                        && delivery.path("resultSha256").asText().equals(digest(
                            delivery.path("resultJson").textValue().getBytes(StandardCharsets.UTF_8))),
                        "T4_BROKER_DELIVERY_MISMATCH");
                JsonNode result = mapper.readTree(delivery.path("resultJson").textValue());
                require(result != null && result.isObject(), "T4_BROKER_DELIVERY_MISMATCH");
                boolean manifest = call.path("method").asText().equals("proxy")
                        && !call.path("params").path("head").asBoolean()
                        && result.path("status").isInt() && result.path("status").intValue() == 200
                        && Set.of("application/vnd.apple.mpegurl", "application/x-mpegurl")
                            .contains(result.path("mime").asText().split(";", 2)[0].trim().toLowerCase(Locale.ROOT));
                require(delivery.has("proxyManifestTargets") == manifest, "T4_BROKER_DELIVERY_MISMATCH");
                if (manifest) {
                    JsonNode targets = delivery.path("proxyManifestTargets");
                    require(Set.of("", "bytes=0-32767").contains(call.path("params").path("range").asText())
                            && targets.isArray() && !targets.isEmpty() && targets.size() <= 5,
                            "T4_BROKER_DELIVERY_MISMATCH");
                    Set<String> distinct = new HashSet<>();
                    for (JsonNode target : targets) {
                        require(target.isTextual() && distinct.add(target.textValue()), "T4_BROKER_DELIVERY_MISMATCH");
                        validateProxyTarget(target.textValue());
                    }
                }
                ((ObjectNode) delivery).remove("resultJson");
                ((ObjectNode) delivery).set("result", result);
                var action = sessionActions.addObject();
                for (String key : List.of("ordinal", "method", "t4CallSha256", "paramsSha256",
                        "resultSha256", "workerCallId")) action.set(key, delivery.get(key).deepCopy());
                synchronized (lifecycle) { require(!closed, "T4_BROKER_CLOSED"); }
                return delivery;
            }
        } catch (IOException | InterruptedException error) {
            if (sessionJob != null) {
                String failedJob = sessionJob;
                clearSession();
                boolean interrupted = Thread.interrupted();
                try { cancelAndConfirm(failedJob); }
                catch (IOException | InterruptedException cleanup) {
                    synchronized (lifecycle) { cleanupFailure = new IOException("T4_BROKER_CLEANUP_UNCONFIRMED"); }
                    throw new IOException("T4_BROKER_CLEANUP_UNCONFIRMED");
                } finally { if (interrupted || error instanceof InterruptedException) Thread.currentThread().interrupt(); }
            }
            throw error;
        } finally { done.countDown(); }
    }

    public synchronized String sessionId() { return sessionJob; }

    static void validateProxyTarget(String target) throws IOException {
        try {
            require(target != null && target.length() <= 8192 && target.matches("[\\x21-\\x7e]+")
                    && !target.contains("\\"), "T4_BROKER_PROXY_TARGET_INVALID");
            java.net.URI uri = java.net.URI.create(target);
            require(uri.getScheme() == null && uri.getRawAuthority() == null && uri.getRawFragment() == null
                    && "/local".equals(uri.getRawPath()) && uri.getRawQuery() != null
                    && !uri.getRawQuery().isEmpty(), "T4_BROKER_PROXY_TARGET_INVALID");
            String[] pairs = uri.getRawQuery().split("&", -1);
            require(pairs.length <= 24, "T4_BROKER_PROXY_TARGET_INVALID");
            Set<String> names = new HashSet<>();
            for (String pair : pairs) {
                int separator = pair.indexOf('=');
                require(separator >= 0 && names.add(java.net.URLDecoder.decode(
                        pair.substring(0, separator), StandardCharsets.UTF_8)), "T4_BROKER_PROXY_TARGET_INVALID");
            }
        } catch (IllegalArgumentException error) {
            throw new IOException("T4_BROKER_PROXY_TARGET_INVALID");
        }
    }

    public synchronized JsonNode finishSession() throws IOException, InterruptedException {
        require(sessionJob != null, "T4_BROKER_SESSION_REQUIRED");
        CountDownLatch done;
        synchronized (lifecycle) {
            require(!closed && cleanupFailure == null, "T4_BROKER_CLOSED");
            done = completion = new CountDownLatch(1);
        }
        String job = sessionJob;
        long deadline = System.nanoTime() + timeoutNanos;
        try {
            // Close is issued once. An ambiguous reply fences this job instead of replaying close.
            JsonNode row = control(request("session_close", job), deadline, false);
            while (true) {
                validateRow(row, job);
                require(parentMatches(row, sessionParent, sessionProfile)
                        && row.path("sourceSha256").asText().equals(sourceSha)
                        && row.path("t4CallSha256").asText().equals(sessionInitialCall)
                        && row.path("executionKind").asText().equals("t4_bounded_session"),
                        "T4_BROKER_RECEIPT_MISMATCH");
                if (TERMINAL.contains(row.path("status").asText())) break;
                pause(deadline);
                row = control(request("status", job), deadline, false);
            }
            require(row.path("status").asText().equals("COMPLETED") && cleaned(row),
                    "T4_BROKER_SESSION_UNCONFIRMED");
            JsonNode result = row.path("result"), receipt = result.path("t4SessionValidation");
            require(result.path("schemaVersion").isInt() && result.path("schemaVersion").intValue() == 1
                    && result.path("authority").asText().equals("managed_source_host_observed")
                    && result.path("executionKind").asText().equals("t4_bounded_session")
                    && falseFlag(result, "rawOutputStored") && falseFlag(result, "businessChainVerified")
                    && falseFlag(result, "publishEligible")
                    && receipt.path("schemaVersion").isInt() && receipt.path("schemaVersion").intValue() == 1
                    && receipt.path("authority").asText().equals("host_t4_session_observed")
                    && receipt.path("inputSourceSha256").asText().equals(sourceSha)
                    && receipt.path("loadedTextSha256").asText().equals(textSha)
                    && receipt.path("runtimeSha256").asText().equals(runtimeSha)
                    && receipt.path("playType").asText().equals(sessionPlayType)
                    && receipt.path("sourceDataPresent").isBoolean()
                    && receipt.path("sourceDataPresent").booleanValue() == sessionDataPresent
                    && receipt.path("sourceDataSha256").asText().equals(sessionDataSha)
                    && receipt.path("initializationKind").asText().equals("credential_free_local_source_projection")
                    && falseFlag(receipt, "fullGeneratedExtForwarded")
                    && receipt.path("initCount").isInt() && receipt.path("initCount").intValue() == 1
                    && trueFlag(receipt, "destroyReturned") && cleaned(receipt)
                    && falseFlag(receipt, "rawOutputStored") && falseFlag(receipt, "businessChainVerified")
                    && falseFlag(receipt, "publishEligible") && sessionActions.size() == sessionOrdinal
                    && receipt.path("actions").equals(sessionActions) && row.path("sessionActions").equals(sessionActions),
                    "T4_BROKER_RECEIPT_MISMATCH");
            synchronized (lifecycle) { require(!closed, "T4_BROKER_CLOSED"); }
            clearSession();
            return row;
        } catch (IOException | InterruptedException error) {
            boolean interrupted = Thread.interrupted();
            try { discardSession(); }
            finally { if (interrupted || error instanceof InterruptedException) Thread.currentThread().interrupt(); }
            throw error;
        } finally { done.countDown(); }
    }

    private void clearSession() {
        sessionJob = sessionContext = sessionParent = sessionProfile = sessionInitialCall = null;
        sessionPlayType = sessionDataSha = null;
        sessionDataPresent = false;
        sessionActions = null;
        sessionOrdinal = 0;
    }

    public synchronized void discardSession() throws IOException, InterruptedException {
        if (sessionJob == null) return;
        String job = sessionJob;
        clearSession();
        try { cancelAndConfirm(job); }
        catch (IOException | InterruptedException error) {
            synchronized (lifecycle) { cleanupFailure = new IOException("T4_BROKER_CLEANUP_UNCONFIRMED"); }
            throw error;
        }
    }

    private JsonNode execute(JsonNode input, String parentJobId, String profileSha)
            throws IOException, InterruptedException {
        JsonNode call = validateCall(input);
        String callJson = canonical(call), callSha = digest(callJson.getBytes(StandardCharsets.UTF_8));
        String job = java.util.UUID.randomUUID().toString();
        CountDownLatch done;
        synchronized (lifecycle) {
            require(!closed && cleanupFailure == null, "T4_BROKER_CLOSED");
            done = completion = new CountDownLatch(1);
        }
        boolean startAttempted = false;
        JsonNode delivery;
        long deadline = System.nanoTime() + timeoutNanos;
        try {
            JsonNode identity = control(mapper.createObjectNode().put("schemaVersion", 1)
                    .put("operation", "identity"), deadline, false);
            validateIdentity(identity);
            ObjectNode start = request("start", job);
            start.put("sourceBase64", sourceBase64).put("sourceSha256", sourceSha).put("t4CallJson", callJson);
            if (parentJobId != null) start.put("parentJobId", parentJobId);
            // From this point a lost reply is ambiguous: always fence this exact job, never replay it.
            startAttempted = true;
            JsonNode row = control(start, deadline, false);
            while (true) {
                validateRow(row, job);
                require(row.path("sourceSha256").asText().equals(sourceSha)
                        && row.path("t4CallSha256").asText().equals(callSha)
                        && parentMatches(row, parentJobId, profileSha), "T4_BROKER_RECEIPT_MISMATCH");
                if (TERMINAL.contains(row.path("status").asText())) break;
                pause(deadline);
                row = control(request("status", job), deadline, false);
            }
            require(row.path("status").asText().equals("COMPLETED") && cleaned(row),
                    "T4_BROKER_ACTION_UNCONFIRMED");
            validateSummary(row.path("result"), call);
            ObjectNode take = request("take", job).put("sourceSha256", sourceSha).put("t4CallSha256", callSha);
            delivery = control(take, deadline, false);
            Set<String> deliveryFields = new HashSet<>(Set.of("schemaVersion", "authority", "jobId", "sourceSha256",
                            "runtimeSha256", "t4CallSha256", "resultSha256", "evidenceClass",
                            "businessChainVerified", "publishEligible", "result"));
            if (parentJobId != null) deliveryFields.addAll(Set.of("parentJobId", "parentProfileSha256"));
            require(fields(delivery).equals(deliveryFields) && parentMatches(delivery, parentJobId, profileSha)
                    && delivery.path("schemaVersion").isInt() && delivery.path("schemaVersion").intValue() == 1
                    && delivery.path("authority").asText().equals("host_t4_rpc_transport_observed")
                    && delivery.path("jobId").asText().equals(job)
                    && delivery.path("sourceSha256").asText().equals(sourceSha)
                    && delivery.path("runtimeSha256").asText().equals(runtimeSha)
                    && delivery.path("t4CallSha256").asText().equals(callSha)
                    && delivery.path("resultSha256").equals(row.path("result").path("t4RpcValidation").path("resultSha256"))
                    && delivery.path("evidenceClass").asText().equals("untrusted_source_output")
                    && falseFlag(delivery, "businessChainVerified") && falseFlag(delivery, "publishEligible")
                    && delivery.path("result").isObject(), "T4_BROKER_DELIVERY_MISMATCH");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                if (startAttempted) cancelAndConfirm(job);
            } catch (IOException | InterruptedException error) {
                interrupted |= error instanceof InterruptedException;
                synchronized (lifecycle) {
                    cleanupFailure = new IOException("T4_BROKER_CLEANUP_UNCONFIRMED");
                }
                throw new IOException("T4_BROKER_CLEANUP_UNCONFIRMED");
            } finally {
                done.countDown();
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        synchronized (lifecycle) {
            require(!closed, "T4_BROKER_CLOSED");
            return delivery;
        }
    }

    private void validateIdentity(JsonNode identity) throws IOException {
        require(identity.path("schemaVersion").isInt() && identity.path("schemaVersion").intValue() == 1
                && identity.path("authority").asText().equals("managed_source_broker_configuration")
                && configuredIdentity(identity) && identity.path("recoveryRequired").isBoolean()
                && !identity.path("recoveryRequired").booleanValue(), "T4_BROKER_IDENTITY_MISMATCH");
    }

    private static boolean parentMatches(JsonNode row, String jobId, String profileSha) {
        return jobId == null ? !row.has("parentJobId") && !row.has("parentProfileSha256")
                : row.path("parentJobId").isTextual() && row.path("parentJobId").textValue().equals(jobId)
                    && row.path("parentProfileSha256").isTextual()
                    && row.path("parentProfileSha256").textValue().equals(profileSha);
    }

    private void validateSummary(JsonNode result, JsonNode call) throws IOException {
        JsonNode receipt = result.path("t4RpcValidation");
        require(result.path("schemaVersion").isInt() && result.path("schemaVersion").intValue() == 1
                && result.path("authority").asText().equals("managed_source_host_observed")
                && result.path("executionKind").asText().equals("t4_single_action_rpc")
                && falseFlag(result, "rawOutputStored") && falseFlag(result, "businessChainVerified")
                && falseFlag(result, "publishEligible")
                && receipt.path("schemaVersion").isInt() && receipt.path("schemaVersion").intValue() == 1
                && receipt.path("authority").asText().equals("host_t4_rpc_transport_observed")
                && receipt.path("inputSourceSha256").asText().equals(sourceSha)
                && receipt.path("loadedTextSha256").asText().equals(textSha)
                && receipt.path("runtimeSha256").asText().equals(runtimeSha)
                && receipt.path("method").equals(call.path("method"))
                && receipt.path("playType").equals(call.path("playType"))
                && receipt.path("paramsSha256").asText().equals(digest(canonical(call.path("params")).getBytes(StandardCharsets.UTF_8)))
                && hash(receipt.path("resultSha256").asText())
                && trueFlag(receipt, "actionReturned") && trueFlag(receipt, "destroyReturned")
                && receipt.path("initializationKind").asText().equals("credential_free_local_source_projection")
                && receipt.path("sourceDataPresent").isBoolean()
                && receipt.path("sourceDataPresent").booleanValue() == call.has("sourceData")
                && receipt.path("sourceDataSha256").asText().equals(digest(canonical(
                        call.has("sourceData") ? call.get("sourceData") : mapper.createObjectNode())
                        .getBytes(StandardCharsets.US_ASCII)))
                && falseFlag(receipt, "fullGeneratedExtForwarded") && falseFlag(receipt, "businessChainVerified")
                && falseFlag(receipt, "publishEligible") && falseFlag(receipt, "rawOutputStored")
                && receipt.path("productNodePassesAdded").isInt()
                && receipt.path("productNodePassesAdded").intValue() == 0 && cleaned(receipt),
                "T4_BROKER_RECEIPT_MISMATCH");
    }

    private void cancelAndConfirm(String job) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (true) {
            JsonNode row = control(request("cancel", job), deadline, true);
            validateRow(row, job);
            if (TERMINAL.contains(row.path("status").asText())) {
                require(cleaned(row), "T4_BROKER_CLEANUP_UNCONFIRMED");
                return;
            }
            pause(deadline);
        }
    }

    private boolean configuredIdentity(JsonNode node) {
        return node.path("instanceId").asText().equals(instanceId) && node.path("imageId").asText().equals(imageId)
                && node.path("bundleSha256").asText().equals(bundleSha)
                && node.path("egressPolicySha256").asText().equals(egressSha);
    }

    private void validateRow(JsonNode row, String job) throws IOException {
        require(row.path("schemaVersion").isInt() && row.path("schemaVersion").intValue() == 1
                && row.path("authority").asText().equals("managed_source_broker_observed")
                && configuredIdentity(row) && row.path("jobId").asText().equals(job)
                && row.path("executionPolicySha256").asText().equals(policySha)
                && (TERMINAL.contains(row.path("status").asText()) || row.path("status").asText().equals("RUNNING"))
                && falseFlag(row, "businessChainVerified") && falseFlag(row, "publishEligible"),
                "T4_BROKER_RECEIPT_MISMATCH");
    }

    private ObjectNode request(String operation, String job) {
        return mapper.createObjectNode().put("schemaVersion", 1).put("operation", operation).put("jobId", job)
                .put("instanceId", instanceId).put("imageId", imageId).put("bundleSha256", bundleSha)
                .put("executionPolicySha256", policySha).put("egressPolicySha256", egressSha);
    }

    private JsonNode control(ObjectNode request, long deadline, boolean cleanup) throws IOException, InterruptedException {
        byte[] body = mapper.writeValueAsBytes(request);
        require(body.length <= 1_500_000, "T4_BROKER_FRAME_LIMIT");
        deadline = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(8));
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            synchronized (lifecycle) {
                require(cleanup || !closed, "T4_BROKER_CLOSED");
                if (!cleanup) activeChannel = channel;
            }
            try {
                channel.configureBlocking(false);
                if (!channel.connect(UnixDomainSocketAddress.of(endpoint)))
                    while (!channel.finishConnect()) pause(deadline);
                ByteBuffer output = ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).flip();
                while (output.hasRemaining()) {
                    if (channel.write(output) == 0) pause(deadline);
                    checkDeadline(deadline);
                }
                ByteBuffer length = ByteBuffer.allocate(4);
                read(channel, length, deadline);
                int size = length.flip().getInt();
                int replyLimit = request.path("operation").asText().equals("session_take") ? 786_432 : 65_536;
                require(size > 0 && size <= replyLimit, "T4_BROKER_FRAME_LIMIT");
                ByteBuffer input = ByteBuffer.allocate(size);
                read(channel, input, deadline);
                JsonNode frame = mapper.readTree(input.array());
                require(frame != null && frame.isObject() && frame.path("ok").isBoolean(), "T4_BROKER_FRAME_INVALID");
                require(frame.path("ok").booleanValue(), "T4_BROKER_REJECTED");
                require(fields(frame).equals(Set.of("ok", "result")) && frame.path("result").isObject(),
                        "T4_BROKER_FRAME_INVALID");
                return frame.get("result");
            } finally {
                synchronized (lifecycle) {
                    if (activeChannel == channel) activeChannel = null;
                }
            }
        } catch (UnsupportedOperationException error) {
            throw new IOException("T4_BROKER_UNIX_SOCKET_UNAVAILABLE");
        } catch (com.fasterxml.jackson.core.JacksonException error) {
            throw new IOException("T4_BROKER_FRAME_INVALID");
        }
    }

    private static void read(SocketChannel channel, ByteBuffer buffer, long deadline)
            throws IOException, InterruptedException {
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer);
            require(count >= 0, "T4_BROKER_FRAME_INCOMPLETE");
            if (count == 0) pause(deadline);
            checkDeadline(deadline);
        }
    }

    private static void checkDeadline(long deadline) throws IOException, InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        require(System.nanoTime() < deadline, "T4_BROKER_TIMEOUT");
    }

    private static void pause(long deadline) throws IOException, InterruptedException {
        checkDeadline(deadline);
        Thread.sleep(10);
    }

    private static JsonNode validateCall(JsonNode input) throws IOException {
        require(input != null && (fields(input).equals(Set.of("method", "params", "playType"))
                        || fields(input).equals(Set.of("method", "params", "playType", "sourceData"))),
                "T4_BROKER_CALL_INVALID");
        JsonNode call = input.deepCopy(), params = call.path("params");
        if (call.has("sourceData")) validateSourceData(call.get("sourceData"));
        String method = call.path("method").asText();
        require(REQUIRED.containsKey(method) && params.isObject() && call.path("playType").isTextual()
                && Set.of("t4", "t4-direct").contains(call.path("playType").asText()), "T4_BROKER_CALL_INVALID");
        Set<String> keys = fields(params), allowed = new HashSet<>(REQUIRED.get(method));
        allowed.addAll(OPTIONAL.getOrDefault(method, Set.of()));
        require(keys.containsAll(REQUIRED.get(method)) && allowed.containsAll(keys), "T4_BROKER_CALL_INVALID");
        for (String key : keys) {
            JsonNode value = params.get(key);
            if (Set.of("filter", "quick").contains(key)) require(value.isBoolean(), "T4_BROKER_CALL_INVALID");
            else if (key.equals("extend")) {
                require(value.isObject() && value.size() <= 32, "T4_BROKER_CALL_INVALID");
                for (var entry : value.properties())
                    require(codePoints(entry.getKey()) > 0 && codePoints(entry.getKey()) <= 100
                            && entry.getValue().isTextual() && codePoints(entry.getValue().textValue()) <= 1024,
                            "T4_BROKER_CALL_INVALID");
            } else {
                require(value.isTextual() && codePoints(value.textValue()) > 0
                        && codePoints(value.textValue()) <= 8192, "T4_BROKER_CALL_INVALID");
                if (key.equals("pg"))
                    require(value.textValue().matches("[0-9]{1,5}") && Integer.parseInt(value.textValue()) >= 1
                            && Integer.parseInt(value.textValue()) <= 10000, "T4_BROKER_CALL_INVALID");
            }
        }
        require(canonical(call).length() <= 32_768, "T4_BROKER_CALL_INVALID");
        return call;
    }

    public static void validateSourceData(JsonNode data) throws IOException {
        require(data != null && data.isObject() && data.size() >= 1 && data.size() <= 32,
                "T4_BROKER_SOURCE_DATA_INVALID");
        var reserved = Set.of("api", "source", "loader", "raw", "data", "extend", "preheaturl",
                "t4playtype", "playbacksourcekind", "playbacksourcekey", "playbacksourcename",
                "playbackconfigurl", "proxybase");
        var numeric = Set.of("gateway_timeout", "gateway_total_timeout", "gateway_retries",
                "detail_gateway_retries", "session_ttl_minutes");
        for (var entry : data.properties()) {
            String key = entry.getKey(), normalized = key.replace("_", "").toLowerCase(Locale.ROOT);
            JsonNode value = entry.getValue();
            require(key.matches("[A-Za-z][A-Za-z0-9_]{0,63}") && !reserved.contains(normalized),
                    "T4_BROKER_SOURCE_DATA_INVALID");
            if (key.equals("proxy_gateway")) {
                require(value.isTextual() && (value.textValue().startsWith("http://")
                                || value.textValue().startsWith("https://"))
                        && !value.textValue().matches(".*[@?#\\r\\n].*")
                        && codePoints(value.textValue()) <= 1024,
                        "T4_BROKER_SOURCE_DATA_INVALID");
            } else {
                require(List.of("token", "secret", "password", "cookie", "authorization", "proxy",
                                "credential", "apikey").stream().noneMatch(normalized::contains),
                        "T4_BROKER_SOURCE_DATA_INVALID");
                if (numeric.contains(key)) {
                    require(value.isIntegralNumber() && value.canConvertToInt()
                                    && value.intValue() >= 0 && value.intValue() <= 100000,
                            "T4_BROKER_SOURCE_DATA_INVALID");
                } else if (key.equals("subtitle_enabled")) {
                    require(value.isBoolean(), "T4_BROKER_SOURCE_DATA_INVALID");
                } else {
                    require(value.isTextual() && codePoints(value.textValue()) <= 1024
                                    && value.textValue().codePoints().allMatch(ch -> ch >= 32 && ch != 127
                                    && (ch < 0xD800 || ch > 0xDFFF)),
                            "T4_BROKER_SOURCE_DATA_INVALID");
                }
            }
        }
        require(canonical(data).length() <= 8192, "T4_BROKER_SOURCE_DATA_INVALID");
    }

    // Match Python's ensure_ascii and code-point key ordering for the bounded T4 grammar.
    static String canonical(JsonNode value) throws IOException {
        var factory = new com.fasterxml.jackson.core.JsonFactory();
        factory.setCharacterEscapes(new CharacterEscapes() {
            final int[] codes = CharacterEscapes.standardAsciiEscapesForJSON();
            {
                for (int i = 0; i < 32; i++)
                    if (codes[i] == ESCAPE_STANDARD) codes[i] = ESCAPE_CUSTOM;
                codes[127] = ESCAPE_CUSTOM;
            }
            @Override public int[] getEscapeCodesForAscii() { return codes; }
            @Override public SerializedString getEscapeSequence(int ch) {
                if (ch >= 32 && ch < 127) return null;
                StringBuilder escaped = new StringBuilder();
                for (char unit : Character.toChars(ch))
                    escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) unit));
                return new SerializedString(escaped.toString());
            }
        });
        var bytes = new ByteArrayOutputStream();
        try (JsonGenerator generator = factory.createGenerator(bytes)) { canonicalValue(generator, value); }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    private static void canonicalValue(JsonGenerator generator, JsonNode value) throws IOException {
        if (value.isObject()) {
            generator.writeStartObject();
            var keys = new ArrayList<>(fields(value));
            keys.sort((a, b) -> Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray()));
            for (String key : keys) {
                generator.writeFieldName(key);
                canonicalValue(generator, value.get(key));
            }
            generator.writeEndObject();
        } else if (value.isTextual()) generator.writeString(value.textValue());
        else if (value.isBoolean()) generator.writeBoolean(value.booleanValue());
        else if (value.isIntegralNumber()) generator.writeNumber(value.bigIntegerValue());
        else throw new IOException("T4_BROKER_CALL_INVALID");
    }

    private static int codePoints(String value) { return value.codePointCount(0, value.length()); }
    private static Set<String> fields(JsonNode node) {
        var keys = new HashSet<String>();
        if (node.isObject()) node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static boolean trueFlag(JsonNode value, String key) {
        return value.path(key).isBoolean() && value.path(key).booleanValue();
    }
    private static boolean falseFlag(JsonNode value, String key) {
        return value.path(key).isBoolean() && !value.path(key).booleanValue();
    }
    private static boolean cleaned(JsonNode row) {
        return trueFlag(row.path("cleanup"), "confirmed") && trueFlag(row.path("cleanup"), "containerAbsent");
    }
    private static String digest(byte[] value) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException("T4_BROKER_DIGEST_UNAVAILABLE");
        }
    }
    private static void require(boolean value, String code) throws IOException {
        if (!value) throw new IOException(code);
    }

    @Override
    public void close() throws IOException {
        CountDownLatch done;
        synchronized (lifecycle) {
            closed = true;
            done = completion;
            if (activeChannel != null) activeChannel.close();
        }
        try {
            require(done.await(95, TimeUnit.SECONDS), "T4_BROKER_CLEANUP_UNCONFIRMED");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("T4_BROKER_CLEANUP_UNCONFIRMED");
        }
        synchronized (lifecycle) {
            if (cleanupFailure != null) throw new IOException("T4_BROKER_CLEANUP_UNCONFIRMED");
        }
        synchronized (this) {
            if (sessionJob != null) {
                String job = sessionJob;
                clearSession();
                try { cancelAndConfirm(job); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("T4_BROKER_CLEANUP_UNCONFIRMED");
                }
            }
        }
    }
}
