package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.config.ModelSettings;
import cn.har01d.atmaintenance.dto.AtConnectionSettings;
import cn.har01d.atmaintenance.dto.EvidenceGatewaySettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.List;
import java.util.Set;

/** Local administrator configuration; never included in source exports or API responses. */
@Component
public class ConnectionSettingsStore {
    private final ObjectMapper mapper;
    private final Environment environment;
    private final Path path;
    private ObjectNode saved;

    public ConnectionSettingsStore(ObjectMapper mapper, Environment environment,
            @Value("${maintenance.connections-file:${MAINTENANCE_DATA_DIR:./data}/connections.json}") String filename) {
        this.mapper = mapper;
        this.environment = environment;
        path = Path.of(filename).toAbsolutePath().normalize();
        try {
            saved = Files.exists(path) ? (ObjectNode) mapper.readTree(Files.readAllBytes(path))
                    : mapper.createObjectNode();
        } catch (IOException | ClassCastException error) {
            throw new IllegalStateException("CONNECTION_SETTINGS_READ_FAILED");
        }
    }

    public synchronized AtConnectionSettings at() {
        if (saved.has("at")) return mapper.convertValue(saved.get("at"), AtConnectionSettings.class);
        String mode = property("auth-mode", "session");
        return new AtConnectionSettings(property("origin", ""), property("instance-id", ""),
                property("revision", "unverified"), mode,
                property("api_key".equals(mode) ? "api-key" : "session-token", ""),
                Boolean.parseBoolean(property("allow-http", "false")), property("runtime-identity-path", ""));
    }

    public synchronized ModelSettings model(ModelSettings defaults) {
        var settings = new ModelSettings();
        BeanUtils.copyProperties(defaults, settings);
        if (saved.has("model")) {
            try { mapper.readerForUpdating(settings).readValue(saved.get("model")); }
            catch (IOException error) { throw new IllegalStateException("CONNECTION_SETTINGS_READ_FAILED"); }
        }
        if (saved.has("modelLimits")) mapper.convertValue(saved.get("modelLimits"),
                cn.har01d.atmaintenance.dto.ModelLimits.class).apply(settings);
        return settings;
    }

    public synchronized void saveAt(AtConnectionSettings settings) throws IOException {
        var updated = saved.deepCopy();
        updated.set("at", mapper.valueToTree(settings));
        save(updated);
    }

    public synchronized void saveModel(ModelSettings settings) throws IOException {
        var updated = saved.deepCopy();
        ObjectNode value = mapper.createObjectNode().put("enabled", settings.isEnabled())
                .put("autoSubmitEnabled", settings.isAutoSubmitEnabled())
                .put("endpoint", settings.getEndpoint()).put("baseUrl", settings.getBaseUrl())
                .put("model", settings.getModel()).put("apiKey", settings.getApiKey())
                .put("keyEnv", settings.getKeyEnv()).put("timeoutMillis", settings.getTimeoutMillis())
                .put("maxCompletionTokens", settings.getMaxCompletionTokens())
                .put("maxTokenParameter", settings.getMaxTokenParameter())
                .put("jsonMode", settings.getJsonMode()).put("includeStoreParameter",
                        settings.isIncludeStoreParameter());
        updated.set("model", value);
        updated.set("modelLimits", mapper.valueToTree(cn.har01d.atmaintenance.dto.ModelLimits.from(settings)));
        save(updated);
    }

    public synchronized EvidenceGatewaySettings evidenceGateway() {
        return saved.has("evidenceGateway")
                ? mapper.convertValue(saved.get("evidenceGateway"), EvidenceGatewaySettings.class)
                : EvidenceGatewaySettings.disabled();
    }

    public synchronized void saveEvidenceGateway(EvidenceGatewaySettings settings) throws IOException {
        var updated = saved.deepCopy();
        updated.set("evidenceGateway", mapper.valueToTree(settings));
        save(updated);
    }

    synchronized com.fasterxml.jackson.databind.JsonNode sourceCredentials(String sourceId) {
        return saved.path("sourceCredentials").path(sourceId).deepCopy();
    }

    synchronized void saveSourceCredentials(String sourceId, ObjectNode value) throws IOException {
        var updated = saved.deepCopy();
        updated.withObject("/sourceCredentials").set(sourceId, value);
        save(updated);
    }

    private void save(ObjectNode updated) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".connections-", ".tmp");
        try {
            // Restrict the file before writing credentials, including on Windows.
            var acl = Files.getFileAttributeView(temporary, AclFileAttributeView.class);
            if (acl != null) {
                var owner = Files.getOwner(temporary);
                acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                        .setPrincipal(owner).setPermissions(Set.of(AclEntryPermission.values())).build()));
            } else {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            }
            Files.write(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(updated));
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            saved = updated;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String property(String name, String fallback) {
        return environment.getProperty("maintenance.at." + name, fallback);
    }
}
