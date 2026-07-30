package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.service.PluginCompilerService;
import cn.har01d.alist_tvbox.service.PluginService;
import cn.har01d.alist_tvbox.service.SecspiderKeyService;
import cn.har01d.alist_tvbox.service.SelfPluginFileService;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/plugins")
public class PluginController {
    private final PluginService pluginService;
    private final PluginCompilerService pluginCompilerService;
    private final SecspiderKeyService secspiderKeyService;
    private final SelfPluginFileService selfPluginFileService;

    private record PluginImportRequest(String url) {
    }

    private record PluginBatchDeleteRequest(List<Integer> ids) {
    }

    public PluginController(PluginService pluginService,
                            PluginCompilerService pluginCompilerService,
                            SecspiderKeyService secspiderKeyService,
                            SelfPluginFileService selfPluginFileService) {
        this.pluginService = pluginService;
        this.pluginCompilerService = pluginCompilerService;
        this.secspiderKeyService = secspiderKeyService;
        this.selfPluginFileService = selfPluginFileService;
    }

    @GetMapping
    public List<Plugin> findAll() {
        return pluginService.findAll().stream()
                .peek(e -> e.setLastCheckedAt(e.getLastCheckedAt().truncatedTo(ChronoUnit.SECONDS)))
                .toList();
    }

    @PostMapping
    public Plugin create(@RequestBody Plugin plugin) {
        return pluginService.create(plugin);
    }

    @PostMapping("/import")
    public PluginService.ImportResult importPlugins(@RequestBody PluginImportRequest request) {
        return pluginService.importFromSource(request.url());
    }

    @GetMapping("/secspider/key")
    public SecspiderKeyService.KeyStatus secspiderKeyStatus() {
        return secspiderKeyService.ensureKeyMaterial();
    }

    @PostMapping("/secspider/key/generate")
    public SecspiderKeyService.KeyStatus generateSecspiderKey() {
        return secspiderKeyService.generateKeyMaterial(false);
    }

    @PostMapping("/secspider/key/reset")
    public SecspiderKeyService.KeyStatus resetSecspiderKey() {
        return secspiderKeyService.generateKeyMaterial(true);
    }

    @PostMapping("/compile/secspider")
    public PluginCompilerService.CompileResponse compileSecspider(@RequestBody PluginCompilerService.CompileRequest request) {
        PluginCompilerService.CompileRequest compileRequest = withManagedKeyMaterial(request);
        PluginCompilerService.CompileResponse response = pluginCompilerService.compileSecspider(compileRequest);
        if (!Boolean.TRUE.equals(compileRequest.autoImport())) {
            return response;
        }

        String pluginId = StringUtils.defaultIfBlank(compileRequest.id(), derivePluginId(compileRequest.name()));
        String contextPath = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        SelfPluginFileService.StoredPlugin storedPlugin = selfPluginFileService.store(
                pluginId,
                compileRequest.version(),
                response.packageText(),
                contextPath
        );
        Plugin plugin = pluginService.upsertCompiledPlugin(
                storedPlugin.pluginUrl(),
                storedPlugin.localPath(),
                pluginId,
                compileRequest.name(),
                compileRequest.version(),
                response.packageText()
        );
        return response.withImportedPlugin(
                plugin.getId(),
                plugin.getName(),
                storedPlugin.pluginUrl(),
                storedPlugin.repositoryUrl(),
                storedPlugin.localPath()
        );
    }

    @PostMapping("/compatibility-check/secspider")
    public PluginCompilerService.CompatibilityCheckResponse checkSecspiderCompatibility(@RequestBody PluginCompilerService.CompatibilityCheckRequest request) {
        return pluginCompilerService.checkMagnetSpiderCompatibility(request);
    }

    @PutMapping("/{id}")
    public Plugin update(@PathVariable Integer id, @RequestBody Plugin plugin) {
        return pluginService.update(id, plugin);
    }

    @PostMapping("/{id}/refresh")
    public Plugin refresh(@PathVariable Integer id) {
        return pluginService.refresh(id);
    }

    @PostMapping("/reorder")
    public void reorder(@RequestBody List<Integer> ids) {
        pluginService.reorder(ids);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) {
        pluginService.delete(id);
    }

    @PostMapping("/delete-batch")
    public int deleteBatch(@RequestBody PluginBatchDeleteRequest request) {
        return pluginService.deleteBatch(request.ids());
    }

    private PluginCompilerService.CompileRequest withManagedKeyMaterial(PluginCompilerService.CompileRequest request) {
        boolean useManagedKey = Boolean.TRUE.equals(request.useManagedKey())
                || StringUtils.isBlank(request.privateKey())
                || StringUtils.isBlank(request.masterSecret());
        String pluginId = StringUtils.defaultIfBlank(request.id(), derivePluginId(request.name()));
        if (!useManagedKey) {
            return new PluginCompilerService.CompileRequest(
                    request.name(),
                    request.version(),
                    request.remark(),
                    pluginId,
                    request.kid(),
                    request.source(),
                    request.privateKey(),
                    request.publicKey(),
                    request.masterSecret(),
                    request.useManagedKey(),
                    request.autoImport()
            );
        }

        SecspiderKeyService.CompilerKeyMaterial keyMaterial = secspiderKeyService.compilerKeyMaterial();
        return new PluginCompilerService.CompileRequest(
                request.name(),
                request.version(),
                request.remark(),
                pluginId,
                StringUtils.defaultIfBlank(request.kid(), "self"),
                request.source(),
                keyMaterial.privateKey(),
                keyMaterial.publicKey(),
                keyMaterial.masterSecret(),
                true,
                request.autoImport()
        );
    }

    private String derivePluginId(String name) {
        String normalized = StringUtils.defaultString(name)
                .trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9_.-]+", "_")
                .replaceAll("^_+|_+$", "");
        if (StringUtils.isNotBlank(normalized)) {
            return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
        }
        return "plugin_" + DigestUtils.sha256Hex(StringUtils.defaultString(name)).substring(0, 12);
    }
}
