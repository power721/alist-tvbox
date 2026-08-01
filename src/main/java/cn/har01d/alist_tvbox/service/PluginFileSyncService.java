package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 静态文件目录与订阅源（Spider Plugin）的双向同步。
 * <p>
 * 静态目录 {@code static/plugins/} 下的 {@code .py} 文件会自动注册为订阅源：
 * url 形如 {@code /static/plugins/<相对路径>.py} 作为唯一身份，文件删除后对应插件一并删除。
 * 远程导入的插件 url 不带该前缀，永不受影响。所有写操作后调用 {@link #reconcile()} 幂等收敛。
 */
@Slf4j
@Service
public class PluginFileSyncService {

    private final PluginService pluginService;
    private final PluginRepository pluginRepository;

    public PluginFileSyncService(PluginService pluginService, PluginRepository pluginRepository) {
        this.pluginService = pluginService;
        this.pluginRepository = pluginRepository;
    }

    /**
     * 重扫 static/plugins/ 下的 .py 文件，upsert 对应插件，并删除文件已不存在的 file-backed 插件。
     * 幂等，可安全重复调用。
     */
    public synchronized void reconcile() {
        Path base = Utils.getWebPath("static");
        Path dir = base.resolve(PluginService.FILE_PLUGIN_DIR);
        // 自动创建 plugins 目录，确保静态文件页面可见、可直接上传
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("failed to create plugins dir: {}", dir, e);
        }

        List<Plugin> existing = pluginRepository.findByUrlStartingWithOrderBySortOrderAscIdAsc(PluginService.FILE_PLUGIN_URL_PREFIX);
        if (!Files.exists(dir)) {
            // 目录创建失败：无法提供文件，移除残留的 file-backed 插件
            log.debug("plugins dir unavailable, removing {} file-backed plugins", existing.size());
            existing.forEach(this::deleteQuietly);
            return;
        }

        Set<String> presentUrls = new HashSet<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".py"))
                    .forEach(file -> {
                        String url = toStaticUrl(base, file);
                        try {
                            String body = Files.readString(file);
                            pluginService.upsertFromContent(url, body);
                            presentUrls.add(url);
                        } catch (Exception e) {
                            log.warn("failed to register plugin file {}: {}", file, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("failed to walk plugins dir: {}", dir, e);
            return;
        }

        for (Plugin plugin : existing) {
            if (!presentUrls.contains(plugin.getUrl())) {
                deleteQuietly(plugin);
            }
        }
        log.debug("plugin file sync reconciled: {} present", presentUrls.size());
    }

    private String toStaticUrl(Path base, Path file) {
        String relative = base.relativize(file).toString().replace('\\', '/');
        return PluginService.STATIC_URL_PREFIX + relative;
    }

    private void deleteQuietly(Plugin plugin) {
        try {
            pluginService.delete(plugin.getId());
        } catch (Exception e) {
            log.warn("failed to delete stale plugin {}: {}", plugin.getUrl(), e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            reconcile();
        } catch (Exception e) {
            log.warn("startup plugin file reconcile failed", e);
        }
    }
}
