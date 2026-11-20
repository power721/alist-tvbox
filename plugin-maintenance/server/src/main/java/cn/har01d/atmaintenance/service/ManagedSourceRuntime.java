package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Calls a separately installed trusted host broker, never a source-bearing host subprocess. */
@Service
public class ManagedSourceRuntime {
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TOTAL_RESPONSE_BYTES = 64 * 1024 * 1024;
    private static final String UUID = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED", "CLEANUP_UNCONFIRMED");
    private static final Set<String> EGRESS_FAILURES = Set.of("EGRESS_URL_DENIED", "EGRESS_NONPUBLIC_TARGET_DENIED",
            "EGRESS_HEADER_ORIGIN_DENIED", "EGRESS_HEADERS_DENIED", "EGRESS_SESSION_TOKEN_DENIED",
            "EGRESS_REQUEST_BUDGET", "EGRESS_BYTE_BUDGET", "EGRESS_HTTP_FAILED", "EGRESS_HTTP_DEADLINE",
            "EGRESS_HTTP_STATUS_DENIED", "EGRESS_REDIRECT_DENIED", "EGRESS_RATE_LIMITED", "EGRESS_COOLDOWN_BUDGET",
            "EGRESS_CANCELLED", "EGRESS_DNS_FAILED", "EGRESS_DNS_NO_ADDRESS",
            "EGRESS_NONPUBLIC_DNS", "EGRESS_CONTENT_ENCODING_DENIED",
            "EGRESS_CONTENT_LENGTH_INVALID", "EGRESS_RANGE_INVALID");
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String endpoint;
    private final String instanceId;
    private final String imageId;
    private final String egressPolicySha;
    private final String httpImageId;
    private final JsonNode manifest;
    private final String runtimeSha;
    private final ObjectNode snapshot;
    private final String policySha;
    private ConnectionSettingsStore connections;
    private volatile Map<String, ManagedSourceRuntime> sourceRuntimes = Map.of();
    private volatile Map<String, String> sourceRouteLabels = Map.of();

    public ManagedSourceRuntime(ObjectMapper mapper, FixtureRuntime fixture,
                                @Value("${maintenance.managed-source.enabled:false}") boolean enabled,
                                @Value("${maintenance.managed-source.socket:}") String endpoint,
                                @Value("${maintenance.managed-source.instance-id:}") String instanceId,
                                @Value("${maintenance.managed-source.image-id:}") String imageId,
                                @Value("${maintenance.managed-source.egress-policy-sha256:}") String egressPolicySha,
                                @Value("${maintenance.managed-source.http-image-id:}") String httpImageId) throws IOException {
        this.mapper = mapper;
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.instanceId = instanceId;
        this.imageId = imageId;
        this.egressPolicySha = egressPolicySha;
        this.httpImageId = httpImageId;
        byte[] bytes = new ClassPathResource("worker/managed-runtime.json").getContentAsByteArray();
        manifest = mapper.readTree(bytes);
        if (!manifest.path("protocol").asText().equals("managed-source-unix-v1")
                || !manifest.path("runtimeSha256").asText().equals(fixture.sha256()))
            throw new IOException("MANAGED_RUNTIME_BUNDLE_CHANGED");
        runtimeSha = Hashes.sha256(bytes);
        snapshot = mapper.createObjectNode().put("schemaVersion", 1).put("authority", "expected_configuration")
                .put("mode", "managed_source").put("enabled", enabled)
                .put("transport", loopbackTunnel() ? "loopback_tunnel" : "unix_socket")
                .put("transportPathSha256", Hashes.sha256(endpoint))
                .put("instanceId", instanceId.matches(UUID) ? instanceId : null)
                .put("imageId", imageId.matches("sha256:[a-f0-9]{64}") ? imageId : null)
                .put("bundleSha256", runtimeSha);
        snapshot.set("policy", manifest.path(sampling() ? "egressPolicy" : "policy").deepCopy());
        snapshot.put("executionKind", sampling() ? "t4_business_sample" : "initialization_only");
        if (sampling()) snapshot.put("egressPolicySha256", egressPolicySha);
        if (!httpImageId.isEmpty()) {
            snapshot.put("httpImageId", httpImageId);
            snapshot.set("httpPolicy", manifest.path("httpPolicy").deepCopy());
        }
        snapshot.put("boundary", "源容器无网络、无凭据、无生产挂载。业务采样仅经固定只读出站策略；"
                + "源返回值为未受信任的运行观察，不认证媒体、客户端或发布资格。");
        policySha = Hashes.sha256(snapshot.toString());
    }

    public String sha256() { return runtimeSha; }
    public JsonNode manifest() { return manifest.deepCopy(); }
    public String policySha256() { return policySha; }
    public JsonNode snapshot() { return snapshot.deepCopy(); }
    public boolean sampling() { return !egressPolicySha.isEmpty(); }

    @Autowired
    synchronized void configureGateway(ConnectionSettingsStore connections) {
        this.connections = connections;
        sourceRuntimes.values().forEach(runtime -> runtime.connections = connections);
    }

