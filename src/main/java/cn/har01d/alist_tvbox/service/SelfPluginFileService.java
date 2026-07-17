package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SelfPluginFileService {
    private static final String REPOSITORY_DIR = "self-plugins";
    private static final String PLUGIN_DIR = "py";
    private static final String INDEX_FILE = "spiders_v2.json";

    private final ObjectMapper objectMapper;

    public SelfPluginFileService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public StoredPlugin store(String pluginId, Integer version, String packageText, String contextPath) {
        String safeId = safePluginId(pluginId);
        try {
            Path repoDir = repositoryPath();
            Path pluginDir = repoDir.resolve(PLUGIN_DIR);
            Files.createDirectories(pluginDir);

            Path pluginPath = pluginDir.resolve(safeId + ".txt");
            Files.writeString(pluginPath, packageText, StandardCharsets.UTF_8);
            upsertIndexEntry(safeId, version == null ? 1 : version);

            String relativeFile = REPOSITORY_DIR + "/" + PLUGIN_DIR + "/" + safeId + ".txt";
            String relativeIndex = REPOSITORY_DIR + "/" + INDEX_FILE;
            String base = StringUtils.removeEnd(StringUtils.defaultString(contextPath), "/");
            return new StoredPlugin(
                    "/static/" + relativeFile,
                    "/static/" + relativeIndex,
                    base + "/static/" + relativeFile,
                    base + "/static/" + relativeIndex,
                    pluginPath.toString()
            );
        } catch (IOException e) {
            throw new BadRequestException("自用插件仓库写入失败: " + e.getMessage(), e);
        }
    }

    private void upsertIndexEntry(String pluginId, Integer version) throws IOException {
        Path indexPath = repositoryPath().resolve(INDEX_FILE);
        List<Map<String, Object>> entries = readIndex(indexPath);
        String file = PLUGIN_DIR + "/" + pluginId + ".txt";

        boolean updated = false;
        for (Map<String, Object> entry : entries) {
            if (pluginId.equals(entry.get("id"))) {
                entry.put("file", file);
                entry.put("version", version);
                entry.put("valid", true);
                updated = true;
                break;
            }
        }
        if (!updated) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", pluginId);
            entry.put("file", file);
            entry.put("version", version);
            entry.put("valid", true);
            entries.add(entry);
        }

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(indexPath.toFile(), entries);
    }

    private List<Map<String, Object>> readIndex(Path indexPath) throws IOException {
        if (!Files.isRegularFile(indexPath)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(objectMapper.readValue(
                Files.readString(indexPath, StandardCharsets.UTF_8),
                new TypeReference<List<Map<String, Object>>>() {
                }
        ));
    }

    private Path repositoryPath() {
        return Utils.getWebPath("static", REPOSITORY_DIR);
    }

    private String safePluginId(String pluginId) {
        String value = StringUtils.trimToNull(pluginId);
        if (value == null) {
            throw new BadRequestException("插件 ID 不能为空");
        }
        if (!value.matches("[A-Za-z0-9_.-]{1,80}")) {
            throw new BadRequestException("插件 ID 只能包含字母、数字、点、下划线和短横线");
        }
        return value;
    }

    public record StoredPlugin(
            String relativePluginUrl,
            String relativeRepositoryUrl,
            String pluginUrl,
            String repositoryUrl,
            String localPath
    ) {
    }
}
