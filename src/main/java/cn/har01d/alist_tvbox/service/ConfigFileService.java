package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.FileDto;
import cn.har01d.alist_tvbox.dto.FileItem;
import cn.har01d.alist_tvbox.entity.ConfigFile;
import cn.har01d.alist_tvbox.entity.ConfigFileRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
            Path path = Path.of("/data/label.txt");
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
        readFile("/data/tv.txt");
        readFile("/data/proxy.txt");

        if (Files.exists(Paths.get("/data/iptv.m3u"))) {
            readFile("/www/tvbox/iptv.m3u");
        }

        if (Files.exists(Paths.get("/data/my.json"))) {
            readFile("/www/tvbox/my.json");
        }

        //readFile("/opt/alist/data/config.json");
        //readFile("/etc/nginx/http.d/default.conf");
    }

    private void readFile(String filepath) {
        try {
            Path path = Paths.get(filepath);
            if (Files.exists(path)) {
                String content = Files.readString(path);
                ConfigFile file = new ConfigFile();
                file.setDir(path.getParent().toString());
                file.setName(path.getFileName().toString());
                file.setPath(filepath);
                file.setContent(content);
                repository.save(file);
                log.info("load file: {}", path);
            }
        } catch (Exception e) {
            log.warn("read file " + filepath, e);
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
        dto.setPath(new File(dto.getDir(), dto.getName()).getAbsolutePath());
        if (dto.getName().endsWith(".json")) {
            try {
                var node = objectMapper.readTree(dto.getContent());
                dto.setContent(objectMapper.writeValueAsString(node));
            } catch (IOException e) {
                throw new BadRequestException("JSON格式错误", e);
            }
        }
    }

    public void writeFileContent(ConfigFile configFile) throws IOException {
        log.info("write file: {}", configFile.getPath());
        Files.createDirectories(Paths.get(configFile.getDir()));
        Path path = Paths.get(configFile.getDir(), configFile.getName());
        Files.writeString(path, configFile.getContent());
        if (path.toString().equals("/data/label.txt")) {
            loadLabels(Arrays.asList(configFile.getContent().split("\n")));
        }
    }

    public ConfigFile update(Integer id, FileDto dto) throws IOException {
        validate(dto);
        ConfigFile configFile = repository.findById(id).orElseThrow(NotFoundException::new);
        try {
            Path path = Paths.get(configFile.getDir(), configFile.getName());
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
        Path path = Paths.get(configFile.getDir(), configFile.getName());
        Files.delete(path);
    }
}