    @Autowired
    synchronized void configureRoutes(FixtureRuntime fixture,
                         @Value("${maintenance.managed-source.routes-file:}") String path) throws IOException {
        if (path.isBlank()) return;
        Path file = Path.of(path);
        require(Files.size(file) <= 65_536, "MANAGED_ROUTES_INVALID");
        JsonNode config;
        try (JsonParser parser = mapper.getFactory().createParser(Files.readAllBytes(file))) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            config = mapper.readTree(parser);
            require(parser.nextToken() == null, "MANAGED_ROUTES_INVALID");
        }
        require(config != null && config.path("schemaVersion").isInt()
                && config.path("schemaVersion").intValue() == 1 && config.path("runners").isArray()
                && config.path("runners").size() <= 64, "MANAGED_ROUTES_INVALID");
        var routes = new LinkedHashMap<String, ManagedSourceRuntime>();
        var labels = new LinkedHashMap<String, String>();
        var ids = new java.util.HashSet<String>();
        for (JsonNode definition : config.path("runners")) {
            String id = definition.path("id").asText();
            String label = definition.path("label").asText(id);
            require(id.matches("[A-Za-z0-9_-]{1,64}") && ids.add(id)
                    && !label.isBlank() && label.length() <= 100
                    && definition.path("sourceIds").isArray() && !definition.path("sourceIds").isEmpty(),
                    "MANAGED_ROUTES_INVALID");
            var selected = new ManagedSourceRuntime(mapper, fixture, enabled,
                    definition.path("socket").asText(), definition.path("instanceId").asText(),
                    definition.path("imageId").asText(), definition.path("egressPolicySha256").asText(),
                    definition.path("httpImageId").asText());
            selected.connections = connections;
            // Validate the installed channel even when execution is temporarily disabled.
            var validation = enabled ? selected : new ManagedSourceRuntime(mapper, fixture, true,
                    selected.endpoint, selected.instanceId, selected.imageId, selected.egressPolicySha,
                    selected.httpImageId);
            require(validation.configurationBlockedReason() == null, "MANAGED_ROUTES_INVALID");
            for (JsonNode source : definition.path("sourceIds")) {
                require(source.isTextual() && source.textValue().matches(UUID)
                        && routes.size() < 512 && !routes.containsKey(source.textValue()), "MANAGED_ROUTES_INVALID");
                routes.put(source.textValue(), selected);
                labels.put(source.textValue(), label);
            }
        }
        sourceRuntimes = Map.copyOf(routes);
        sourceRouteLabels = Map.copyOf(labels);
    }

    public ManagedSourceRuntime forSource(String sourceId) {
        return sourceId == null ? this : sourceRuntimes.getOrDefault(sourceId, this);
    }

    public boolean hasSourceRoute(String sourceId) {
        return sourceRuntimes.containsKey(sourceId);
    }

    public String routeLabel(String sourceId) {
        return sourceId == null ? "默认执行器" : sourceRouteLabels.getOrDefault(sourceId, "默认执行器");
    }

    private ManagedSourceRuntime forPolicy(String sha) throws IOException {
        if (policySha.equals(sha)) return this;
        return sourceRuntimes.values().stream().filter(route -> route.policySha.equals(sha)).findFirst()
                .orElseThrow(() -> new IOException("MANAGED_INPUT_MISMATCH"));
    }

    private boolean loopbackTunnel() {
        if (!endpoint.matches("tcp://127\\.0\\.0\\.1:[1-9][0-9]{0,4}")) return false;
        return Integer.parseInt(endpoint.substring(endpoint.lastIndexOf(':') + 1)) <= 65535;
    }

    public String configurationBlockedReason() {
        if (!enabled) return "MANAGED_EXECUTION_DISABLED";
        if (!loopbackTunnel() && (!endpoint.matches("/[A-Za-z0-9_./-]+/executor\\.sock")
                || endpoint.getBytes(StandardCharsets.UTF_8).length > 100
                || List.of(endpoint.split("/")).contains(".."))) return "MANAGED_LOCAL_SOCKET_REQUIRED";
        if (!instanceId.matches(UUID)) return "MANAGED_INSTANCE_REQUIRED";
        if (!imageId.matches("sha256:[a-f0-9]{64}")) return "MANAGED_PINNED_IMAGE_REQUIRED";
        if (sampling() && !egressPolicySha.matches("[a-f0-9]{64}")) return "MANAGED_EGRESS_POLICY_REQUIRED";
        if (!httpImageId.isEmpty() && (!sampling() || !httpImageId.matches("sha256:[a-f0-9]{64}")))
            return "MANAGED_PINNED_HTTP_IMAGE_REQUIRED";
        return null;
    }

    public String blockedReason(JsonNode profile) {
        String configuration = configurationBlockedReason();
        if (configuration != null) return configuration;
        if (!profile.path("runtime").asText().equals("t4")) return "MANAGED_T4_PROFILE_REQUIRED";
        if (!profile.path("extFingerprint").isMissingNode() && !profile.path("extFingerprint").isNull())
            return "MANAGED_EXT_NOT_AVAILABLE";
        try { PublicSourceData.read(profile); }
        catch (IOException error) { return "PUBLIC_SOURCE_DATA_INVALID"; }
        try { SourceConfig.read(profile); }
        catch (IOException error) { return "SOURCE_CONFIG_INVALID"; }
        return null;
    }

    public JsonNode inspect(String jobId, String body, JsonNode profile,
                            String expectedRuntime, String expectedPolicy) throws IOException, InterruptedException {
        return inspect(jobId, body, profile, expectedRuntime, expectedPolicy, null);
    }

    public JsonNode inspect(String jobId, String body, JsonNode profile,
                            String expectedRuntime, String expectedPolicy, JsonNode hostCredentials)
            throws IOException, InterruptedException {
        if (!sourceRuntimes.isEmpty() && !policySha.equals(expectedPolicy))
            return forPolicy(expectedPolicy).inspect(jobId, body, profile, expectedRuntime, expectedPolicy, hostCredentials);
        String blocked = blockedReason(profile);
        if (blocked != null) throw new IOException(blocked);
        if (!runtimeSha.equals(expectedRuntime) || !policySha.equals(expectedPolicy))
            throw new IOException("MANAGED_INPUT_MISMATCH");
        byte[] source = body.getBytes(StandardCharsets.UTF_8);
        if (source.length == 0 || source.length > 1_048_576) throw new IOException("MANAGED_SOURCE_LIMIT");
        validateIdentity(control(mapper.createObjectNode().put("schemaVersion", 1).put("operation", "identity")));
        ObjectNode request = request("start", jobId, policySha);
        request.put("sourceSha256", Hashes.sha256(source));
        request.put("sourceBase64", Base64.getEncoder().encodeToString(source));
        if (sampling()) request.put("loaderProfileJson", profile.toString());
        if (hostCredentials != null) request.set("hostCredentials", hostCredentials);
        if (sampling() && connections != null) {
            var gateway = connections.evidenceGateway();
            if (gateway.enabled()) request.put("evidenceGatewayJson", mapper.writeValueAsString(gateway));
        }
        JsonNode diagnostic = null;
        boolean completed = false;
        try {
            // Never retry start. A lost reply is resolved by a durable cancellation barrier.
            JsonNode row = control(request);
            JsonNode policy = snapshot.path("policy");
            long seconds = policy.path("acceptanceTimeoutSeconds").asLong(
                    policy.path("runtimeTimeoutSeconds").asLong())
                    + policy.path("createTimeoutSeconds").asLong()
                    + policy.path("cleanupConfirmationSeconds").asLong() + 10;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (true) {
                validateRow(row, jobId, policySha);
                if (TERMINAL.contains(row.path("status").asText())) break;
                if (System.nanoTime() >= deadline) throw new IOException("MANAGED_EXECUTION_TIMEOUT");
                Thread.sleep(100);
                row = control(request("status", jobId, policySha));
            }
            diagnostic = projectDiagnostic(row.path("diagnostics"));
            if (request.has("evidenceGatewayJson"))
                require(row.path("evidenceGatewaySettingsSha256").asText().equals(
                        Hashes.sha256(request.path("evidenceGatewayJson").asText())),
                        "MANAGED_RECEIPT_MISMATCH");
            require(cleaned(row), "MANAGED_CLEANUP_UNCONFIRMED");
            if (!row.path("status").asText().equals("COMPLETED"))
                throw new IOException(terminalErrorCode(row, diagnostic));
            String loadedText = body.startsWith("\uFEFF") ? body.substring(1) : body;
            loadedText = loadedText.replace("\r\n", "\n").replace('\r', '\n');
            JsonNode evidence = validateEvidence(row.path("result"), jobId, Hashes.sha256(source), source.length,
                    profile, Hashes.sha256(loadedText));
            if (!httpImageId.isEmpty() && evidence.path("loaderValidation").path("nodes").valueStream()
                    .anyMatch(node -> node.path("id").asText().equals("S06")
                            && node.path("status").asText().equals("pass"))
                    && profile.path("requiredNodes").valueStream().anyMatch(node -> node.asText().equals("R04"))) {
                observeHttp(jobId, source, profile, (ObjectNode) evidence);
            }
            if (Thread.interrupted()) throw new InterruptedException();
            completed = true;
            return evidence;
        } catch (IOException e) {
            if (diagnostic != null) throw new ManagedExecutionFailure(errorCode(e.getMessage()), diagnostic);
            throw e;
        } finally {
            try {
                if (completed && !Thread.currentThread().isInterrupted()) cleanup(jobId, snapshot);
                else cancelAcceptance(jobId, snapshot);
            } catch (IOException e) {
                if (diagnostic != null) throw new ManagedExecutionFailure("MANAGED_CLEANUP_UNCONFIRMED", diagnostic);
                throw e;
            }
        }
    }

    private String terminalErrorCode(JsonNode row, JsonNode diagnostic) {
        JsonNode context = diagnostic == null ? mapper.nullNode() : diagnostic.path("exchangeContext");
        String denied = context.path("lastDeniedCode").asText();
        if (row.path("code").asText().equals("EGRESS_PROTOCOL_INVALID")
                && context.path("lastFetchDenied").asBoolean(false) && EGRESS_FAILURES.contains(denied))
            return denied;
        return errorCode(row.path("code").asText());
    }

    private void validateIdentity(JsonNode identity) throws IOException {
        require(identity.path("authority").asText().equals("managed_source_broker_configuration")
                && identity.path("instanceId").asText().equals(instanceId)
                && identity.path("imageId").asText().equals(imageId)
                && identity.path("bundleSha256").asText().equals(runtimeSha)
                && (sampling() ? identity.path("egressPolicySha256").asText().equals(egressPolicySha)
                    : identity.path("egressPolicySha256").isMissingNode()
                        || identity.path("egressPolicySha256").isNull())
                && identity.path("recoveryRequired").isBoolean()
                && (httpImageId.isEmpty() ? !identity.hasNonNull("httpImageId")
                    : identity.path("httpImageId").asText().equals(httpImageId))
                && !identity.path("recoveryRequired").booleanValue(), "MANAGED_RUNNER_IDENTITY_MISMATCH");
    }

    public JsonNode checkConnection() throws InterruptedException {
        ObjectNode result = mapper.createObjectNode().put("authority", "managed_source_connection_observed")
                .put("connected", false).put("ready", false).put("sourceExecuted", false);
        String blocked = configurationBlockedReason();
        if (blocked != null) return result.put("code", blocked);
        try {
            JsonNode identity = control(mapper.createObjectNode().put("schemaVersion", 1).put("operation", "identity"));
            result.put("connected", true);
            validateIdentity(identity);
            return result.put("ready", true).put("code", "");
        } catch (IOException error) {
            return result.put("code", error instanceof java.net.ConnectException
                    ? "MANAGED_TRANSPORT_UNAVAILABLE" : errorCode(error.getMessage()));
        }
    }

    private void observeHttp(String jobId, byte[] source, JsonNode profile, ObjectNode evidence)
            throws IOException, InterruptedException {
        ObjectNode start = request("start_http", jobId, policySha)
                .put("sourceSha256", Hashes.sha256(source))
                .put("sourceBase64", Base64.getEncoder().encodeToString(source))
                .put("loaderProfileJson", profile.toString());
        // A lost start reply cancels the whole acceptance in inspect's finally; never replay the matrix.
        JsonNode row = control(start);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(
                manifest.path("httpPolicy").path("timeoutSeconds").asLong() + 90);
        while (true) {
            validateRow(row, jobId, policySha);
            JsonNode phase = row.path("httpAcceptance");
            require(row.path("status").asText().equals("COMPLETED")
                    && row.path("sourceSha256").asText().equals(Hashes.sha256(source))
                    && row.path("loaderProfileSha256").asText().equals(Hashes.sha256(profile.toString()))
                    && phase.path("schemaVersion").isInt() && phase.path("schemaVersion").intValue() == 1
                    && phase.path("authority").asText().equals("managed_t4_http_acceptance_observed")
                    && phase.path("imageId").asText().equals(httpImageId)
                    && Set.of("RUNNING", "COMPLETED", "FAILED", "CANCELLED", "CLEANUP_UNCONFIRMED")
                        .contains(phase.path("status").asText()), "T4_HTTP_RECEIPT_MISMATCH");
            if (TERMINAL.contains(phase.path("status").asText())) {
                require(cleaned(phase), "MANAGED_CLEANUP_UNCONFIRMED");
                require(!phase.path("status").asText().equals("CANCELLED"), "MANAGED_CANCELLED");
                var binding = mapper.createObjectNode().put("jobId", jobId).put("instanceId", instanceId)
                        .put("imageId", imageId).put("bundleSha256", runtimeSha)
                        .put("runtimeSha256", manifest.path("runtimeSha256").asText())
                        .put("executionPolicySha256", policySha).put("egressPolicySha256", egressPolicySha)
                        .put("sourceSha256", Hashes.sha256(source))
                        .put("inputProfileSha256", Hashes.sha256(profile.toString()));
                new ManagedLoaderReceipt(mapper).applyHttp(
                        (ObjectNode) evidence.path("loaderValidation"), phase, binding);
                return;
            }
            require(System.nanoTime() < deadline, "MANAGED_EXECUTION_TIMEOUT");
            Thread.sleep(100);
            row = control(request("status", jobId, policySha));
        }
    }

    JsonNode projectDiagnostic(JsonNode input) {
        if (!input.path("schemaVersion").isIntegralNumber()
                || !input.path("schemaVersion").canConvertToInt() || input.path("schemaVersion").intValue() != 1
                || !input.path("authority").asText().equals("managed_source_broker_observed")
                || !input.path("createIssued").isBoolean() || !input.path("createConfirmed").isBoolean()
                || !boundedInteger(input.path("createAckBytes"), 196608)
                || !input.path("calls").isArray() || input.path("calls").size() > 24) return null;
        Set<String> operations = Set.of("engine_info", "image_inspect", "container_lookup", "container_create",
                "container_inspect", "container_remove", "unknown");
        Set<String> stages = Set.of("engine_info", "image_inspect", "container_lookup", "container_create",
                "container_inspect", "container_remove", "unknown", "execution", "container_validate",
                "source_exchange", "container_exit_validate", "cleanup");
        ObjectNode safe = mapper.createObjectNode().put("schemaVersion", 1)
                .put("authority", "managed_source_broker_observed")
                .put("createIssued", input.path("createIssued").booleanValue())
                .put("createConfirmed", input.path("createConfirmed").booleanValue())
                .put("createAckBytes", input.path("createAckBytes").intValue());
        safe.put("failureStage", stages.contains(input.path("failureStage").asText())
                ? input.path("failureStage").asText() : null);
        for (String field : List.of("primaryCode", "cleanupCode"))
            safe.put(field, input.path(field).isNull() ? null : diagnosticCode(input.path(field).asText()));
        var calls = safe.putArray("calls");
        for (JsonNode call : input.path("calls")) {
            if (!operations.contains(call.path("operation").asText())
                    || !Set.of("returned", "failed").contains(call.path("outcome").asText())
                    || !boundedInteger(call.path("elapsedMillis"), 3_600_000)) return null;
            var projected = calls.addObject().put("operation", call.path("operation").asText())
                    .put("outcome", call.path("outcome").asText())
                    .put("elapsedMillis", call.path("elapsedMillis").intValue());
            if (call.path("outcome").asText().equals("failed"))
                projected.put("code", diagnosticCode(call.path("code").asText()));
            if (boundedInteger(call.path("osErrno"), 4095)) projected.put("osErrno", call.path("osErrno").intValue());
        }
        JsonNode context = input.path("exchangeContext");
        if (context.isObject() && Set.of("ready", "source", "frame", "loader", "fetch", "exit")
                .contains(context.path("phase").asText()) && boundedInteger(context.path("lastHttpId"), 64)
                && boundedInteger(context.path("loaderCallCount"), 32) && context.path("policyDenied").isBoolean()
                && (context.path("peerExitCode").isNull()
                    || context.path("peerExitCode").isInt() && Math.abs((long) context.path("peerExitCode").intValue()) <= 255)) {
            var exchange = safe.putObject("exchangeContext");
            for (String field : List.of("phase", "lastHttpId", "loaderCallCount", "policyDenied", "peerExitCode"))
                exchange.set(field, context.path(field).deepCopy());
            if (context.path("lastFetchDenied").isBoolean())
                exchange.set("lastFetchDenied", context.path("lastFetchDenied").deepCopy());
            exchange.put("lastDeniedCode", EGRESS_FAILURES.contains(context.path("lastDeniedCode").asText())
                    ? context.path("lastDeniedCode").asText() : null);
            JsonNode redirect = context.path("redirectObservation");
            if (Set.of(301, 302, 303, 307, 308).contains(redirect.path("responseStatus").asInt())
                    && redirect.path("requestUrlSha256").asText().matches("[a-f0-9]{64}")
                    && redirect.path("targetUrlSha256").asText().matches("[a-f0-9]{64}")
                    && Set.of("http", "https", "other").contains(redirect.path("targetScheme").asText())) {
                var observed = exchange.putObject("redirectObservation");
                for (String field : List.of("responseStatus", "requestUrlSha256", "targetUrlSha256", "targetScheme"))
                    observed.set(field, redirect.path(field).deepCopy());
                String origin = redirect.path("targetOrigin").asText();
                if (origin.matches("https://[a-z0-9][a-z0-9.-]{0,251}[a-z0-9](?::[0-9]{1,5})?"))
                    observed.put("targetOrigin", origin);
            }
            JsonNode rate = projectRateLimit(context.path("rateLimit"));
            if (rate != null) exchange.set("rateLimit", rate);
            JsonNode network = projectNetworkObservation(context.path("networkObservation"));
            if (network != null) exchange.set("networkObservation", network);
            if (boundedInteger(context.path("completedHttpCount"), 64))
                exchange.set("completedHttpCount", context.path("completedHttpCount").deepCopy());
            JsonNode request = projectRequestDiagnostic(context.path("requestDiagnostic"));
            if (request != null) exchange.set("requestDiagnostic", request);
            JsonNode denials = context.path("denialObservations");
            if (denials.isArray() && denials.size() <= 64) {
                var observations = mapper.createArrayNode();
                boolean valid = true;
                int previousId = 0;
                for (JsonNode denial : denials) {
                    if (!boundedInteger(denial.path("httpId"), context.path("lastHttpId").asInt())
                            || denial.path("httpId").asInt() <= previousId
                            || !EGRESS_FAILURES.contains(denial.path("code").asText())) {
                        valid = false;
                        break;
                    }
                    previousId = denial.path("httpId").asInt();
                    var observation = observations.addObject().put("httpId", previousId)
                            .put("code", denial.path("code").asText());
                    JsonNode detail = projectRequestDiagnostic(denial.path("requestDiagnostic"));
                    if (detail != null) observation.set("requestDiagnostic", detail);
                }
                if (valid) exchange.set("denialObservations", observations);
            }
            JsonNode completed = projectCompletedHttp(context.path("completedHttpObservations"),
                    context.path("completedHttpCount"));
            if (completed != null) exchange.set("completedHttpObservations", completed);
            JsonNode gateway = projectGatewayProgress(context.path("runtimeGateway"));
            if (gateway != null) exchange.set("runtimeGateway", gateway);
            JsonNode progress = projectLoaderProgress(context.path("loaderProgress"));
            if (progress != null) exchange.set("loaderProgress", progress);
            JsonNode http = context.path("httpObservation");
            if (Set.of("GET", "HEAD", "POST").contains(http.path("method").asText())
                    && http.path("requestUrlSha256").asText().matches("[a-f0-9]{64}")
                    && boundedInteger(http.path("requestNumber"), 64) && http.path("requestNumber").asInt() > 0
                    && boundedInteger(http.path("responseStatus"), 599) && http.path("responseStatus").asInt() >= 100
                    && validContentType(http.path("responseContentType"))) {
                var observed = exchange.putObject("httpObservation");
                for (String field : List.of("method", "requestUrlSha256", "requestNumber",
                        "responseStatus", "responseContentType")) observed.set(field, http.path(field).deepCopy());
            }
            JsonNode dns = projectDnsObservation(context.path("dnsObservation"));
            if (dns != null) exchange.set("dnsObservation", dns);
            JsonNode dnsRows = context.path("dnsObservations");
            if (dnsRows.isArray() && dnsRows.size() <= 64
                    && boundedInteger(context.path("dnsObservationsDropped"), 2048)) {
                var safeDns = exchange.putArray("dnsObservations");
                for (JsonNode row : dnsRows) {
                    JsonNode projected = projectDnsObservation(row);
                    if (projected != null) safeDns.add(projected);
                }
                exchange.set("dnsObservationsDropped", context.path("dnsObservationsDropped"));
            }
        }
        return safe;
    }

    JsonNode projectLoaderProgress(JsonNode input) {
        return new ManagedLoaderReceipt(mapper).partialProgress(input);
    }

    private JsonNode projectRequestDiagnostic(JsonNode request) {
        String kind = request.path("kind").asText(), reason = request.path("reason").asText();
        var projected = mapper.createObjectNode().put("kind", kind).put("reason", reason);
        if (kind.equals("byte_budget")) {
            JsonNode observed = request.path("observedResponseBytes");
            if (!Set.of("single_response_bytes", "total_response_bytes", "range_response_bytes").contains(reason)
                    || !Set.of("before_request", "content_length", "body_read").contains(request.path("stage").asText())
                    || !boundedInteger(request.path("maxResponseBytes"), MAX_RESPONSE_BYTES)
                    || request.path("maxResponseBytes").asInt() == 0
                    || !boundedInteger(request.path("maxTotalResponseBytes"), MAX_TOTAL_RESPONSE_BYTES)
                    || request.path("maxTotalResponseBytes").asInt() == 0
                    || !boundedInteger(request.path("totalResponseBytesBefore"), MAX_TOTAL_RESPONSE_BYTES + 1)
                    || !boundedInteger(request.path("effectiveResponseLimitBytes"), MAX_RESPONSE_BYTES)
                    || !observed.isIntegralNumber() || observed.bigIntegerValue().signum() < 0
                    || observed.bigIntegerValue().toString().length() > 20) return null;
            for (String field : List.of("stage", "maxResponseBytes", "maxTotalResponseBytes",
                    "totalResponseBytesBefore", "effectiveResponseLimitBytes", "observedResponseBytes"))
                projected.set(field, request.path(field).deepCopy());
            return projected;
        }
        if (kind.equals("url") && Set.of("origin_not_registered", "url_type", "url_length", "url_encoding",
                "url_control", "url_userinfo", "url_fragment", "url_not_registered", "origin_mismatch",
                "url_syntax", "url_scheme").contains(reason)) {
            if (Set.of("GET", "HEAD", "POST").contains(request.path("method").asText()))
                projected.set("method", request.path("method").deepCopy());
            for (String field : List.of("requestUrlSha256", "requestOriginSha256"))
                if (request.path(field).asText().matches("[a-f0-9]{64}"))
                    projected.set(field, request.path(field).deepCopy());
            if (Set.of("http", "https").contains(request.path("requestScheme").asText()))
                projected.set("requestScheme", request.path("requestScheme").deepCopy());
            return projected;
        }
        if (!kind.equals("header") || !Set.of("header_shape", "header_count", "name_type",
                "header_not_allowed", "duplicate_name", "value_type", "value_size", "value_encoding",
                "value_control", "total_bytes", "cookie_format", "cookie_capacity", "cookie_not_observed",
                "origin_not_allowed", "session_token_not_observed").contains(reason)) return null;
        if (request.path("headerName").isTextual()
                && request.path("headerName").textValue().matches("[a-z][a-z0-9-]{0,63}"))
            projected.put("headerName", request.path("headerName").textValue());
        if (Set.of("origin_not_allowed", "session_token_not_observed").contains(reason))
            for (String field : List.of("requestUrlSha256", "requestOriginSha256", "headerOriginSha256"))
                if (request.path(field).asText().matches("[a-f0-9]{64}"))
                    projected.set(field, request.path(field).deepCopy());
        JsonNode state = request.path("cookieState");
        if (reason.equals("cookie_not_observed") && state.path("expectedPresent").isBoolean()
                && state.path("suppliedParsed").isBoolean() && state.path("expectedParsed").isBoolean()
                && boundedInteger(state.path("storedCount"), 32) && boundedInteger(state.path("suppliedCount"), 32)
                && boundedInteger(state.path("expectedCount"), 32)
                && boundedInteger(state.path("matchingStoredCount"), 32)) {
            var cookies = projected.putObject("cookieState");
            for (String field : List.of("storedCount", "expectedPresent", "suppliedParsed",
                    "expectedParsed", "suppliedCount", "expectedCount", "matchingStoredCount"))
                cookies.set(field, state.path(field).deepCopy());
        }
        return projected;
    }

    private JsonNode projectCompletedHttp(JsonNode rows, JsonNode count) {
        if (!rows.isArray() || !boundedInteger(count, 64) || rows.size() != count.intValue()) return null;
        var safe = mapper.createArrayNode();
        for (JsonNode row : rows) {
            if (!Set.of("GET", "HEAD", "POST").contains(row.path("method").asText())
                    || !row.path("requestUrlSha256").asText().matches("[a-f0-9]{64}")
                    || !boundedInteger(row.path("responseStatus"), 599)
                    || row.path("responseStatus").intValue() < 100
                    || !boundedInteger(row.path("responseBytes"), MAX_RESPONSE_BYTES)
                    || !row.path("responseSha256").asText().matches("[a-f0-9]{64}")
                    || !row.path("responseComplete").isBoolean()
                    || !validContentType(row.path("responseContentType"))) return null;
            var event = safe.addObject();
            for (String field : List.of("method", "requestUrlSha256", "responseStatus", "responseBytes",
                    "responseSha256", "responseComplete", "responseContentType"))
                event.set(field, row.path(field).deepCopy());
            if (row.path("requestCookiePresent").isBoolean())
                event.set("requestCookiePresent", row.path("requestCookiePresent").deepCopy());
            if (boundedInteger(row.path("receivedSetCookieCount"), 32))
                event.set("receivedSetCookieCount", row.path("receivedSetCookieCount").deepCopy());
            if (row.path("transport").asText().equals("external_evidence_gateway")
                    && row.path("representation").asText().equals("gateway_rendered_document")
                    && row.path("gatewaySessionReused").isBoolean()
                    && row.path("cookiesExported").isBoolean() && !row.path("cookiesExported").asBoolean()) {
                for (String field : List.of("transport", "representation", "gatewaySessionReused", "cookiesExported"))
                    event.set(field, row.path(field).deepCopy());
            }
            if (row.path("challengePage").isBoolean()) event.set("challengePage", row.path("challengePage"));
            if (knownGatewayOutcome(row.path("gatewayOutcome").asText()))
                event.set("gatewayOutcome", row.path("gatewayOutcome"));
            if (row.path("gatewayOutcome").asText().equals("request_not_reproducible")
                    && Set.of("request_method", "request_body", "host_credentials", "request_headers",
                        "response_content_type").contains(row.path("gatewaySkipReason").asText()))
                event.set("gatewaySkipReason", row.path("gatewaySkipReason"));
        }
        return safe;
    }

    private JsonNode projectGatewayProgress(JsonNode input) {
        if (!Set.of("trawl", "flaresolverr", "flaresolverr-rs-alt").contains(input.path("adapter").asText())
                || !input.path("cookiesExported").isBoolean() || input.path("cookiesExported").asBoolean()
                || !input.path("cleanupConfirmed").isBoolean()) return null;
        var safe = mapper.createObjectNode().put("fullAcceptance", false);
        for (String key : List.of("sessionCount", "requestCount", "responseCount", "sessionReuseCount")) {
            if (!boundedInteger(input.path(key), 64)) return null;
            safe.set(key, input.path(key));
        }
        for (String key : List.of("adapter", "cookiesExported", "cleanupConfirmed")) safe.set(key, input.path(key));
        return safe;
    }

    private boolean knownGatewayOutcome(String outcome) {
        return Set.of("request_not_reproducible", "response_returned", "target_rate_limited", "EVIDENCE_GATEWAY_BUSY",
                "EVIDENCE_GATEWAY_UNAVAILABLE", "EVIDENCE_GATEWAY_DEADLINE", "EVIDENCE_GATEWAY_TIMEOUT",
                "EVIDENCE_GATEWAY_TARGET_DNS_FAILED", "EVIDENCE_GATEWAY_HTTP_FAILED",
                "EVIDENCE_GATEWAY_CHALLENGE_BLOCKED", "EVIDENCE_GATEWAY_SESSION_INVALID",
                "EVIDENCE_GATEWAY_RESPONSE_INVALID", "EVIDENCE_GATEWAY_RESPONSE_TOO_LARGE",
                "EVIDENCE_GATEWAY_BUDGET", "EVIDENCE_GATEWAY_EMPTY_OR_INVALID_RESPONSE",
                "EVIDENCE_GATEWAY_FINAL_URL_CHANGED", "EVIDENCE_GATEWAY_FINAL_URL_DENIED",
                "EVIDENCE_GATEWAY_REPRESENTATION_INVALID", "EVIDENCE_GATEWAY_SESSION_LOST",
                "EVIDENCE_GATEWAY_CONNECTION_FAILED", "EVIDENCE_GATEWAY_CONNECT_TIMEOUT",
                "EVIDENCE_GATEWAY_READ_TIMEOUT", "EVIDENCE_GATEWAY_CLEANUP_UNCONFIRMED").contains(outcome);
    }

    private JsonNode projectNetworkObservation(JsonNode row) {
        if (!row.path("requestUrlSha256").isTextual()
                || !row.path("requestUrlSha256").asText().matches("[a-f0-9]{64}")
                || !boundedInteger(row.path("requestNumber"), 64) || row.path("requestNumber").asInt() == 0
                || !boundedInteger(row.path("addressIndex"), 15)
                || !boundedInteger(row.path("elapsedMilliseconds"), 120000)
                || !Set.of("tcp_connect", "tls_handshake", "request_send", "response_headers", "response_body")
                    .contains(row.path("stage").asText())
                || !Set.of("certificate", "timeout", "tls", "connection_refused", "connection_reset",
                    "http_protocol", "invalid_response", "io").contains(row.path("kind").asText())
                || !(row.path("errno").isNull() || row.path("errno").isIntegralNumber()
                    && row.path("errno").canConvertToInt() && row.path("errno").intValue() >= -1
                    && row.path("errno").intValue() <= 65535)) return null;
        var safe = mapper.createObjectNode();
        for (String key : List.of("requestUrlSha256", "requestNumber", "addressIndex", "stage",
                "kind", "errno", "elapsedMilliseconds")) safe.set(key, row.path(key).deepCopy());
        return safe;
    }

    private JsonNode projectRateLimit(JsonNode input) {
        JsonNode timing = input.path("retryAfter"), seconds = timing.path("seconds");
        String kind = timing.path("kind").asText();
        if (!input.path("responseStatus").isInt() || input.path("responseStatus").intValue() != 429
                || !input.path("automaticRetry").isBoolean() || input.path("automaticRetry").booleanValue()
                || !Set.of("absent", "invalid", "delay", "date").contains(kind)
                || !(Set.of("absent", "invalid").contains(kind) ? seconds.isNull()
                    : seconds.isIntegralNumber() && seconds.bigIntegerValue().signum() >= 0
                        && seconds.bigIntegerValue().toString().length() <= 128)) return null;
        var safe = mapper.createObjectNode().put("responseStatus", 429).put("automaticRetry", false);
        safe.putObject("retryAfter").put("kind", kind).set("seconds", seconds.deepCopy());
        if (boundedInteger(input.path("requestNumber"), 64) && input.path("requestNumber").asInt() > 0
                && input.path("requestUrlSha256").asText().matches("[a-f0-9]{64}")) {
            safe.set("requestNumber", input.path("requestNumber").deepCopy());
            safe.set("requestUrlSha256", input.path("requestUrlSha256").deepCopy());
        }
        JsonNode observed = input.path("observedAtEpochMilliseconds"), context = input.path("cooldownContext");
        JsonNode recorded = context.path("recordedAtEpochMilliseconds"), remaining = context.path("remainingWaitMilliseconds"),
                budget = context.path("taskBudgetRemainingMilliseconds"), fits = context.path("fitsTaskBudget");
        if (nonnegativeNumber(observed, 13) && nonnegativeNumber(recorded, 13)
                && recorded.bigIntegerValue().compareTo(observed.bigIntegerValue()) >= 0
                && Set.of("at_response", "before_wait", "receipt").contains(context.path("phase").asText())
                && (remaining.isNull() || nonnegativeNumber(remaining, 131))
                && (budget.isNull() || nonnegativeNumber(budget, 9))
                && (seconds.isNull() == remaining.isNull())
                && (remaining.isNull() || remaining.bigIntegerValue().compareTo(
                    seconds.bigIntegerValue().multiply(java.math.BigInteger.valueOf(1000))) <= 0)
                && ((remaining.isNull() || budget.isNull()) ? fits.isNull() : fits.isBoolean()
                    && fits.booleanValue() == (remaining.bigIntegerValue().compareTo(budget.bigIntegerValue()) < 0))) {
            safe.set("observedAtEpochMilliseconds", observed.deepCopy());
            var snapshot = safe.putObject("cooldownContext");
            for (String field : List.of("phase", "recordedAtEpochMilliseconds", "remainingWaitMilliseconds",
                    "taskBudgetRemainingMilliseconds", "fitsTaskBudget"))
                snapshot.set(field, context.path(field).deepCopy());
        }
        return safe;
    }

    private boolean nonnegativeNumber(JsonNode value, int digits) {
        return value.isIntegralNumber() && value.bigIntegerValue().signum() >= 0
                && value.bigIntegerValue().toString().length() <= digits;
    }

    private void projectGatewayExecution(ObjectNode safe, JsonNode input) throws IOException {
        if (!input.has("gatewayExecution")) return;
        JsonNode execution = input.path("gatewayExecution"), tier = execution.path("tier"), cached = execution.path("sessionCached");
        require(execution.isObject() && (tier.isNull() || boundedInteger(tier, 4) && tier.asInt() > 0)
                && (cached.isNull() || cached.isBoolean()), "MANAGED_RECEIPT_MISMATCH");
        safe.putObject("gatewayExecution").set("tier", tier.deepCopy());
        ((ObjectNode) safe.path("gatewayExecution")).set("sessionCached", cached.deepCopy());
    }

    private void projectGatewayRecovery(ObjectNode safe, JsonNode input) throws IOException {
        if (!input.has("gatewayRecovery")) return;
        JsonNode recovery = input.path("gatewayRecovery"), wait = recovery.path("remainingWaitMilliseconds"),
                budget = recovery.path("taskBudgetRemainingMilliseconds"), fits = recovery.path("fitsTaskBudget");
        String action = recovery.path("action").asText();
        require(recovery.isObject() && Set.of("response", "connect", "read", "decode", "cleanup", "create", "navigate")
                .contains(recovery.path("phase").asText())
                && Set.of("stopped", "retry_ready", "reset_after_cooldown", "cooldown").contains(action)
                && (wait.isNull() || nonnegativeNumber(wait, 131)) && nonnegativeNumber(budget, 9)
                && (wait.isNull() ? action.equals("stopped") && fits.isNull() : fits.isBoolean()
                    && !action.equals("stopped") && (action.equals("retry_ready") == (wait.bigIntegerValue().signum() == 0))
                    && fits.booleanValue() == (wait.bigIntegerValue().compareTo(budget.bigIntegerValue()) < 0)),
                "MANAGED_RECEIPT_MISMATCH");
        var projected = safe.putObject("gatewayRecovery");
        for (String key : List.of("phase", "action", "remainingWaitMilliseconds", "taskBudgetRemainingMilliseconds", "fitsTaskBudget"))
            projected.set(key, recovery.path(key).deepCopy());
    }

    private JsonNode projectDnsObservation(JsonNode row) {
        if (!(row.path("dnsStatus").isNull() || boundedInteger(row.path("dnsStatus"), 65535))
                || !boundedInteger(row.path("answerCount"), 65535)
                || !boundedInteger(row.path("publicIpv4Count"), 16)
                || !Set.of("success", "no_address", "resolver_error", "nonpublic_address", "invalid_response",
                    "transport_error").contains(row.path("kind").asText())) return null;
        var safe = mapper.createObjectNode();
        for (String field : List.of("dnsStatus", "answerCount", "publicIpv4Count", "kind"))
            safe.set(field, row.path(field).deepCopy());
        if (Set.of("A", "AAAA").contains(row.path("recordType").asText()))
            safe.set("recordType", row.path("recordType").deepCopy());
        if ("AAAA".equals(row.path("recordType").asText()) && boundedInteger(row.path("publicIpv6Count"), 16))
            safe.set("publicIpv6Count", row.path("publicIpv6Count").deepCopy());
        if (Set.of("alidns", "cloudflare").contains(row.path("resolver").asText()))
            safe.set("resolver", row.path("resolver").deepCopy());
        if ("alidns".equals(row.path("fallbackFrom").asText())) safe.put("fallbackFrom", "alidns");
        if (row.path("originSha256").asText().matches("[a-f0-9]{64}"))
            safe.set("originSha256", row.path("originSha256").deepCopy());
        if (row.path("cacheHit").isBoolean()) safe.set("cacheHit", row.path("cacheHit").deepCopy());
        if (!row.path("cacheHit").asBoolean() && boundedInteger(row.path("elapsedMilliseconds"), 120000))
            safe.set("elapsedMilliseconds", row.path("elapsedMilliseconds").deepCopy());
        return safe;
    }

    private void projectObservedCall(ObjectNode safe, JsonNode input, JsonNode calls) {
        JsonNode observed = input.path("observedDuringCall");
        if (!observed.isObject() || observed.size() != 3 || !boundedInteger(observed.path("id"), 32)
                || observed.path("id").asInt() == 0
                || !observed.path("inputSha256").asText().matches("[a-f0-9]{64}")) return;
        for (JsonNode call : calls)
            if (observed.path("id").equals(call.path("id"))
                    && observed.path("method").equals(call.path("method"))
                    && observed.path("inputSha256").equals(call.path("inputSha256"))) {
                safe.set("observedDuringCall", observed.deepCopy());
                return;
            }
    }

    private boolean boundedInteger(JsonNode value, int maximum) {
        return value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= 0 && value.intValue() <= maximum;
    }

    private boolean validContentType(JsonNode value) {
        return value.isTextual() && (value.asText().isEmpty()
                || value.asText().matches("[a-z0-9!#$&^_.+-]{1,64}/[a-z0-9!#$&^_.+-]{1,64}"));
    }

    private String diagnosticCode(String code) {
        return Set.of("MANAGED_BROKER_FAILED", "ENGINE_STREAM_UNCLOSED", "ENGINE_COMMAND_FAILED",
                "CONTAINER_LOOKUP_INVALID", "CONTAINER_OWNERSHIP_MISMATCH", "CONTAINER_CLEANUP_UNCONFIRMED",
                "CONTAINER_CREATE_INVALID", "CONTAINER_INSPECT_INVALID", "CONTAINER_IMAGE_MISMATCH",
                "CONTAINER_CONFIG_MISMATCH", "CONTAINER_ENV_MISMATCH", "CONTAINER_LIMITS_MISMATCH",
                "CONTAINER_ACCESS_MISMATCH", "CONTAINER_STATE_MISMATCH", "CONTAINER_CONTROLS_UNCONFIRMED",
                "LOCAL_IMAGE_MISMATCH", "MANAGED_BUNDLE_MISMATCH", "MANAGED_READY_MISMATCH",
                "MANAGED_SOURCE_SEND_FAILED", "EGRESS_PROTOCOL_INVALID").contains(code) ? code : errorCode(code);
    }

    public void cleanup(String jobId, JsonNode frozenPolicy) throws IOException {
        if (!sourceRuntimes.isEmpty() && frozenPolicy != null
                && !policySha.equals(Hashes.sha256(frozenPolicy.toString()))) {
            cleanupRoute(frozenPolicy).cleanup(jobId, frozenPolicy);
            return;
        }
        cleanup(jobId, frozenPolicy, false);
    }

    public void cancelAcceptance(String jobId, JsonNode frozenPolicy) throws IOException {
        if (!sourceRuntimes.isEmpty() && frozenPolicy != null
                && !policySha.equals(Hashes.sha256(frozenPolicy.toString()))) {
            cleanupRoute(frozenPolicy).cancelAcceptance(jobId, frozenPolicy);
            return;
        }
        cleanup(jobId, frozenPolicy, true);
    }

    private ManagedSourceRuntime cleanupRoute(JsonNode frozenPolicy) throws IOException {
        try { return forPolicy(Hashes.sha256(frozenPolicy.toString())); }
        catch (IOException error) { throw new IOException("MANAGED_CLEANUP_UNCONFIRMED"); }
    }

    private void cleanup(String jobId, JsonNode frozenPolicy, boolean acceptance) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            for (String field : List.of("transportPathSha256", "instanceId", "imageId", "bundleSha256")) {
                require(frozenPolicy != null && !snapshot.path(field).isNull()
                        && snapshot.path(field).equals(frozenPolicy.path(field)), "MANAGED_CLEANUP_UNCONFIRMED");
            }
            require(frozenPolicy.path("executionKind").equals(snapshot.path("executionKind"))
                    && frozenPolicy.path("egressPolicySha256").equals(snapshot.path("egressPolicySha256"))
                    && frozenPolicy.path("httpImageId").equals(snapshot.path("httpImageId"))
                    && frozenPolicy.path("httpPolicy").equals(snapshot.path("httpPolicy")),
                    "MANAGED_CLEANUP_UNCONFIRMED");
            require(!endpoint.isBlank(), "MANAGED_CLEANUP_UNCONFIRMED");
            String frozenSha = Hashes.sha256(frozenPolicy.toString());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (true) {
                JsonNode row = control(request(acceptance ? "cancel_acceptance" : "cancel", jobId, frozenSha));
                validateRow(row, jobId, frozenSha);
                JsonNode summary = row.path("acceptanceCleanup");
                if (acceptance) {
                    require(row.path("acceptanceCancelled").isBoolean()
                            && row.path("acceptanceCancelled").booleanValue()
                            && summary.path("confirmed").isBoolean()
                            && summary.path("recoveryRequired").isBoolean()
                            && !summary.path("recoveryRequired").booleanValue()
                            && boundedInteger(summary.path("childJobs"), Integer.MAX_VALUE)
                            && boundedInteger(summary.path("pendingJobs"), Integer.MAX_VALUE)
                            && summary.path("pendingJobs").asLong() <= summary.path("childJobs").asLong() + 1,
                            "MANAGED_CLEANUP_UNCONFIRMED");
                }
                if (TERMINAL.contains(row.path("status").asText())
                        && (!acceptance || summary.path("confirmed").booleanValue())) {
                    require(cleaned(row), "MANAGED_CLEANUP_UNCONFIRMED");
                    if (acceptance) require(summary.path("pendingJobs").intValue() == 0,
                            "MANAGED_CLEANUP_UNCONFIRMED");
                    return;
                }
                require(System.nanoTime() < deadline, "MANAGED_CLEANUP_UNCONFIRMED");
                Thread.sleep(100);
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            throw new IOException("MANAGED_CLEANUP_UNCONFIRMED", e);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private ObjectNode request(String operation, String jobId, String policy) throws IOException {
        require(jobId.matches(UUID), "MANAGED_INPUT_MISMATCH");
        ObjectNode request = mapper.createObjectNode().put("schemaVersion", 1).put("operation", operation).put("jobId", jobId)
                .put("instanceId", instanceId).put("imageId", imageId).put("bundleSha256", runtimeSha)
                .put("executionPolicySha256", policy);
        if (sampling()) request.put("egressPolicySha256", egressPolicySha);
        return request;
    }

    private void validateRow(JsonNode row, String jobId, String policy) throws IOException {
        require(row.path("schemaVersion").isIntegralNumber() && row.path("schemaVersion").canConvertToInt()
                && row.path("schemaVersion").intValue() == 1
                && row.path("authority").asText().equals("managed_source_broker_observed")
                && row.path("jobId").asText().equals(jobId) && row.path("instanceId").asText().equals(instanceId)
                && row.path("imageId").asText().equals(imageId) && row.path("bundleSha256").asText().equals(runtimeSha)
                && row.path("executionPolicySha256").asText().equals(policy)
                && Set.of("RUNNING", "COMPLETED", "FAILED", "CANCELLED", "CLEANUP_UNCONFIRMED")
                    .contains(row.path("status").asText()), "MANAGED_RECEIPT_MISMATCH");
    }

    private boolean cleaned(JsonNode row) {
        return row.path("cleanup").path("confirmed").isBoolean()
                && row.path("cleanup").path("confirmed").booleanValue()
                && row.path("cleanup").path("containerAbsent").isBoolean()
                && row.path("cleanup").path("containerAbsent").booleanValue();
    }

    private JsonNode validateEvidence(JsonNode result, String jobId, String sourceSha, int sourceBytes,
                                      JsonNode profile, String textSha)
            throws IOException {
        JsonNode ready = result.path("preSourceReady");
        JsonNode controls = ready.path("controls");
        require(result.path("schemaVersion").isIntegralNumber() && result.path("schemaVersion").canConvertToInt()
                && result.path("schemaVersion").intValue() == 1
                && result.path("authority").asText().equals("managed_source_host_observed")
                && result.path("jobId").asText().equals(jobId)
                && result.path("imageId").asText().equals(imageId)
                && result.path("containerId").asText().matches("[a-f0-9]{64}")
                && result.path("inputSourceSha256").asText().equals(sourceSha)
                && result.path("inputSourceBytes").isIntegralNumber()
                && result.path("inputSourceBytes").asLong() == sourceBytes
                && result.path("sourceSentAfterControlAck").isBoolean()
                && result.path("sourceSentAfterControlAck").booleanValue()
                && result.path("rawOutputStored").isBoolean() && !result.path("rawOutputStored").booleanValue()
                && result.path("businessChainVerified").isBoolean()
                && !result.path("businessChainVerified").booleanValue()
                && (sampling() ? result.path("egressPolicySha256").asText().equals(egressPolicySha)
                    && Hashes.sha256(result.path("policy").toString()).equals(egressPolicySha)
                    && result.path("executionKind").asText().equals("t4_business_sample")
                    : result.path("policy").equals(manifest.path("policy")))
                && cleaned(result)
                && ready.path("schemaVersion").isIntegralNumber() && ready.path("schemaVersion").canConvertToInt()
                && ready.path("schemaVersion").intValue() == 1
                && ready.path("phase").asText().equals("ready_before_source")
                && ready.path("jobId").asText().equals(jobId)
                && ready.path("runtimeSha256").asText().equals(manifest.path("runtimeSha256").asText())
                && (sampling() ? ready.path("files").equals(manifest.path("egressFiles"))
                    : ready.path("entrySha256").asText().equals(manifest.path("entrySha256").asText())
                        && ready.path("payloadSha256").asText().equals(manifest.path("payloadSha256").asText())),
                "MANAGED_RECEIPT_MISMATCH");
        JsonNode state = result.path("containerState");
        require(state.path("status").asText().equals("exited") && state.path("exitCode").isIntegralNumber()
                && state.path("exitCode").canConvertToInt() && state.path("exitCode").intValue() == 0
                && state.path("oomKilled").isBoolean() && !state.path("oomKilled").booleanValue(),
                "MANAGED_RECEIPT_MISMATCH");
        ObjectNode safeControls = mapper.createObjectNode();
        for (var pair : java.util.Map.ofEntries(
                java.util.Map.entry("uid", 65532L), java.util.Map.entry("gid", 65532L),
                java.util.Map.entry("capEffective", 0L), java.util.Map.entry("noNewPrivileges", 1L),
                java.util.Map.entry("seccomp", 2L), java.util.Map.entry("memoryMax", 268435456L),
                java.util.Map.entry("swapMax", 0L), java.util.Map.entry("pidsMax", 64L),
                java.util.Map.entry("cpuQuota", 50000L), java.util.Map.entry("cpuPeriod", 100000L),
                java.util.Map.entry("workSizeBytes", 33554432L)).entrySet()) {
            require(controls.path(pair.getKey()).isIntegralNumber()
                    && controls.path(pair.getKey()).canConvertToLong()
                    && controls.path(pair.getKey()).longValue() == pair.getValue(), "MANAGED_CONTROLS_UNCONFIRMED");
            safeControls.put(pair.getKey(), pair.getValue());
        }
        require(controls.path("rootReadOnly").isBoolean() && controls.path("rootReadOnly").booleanValue()
                && controls.path("allChecksPassed").isBoolean() && controls.path("allChecksPassed").booleanValue()
                && controls.path("interfaces").equals(mapper.valueToTree(List.of("lo")))
                && controls.path("coreLimit").equals(mapper.valueToTree(List.of(0, 0)))
                && controls.path("openFileLimit").equals(mapper.valueToTree(List.of(128, 128))),
                "MANAGED_CONTROLS_UNCONFIRMED");
        JsonNode options = controls.path("workMountOptions");
        Set<String> allowed = Set.of("rw", "nosuid", "nodev", "noexec", "relatime", "strictatime", "noatime", "size=32768k",
                "mode=700", "uid=65532", "gid=65532", "inode64");
        require(options.isArray() && options.size() <= 16, "MANAGED_CONTROLS_UNCONFIRMED");
        for (JsonNode option : options) require(option.isTextual() && allowed.contains(option.asText()),
                "MANAGED_CONTROLS_UNCONFIRMED");
        for (String required : List.of("rw", "nosuid", "nodev", "noexec"))
            require(options.valueStream().anyMatch(value -> value.asText().equals(required)), "MANAGED_CONTROLS_UNCONFIRMED");
        safeControls.put("rootReadOnly", true).put("allChecksPassed", true);
        for (String key : List.of("interfaces", "coreLimit", "openFileLimit", "workMountOptions"))
            safeControls.set(key, controls.path(key).deepCopy());
        String status = result.path("untrustedRuntimeSummary").path("status").asText();
        require((sampling() ? Set.of("init_returned", "home_empty", "category_returned", "detail_returned",
                    "source_exception", "invalid_output")
                : Set.of("init_returned", "missing_dependency", "source_exception", "invalid_output")).contains(status)
                && result.path("untrustedRuntimeSummary").path("evidenceClass").asText()
                    .equals("untrusted_source_output"), "MANAGED_RECEIPT_MISMATCH");
        ObjectNode evidence = mapper.createObjectNode().put("authority", "managed_source_host_observed")
                .put("jobId", jobId).put("instanceId", instanceId).put("imageId", imageId)
                .put("bundleSha256", runtimeSha).put("inputSourceSha256", sourceSha).put("inputSourceBytes", sourceBytes)
                .put("sourceSentAfterControlAck", true).put("businessChainVerified", false)
                .put("sourceHealthEligible", false).put("publishEligible", false).put("rawOutputStored", false);
        evidence.set("preSourceControls", safeControls);
        evidence.set("policy", snapshot.path("policy").deepCopy());
        evidence.putObject("untrustedRuntimeSummary").put("status", status).put("evidenceClass", "untrusted_source_output");
        evidence.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
        if (sampling() && result.has("loaderValidation")) {
            evidence.set("loaderValidation", new ManagedLoaderReceipt(mapper).project(
                    result.path("loaderValidation"), profile, sourceSha, textSha,
                    manifest.path("runtimeSha256").asText()));
        }
        if (sampling()) projectBusiness(result, evidence);
        for (String key : List.of("untrustedStdoutBytes", "untrustedStderrBytes")) {
            require(result.path(key).isIntegralNumber() && result.path(key).canConvertToInt()
                    && result.path(key).intValue() >= 0 && result.path(key).intValue() <=
                    (sampling() && key.equals("untrustedStdoutBytes") ? 1_048_576 : 196608),
                    "MANAGED_RECEIPT_MISMATCH");
            evidence.put(key, result.path(key).intValue());
        }
        return evidence;
    }

    private void projectBusiness(JsonNode result, ObjectNode evidence) throws IOException {
        evidence.put("executionKind", "t4_business_sample").put("egressPolicySha256", egressPolicySha);
        ObjectNode counts = (ObjectNode) evidence.path("untrustedRuntimeSummary");
        for (String key : List.of("homeClasses", "homeItems", "homeCovers", "categoryItems", "categoryCovers",
                "page2Items", "detailItems", "detailCovers", "playGroups", "playEpisodes")) {
            JsonNode value = result.path("untrustedRuntimeSummary").path(key);
            require(value.isNull() || boundedInteger(value, 10000), "MANAGED_RECEIPT_MISMATCH");
            counts.set(key, value.deepCopy());
        }
        for (var entry : java.util.Map.of(
                "playerStatus", Set.of("not_sampled", "selection_unavailable", "returned", "empty",
                        "invalid_output", "exception", "policy_denied"),
                "playerUrlKind", Set.of("absent", "http", "https", "other"),
                "playerParse", Set.of("direct", "parse_required", "invalid"),
                "mediaStatus", Set.of("not_requested", "prefix_returned", "http_failed",
                        "policy_denied", "invalid_headers")).entrySet()) {
            JsonNode value = result.path("untrustedRuntimeSummary").path(entry.getKey());
            if (value.isMissingNode()) continue;
            require(value.isTextual() && entry.getValue().contains(value.textValue()), "MANAGED_RECEIPT_MISMATCH");
            counts.set(entry.getKey(), value.deepCopy());
        }
        ObjectNode limits = evidence.putObject("egressLimits");
        for (var entry : java.util.Map.of("maxRequests", 64, "maxResponseBytes", MAX_RESPONSE_BYTES,
                "maxTotalResponseBytes", MAX_TOTAL_RESPONSE_BYTES, "minIntervalSeconds", 5).entrySet()) {
            JsonNode value = result.path("policy").path(entry.getKey());
            require(boundedInteger(value, entry.getValue()) && value.intValue() > 0, "MANAGED_RECEIPT_MISMATCH");
            limits.set(entry.getKey(), value.deepCopy());
        }
        JsonNode events = result.path("httpObservations");
        require(events.isArray() && events.size() <= limits.path("maxRequests").intValue(),
                "MANAGED_RECEIPT_MISMATCH");
        var safeEvents = evidence.putArray("httpObservations");
        for (JsonNode event : events) {
            require(Set.of("GET", "HEAD", "POST").contains(event.path("method").asText())
                    && event.path("pathSha256").asText().matches("[a-f0-9]{64}")
                    && event.path("responseSha256").asText().matches("[a-f0-9]{64}")
                    && boundedInteger(event.path("responseStatus"), 599) && event.path("responseStatus").intValue() >= 100
                    && boundedInteger(event.path("responseBytes"), limits.path("maxResponseBytes").intValue())
                    && boundedInteger(event.path("elapsedMilliseconds"), 120000), "MANAGED_RECEIPT_MISMATCH");
            ObjectNode safe = safeEvents.addObject();
            for (String key : List.of("method", "pathSha256", "responseStatus", "responseBytes",
                    "responseSha256", "elapsedMilliseconds")) safe.set(key, event.path(key).deepCopy());
            projectObservedCall(safe, event, evidence.path("loaderValidation").path("calls"));
            if (event.has("requestUrlSha256")) {
                require(event.path("requestUrlSha256").isTextual()
                        && event.path("requestUrlSha256").asText().matches("[a-f0-9]{64}"),
                        "MANAGED_RECEIPT_MISMATCH");
                safe.set("requestUrlSha256", event.path("requestUrlSha256").deepCopy());
            }
            if (event.has("responseComplete")) {
                require(event.path("responseComplete").isBoolean(), "MANAGED_RECEIPT_MISMATCH");
                safe.set("responseComplete", event.path("responseComplete").deepCopy());
            }
            if (event.has("responseContentType")) {
                require(validContentType(event.path("responseContentType")), "MANAGED_RECEIPT_MISMATCH");
                safe.set("responseContentType", event.path("responseContentType").deepCopy());
            }
            if (event.has("transport")) {
                require(event.path("transport").asText().equals("external_evidence_gateway")
                        && event.path("representation").asText().equals("gateway_rendered_document")
                        && event.path("cookiesExported").isBoolean()
                        && !event.path("cookiesExported").booleanValue()
                        && event.path("gatewaySessionReused").isBoolean()
                        && !event.has("connectedAddressIndex"), "MANAGED_RECEIPT_MISMATCH");
                for (String key : List.of("transport", "representation", "cookiesExported", "gatewaySessionReused"))
                    safe.set(key, event.path(key).deepCopy());
                projectGatewayExecution(safe, event);
                if (event.has("hostCookieState")) {
                    require(Set.of("not_requested", "cookies_missing", "user_agent_missing", "available",
                            "cookie_scope_unusable").contains(event.path("hostCookieState").asText()),
                            "MANAGED_RECEIPT_MISMATCH");
                    safe.set("hostCookieState", event.path("hostCookieState").deepCopy());
                }
            }
            if (event.has("challengePage")) {
                require(event.path("challengePage").isBoolean() && event.path("challengePage").booleanValue(),
                        "MANAGED_RECEIPT_MISMATCH");
                safe.put("challengePage", true);
            }
            if (event.has("lightweightCookieReuse")) {
                require(Set.of("http_accepted", "rejected").contains(event.path("lightweightCookieReuse").asText())
                        && event.has("connectedAddressIndex") && !event.has("transport"),
                        "MANAGED_RECEIPT_MISMATCH");
                safe.set("lightweightCookieReuse", event.path("lightweightCookieReuse").deepCopy());
            }
            if (event.has("lightweightCookieSkip")) {
                require(Set.of("request_not_reproducible", "scope_or_expiry", "observed_cookie_collision")
                        .contains(event.path("lightweightCookieSkip").asText())
                        && event.has("connectedAddressIndex") && !event.has("transport"),
                        "MANAGED_RECEIPT_MISMATCH");
                safe.set("lightweightCookieSkip", event.path("lightweightCookieSkip").deepCopy());
            }
            if (event.has("gatewayOutcome")) {
                String outcome = event.path("gatewayOutcome").asText();
                require(knownGatewayOutcome(outcome), "MANAGED_RECEIPT_MISMATCH");
                safe.put("gatewayOutcome", outcome);
                projectGatewayRecovery(safe, event);
            }
            if (event.has("gatewaySkipReason")) {
                require(event.path("gatewayOutcome").asText().equals("request_not_reproducible")
                        && event.path("challengePage").asBoolean()
                        && Set.of("request_method", "request_body", "host_credentials", "request_headers",
                            "response_content_type").contains(event.path("gatewaySkipReason").asText()),
                        "MANAGED_RECEIPT_MISMATCH");
                safe.set("gatewaySkipReason", event.path("gatewaySkipReason").deepCopy());
            }
            if (event.has("connectedAddressIndex")) {
                require(boundedInteger(event.path("connectedAddressIndex"), 15), "MANAGED_RECEIPT_MISMATCH");
                safe.set("connectedAddressIndex", event.path("connectedAddressIndex").deepCopy());
            }
            if (event.has("responseClass")) {
                int status = event.path("responseStatus").intValue();
                String expected = status == 200 ? "http_200" : status == 206 ? "partial_content"
                        : Set.of(301, 302, 303, 307, 308).contains(status) ? "http_redirect"
                        : status == 404 && event.path("method").asText().equals("HEAD")
                                && event.path("responseBytes").intValue() == 0 ? "head_not_found"
                        : status >= 500 ? "textual_server_error"
                        : status >= 400 ? "textual_client_error"
                        : status >= 200 && status < 300 ? "http_2xx" : "";
                if (status >= 400 && status <= 599 && event.path("responseClass").asText()
                        .equals(status >= 500 ? "server_error" : "client_error"))
                    expected = status >= 500 ? "server_error" : "client_error";
                require(event.path("responseClass").isTextual() && !expected.isEmpty()
                        && event.path("responseClass").textValue().equals(expected), "MANAGED_RECEIPT_MISMATCH");
                safe.set("responseClass", event.path("responseClass").deepCopy());
            }
            if (event.has("registeredUrlSha256")) {
                JsonNode registered = event.path("registeredUrlSha256");
                // Five sampled segments, their keys, maps and each map's declaration key.
                require(registered.isArray() && registered.size() <= 20, "MANAGED_RECEIPT_MISMATCH");
                var hashes = safe.putArray("registeredUrlSha256");
                for (JsonNode hash : registered) {
                    require(hash.isTextual() && hash.textValue().matches("[a-f0-9]{64}"),
                            "MANAGED_RECEIPT_MISMATCH");
                    hashes.add(hash.textValue());
                }
            }
            if (event.has("rangeRequested")) {
                require(event.path("rangeRequested").isBoolean() && event.path("rangeRequested").booleanValue()
                        && event.path("responseBytes").intValue() <= 65536
                        && Set.of("hls", "mp4", "mpeg_ts", "error_document", "unknown")
                        .contains(event.path("prefixFormat").asText()), "MANAGED_RECEIPT_MISMATCH");
                safe.put("rangeRequested", true).put("prefixFormat", event.path("prefixFormat").asText());
                if (event.has("resourceComplete")) {
                    require(event.path("resourceComplete").isBoolean(), "MANAGED_RECEIPT_MISMATCH");
                    safe.put("resourceComplete", event.path("resourceComplete").booleanValue());
                }
            }
        }
        JsonNode denied = result.path("deniedCodes");
        if (result.has("runtimeGateway")) {
            JsonNode gateway = result.path("runtimeGateway");
            require(Set.of("trawl", "flaresolverr", "flaresolverr-rs-alt").contains(gateway.path("adapter").asText())
                    && gateway.path("sessionScope").asText().equals("task_and_origin")
                    && gateway.path("targetAddressPolicy").asText()
                        .equals("controller_public_preflight_gateway_managed_network")
                    && gateway.path("cookiesExported").isBoolean() && !gateway.path("cookiesExported").asBoolean()
                    && gateway.path("cleanupConfirmed").isBoolean() && gateway.path("cleanupConfirmed").asBoolean(),
                    "MANAGED_RECEIPT_MISMATCH");
            ObjectNode safe = evidence.putObject("runtimeGateway");
            for (String key : List.of("sessionCount", "requestCount", "responseCount", "sessionReuseCount")) {
                require(boundedInteger(gateway.path(key), 64), "MANAGED_RECEIPT_MISMATCH");
                safe.set(key, gateway.path(key).deepCopy());
            }
            for (String key : List.of("adapter", "sessionScope", "targetAddressPolicy", "cookiesExported", "cleanupConfirmed"))
                safe.set(key, gateway.path(key).deepCopy());
            if (gateway.has("lightweightCounts")) {
                ObjectNode lightweight = safe.putObject("lightweightCounts");
                for (String key : List.of("attempts", "httpAccepted", "rejected")) {
                    JsonNode count = gateway.path("lightweightCounts").path(key);
                    require(boundedInteger(count, 64), "MANAGED_RECEIPT_MISMATCH");
                    lightweight.set(key, count.deepCopy());
                }
                require(lightweight.path("attempts").asInt() == lightweight.path("httpAccepted").asInt()
                        + lightweight.path("rejected").asInt(), "MANAGED_RECEIPT_MISMATCH");
            }
            for (String key : List.of("sessionRecoveryCount", "recoveryAttemptCount")) {
                if (!gateway.has(key)) continue;
                require(boundedInteger(gateway.path(key), 64), "MANAGED_RECEIPT_MISMATCH");
                safe.set(key, gateway.path(key).deepCopy());
            }
            if (gateway.has("executionCounts")) {
                var countsByTier = safe.putObject("executionCounts");
                int responsesByTier = 0;
                for (String key : List.of("tier1", "tier2", "tier3", "tier4", "unknown", "cachedSession", "unknownCache")) {
                    JsonNode count = gateway.path("executionCounts").path(key);
                    require(boundedInteger(count, gateway.path("responseCount").asInt()), "MANAGED_RECEIPT_MISMATCH");
                    countsByTier.set(key, count.deepCopy());
                    if (!Set.of("cachedSession", "unknownCache").contains(key)) responsesByTier += count.asInt();
                }
                require(responsesByTier == gateway.path("responseCount").asInt()
                        && countsByTier.path("cachedSession").asInt() + countsByTier.path("unknownCache").asInt()
                            <= responsesByTier, "MANAGED_RECEIPT_MISMATCH");
            }
        }
        require(denied.isArray() && denied.size() <= 64, "MANAGED_RECEIPT_MISMATCH");
        var safeDenied = evidence.putArray("deniedCodes");
        Set<String> allowed = Set.of("EGRESS_URL_DENIED", "EGRESS_METHOD_DENIED", "EGRESS_HEADERS_DENIED",
                "EGRESS_ACCOUNT_TOKEN_DENIED", "EGRESS_REQUEST_BUDGET", "EGRESS_BYTE_BUDGET",
                "EGRESS_HTTP_FAILED", "EGRESS_REDIRECT_DENIED", "EGRESS_BODY_DENIED",
                "EGRESS_HEADER_ORIGIN_DENIED", "EGRESS_HTTP_DEADLINE", "EGRESS_CONTENT_ENCODING_DENIED",
                "EGRESS_REQUEST_INVALID", "EGRESS_CONNECT_MISMATCH", "EGRESS_PEER_MISMATCH",
                "EGRESS_RANGE_DENIED", "EGRESS_RANGE_INVALID", "EGRESS_NONPUBLIC_TARGET_DENIED",
                "EGRESS_HTTP_STATUS_DENIED", "EGRESS_CONTENT_LENGTH_INVALID", "EGRESS_ERROR_BODY_ENCODING_DENIED",
                "EGRESS_RATE_LIMITED", "EGRESS_COOLDOWN_BUDGET", "EGRESS_CANCELLED",
                "EGRESS_DNS_FAILED", "EGRESS_DNS_NO_ADDRESS", "EGRESS_NONPUBLIC_DNS");
        for (JsonNode code : denied)
            safeDenied.add(code.isTextual() && allowed.contains(code.textValue()) ? code.textValue() : "EGRESS_DENIED");
        if (result.has("networkFailureCalls")) {
            var calls = result.path("networkFailureCalls");
            require(calls.isArray() && calls.size() <= denied.size(), "MANAGED_RECEIPT_MISMATCH");
            var safeCalls = evidence.putArray("networkFailureCalls");
            var indexes = new java.util.HashSet<Integer>();
            for (JsonNode call : calls) {
                require(call.isObject() && call.size() == 2
                        && boundedInteger(call.path("denialIndex"), 63)
                        && call.path("denialIndex").asInt() < denied.size()
                        && indexes.add(call.path("denialIndex").asInt())
                        && RepairReport.NETWORK_CODES.contains(denied.get(call.path("denialIndex").asInt()).asText())
                        && call.path("method").isTextual()
                        && RepairReport.NETWORK_METHOD_NODES.containsKey(call.path("method").asText()),
                        "MANAGED_RECEIPT_MISMATCH");
                safeCalls.add(call.deepCopy());
            }
        }
        for (String field : List.of("resourceEvidence", "contentResourceEvidence"))
            if (result.has(field))
                evidence.set(field, new ManagedResourceEvidence(mapper).project(
                        result.path(field), evidence.path("inputSourceSha256").asText(), safeEvents,
                        evidence.path("loaderValidation").path("calls")));
        JsonNode rates = result.path("rateLimitObservations");
        if (!rates.isMissingNode()) {
            require(rates.isArray() && rates.size() <= 64, "MANAGED_RECEIPT_MISMATCH");
            var safeRates = evidence.putArray("rateLimitObservations");
            for (JsonNode rate : rates) {
                JsonNode safe = projectRateLimit(rate);
                require(safe != null, "MANAGED_RECEIPT_MISMATCH");
                projectObservedCall((ObjectNode) safe, rate, evidence.path("loaderValidation").path("calls"));
                safeRates.add(safe);
            }
        }
        JsonNode network = result.path("networkObservations");
        if (!network.isMissingNode()) {
            require(network.isArray() && network.size() <= 32
                    && boundedInteger(result.path("networkObservationsDropped"), 2048),
                    "MANAGED_RECEIPT_MISMATCH");
            var safeNetwork = evidence.putArray("networkObservations");
            evidence.set("networkObservationsDropped", result.path("networkObservationsDropped").deepCopy());
            for (JsonNode row : network) {
                JsonNode safe = projectNetworkObservation(row);
                require(safe != null, "MANAGED_RECEIPT_MISMATCH");
                projectObservedCall((ObjectNode) safe, row, evidence.path("loaderValidation").path("calls"));
                safeNetwork.add(safe);
            }
        }
        JsonNode dns = result.path("dnsObservations");
        if (!dns.isMissingNode()) {
            require(dns.isArray() && dns.size() <= 64
                    && boundedInteger(result.path("dnsObservationsDropped"), 2048), "MANAGED_RECEIPT_MISMATCH");
            var safeDns = evidence.putArray("dnsObservations");
            evidence.set("dnsObservationsDropped", result.path("dnsObservationsDropped").deepCopy());
            for (JsonNode row : dns) {
                JsonNode safe = projectDnsObservation(row);
                require(safe != null, "MANAGED_RECEIPT_MISMATCH");
                projectObservedCall((ObjectNode) safe, row, evidence.path("loaderValidation").path("calls"));
                safeDns.add(safe);
            }
        }
    }

    JsonNode control(ObjectNode request) throws IOException, InterruptedException {
        String blocked = configurationBlockedReason();
        if (blocked != null) throw new IOException(blocked);
        byte[] body = mapper.writeValueAsBytes(request);
        require(body.length <= 1_500_000, "MANAGED_SOURCE_LIMIT");
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        boolean tunnel = loopbackTunnel();
        SocketAddress address = tunnel ? new InetSocketAddress("127.0.0.1",
                Integer.parseInt(endpoint.substring(endpoint.lastIndexOf(':') + 1)))
                : UnixDomainSocketAddress.of(endpoint);
        try (SocketChannel channel = SocketChannel.open(
                tunnel ? StandardProtocolFamily.INET : StandardProtocolFamily.UNIX)) {
            channel.configureBlocking(false);
            if (!channel.connect(address)) {
                while (!channel.finishConnect()) waitForIo(deadline);
            }
            ByteBuffer output = ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).flip();
            while (output.hasRemaining()) {
                if (channel.write(output) == 0) waitForIo(deadline);
                checkDeadline(deadline);
            }
            ByteBuffer length = ByteBuffer.allocate(4);
            read(channel, length, deadline);
            int size = length.flip().getInt();
            int replyLimit = Set.of("status", "cancel", "cancel_acceptance", "start_http")
                    .contains(request.path("operation").asText()) ? 525312 : 65536;
            require(size > 0 && size <= replyLimit, "MANAGED_FRAME_LIMIT");
            ByteBuffer input = ByteBuffer.allocate(size);
            read(channel, input, deadline);
            JsonNode value;
            try (JsonParser parser = mapper.getFactory().createParser(input.array())) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                value = mapper.readTree(parser);
                require(parser.nextToken() == null, "MANAGED_FRAME_INVALID");
            }
            require(value != null && value.path("ok").isBoolean(), "MANAGED_FRAME_INVALID");
            if (!value.path("ok").booleanValue()) throw new IOException(errorCode(value.path("code").asText()));
            return value.path("result");
        } catch (UnsupportedOperationException | java.nio.file.InvalidPathException e) {
            throw new IOException("MANAGED_LOCAL_SOCKET_UNAVAILABLE", e);
        }
    }

    private void read(SocketChannel channel, ByteBuffer input, long deadline) throws IOException, InterruptedException {
        while (input.hasRemaining()) {
            int count = channel.read(input);
            require(count != -1, "MANAGED_FRAME_INVALID");
            if (count == 0) waitForIo(deadline);
            checkDeadline(deadline);
        }
    }

    private void waitForIo(long deadline) throws IOException, InterruptedException {
        checkDeadline(deadline);
        Thread.sleep(10);
    }

    private void checkDeadline(long deadline) throws IOException, InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        require(System.nanoTime() < deadline, "MANAGED_TRANSPORT_TIMEOUT");
    }

    public String errorCode(String code) {
        if (EGRESS_FAILURES.contains(code == null ? "" : code)) return code;
        return code != null && Set.of("MANAGED_CLEANUP_UNCONFIRMED", "MANAGED_RUNNER_RECOVERY_REQUIRED", "MANAGED_RUNNER_BUSY",
                "MANAGED_RUNNER_IDENTITY_MISMATCH", "MANAGED_RECEIPT_MISMATCH", "MANAGED_CONTROLS_UNCONFIRMED",
                "MANAGED_INPUT_MISMATCH", "MANAGED_EXECUTION_TIMEOUT", "MANAGED_TRANSPORT_TIMEOUT",
                "MANAGED_LOCAL_SOCKET_UNAVAILABLE", "MANAGED_LOCAL_SOCKET_REQUIRED", "ENGINE_TIMEOUT", "ENGINE_OUTPUT_LIMIT",
                "EGRESS_RATE_LIMITED", "EGRESS_COOLDOWN_BUDGET", "EGRESS_CANCELLED",
                "CONTAINER_EXECUTION_FAILED", "LINUX_CGROUP_V2_REQUIRED", "MANAGED_CANCELLED",
                "EGRESS_IMAGE_BUNDLE_MISMATCH",
                "MANAGED_ACCEPTANCE_PARENT_INVALID", "MANAGED_ACCEPTANCE_CANCELLED",
                "T4_HTTP_DISABLED", "T4_HTTP_ALREADY_STARTED", "T4_HTTP_DRIVER_FAILED", "T4_HTTP_RECEIPT_MISMATCH",
                "LOADER_PROFILE_INVALID", "LOADER_RECEIPT_INVALID", "MANAGED_RESOURCE_EVIDENCE_INVALID",
                "SOURCE_CREDENTIALS_INVALID", "SOURCE_CREDENTIALS_CHANGED",
                "EVIDENCE_GATEWAY_CONFIGURATION_INVALID", "EVIDENCE_GATEWAY_CLEANUP_UNCONFIRMED",
                "SOURCE_CREDENTIALS_REQUIRED", "SOURCE_AUTHENTICATION_REJECTED",
                "MANAGED_LOADER_IDENTITY_MISMATCH", "MANAGED_LOADER_RECEIPT_INVALID",
                "JOB_ALREADY_EXISTS").contains(code) ? code : "MANAGED_EXECUTION_ERROR";
    }

    private static void require(boolean condition, String code) throws IOException {
        if (!condition) throw new IOException(code);
    }
}
