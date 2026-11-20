package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.exception.BadGatewayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * Isolated HTTP verifier: consumes the real AT-generated EXT, then calls the Unix broker.
 * Source sessions and bounded localProxy callbacks carry no AT credentials into source execution.
 */
public final class ManagedT4WorkerService extends PluginWorkerService {
    private static final Set<String> METHODS = Set.of("home", "category", "detail", "search", "play");
    private final ObjectMapper mapper;
    private final PluginRepository repository;
    private final SubscriptionService subscriptions;
    private final AppProperties properties;
    private final ManagedT4BrokerClient broker;
    private final Integer pluginId;
    private final String siteKey;
    private final JsonNode binding;
    private final JsonNode approvedPublicData;
    private volatile boolean closed;
    private volatile ManagedT4HttpObservation httpObservation;
    private final Set<String> proxyTargets = new HashSet<>();
    private final Set<String> proxyManifests = new HashSet<>();
    private String proxySession, proxyBase;

    public ManagedT4WorkerService(ObjectMapper mapper, PluginRepository repository,
                                  SubscriptionService subscriptions, AppProperties properties,
                                  Integer pluginId, ManagedT4BrokerClient broker) {
        this(mapper, repository, subscriptions, properties, pluginId, broker,
                mapper.createObjectNode());
    }

    public ManagedT4WorkerService(ObjectMapper mapper, PluginRepository repository,
                                  SubscriptionService subscriptions, AppProperties properties,
                                  Integer pluginId, ManagedT4BrokerClient broker,
                                  Map<String, String> approvedPublicData) {
        this(mapper, repository, subscriptions, properties, pluginId, broker,
                (JsonNode) mapper.valueToTree(approvedPublicData));
    }

    public ManagedT4WorkerService(ObjectMapper mapper, PluginRepository repository,
                                  SubscriptionService subscriptions, AppProperties properties,
                                  Integer pluginId, ManagedT4BrokerClient broker,
                                  JsonNode approvedPublicData) {
        super(mapper);
        if (repository == null || subscriptions == null || properties == null || pluginId == null || broker == null
                || approvedPublicData == null)
            throw new IllegalArgumentException("AT_MANAGED_WORKER_CONFIGURATION_INVALID");
        this.mapper = mapper.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
        this.repository = repository;
        this.subscriptions = subscriptions;
        this.properties = properties;
        this.pluginId = pluginId;
        this.broker = broker;
        this.approvedPublicData = approvedPublicData.deepCopy();
        try {
            if (!this.approvedPublicData.isEmpty())
                ManagedT4BrokerClient.validateSourceData(this.approvedPublicData);
        } catch (IOException error) {
            throw new IllegalArgumentException("AT_MANAGED_WORKER_PUBLIC_DATA_INVALID");
        }
        Plugin plugin = repository.findById(pluginId).orElseThrow(
                () -> new IllegalArgumentException("AT_MANAGED_WORKER_PLUGIN_MISSING"));
        siteKey = SubscriptionService.pluginSiteKey(plugin);
        binding = describe(plugin);
        require(broker.sourceSha256().equals(binding.path("sourceSha256").asText()),
                "AT_MANAGED_WORKER_SOURCE_MISMATCH");
    }

    @Override
    public boolean isAvailable() {
        if (closed) return false;
        try {
            broker.verify();
            return !closed;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException error) {
            return false;
        }
    }

    public synchronized ManagedT4HttpObservation openHttpObservation(String jobId, String frozenProfileJson)
            throws IOException {
        return openHttpObservation(jobId, frozenProfileJson, false);
    }

    public synchronized ManagedT4HttpObservation openHttpObservation(String jobId, String frozenProfileJson,
                                                                     boolean sessionMode) throws IOException {
        require(!closed && httpObservation == null, "AT_MANAGED_WORKER_OBSERVATION_ALREADY_OPENED");
        require(broker.sessionId() == null, "AT_MANAGED_WORKER_SESSION_ALREADY_OPENED");
        httpObservation = new ManagedT4HttpObservation(
                mapper, broker.observationBinding(), jobId, frozenProfileJson, sessionMode);
        return httpObservation;
    }

