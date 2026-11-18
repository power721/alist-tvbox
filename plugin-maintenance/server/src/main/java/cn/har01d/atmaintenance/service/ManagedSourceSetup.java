package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.RepairSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Administrator-installed infrastructure helper. Submitted source is AST data only. */
@Service
public class ManagedSourceSetup {
    private final SourceService sources;
    private final ProfileService profiles;
    private final ManagedSourceRuntime managed;
    private final FixtureRuntime fixture;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String python;
    private final String script;
    private final String routes;
    private final String tunnels;
    private String localSocket = "";

    public ManagedSourceSetup(SourceService sources, ProfileService profiles, ManagedSourceRuntime managed, FixtureRuntime fixture,
                              ObjectMapper mapper,
                              @Value("${maintenance.managed-source.setup.enabled:false}") boolean enabled,
                              @Value("${maintenance.managed-source.setup.python:${maintenance.python}}") String python,
                              @Value("${maintenance.managed-source.setup.script:}") String script,
                              @Value("${maintenance.managed-source.routes-file:}") String routes,
                              @Value("${maintenance.managed-source.setup.tunnels-file:}") String tunnels) {
        this.sources = sources;
        this.profiles = profiles;
        this.managed = managed;
        this.fixture = fixture;
        this.mapper = mapper;
        this.enabled = enabled;
        this.python = python;
        this.script = script;
        this.routes = routes;
        this.tunnels = tunnels;
    }

    @Autowired
    void configureLocal(@Value("${maintenance.managed-source.setup.socket:}") String socket) {
        this.localSocket = socket;
    }

    private boolean configured() {
        return enabled && !routes.isBlank() && (!localSocket.isBlank() || !script.isBlank() && !tunnels.isBlank());
    }

    public JsonNode localConnection() throws InterruptedException {
        var result = mapper.createObjectNode().put("backend", "same_host_t4")
                .put("automaticPreparation", true).put("connected", false);
        if (!configured() || localSocket.isBlank()) return result.put("code", "SOURCE_SETUP_UNAVAILABLE");
        try {
            var observed = LocalSourceSetupClient.call(mapper, localSocket,
                    mapper.createObjectNode().put("operation", "identity"));
            if (!observed.path("ok").asBoolean() || !observed.path("ready").asBoolean()
                    || !"same_host_t4".equals(observed.path("backend").asText())
                    || !observed.path("sourceExecuted").isBoolean() || observed.path("sourceExecuted").asBoolean())
                return result.put("code", "SOURCE_SETUP_RECEIPT_INVALID");
            return result.put("connected", true).put("authority", "source_setup_connection_observed");
        } catch (IOException error) {
            return result.put("code", "SOURCE_SETUP_UNAVAILABLE");
        }
    }

    public boolean sameHost() { return configured() && !localSocket.isBlank(); }

    public boolean availableFor(String sourceId) {
        return sourceId != null && configured()
                && !managed.hasSourceRoute(sourceId) && managed.forSource(sourceId).configurationBlockedReason() != null;
    }

    public synchronized void ensure(String sourceId, String expectedSha) {
        sources.requireMaintenanceAllowed(sourceId);
        if (!availableFor(sourceId)) return;
        configure(sourceId, expectedSha, null);
    }

    boolean hasVerifiedOrigin(RepairSummary repair) {
        return !RepairEvidenceMaterial.originRoute(mapper, repair, repair.frozen().path("reverseAnalysis")).isMissingNode();
    }

