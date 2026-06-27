package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.FileDto;
import cn.har01d.alist_tvbox.dto.FileItem;
import cn.har01d.alist_tvbox.entity.ConfigFile;
import cn.har01d.alist_tvbox.entity.ConfigFileRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service

public class ConfigFileService {
    private final ConfigFileRepository repository;
    private final ObjectMapper objectMapper;
    private List<FileItem> labels = new ArrayList<>();

    public ConfigFileService(ConfigFileRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void setup() {
        if (repository.count() == 0) {
            readFiles();
        }
        loadLabels();
    }

    public List<FileItem> getLabels() {
        return labels;
    }

    private void loadLabels() {
        try {
            Path path = Utils.getDataPath("label.txt");
            if (Files.exists(path)) {
                loadLabels(Files.readAllLines(path));
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private void loadLabels(List<String> lines) {
        try {
            labels = lines.stream()
                    .map(e -> e.split("#")[0])
                    .filter(StringUtils::isNotBlank)
                    .map(e -> e.split(":"))
                    .filter(e -> e.length == 2)
                    .map(parts -> new FileItem(parts[0].trim(), parts[1].trim(), 0))
                    .toList();
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    public void writeFiles() {
        for (ConfigFile file : repository.findAll()) {
            try {
                writeFileContent(file);
            } catch (Exception e) {
                log.warn("write file " + file.getPath(), e);
            }
        }
    }

    private void readFiles() {
        readFile(Utils.getDataPath("tv.txt"));
        readFile(Utils.getDataPath("proxy.txt"));

        if (Files.exists(Utils.getDataPath("iptv.m3u"))) {
            readFile(Utils.getWebPath("tvbox", "iptv.m3u"));
        }

        if (Files.exists(Utils.getDataPath("my.json"))) {
            readFile(Utils.getWebPath("tvbox", "my.json"));
        }

        //readFile("/opt/alist/data/config.json");
        //readFile("/etc/nginx/http.d/default.conf");
    }

    private void readFile(Path path) {
        try {
            if (Files.exists(path)) {
                String content = Files.readString(path);
                ConfigFile file = new ConfigFile();
                file.setDir(path.getParent().toString());
                file.setName(path.getFileName().toString());
                file.setPath(path.toString());
                file.setContent(content);
                repository.save(file);
                log.info("load file: {}", path);
            }
        } catch (Exception e) {
            log.warn("read file failed: {}", path, e);
        }
    }

    public List<ConfigFile> list() {
        return repository.findAll();
    }

    public ConfigFile create(FileDto dto) throws IOException {
        validate(dto);
        dto.setId(null);
        if (repository.existsByPath(dto.getPath())) {
            throw new BadRequestException("文件已经存在");
        }

        ConfigFile file = new ConfigFile(dto);
        repository.save(file);
        writeFileContent(file);
        return file;
    }

    private void validate(FileDto dto) {
        if (StringUtils.isBlank(dto.getDir())) {
            throw new BadRequestException("目录不能为空");
        }
        if (StringUtils.isBlank(dto.getName())) {
            throw new BadRequestException("文件名不能为空");
        }
        // 防路径穿越:目录不得含 ..,文件名不得含路径分隔符或 ..
        if (dto.getDir().contains("..")) {
            throw new BadRequestException("非法目录");
        }
        if (dto.getName().contains("..") || dto.getName().contains("/") || dto.getName().contains("\\")) {
            throw new BadRequestException("非法文件名");
        }
        // 安全:目录必须落在受信任根(getDataPath/getWebPath)之下,杜绝任意绝对路径写入。
        // 前端只使用 /data 与 /www/* 这些逻辑前缀;writeFileContent 也只对它们做实际路径映射。
        ensureTrustedDir(dto.getDir());
        dto.setPath(new File(dto.getDir(), dto.getName()).getAbsolutePath());
        if (dto.getName().endsWith(".json")) {
            if (StringUtils.isNotBlank(dto.getContent())) {
                try {
                    var node = objectMapper.readTree(dto.getContent());
                    dto.setContent(objectMapper.writeValueAsString(node));
                } catch (IOException e) {
                    throw new BadRequestException("JSON格式错误", e);
                }
            } else if ("null".equals(dto.getContent())) {
                dto.setContent("");
            }
        }
    }

    /**
     * 校验目录规范化后仍位于受信任根(getDataPath / getWebPath)之下。
     * 接受逻辑前缀("/data"、"/www" 及 "/www/...")与非 docker/自定义 data 目录的真实绝对路径
     * (如 /opt/atv/data、atv.data.dir);其余一律拒绝,防止越界写入(如 /etc/cron.d、/Local/...)。
     */
    private void ensureTrustedDir(String dir) {
        Path candidate;
        if ("/data".equals(dir)) {
            candidate = Utils.getDataPath();
        } else if ("/www".equals(dir) || dir.startsWith("/www/")) {
            String sub = "/www".equals(dir) ? "" : dir.substring("/www/".length());
            candidate = Utils.getWebPath(sub);
        } else {
            // 非 docker / 自定义 data 目录的真实绝对路径,交由下方 containment 校验是否受信任
            candidate = Path.of(dir);
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        Path dataRoot = Utils.getDataPath().toAbsolutePath().normalize();
        Path webRoot = Utils.getWebPath().toAbsolutePath().normalize();
        if (!normalized.equals(dataRoot) && !normalized.startsWith(dataRoot)
                && !normalized.equals(webRoot) && !normalized.startsWith(webRoot)) {
            throw new BadRequestException("非法目录");
        }
    }

    public void writeFileContent(ConfigFile configFile) throws IOException {
        Path dir = Path.of(configFile.getDir());
        if ("/data".equals(configFile.getDir())) {
            dir = Utils.getDataPath();
        } else if (configFile.getDir().startsWith("/www/")) {
            dir = Utils.getWebPath(configFile.getDir().replace("/www/", ""));
        }
        Files.createDirectories(dir);
        Path path = Path.of(dir.toString(), configFile.getName());
        log.info("write file: {}", path);
        Files.writeString(path, configFile.getContent());
        if ("/data/label.txt".equals(configFile.getPath())) {
            loadLabels(Arrays.asList(configFile.getContent().split("\n")));
        }
    }

    public ConfigFile update(Integer id, FileDto dto) throws IOException {
        validate(dto);
        ConfigFile configFile = repository.findById(id).orElseThrow(NotFoundException::new);
        try {
            Path path = Path.of(configFile.getDir(), configFile.getName());
            Files.delete(path);
        } catch (Exception e) {
            log.warn("", e);
        }

        dto.setId(id);

        ConfigFile other = repository.findByPath(dto.getPath());
        if (other != null && !id.equals(other.getId())) {
            throw new BadRequestException("文件已经存在");
        }

        ConfigFile file = new ConfigFile(dto);
        repository.save(file);
        writeFileContent(file);
        return file;
    }

    public void delete(Integer id) throws IOException {
        ConfigFile configFile = repository.findById(id).orElse(null);
        if (configFile == null) {
            return;
        }

        repository.deleteById(id);
        Path path = Path.of(configFile.getDir(), configFile.getName());
        Files.delete(path);
    }
}