    public synchronized void finishHttpSession() throws IOException, InterruptedException {
        require(!closed && httpObservation != null && httpObservation.sessionMode(),
                "AT_MANAGED_WORKER_OBSERVATION_NOT_OPENED");
        try {
            httpObservation.recordSessionReceipt(broker.finishSession());
        } catch (IOException | InterruptedException | RuntimeException error) {
            httpObservation.fail();
            throw error;
        } finally {
            proxyTargets.clear();
            proxyManifests.clear();
            proxySession = proxyBase = null;
        }
    }

    public synchronized JsonNode finishHttpObservation() throws IOException {
        require(!closed && httpObservation != null, "AT_MANAGED_WORKER_OBSERVATION_NOT_OPENED");
        JsonNode receipt = httpObservation.finish();
        httpObservation = null;
        return receipt;
    }

    @Override
    public synchronized JsonNode call(String site, String extend, String method, Map<String, Object> params) {
        require(!closed && siteKey.equals(site) && METHODS.contains(method) && params != null,
                "AT_MANAGED_WORKER_CALL_INVALID");
        Plugin plugin = repository.findById(pluginId).orElseThrow(
                () -> new BadGatewayException("AT_MANAGED_WORKER_PLUGIN_MISSING"));
        require(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes,
                "AT_MANAGED_WORKER_REQUEST_CONTEXT_REQUIRED");
        var request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        require(variables instanceof Map<?, ?>, "AT_MANAGED_WORKER_REQUEST_CONTEXT_REQUIRED");
        Map<?, ?> paths = (Map<?, ?>) variables;
        require(siteKey.equals(paths.get("site")) && paths.get("token") instanceof String,
                "AT_MANAGED_WORKER_REQUEST_CONTEXT_REQUIRED");
        String pathToken = (String) paths.get("token");
        String token = subscriptions.getCurrentToken();
        require(token.equals(subscriptions.resolveT4Token(pathToken)), "AT_MANAGED_WORKER_CONTEXT_MISMATCH");
        String mode = "direct".equals(properties.getT4ProxyMode()) ? "t4-direct" : "t4";
        ObjectNode arguments = mapper.valueToTree(params);
        ManagedT4HttpObservation observation = httpObservation;
        boolean observedReturn = false;
        try {
            JsonNode data = publicData(plugin);
            require(binding.equals(describe(plugin)), "AT_MANAGED_WORKER_BINDING_CHANGED");
            require(extend != null && extend.length() <= 32_768, "AT_MANAGED_WORKER_EXT_INVALID");
            JsonNode actual = mapper.readTree(Base64.getDecoder().decode(extend));
            Map<String, Object> expected = SubscriptionService.buildPluginExtPayload(
                    plugin, subscriptions.readHostAddress(""), token, token, "", false, null);
            expected.remove("local_proxy_config");
            expected.remove("preheatUrl");
            expected.put("t4_play_type", mode);
            require(mapper.valueToTree(expected).equals(actual), "AT_MANAGED_WORKER_EXT_MISMATCH");
            // Approval is explicit; the live AT/request authority still cannot become public data.
            for (JsonNode value : data) {
                if (value.isTextual()) {
                    for (String key : List.of("token", "secret")) rejectAuthority(value, actual.path(key).asText());
                    rejectAuthority(value, pathToken);
                    rejectAuthority(value, request.getHeader("X-API-KEY"));
                    rejectAuthority(value, request.getHeader("Authorization"));
                }
            }
            if (method.equals("play")) {
                String proxy = subscriptions.readHostAddress("/t4cenc/" + pathToken + "/" + site);
                require(arguments.path("proxy_base").isTextual()
                        && proxy.equals(arguments.path("proxy_base").textValue()),
                        "AT_MANAGED_WORKER_PROXY_CONTEXT_MISMATCH");
                // Only the trusted owner retains the real callback authority.
                arguments.remove("proxy_base");
                proxyBase = proxy;
            }
            var call = mapper.createObjectNode().put("method", method).put("playType", mode);
            call.set("params", arguments);
            if (!data.isEmpty()) call.set("sourceData", data);
            if (observation != null) observation.dispatch(request.getMethod(), request.getContentType(), call);
            JsonNode delivery = observation == null ? broker.executeSession(call)
                    : observation.sessionMode() ? broker.executeSessionForAcceptance(
                        call, observation.parentJobId(), observation.profileSha256())
                    : broker.executeForAcceptance(call, observation.parentJobId(), observation.profileSha256());
            JsonNode result = delivery.path("result");
            require(!closed, "AT_MANAGED_WORKER_UNAVAILABLE");
            if (observation != null) observation.receive(delivery);
            if (method.equals("play")) {
                if ((observation == null || observation.sessionMode())
                        && result.path("url").asText().startsWith("http://managed.invalid/local?")) {
                    String target = result.path("url").asText().substring("http://managed.invalid".length());
                    ManagedT4BrokerClient.validateProxyTarget(target);
                    require(broker.sessionId() != null, "AT_MANAGED_WORKER_PROXY_NOT_CONNECTED");
                    if (!broker.sessionId().equals(proxySession)) {
                        proxyTargets.clear();
                        proxyManifests.clear();
                    }
                    require(proxyTargets.contains(target) || proxyTargets.size() < 16,
                            "AT_MANAGED_WORKER_PROXY_TARGET_LIMIT");
                    proxySession = broker.sessionId();
                    proxyTargets.add(target);
                    ((ObjectNode) result).put("url", proxyBase + "/session/" + proxySession + target);
                    if (observation != null) observation.rewriteProxyResult(result, target);
                } else validateDirectResult(result);
            }
            observedReturn = true;
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new BadGatewayException("AT_MANAGED_WORKER_INTERRUPTED");
        } catch (com.fasterxml.jackson.core.JacksonException error) {
            throw new BadGatewayException("AT_MANAGED_WORKER_EXT_INVALID");
        } catch (IOException error) {
            throw new BadGatewayException("AT_MANAGED_WORKER_BROKER_FAILED");
        } catch (IllegalArgumentException error) {
            throw new BadGatewayException("AT_MANAGED_WORKER_EXT_INVALID");
        } finally {
            if (observation != null && !observedReturn) observation.fail();
            if (!observedReturn) discardSession();
        }
    }

