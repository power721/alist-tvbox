package cn.har01d.atmaintenance.service;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.service.PluginMutationGuard;
import cn.har01d.alist_tvbox.service.PluginService;
import cn.har01d.alist_tvbox.util.Utils;
import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

@Service
@RequiredArgsConstructor
public class EmbeddedAtHostPublication implements AtHostPublication {
    private final EmbeddedAtHostAccess host;
    private final PluginRepository repository;
    private final ObjectProvider<PluginService> plugins;
    private final ObjectProvider<EmbeddedDeploymentService> deployments;
    private final PluginMutationGuard mutationGuard;
    private final ObjectMapper mapper;

    @Override public String instanceKey() { return host.connection().instanceKey(); }
    @Override public JsonNode plugins() { return mapper.valueToTree(repository.findAll()); }

    private Path staticFile(String path, boolean writing) throws IOException {
        if (!path.startsWith(writing ? "/static/plugins/" : "/static/") || !path.endsWith(".py")
                || path.contains("\\") || path.contains("%") || path.contains("//")) throw failure();
        Path root = Utils.getWebPath("static").toRealPath();
        Path file = root.resolve(path.substring("/static/".length())).normalize();
        if (!file.startsWith(root)) throw failure();
        // Check each existing ancestor before creating any directory, including symlinks.
        Path ancestor = file.getParent();
        while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (!ancestor.toRealPath().startsWith(root)) throw failure();
        if (writing) Files.createDirectories(file.getParent());
        if (Files.exists(file) && !file.toRealPath().startsWith(root)) throw failure();
        return file;
    }

    @Override
    public byte[] source(String path, boolean absentAllowed) {
        try {
            Path file = staticFile(path, false);
            if (absentAllowed && !Files.exists(file)) return null;
            try (var input = Files.newInputStream(file)) {
                byte[] bytes = input.readNBytes(1_048_577);
                if (bytes.length > 1_048_576) throw failure();
                return bytes;
            }
        } catch (IOException error) { throw failure(); }
    }

    @Override
    @Transactional
    public void upload(String path, byte[] content) {
        if (content.length > 1_048_576) throw failure();
        repository.findByUrl(path).ifPresent(plugin -> mutationGuard.writable(plugin.getId()));
        try {
            Path file = staticFile(path, true);
            Path temporary = Files.createTempFile(file.getParent(), ".maintenance-", ".tmp");
            try {
                Files.write(temporary, content);
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
            plugins.getObject().upsertFromContent(path, new String(content, StandardCharsets.UTF_8));
        } catch (IOException error) { throw failure(); }
    }

    @Override public void refresh(int id) { plugins.getObject().refresh(id); }

    @Override
    public void rename(int id, String name, JsonNode original) {
        var saved = repository.findById(id).orElseThrow(EmbeddedAtHostPublication::failure);
        var input = new Plugin();
        input.setName(name); input.setUrl(saved.getUrl()); input.setEnabled(original.path("enabled").asBoolean(true));
        input.setExtend(original.path("extend").isTextual() ? original.path("extend").asText() : null);
        plugins.getObject().update(id, input);
    }

    @Override public AtSavedRuntimeTarget savedRuntime(int id) { return deployments.getObject().savedRuntime(id); }

    @Override
    public AtRuntimeActivation activate(int id, StartAtRuntimeActivationRequest input, String activationId) {
        return activationId == null ? deployments.getObject().activate(id, input)
                : deployments.getObject().resumeActivation(activationId,
                    new RollbackEmbeddedDeploymentRequest(input.expectedWorkerGeneration(), input.interruptIssuedPlayback()));
    }

    private static ResponseStatusException failure() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "AT_PUBLISH_HOST_FILE_UNAVAILABLE");
    }
}
