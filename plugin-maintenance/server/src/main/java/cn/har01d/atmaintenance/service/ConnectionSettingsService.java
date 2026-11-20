package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.config.ModelSettings;
import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.repository.ModelBudgetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class ConnectionSettingsService {
    private final ConnectionSettingsStore store;
    private final ModelSettings defaults;
    private final AtReadClient at;
    private final ModelClient model;
    private final ModelBudgetRepository budgets;
    private final ObjectMapper mapper;
    private final Validator validator;

    public ObjectNode view() {
        var root = mapper.createObjectNode();
        var currentAt = store.at();
        root.putObject("at").put("origin", currentAt.origin()).put("instanceId", currentAt.instanceId())
                .put("revision", currentAt.revision()).put("authMode", currentAt.authMode())
                .put("allowHttp", currentAt.allowHttp()).put("credentialConfigured", !currentAt.credential().isEmpty())
                .put("configured", at.connection().configured())
                .put("embeddedAvailable", at.embeddedAvailable()).put("useEmbedded", at.usingEmbedded());
        var currentModel = store.model(defaults);
        root.putObject("model").put("enabled", currentModel.isEnabled())
                .put("autoSubmitEnabled", currentModel.isAutoSubmitEnabled())
                .put("endpoint", currentModel.getEndpoint()).put("baseUrl", currentModel.getBaseUrl())
                .put("model", currentModel.getModel()).put("keyEnv", currentModel.getKeyEnv())
                .put("apiKeyConfigured", !currentModel.getApiKey().isEmpty())
                .put("timeoutMillis", currentModel.getTimeoutMillis())
                .put("maxCompletionTokens", currentModel.getMaxCompletionTokens())
                .put("maxTokenParameter", currentModel.getMaxTokenParameter())
                .put("jsonMode", currentModel.getJsonMode())
                .put("includeStoreParameter", currentModel.isIncludeStoreParameter());
        root.set("limits", mapper.valueToTree(ModelLimits.from(currentModel)));
        root.set("evidenceGateway", mapper.valueToTree(store.evidenceGateway()));
        return root;
    }

    public ObjectNode updateEvidenceGateway(EvidenceGatewaySettings input) {
        if (!validator.validate(input).isEmpty()) throw invalid("INVALID_EVIDENCE_GATEWAY");
        String endpoint = input.endpoint().trim().replaceAll("/+$", "");
        if (input.enabled() || !endpoint.isEmpty()) {
            try {
                var uri = java.net.URI.create(endpoint);
                if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                        || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || uri.getPort() == 0 || uri.getPort() > 65535 || endpoint.contains("\\"))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException error) { throw invalid("INVALID_EVIDENCE_GATEWAY"); }
        }
        try { store.saveEvidenceGateway(new EvidenceGatewaySettings(input.enabled(), input.adapter(), endpoint,
                input.timeoutMillis())); }
        catch (IOException error) { throw storageFailure(); }
        return view();
    }

    public ObjectNode checkEvidenceGateway() {
        var settings = store.evidenceGateway();
        var result = mapper.createObjectNode().put("adapter", settings.adapter()).put("ready", false);
        if (!settings.enabled()) return result.put("code", "EVIDENCE_GATEWAY_DISABLED");
        try (var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()) {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(settings.endpoint() + "/health"))
                    .timeout(java.time.Duration.ofSeconds(5)).GET().build();
            var pending = client.sendAsync(request, ignored -> new LimitedHttpBody(8192, "EVIDENCE_GATEWAY_RESPONSE_TOO_LARGE"));
            try {
                var response = pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
                var body = mapper.readTree(response.body());
                boolean ready = response.statusCode() == 200 && body != null && "ok".equals(body.path("status").asText());
                return result.put("ready", ready).put("code", ready ? "EVIDENCE_GATEWAY_HEALTHY" : "EVIDENCE_GATEWAY_UNHEALTHY")
                        .put("httpStatus", response.statusCode()).put("businessContentVerified", false);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
                return result.put("code", "EVIDENCE_GATEWAY_UNREACHABLE");
            } finally { pending.cancel(true); }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return result.put("code", "EVIDENCE_GATEWAY_INTERRUPTED");
        } catch (IOException | IllegalArgumentException error) {
            return result.put("code", "EVIDENCE_GATEWAY_UNREACHABLE");
        }
    }

    public synchronized ObjectNode updateAt(UpdateAtConnectionRequest input) {
        var previous = store.at();
        if (!java.util.Set.of("session", "api_key").contains(input.authMode())
                || !input.credential().matches("[\\x21-\\x7e]{0,8192}")
                || !input.instanceId().isBlank() && !input.instanceId().trim().matches("[A-Za-z0-9_-]{1,100}")) {
            throw invalid("INVALID_AT_CONNECTION");
        }
        String credential = input.clearCredential() ? "" : !input.credential().isEmpty() ? input.credential()
                : previous.authMode().equals(input.authMode()) ? previous.credential() : "";
        var settings = new AtConnectionSettings(input.origin().trim(), input.instanceId().trim(),
                input.revision().trim(), input.authMode(), credential, input.allowHttp(),
                previous.runtimeIdentityPath(), input.useEmbedded());
        AtReadClient replacement;
        try { replacement = new AtReadClient(mapper, settings); }
        catch (IllegalArgumentException error) { throw invalid(error.getMessage()); }
        synchronized (at) {
            try { store.saveAt(settings); }
            catch (IOException error) { replacement.close(); throw storageFailure(); }
            at.replace(replacement);
        }
        return view();
    }

    @Transactional
    public ObjectNode updateModel(UpdateModelConnectionRequest input) {
        // Same lock order as call reservation: no endpoint/key switch during a charged call.
        if (budgets.lock().orElseThrow().getActiveCallId() != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MODEL_BUSY");
        var settings = store.model(defaults);
        if (input.apiKey().codePoints().anyMatch(c -> c < 33 || c > 126)) throw invalid("INVALID_MODEL_CONNECTION");
        settings.setEnabled(input.enabled());
        if (input.autoSubmitEnabled() != null) settings.setAutoSubmitEnabled(input.autoSubmitEnabled());
        settings.setEndpoint(input.endpoint().trim());
        settings.setBaseUrl(input.baseUrl().trim());
        settings.setModel(input.model().trim());
        settings.setKeyEnv(input.keyEnv().trim());
        if (input.clearApiKey()) settings.setApiKey("");
        if (!input.apiKey().isEmpty()) settings.setApiKey(input.apiKey());
        settings.setTimeoutMillis(input.timeoutMillis());
        settings.setMaxCompletionTokens(input.maxCompletionTokens());
        settings.setMaxTokenParameter(input.maxTokenParameter());
        settings.setJsonMode(input.jsonMode());
        settings.setIncludeStoreParameter(input.includeStoreParameter());
        ModelClient replacement;
        try { replacement = new ModelClient(mapper, validator, settings); }
        catch (IllegalArgumentException error) { throw invalid(error.getMessage()); }
        synchronized (model) {
            try { store.saveModel(settings); }
            catch (IOException error) { replacement.close(); throw storageFailure(); }
            model.replace(replacement);
        }
        return view();
    }

    @Transactional
    public ObjectNode updateLimits(ModelLimits input) {
        if (budgets.lock().orElseThrow().getActiveCallId() != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MODEL_BUSY");
        var settings = store.model(defaults);
        input.apply(settings);
        ModelClient replacement;
        try { replacement = new ModelClient(mapper, validator, settings); }
        catch (IllegalArgumentException error) { throw invalid(error.getMessage()); }
        synchronized (model) {
            try { store.saveModel(settings); }
            catch (IOException error) { replacement.close(); throw storageFailure(); }
            model.replace(replacement);
        }
        return view();
    }

    private ResponseStatusException invalid(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private ResponseStatusException storageFailure() {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "CONNECTION_SETTINGS_SAVE_FAILED");
    }
}
