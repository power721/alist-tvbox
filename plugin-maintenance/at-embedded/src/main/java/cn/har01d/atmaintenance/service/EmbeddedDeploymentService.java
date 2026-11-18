package cn.har01d.atmaintenance.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.service.PluginService;
import cn.har01d.alist_tvbox.service.PluginMutationAdmission;
import cn.har01d.alist_tvbox.service.PluginMutationGuard;
import cn.har01d.alist_tvbox.service.PluginWorkerAdmission;
import cn.har01d.alist_tvbox.service.PluginWorkerService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.EmbeddedDeployment;
import cn.har01d.atmaintenance.repository.EmbeddedDeploymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class EmbeddedDeploymentService implements PluginWorkerAdmission, PluginMutationAdmission {
    private static final Set<String> BLOCKING = Set.of("PREPARED", "SAVED", "ROLLING_BACK", "RECOVERY_REQUIRED");
    private final EmbeddedDeploymentRepository deployments;
    private final PluginRepository plugins;
    private final PluginMutationGuard mutationGuard;
    private final ReleasePlanService plans;
    private final RepairStore repairs;
    private final ObjectProvider<EmbeddedAtRuntimeService> runtime;
    private final ObjectProvider<PluginWorkerService> workers;
    private final ObjectProvider<SubscriptionService> subscriptions;
    private final ObjectProvider<AppProperties> properties;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final boolean enabled;

    public EmbeddedDeploymentService(EmbeddedDeploymentRepository deployments, PluginRepository plugins,
            ReleasePlanService plans, RepairStore repairs, ObjectProvider<EmbeddedAtRuntimeService> runtime,
            ObjectProvider<PluginWorkerService> workers,
            ObjectProvider<SubscriptionService> subscriptions, ObjectProvider<AppProperties> properties,
            ObjectMapper mapper, PlatformTransactionManager manager,
            @Value("${maintenance.at-native.deployment-enabled:false}") boolean enabled,
            PluginMutationGuard mutationGuard) {
        this.deployments = deployments;
        this.plugins = plugins;
        this.mutationGuard = mutationGuard;
        this.plans = plans;
        this.repairs = repairs;
        this.runtime = runtime;
        this.workers = workers;
        this.subscriptions = subscriptions;
        this.properties = properties;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
        this.enabled = enabled;
    }

    @Override
    public boolean blocked(String siteKey) {
        return deployments.existsBySiteKeyAndStateIn(siteKey, BLOCKING);
    }

    @Override
    public boolean managed(int pluginId) {
        return deployments.existsByActivePluginId(pluginId);
    }

    public synchronized EmbeddedDeploymentSummary deploy(int pluginId, StartEmbeddedDeploymentRequest input) {
        requireEnabled();
        String requestSha = Hashes.sha256(mapper.valueToTree(input).toString());
        var existing = deployments.findByPluginIdAndRequestKey(pluginId, input.requestKey());
        if (existing.isPresent()) {
            if (!requestSha.equals(existing.get().getRequestSha256())) throw conflict("AT_DEPLOYMENT_REQUEST_CONFLICT");
            return summary(existing.get()); // A retry observes the existing intent, never replays it.
        }
        var intent = transaction.execute(status -> {
            var plan = plans.get(input.planId());
            if (!plan.localApplyEligible() || !plan.frozenSha256().equals(input.expectedFrozenSha256())
                    || !plan.baselineArtifactSha256().equals(input.expectedPluginSha256()))
                throw conflict("AT_DEPLOYMENT_VALIDATED_PLAN_REQUIRED");
            if (boundPluginId(plan.id()) != pluginId)
                throw conflict("AT_DEPLOYMENT_PLUGIN_BINDING_REQUIRED");
            Plugin plugin = mutationGuard.lock(pluginId);
            if (!PluginService.isPythonPluginUrl(plugin.getUrl()) || PluginService.isWebPagePlugin(plugin))
                throw conflict("AT_DEPLOYMENT_RAW_PYTHON_REQUIRED");
            if (!input.expectedPluginSha256().equals(Hashes.sha256(content(plugin))))
                throw conflict("AT_DEPLOYMENT_PLUGIN_CHANGED");
            if (deployments.existsByActivePluginId(pluginId)) throw conflict("AT_DEPLOYMENT_ALREADY_ACTIVE");
            String candidate = repairs.content(plan.candidateId());
            if (!Hashes.sha256(candidate).equals(plan.candidateSha256())) throw conflict("AT_DEPLOYMENT_CANDIDATE_CHANGED");
            var row = new EmbeddedDeployment();
            row.setPluginId(pluginId);
            row.setActivePluginId(pluginId);
            row.setSiteKey(SubscriptionService.pluginSiteKey(plugin));
            row.setPlanId(plan.id());
            row.setRequestKey(input.requestKey());
            row.setRequestSha256(requestSha);
            row.setBaselineContent(content(plugin));
            row.setCandidateContent(candidate);
            row.setBaselineSha256(input.expectedPluginSha256());
            row.setCandidateSha256(plan.candidateSha256());
            row.setConfigurationSha256(configuration(plugin));
            row.setExpectedGeneration(input.expectedWorkerGeneration());
            row.setInterruptPlayback(input.interruptIssuedPlayback());
            return deployments.saveAndFlush(row);
        });
        PluginWorkerService.MaintenanceWindow window;
        try {
            window = workers.getObject().openMaintenance(intent.getSiteKey(), intent.getExpectedGeneration(),
                    intent.isInterruptPlayback());
        } catch (RuntimeException e) {
            return finish(intent.getId(), "REJECTED", null, fixedCode(e), true);
        }
        try (window) {
            try {
                verifyCurrent(intent, intent.getBaselineSha256());
                var loaded = window.identity();
                if (loaded != null && !loaded.path("sourceTextSha256").asText().equals(intent.getBaselineSha256()))
                    throw conflict("AT_DEPLOYMENT_BASELINE_NOT_LOADED");
            } catch (RuntimeException e) {
                return finish(intent.getId(), "REJECTED", null, fixedCode(e), true);
            }
            try {
                window.stop();
                saveContent(intent, intent.getBaselineSha256(), intent.getCandidateContent(), "SAVED");
                var identity = window.load(extend(intent), intent.getCandidateSha256());
                verifyCurrent(intent, intent.getCandidateSha256());
                return finish(intent.getId(), "DEPLOYED", identity.path("generationId").asText(), null, false);
            } catch (RuntimeException e) {
                return compensate(intent, window, fixedCode(e));
            }
        }
    }

    public synchronized EmbeddedDeploymentSummary rollback(String id, RollbackEmbeddedDeploymentRequest input) {
        requireEnabled();
        var intent = require(id);
        if (intent.getPlanId() == null) throw conflict("AT_ACTIVATION_RESUME_REQUIRED");
        if (intent.getState().equals("ROLLED_BACK")) return summary(intent);
        if (!Set.of("DEPLOYED", "PREPARED", "SAVED", "ROLLING_BACK", "RECOVERY_REQUIRED").contains(intent.getState()))
            throw conflict("AT_DEPLOYMENT_NOT_ROLLBACKABLE");
        try (var window = workers.getObject().openMaintenance(intent.getSiteKey(), input.expectedWorkerGeneration(),
                input.interruptIssuedPlayback())) {
            finish(id, "ROLLING_BACK", null, intent.getErrorCode(), false);
            return compensate(intent, window, intent.getErrorCode());
        }
    }

    public AtSavedRuntimeTarget savedRuntime(int pluginId) {
        Plugin plugin = plugin(pluginId);
        return new AtSavedRuntimeTarget(enabled, pluginId, Hashes.sha256(content(plugin)), configuration(plugin),
                runtime.getObject().observe(pluginId),
                deployments.findTop20ByPluginIdOrderByCreatedAtDesc(pluginId).stream()
                        .filter(row -> row.getPlanId() == null).map(this::activationSummary).toList());
    }

    public synchronized AtRuntimeActivation activate(int pluginId, StartAtRuntimeActivationRequest input) {
        requireEnabled();
        String requestSha = Hashes.sha256(mapper.valueToTree(input).toString());
        var previous = deployments.findByPluginIdAndRequestKey(pluginId, input.requestKey());
        if (previous.isPresent()) {
            if (previous.get().getPlanId() != null || !requestSha.equals(previous.get().getRequestSha256()))
                throw conflict("AT_DEPLOYMENT_REQUEST_CONFLICT");
            return activationSummary(previous.get());
        }
        var row = transaction.execute(status -> {
            Plugin plugin = mutationGuard.lock(pluginId);
            if (!PluginService.isPythonPluginUrl(plugin.getUrl()) || PluginService.isWebPagePlugin(plugin))
                throw conflict("AT_DEPLOYMENT_RAW_PYTHON_REQUIRED");
            if (deployments.existsByActivePluginId(pluginId)) throw conflict("AT_DEPLOYMENT_ALREADY_ACTIVE");
            if (!input.expectedPluginSha256().equals(Hashes.sha256(content(plugin)))
                    || !input.expectedConfigurationSha256().equals(configuration(plugin)))
                throw conflict("AT_DEPLOYMENT_PLUGIN_CHANGED");
            var intent = new EmbeddedDeployment();
            intent.setPluginId(pluginId);
            intent.setActivePluginId(pluginId);
            intent.setSiteKey(SubscriptionService.pluginSiteKey(plugin));
            intent.setRequestKey(input.requestKey());
            intent.setRequestSha256(requestSha);
            // Activation never rewrites source; the existing durable window also covers restart recovery.
            intent.setBaselineContent(content(plugin));
            intent.setCandidateContent(content(plugin));
            intent.setBaselineSha256(input.expectedPluginSha256());
            intent.setCandidateSha256(input.expectedPluginSha256());
            intent.setConfigurationSha256(input.expectedConfigurationSha256());
            intent.setExpectedGeneration(input.expectedWorkerGeneration());
            intent.setInterruptPlayback(input.interruptIssuedPlayback());
            return deployments.saveAndFlush(intent);
        });
        return activateSaved(row, input.expectedWorkerGeneration(), input.interruptIssuedPlayback(), false);
    }

    public synchronized AtRuntimeActivation resumeActivation(String id, RollbackEmbeddedDeploymentRequest input) {
        requireEnabled();
        var row = require(id);
        if (row.getPlanId() != null) throw conflict("AT_ACTIVATION_NOT_FOUND");
        if ("DEPLOYED".equals(row.getState())) return activationSummary(row);
        if (!Set.of("PREPARED", "RECOVERY_REQUIRED").contains(row.getState()))
            throw conflict("AT_ACTIVATION_NOT_RESUMABLE");
        return activateSaved(row, input.expectedWorkerGeneration(), input.interruptIssuedPlayback(), true);
    }

    private AtRuntimeActivation activateSaved(EmbeddedDeployment row, String generation, boolean interrupt,
                                               boolean recovering) {
        PluginWorkerService.MaintenanceWindow window;
        try {
            window = workers.getObject().openMaintenance(row.getSiteKey(), generation, interrupt);
        } catch (RuntimeException error) {
            finish(row.getId(), recovering ? "RECOVERY_REQUIRED" : "REJECTED", null, fixedCode(error), !recovering);
            return activationSummary(require(row.getId()));
        }
        try (window) {
            try {
                verifyCurrent(row, row.getCandidateSha256());
            } catch (RuntimeException error) {
                finish(row.getId(), recovering ? "RECOVERY_REQUIRED" : "REJECTED", null, fixedCode(error), !recovering);
                return activationSummary(require(row.getId()));
            }
            try {
                window.stop();
                var identity = window.load(extend(row), row.getCandidateSha256());
                verifyCurrent(row, row.getCandidateSha256());
                finish(row.getId(), "DEPLOYED", identity.path("generationId").asText(), null, true);
            } catch (RuntimeException error) {
                finish(row.getId(), "RECOVERY_REQUIRED", null, fixedCode(error), false);
            }
            return activationSummary(require(row.getId()));
        }
    }

    private AtRuntimeActivation activationSummary(EmbeddedDeployment row) {
        return new AtRuntimeActivation(row.getId(), row.getRequestKey(), row.getState(),
                row.getLoadedGeneration(), row.getErrorCode());
    }

    private EmbeddedDeploymentSummary compensate(EmbeddedDeployment intent,
            PluginWorkerService.MaintenanceWindow window, String failure) {
        try {
            finish(intent.getId(), "ROLLING_BACK", null, failure, false);
            window.stop();
            Plugin plugin = plugin(intent.getPluginId());
            String currentSha = Hashes.sha256(content(plugin));
            if (!Set.of(intent.getBaselineSha256(), intent.getCandidateSha256()).contains(currentSha))
                throw conflict("AT_DEPLOYMENT_EXTERNAL_CONTENT_CHANGED");
            saveContent(intent, currentSha, intent.getBaselineContent(), "ROLLING_BACK");
            var identity = window.load(extend(intent), intent.getBaselineSha256());
            verifyCurrent(intent, intent.getBaselineSha256());
            return finish(intent.getId(), "ROLLED_BACK", identity.path("generationId").asText(), failure, true);
        } catch (RuntimeException e) {
            return finish(intent.getId(), "RECOVERY_REQUIRED", null, fixedCode(e), false);
        }
    }

    private void saveContent(EmbeddedDeployment intent, String expectedSha, String body, String nextState) {
        transaction.executeWithoutResult(status -> {
            Plugin plugin = mutationGuard.lock(intent.getPluginId());
            if (!configuration(plugin).equals(intent.getConfigurationSha256())
                    || !expectedSha.equals(Hashes.sha256(content(plugin))))
                throw conflict("AT_DEPLOYMENT_PLUGIN_CHANGED");
            plugin.setContent(body);
            plugin.setVersion(Math.addExact(plugin.getVersion() == null ? 0 : plugin.getVersion(), 1));
            plugins.saveAndFlush(plugin);
            var row = require(intent.getId());
            row.setState(nextState);
            row.setUpdatedAt(Instant.now());
            deployments.saveAndFlush(row);
        });
    }

    private String extend(EmbeddedDeployment intent) {
        try {
            var service = subscriptions.getObject();
            return service.buildT4PluginExtend(plugin(intent.getPluginId()), service.getCurrentOrFirstToken(),
                    properties.getObject().getT4ProxyMode());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("AT_DEPLOYMENT_CONTEXT_FAILED");
        }
    }

    private void verifyCurrent(EmbeddedDeployment intent, String expectedSha) {
        Plugin plugin = plugin(intent.getPluginId());
        if (!configuration(plugin).equals(intent.getConfigurationSha256())
                || !Hashes.sha256(content(plugin)).equals(expectedSha)) throw conflict("AT_DEPLOYMENT_PLUGIN_CHANGED");
    }

    private String configuration(Plugin plugin) {
        return Hashes.sha256(mapper.createObjectNode().put("siteKey", SubscriptionService.pluginSiteKey(plugin))
                .put("url", plugin.getUrl()).put("extend", plugin.getExtend()).put("enabled", plugin.isEnabled())
                .put("t4Visible", plugin.isT4Visible()).toString());
    }

    private EmbeddedDeploymentSummary finish(String id, String state, String generation, String error, boolean release) {
        return transaction.execute(status -> {
            var row = require(id);
            row.setState(state);
            row.setLoadedGeneration(generation);
            row.setErrorCode(error);
            row.setUpdatedAt(Instant.now());
            if (release) row.setActivePluginId(null);
            return summary(deployments.saveAndFlush(row));
        });
    }

    public EmbeddedDeploymentSummary get(String id) { return summary(require(id)); }
    public EmbeddedDeploymentTarget target(String planId) {
        int id = boundPluginId(planId);
        return new EmbeddedDeploymentTarget(enabled, id, Hashes.sha256(content(plugin(id))),
                runtime.getObject().observe(id), list(id));
    }
    public List<EmbeddedDeploymentSummary> list(int pluginId) {
        return deployments.findTop20ByPluginIdOrderByCreatedAtDesc(pluginId).stream().map(this::summary).toList();
    }

    private int boundPluginId(String planId) {
        var id = plans.frozen(planId).path("repair").path("frozen").path("baseline")
                .path("binding").path("plugin").path("id");
        if (!id.isIntegralNumber() || !id.canConvertToInt() || id.asInt() < 1)
            throw conflict("AT_DEPLOYMENT_PLUGIN_BINDING_REQUIRED");
        return id.asInt();
    }

    private EmbeddedDeployment require(String id) {
        return deployments.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AT_DEPLOYMENT_NOT_FOUND"));
    }
    private Plugin plugin(int id) {
        return plugins.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AT_PLUGIN_NOT_FOUND"));
    }
    private String content(Plugin plugin) {
        if (plugin.getContent() == null) throw conflict("AT_DEPLOYMENT_PLUGIN_CONTENT_REQUIRED");
        return plugin.getContent();
    }
    private void requireEnabled() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.CONFLICT, "AT_DEPLOYMENT_DISABLED");
    }
    private static String fixedCode(RuntimeException error) {
        String code = error instanceof ResponseStatusException response ? response.getReason() : error.getMessage();
        return code != null && code.matches("AT_[A-Z_]{1,70}") ? code : "AT_DEPLOYMENT_EXECUTION_FAILED";
    }
    private static ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
    private EmbeddedDeploymentSummary summary(EmbeddedDeployment row) {
        return new EmbeddedDeploymentSummary(row.getId(), row.getPluginId(), row.getSiteKey(), row.getPlanId(),
                row.getBaselineSha256(), row.getCandidateSha256(), row.getState(), row.getLoadedGeneration(),
                row.getErrorCode(), row.getCreatedAt(), row.getUpdatedAt());
    }
}
