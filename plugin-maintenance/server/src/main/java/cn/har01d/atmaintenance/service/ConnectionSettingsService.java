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
                .put("endpoint", currentModel.getEndpoint()).put("baseUrl", currentModel.getBaseUrl())
                .put("model", currentModel.getModel()).put("keyEnv", currentModel.getKeyEnv())
                .put("apiKeyConfigured", !currentModel.getApiKey().isEmpty())
                .put("timeoutMillis", currentModel.getTimeoutMillis())
                .put("maxCompletionTokens", currentModel.getMaxCompletionTokens())
                .put("maxTokenParameter", currentModel.getMaxTokenParameter())
                .put("jsonMode", currentModel.getJsonMode())
                .put("includeStoreParameter", currentModel.isIncludeStoreParameter());
        root.set("limits", mapper.valueToTree(ModelLimits.from(currentModel)));
        return root;
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
