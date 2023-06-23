package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.FileDto;
import cn.har01d.alist_tvbox.entity.ConfigFile;
import cn.har01d.alist_tvbox.entity.ConfigFileRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
@Profile("xiaoya")
public class ConfigFileService {
    private final ConfigFileRepository repository;

    public ConfigFileService(ConfigFileRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void setup() {
        if (repository.count() > 0) {
            writeFiles();
        } else {
            readFiles();
        }
    }

    private void writeFiles() {
        for (ConfigFile file : repository.findAll()) {
            try {
                writeFileContent(file);
            } catch (Exception e) {
                log.warn("", e);
            }
        }
    }

    private void readFiles() {
        readFile("/data/tv.txt");
        readFile("/data/proxy.txt");
        readFile("/data/pikpak.txt");
        readFile("/data/pikpak_list.txt");
        readFile("/data/pikpakshare_list.txt");

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
            log.warn("", e);
        }
    }

    public ConfigFile create(FileDto dto) throws IOException {
        validate(dto);
        dto.setId(null);
        dto.setPath(new File(dto.getDir(), dto.getName()).getAbsolutePath());
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
    }

    private void writeFileContent(ConfigFile configFile) throws IOException {
        log.info("write file: {}", configFile.getPath());
        Path path = Paths.get(configFile.getDir(), configFile.getName());
        Files.writeString(path, configFile.getContent());
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
        dto.setPath(new File(dto.getDir(), dto.getName()).getAbsolutePath());

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