    private JsonNode publicData(Plugin plugin) throws IOException {
        String text = plugin.getExtend();
        require(text == null || text.length() <= 8192, "AT_MANAGED_WORKER_PUBLIC_DATA_INVALID");
        JsonNode data;
        try {
            data = text == null || text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
            require(data != null && data.isObject(), "AT_MANAGED_WORKER_PUBLIC_DATA_INVALID");
            if (!data.isEmpty()) ManagedT4BrokerClient.validateSourceData(data);
        } catch (IOException error) {
            throw new BadGatewayException("AT_MANAGED_WORKER_PUBLIC_DATA_INVALID");
        }
        require(data != null && data.isObject(),
                "AT_MANAGED_WORKER_PUBLIC_DATA_INVALID");
        require(approvedPublicData.equals(data), "AT_MANAGED_WORKER_PUBLIC_DATA_NOT_APPROVED");
        return data;
    }

    private static void rejectAuthority(JsonNode value, String authority) {
        require(authority == null || authority.isBlank() || authority.equals("-")
                || !value.textValue().contains(authority), "AT_MANAGED_WORKER_PUBLIC_DATA_AUTHORITY_DENIED");
    }

    private JsonNode describe(Plugin plugin) {
        require(plugin.isEnabled() && plugin.isT4Visible() && PluginService.isPythonPluginUrl(plugin.getUrl())
                && plugin.getContent() != null && !plugin.getContent().isEmpty(),
                "AT_MANAGED_WORKER_PLUGIN_UNAVAILABLE");
        var value = mapper.createObjectNode();
        value.put("id", plugin.getId()).put("siteKey", SubscriptionService.pluginSiteKey(plugin))
                .put("url", plugin.getUrl()).put("name", plugin.getName())
                .put("extend", plugin.getExtend()).put("version", plugin.getVersion());
        try {
            value.put("sourceSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(plugin.getContent().getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("AT_MANAGED_WORKER_DIGEST_UNAVAILABLE");
        }
        return value;
    }

    private void validateDirectResult(JsonNode result) {
        try {
            URI url = URI.create(result.path("url").asText());
            String host = url.getHost();
            require(("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
                    && host != null && url.getRawUserInfo() == null
                    && !host.equalsIgnoreCase("localhost") && !host.startsWith("127.")
                    && !host.equals("[::1]") && !host.equals("::1")
                    && !url.toString().contains("#hg-cenc=")
                    && !url.getPath().startsWith("/t4cenc/"),
                    "AT_MANAGED_WORKER_PROXY_NOT_CONNECTED");
        } catch (IllegalArgumentException error) {
            throw new BadGatewayException("AT_MANAGED_WORKER_PROXY_NOT_CONNECTED");
        }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new BadGatewayException(code);
    }

    private void discardSession() {
        proxyTargets.clear();
        proxyManifests.clear();
        proxySession = proxyBase = null;
        boolean interrupted = Thread.interrupted();
        try { broker.discardSession(); }
        catch (IOException | InterruptedException error) {
            interrupted |= error instanceof InterruptedException;
            throw new BadGatewayException("AT_MANAGED_WORKER_CLEANUP_UNCONFIRMED");
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    @Override
    public synchronized void forward(String site, String path, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        boolean delivered = false;
        try {
            require(!closed && siteKey.equals(site) && proxySession != null && proxySession.equals(broker.sessionId())
                    && request != null && response != null && path != null
                    && Set.of("GET", "HEAD").contains(request.getMethod())
                    && path.startsWith("/session/" + proxySession + "/local?"),
                    "AT_MANAGED_WORKER_PROXY_NOT_CONNECTED");
            String target = path.substring(("/session/" + proxySession).length());
            require(proxyTargets.contains(target), "AT_MANAGED_WORKER_PROXY_NOT_CONNECTED");
            require(request.getRequestURI().equals(URI.create(proxyBase).getRawPath()
                    + "/session/" + proxySession + "/local"), "AT_MANAGED_WORKER_PROXY_CONTEXT_MISMATCH");
            String range = request.getHeader("Range");
            require(range == null || range.equals("bytes=0-32767"), "AT_MANAGED_WORKER_PROXY_RANGE_DENIED");
            Plugin plugin = repository.findById(pluginId).orElseThrow();
            require(binding.equals(describe(plugin)), "AT_MANAGED_WORKER_BINDING_CHANGED");
            var call = mapper.createObjectNode().put("method", "proxy")
                    .put("playType", "direct".equals(properties.getT4ProxyMode()) ? "t4-direct" : "t4");
            // A rewritten manifest HEAD needs fresh representation bytes, not a cached length.
            boolean sourceHead = request.getMethod().equals("HEAD") && !proxyManifests.contains(target);
            call.putObject("params").put("target", target).put("range", range == null ? "" : range)
                    .put("head", sourceHead);
            JsonNode data = publicData(plugin);
            if (!data.isEmpty()) call.set("sourceData", data);
            ManagedT4HttpObservation observation = httpObservation;
            require(observation == null || observation.sessionMode(), "AT_MANAGED_WORKER_PROXY_NOT_CONNECTED");
            JsonNode delivery = observation == null ? broker.executeSession(call)
                    : broker.executeSessionForAcceptance(call, observation.parentJobId(), observation.profileSha256());
            JsonNode result = delivery.path("result");
            boolean manifest = Set.of("application/vnd.apple.mpegurl", "application/x-mpegurl").contains(
                    result.path("mime").asText().split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT));
            int bodyLimit = manifest ? 524288 : 32768;
            require(result.path("status").isInt() && Set.of(200, 206, 404, 416).contains(result.path("status").intValue())
                    && result.path("mime").isTextual() && result.path("mime").textValue().length() <= 100
                    && !result.path("mime").textValue().contains("\r") && !result.path("mime").textValue().contains("\n")
                    && result.path("body").isTextual()
                    && result.path("body").textValue().length() <= 4 * ((bodyLimit + 2) / 3)
                    && result.path("bytes").isInt() && result.path("bytes").intValue() >= 0
                    && result.path("bytes").intValue() <= bodyLimit, "AT_MANAGED_WORKER_PROXY_RESULT_INVALID");
            byte[] body = Base64.getDecoder().decode(result.path("body").textValue());
            require(body.length <= bodyLimit && (sourceHead ? body.length == 0
                    : body.length == result.path("bytes").intValue())
                    && HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body))
                        .equals(result.path("sha256").asText()), "AT_MANAGED_WORKER_PROXY_RESULT_INVALID");
            if (result.path("status").intValue() == 206) {
                var match = java.util.regex.Pattern.compile("bytes 0-([0-9]{1,5})/([0-9]{1,20})")
                        .matcher(result.path("headers").path("content-range").asText());
                require(match.matches(), "AT_MANAGED_WORKER_PROXY_RESULT_INVALID");
                var end = new java.math.BigInteger(match.group(1));
                var total = new java.math.BigInteger(match.group(2));
                require(end.add(java.math.BigInteger.ONE).intValueExact() == result.path("bytes").intValue()
                        && total.compareTo(end) > 0, "AT_MANAGED_WORKER_PROXY_RESULT_INVALID");
            }
            if (observation != null) observation.receiveProxy(delivery, call);
            if (manifest) {
                require(!sourceHead && result.path("status").intValue() == 200
                        && result.path("headers").path("content-range").asText("").isEmpty(),
                        "AT_MANAGED_WORKER_PROXY_MANIFEST_INVALID");
                body = rewriteManifest(target, body, delivery.path("proxyManifestTargets"));
            }
            response.setStatus(result.path("status").intValue());
            response.setContentType(result.path("mime").textValue());
            response.setHeader("Cache-Control", "no-store");
            response.setContentLength(sourceHead ? result.path("bytes").intValue() : body.length);
            for (String key : List.of("content-range", "accept-ranges")) {
                JsonNode value = result.path("headers").path(key);
                if (value.isTextual() && value.textValue().matches("[A-Za-z0-9 */=-]{1,100}"))
                    response.setHeader(key, value.textValue());
            }
            if (!request.getMethod().equals("HEAD")) response.getOutputStream().write(body);
            delivered = true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            proxyTargets.clear();
            throw new BadGatewayException("AT_MANAGED_WORKER_INTERRUPTED");
        } catch (java.security.NoSuchAlgorithmException | IllegalArgumentException error) {
            proxyTargets.clear();
            throw new BadGatewayException("AT_MANAGED_WORKER_PROXY_RESULT_INVALID");
        } catch (IOException error) {
            proxyTargets.clear();
            throw error;
        } finally {
            // A rejected callback cannot leave a reusable capability or live source session.
            if (!delivered) {
                if (httpObservation != null) httpObservation.fail();
                discardSession();
            }
        }
    }

    private byte[] rewriteManifest(String target, byte[] body, JsonNode grants) throws IOException {
        String failure = "AT_MANAGED_WORKER_PROXY_MANIFEST_INVALID";
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(
                java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(body)).toString();
        String[] lines = text.split("(?<=\n)", -1);
        require(lines.length <= 4096 && (text.startsWith("\ufeff") ? text.substring(1) : text).startsWith("#EXTM3U")
                && grants.isArray() && !grants.isEmpty() && grants.size() <= 5, failure);
        Set<String> published = new HashSet<>(), selected = new HashSet<>();
        String firstTarget = null;
        StringBuilder rewritten = new StringBuilder();
        URI base = URI.create("http://managed.invalid" + target);
        for (String line : lines) {
            String address = line.strip();
            if (address.isEmpty() || address.startsWith("#") || address.startsWith("\ufeff#")) {
                rewritten.append(line);
                continue;
            }
            URI uri = base.resolve(address);
            require("http".equals(uri.getScheme()) && "managed.invalid".equals(uri.getRawAuthority())
                    && uri.getRawFragment() == null && "/local".equals(uri.getRawPath()), failure);
            String child = uri.getRawPath() + "?" + uri.getRawQuery();
            ManagedT4BrokerClient.validateProxyTarget(child);
            if (firstTarget == null) firstTarget = child;
            published.add(child);
            int start = line.indexOf(address);
            rewritten.append(line, 0, start).append(proxyBase).append("/session/").append(proxySession)
                    .append(child).append(line.substring(start + address.length()));
        }
        for (JsonNode grant : grants) {
            require(grant.isTextual() && published.contains(grant.textValue()) && selected.add(grant.textValue()), failure);
        }
        Set<String> combined = new HashSet<>(proxyTargets);
        combined.addAll(selected);
        byte[] result = rewritten.toString().getBytes(StandardCharsets.UTF_8);
        require(combined.size() <= 16 && result.length <= 524288, failure);
        if (httpObservation != null) httpObservation.rewriteProxyManifest(body, result, firstTarget);
        // Parsing and selection belong to the pinned Python m3u8 owner; only its grants become callbacks.
        proxyTargets.addAll(selected);
        proxyManifests.add(target);
        return result;
    }

    @Override
    public void shutdown() {
        closed = true;
        ManagedT4HttpObservation observation = httpObservation;
        if (observation != null) observation.close();
        try {
            broker.close();
        } catch (IOException error) {
            throw new IllegalStateException("AT_MANAGED_WORKER_CLEANUP_UNCONFIRMED");
        }
    }
}