    public synchronized boolean ensureVerifiedOrigin(RepairSummary repair) {
        JsonNode route = RepairEvidenceMaterial.originRoute(mapper, repair, repair.frozen().path("reverseAnalysis"));
        if (route.isMissingNode()) return false;
        sources.requireMaintenanceAllowed(repair.sourceId());
        if (!configured())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_SETUP_UNAVAILABLE");
        if (repair.activeCandidateId() != null || repair.staleInputs().stream().anyMatch(value ->
                !List.of("runtime", "execution_policy", "inspector").contains(value)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REPAIR_INPUTS_CHANGED");
        if (!List.of("BLOCKED", "INTERRUPTED", "AWAITING_RUNTIME_VALIDATION").contains(repair.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "REPAIR_REVALIDATION_NOT_READY");
        configure(repair.sourceId(), repair.baselineArtifactSha256(), route);
        return true;
    }

    private void configure(String sourceId, String expectedSha, JsonNode verifiedRoute) {
        if (!sources.get(sourceId).artifactSha256().equals(expectedSha))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MAINTENANCE_SOURCE_CHANGED");
        Path directory = null;
        try {
            var sourceConfig = SourceConfig.read(profiles.get(sourceId).snapshot());
            String sourceConfigSha = sourceConfig.isEmpty() ? null : SourceConfig.sha256(sourceConfig);
            Map<String, String> environment = new HashMap<>();
            // The trusted SSH helper must find the user's existing known_hosts.
            for (String name : List.of("USERPROFILE", "HOME")) {
                String value = System.getenv(name);
                if (value != null) environment.put(name, value);
            }
            if (localSocket.isBlank()) {
                var ssh = mapper.readTree(Files.readAllBytes(Path.of(tunnels))).path("ssh");
                for (String key : List.of("hostEnv", "portEnv", "userEnv", "passwordEnv", "sudoPasswordEnv")) {
                    if (!ssh.has(key)) continue;
                    String name = ssh.path(key).asText();
                    if (!name.matches("[A-Z][A-Z0-9_]{0,99}")) throw new IOException("SOURCE_SETUP_CONFIGURATION_INVALID");
                    String value = System.getenv(name);
                    if (value != null) environment.put(name, value);
                }
            }
            var request = mapper.createObjectNode().put("sourceId", sourceId).put("sourceSha256", expectedSha)
                    .put("bundleSha256", managed.sha256())
                    .put("content", sources.artifact(sourceId, expectedSha).getBody());
            if (sourceConfigSha != null) {
                request.set("sourceConfig", sourceConfig);
                request.put("sourceConfigSha256", sourceConfigSha);
            }
            if (verifiedRoute != null) request.set("verifiedOriginRoute", verifiedRoute);
            JsonNode result;
            if (!localSocket.isBlank()) {
                result = LocalSourceSetupClient.call(mapper, localSocket, request);
            } else {
                directory = Files.createTempDirectory("at-maintenance-source-setup-");
                result = mapper.readTree(PythonProcess.run(python, Path.of(script).toAbsolutePath(), directory,
                        List.of("--routes-file", routes, "--tunnels-file", tunnels),
                        mapper.writeValueAsBytes(request), 120, environment));
            }
            if (!result.path("ok").asBoolean()) {
                String code = result.path("code").asText();
                throw new IOException(code.matches("SOURCE_SETUP_[A-Z_]{1,80}") ? code : "SOURCE_SETUP_UNAVAILABLE");
            }
            if (!result.path("sourceId").asText().equals(sourceId)
                    || !result.path("sourceSha256").asText().equals(expectedSha)
                    || !result.path("bundleSha256").asText().equals(managed.sha256())
                    || sourceConfigSha != null && !sourceConfigSha.equals(result.path("sourceConfigSha256").asText())
                    || verifiedRoute != null && (!verifiedRoute.equals(result.path("verifiedOriginRoute"))
                        || !result.path("approvedOrigins").valueStream().anyMatch(origin ->
                            origin.asText().equals(verifiedRoute.path("candidateOrigin").asText())))
                    || !result.path("sourceExecuted").isBoolean() || result.path("sourceExecuted").asBoolean()
                    || !result.path("ready").asBoolean())
                throw new IOException("SOURCE_SETUP_RECEIPT_INVALID");
            managed.configureRoutes(fixture, routes);
            var selected = managed.forSource(sourceId);
            if (selected.configurationBlockedReason() != null || !selected.sampling()
                    || !selected.snapshot().path("egressPolicySha256").equals(result.path("egressPolicySha256")))
                throw new IOException("SOURCE_SETUP_RECEIPT_INVALID");
        } catch (IOException error) {
            String code = error.getMessage();
            if ("SOURCE_CONFIG_INVALID".equals(code)) code = "SOURCE_SETUP_SOURCE_CONFIG_INVALID";
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    code != null && code.matches("SOURCE_SETUP_[A-Z_]{1,80}") ? code : "SOURCE_SETUP_UNAVAILABLE");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_SETUP_INTERRUPTED");
        } finally {
            if (directory != null) {
                try { PythonProcess.deleteDirectory(directory); }
                catch (IOException error) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_SETUP_LOCAL_CLEANUP_FAILED");
                }
            }
        }
    }
}
