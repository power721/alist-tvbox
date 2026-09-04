package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.CatFileEntry;
import cn.har01d.alist_tvbox.dto.CatFilesResult;
import cn.har01d.alist_tvbox.dto.CatUploadEntry;
import cn.har01d.alist_tvbox.dto.CatUploadResult;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 自定义爬虫(单文件 OpenCat js)受控上传:合并进猫影视 node bundle 的站点注册表,内置源零影响。
 * <p>
 * 「目录即生效」:爬虫落 /www/cat/custom(即时生效)+ /data/cat/custom(持久覆盖层,容器重建后由
 * init 脚本覆盖回 /www/cat);清单 custom/spiders.json 同步维护两处。node bundle 启动时经
 * /node/{token}/custom/spiders.json 拉清单与爬虫,eval 装载追加注册(CatVodOpen src/spider/custom.js),
 * 上传后在猫影视 App 内刷新配置即可见。依赖文件(./lib/xxx.js)可随 zip 部署到 custom/ 或 lib/。
 * 详见 docs/cat-package-upload-design.md。
 */
@Slf4j
@Service
public class CatPackageService {
    static final long MAX_FILE_SIZE = 8L * 1024 * 1024;
    static final long MAX_ZIP_SIZE = 20L * 1024 * 1024;
    static final int MAX_ZIP_ENTRIES = 200;
    static final String CUSTOM_DIR = "custom/";
    static final String LIB_DIR = "lib/";
    static final String CUSTOM_MANIFEST = "custom/spiders.json";

    private static final String[] LIST_DIRS = {CUSTOM_DIR, LIB_DIR};

    private final ObjectMapper objectMapper;

    public CatPackageService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CatUploadResult upload(MultipartFile file, boolean autoExtract) throws IOException {
        return upload(file, autoExtract, null);
    }

    public CatUploadResult upload(MultipartFile file, boolean autoExtract, String name) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null || !isSafePath(filename)) {
            throw new BadRequestException("非法文件名: " + filename);
        }
        boolean zip = filename.toLowerCase().endsWith(".zip");
        if (zip && !autoExtract) {
            throw new BadRequestException("zip 文件请勾选自动解压");
        }
        if (file.getSize() > (zip ? MAX_ZIP_SIZE : MAX_FILE_SIZE)) {
            throw new BadRequestException("文件过大: " + filename);
        }

        List<CatUploadEntry> entries = new ArrayList<>();
        if (zip) {
            // 两阶段:先全量校验(恶意 zip 零落盘,原子拒绝),再统一写入
            List<String> names = new ArrayList<>();
            List<byte[]> bodies = new ArrayList<>();
            try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    if (names.size() >= MAX_ZIP_ENTRIES) {
                        throw new BadRequestException("zip 条目过多");
                    }
                    if (CUSTOM_MANIFEST.equals(entry.getName())) {
                        throw new BadRequestException("custom/spiders.json 由系统维护,不可上传");
                    }
                    byte[] bytes = zis.readAllBytes();
                    validateEntry(entry.getName(), bytes);
                    names.add(entry.getName());
                    bodies.add(bytes);
                }
            }
            for (int i = 0; i < names.size(); i++) {
                entries.add(writeEntry(names.get(i), bodies.get(i)));
                String entryName = names.get(i);
                // 仅 custom/ 顶层 .js 是爬虫;custom/ 子目录(custom/lib/…)是依赖文件,不登记
                if (entryName.startsWith(CUSTOM_DIR)) {
                    String file0 = entryName.substring(CUSTOM_DIR.length());
                    if (!file0.contains("/") && isSpiderJs(file0)) {
                        upsertSpiderEntry(file0, null, bodies.get(i));
                    }
                }
            }
        } else {
            if (!isSpiderJs(filename)) {
                throw new BadRequestException("仅支持单个爬虫 .js 上传(依赖库请打 zip,含 custom/ 或 lib/ 前缀)");
            }
            byte[] bytes = file.getBytes();
            validateEntry(CUSTOM_DIR + filename, bytes);
            entries.add(writeEntry(CUSTOM_DIR + filename, bytes));
            upsertSpiderEntry(filename, name, bytes);
        }
        return new CatUploadResult(entries);
    }

    // el-upload 会把前端 undefined 序列化成字符串 "undefined"
    private String normalizeName(String name) {
        if (name == null || name.isBlank() || "undefined".equals(name) || "null".equals(name)) {
            return null;
        }
        return name;
    }

    // 黑名单校验(生态爬虫文件名常含中文/全角括号):拒路径分隔外的危险字符,
    // 逐段拒绝空段与纯点段(..)。公开供 /node 深路径端点复用(任意层级子目录文件分发)。
    public static boolean isSafePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 400) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\' || c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.length() > 200 || segment.chars().allMatch(c -> c == '.')) {
                return false;
            }
        }
        return true;
    }

    private void validateEntry(String path, byte[] bytes) {
        if (!path.startsWith(CUSTOM_DIR) && !path.startsWith(LIB_DIR)) {
            throw new BadRequestException("zip 仅接受 custom/(爬虫)与 lib/(依赖)条目: " + path);
        }
        if (!isSafePath(path)) {
            throw new BadRequestException("非法路径: " + path);
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || !"js".equals(path.substring(dot + 1).toLowerCase())) {
            throw new BadRequestException("仅支持 .js 文件: " + path);
        }
        if (bytes.length > MAX_FILE_SIZE) {
            throw new BadRequestException("文件过大: " + path);
        }
    }

    private boolean isSpiderJs(String filename) {
        if (!filename.toLowerCase().endsWith(".js")) {
            return false;
        }
        // index.js/index.config.js 是 bundle 运行时文件,不属于自定义爬虫
        return !"index.js".equals(filename) && !"index.config.js".equals(filename);
    }

    private CatUploadEntry writeEntry(String path, byte[] bytes) throws IOException {
        Path web = webRoot().resolve(path);
        Path data = dataRoot().resolve(path);
        boolean overwritten = Files.exists(web) || Files.exists(data);
        write(web, bytes);
        write(data, bytes);
        log.info("cat custom file {}: {} bytes{}", path, bytes.length, overwritten ? " (overwritten)" : "");
        return new CatUploadEntry(path, overwritten);
    }

    private void write(Path target, byte[] bytes) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.write(target, bytes);
    }

    public boolean delete(String path) throws IOException {
        Path file = requireFile(path);
        boolean existed = Files.deleteIfExists(webRoot().resolve(path)) | Files.deleteIfExists(dataRoot().resolve(path));
        if (!existed) {
            throw new NotFoundException("文件不存在: " + path);
        }
        if (path.startsWith(CUSTOM_DIR)) {
            removeSpiderEntry(path.substring(CUSTOM_DIR.length()));
        }
        log.info("cat custom file {} deleted", path);
        return existed;
    }

    // 下载/预览入口:校验范围与存在性,返回实际文件(优先 /www/cat 生效版)
    public Path requireFile(String path) throws IOException {
        if (!isSafePath(path)) {
            throw new BadRequestException("非法路径: " + path);
        }
        if (!path.startsWith(CUSTOM_DIR) && !path.startsWith(LIB_DIR)) {
            throw new BadRequestException("仅支持 custom/ 与 lib/ 文件");
        }
        if (CUSTOM_MANIFEST.equals(path)) {
            throw new BadRequestException("custom/spiders.json 由系统维护,不可下载");
        }
        Path web = webRoot().resolve(path);
        if (Files.exists(web)) {
            return web;
        }
        Path data = dataRoot().resolve(path);
        if (Files.exists(data)) {
            return data;
        }
        throw new NotFoundException("文件不存在: " + path);
    }

    public CatFilesResult list() throws IOException {
        List<CatFileEntry> files = new ArrayList<>();
        Path data = dataRoot();
        for (String dir : LIST_DIRS) {
            Path base = data.resolve(dir);
            if (!Files.exists(base)) {
                continue;
            }
            try (var stream = Files.walk(base)) {
                stream.filter(Files::isRegularFile)
                        // 系统清单不在管理列表展示(删除/上传均有专门保护)
                        .filter(p -> !CUSTOM_MANIFEST.equals(data.relativize(p).toString().replace('\\', '/')))
                        .forEach(p -> {
                    String path = data.relativize(p).toString().replace('\\', '/');
                    try {
                        files.add(new CatFileEntry(path, Files.size(p), Files.getLastModifiedTime(p).toMillis()));
                    } catch (IOException e) {
                        log.warn("failed to stat cat file {}", p, e);
                    }
                });
            }
        }
        files.sort(Comparator.comparing(CatFileEntry::path));
        return new CatFilesResult(files);
    }

    private void upsertSpiderEntry(String file, String name, byte[] bytes) throws IOException {
        ArrayNode manifest = readManifest();
        String key = deriveSpiderKey(file);
        ObjectNode entry = null;
        for (JsonNode node : manifest) {
            if (node instanceof ObjectNode obj && file.equals(obj.path("file").asText())) {
                entry = obj;
                break;
            }
        }
        if (entry == null) {
            entry = manifest.addObject();
        }
        String displayName = normalizeName(name);
        entry.put("key", key);
        entry.put("name", displayName != null ? displayName : key);
        entry.put("type", 0);
        entry.put("file", file);
        // 内容指纹:node bundle 端磁盘缓存的失效依据
        entry.put("md5", cn.har01d.alist_tvbox.util.Utils.md5(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
        writeManifest(manifest);
        log.info("custom spider registered: {} -> {}", key, file);
    }

    private void removeSpiderEntry(String file) throws IOException {
        ArrayNode manifest = readManifest();
        boolean removed = false;
        var iterator = manifest.iterator();
        while (iterator.hasNext()) {
            if (file.equals(iterator.next().path("file").asText())) {
                iterator.remove();
                removed = true;
            }
        }
        if (removed) {
            writeManifest(manifest);
            log.info("custom spider unregistered: {}", file);
        }
    }

    private ArrayNode readManifest() {
        for (Path p : new Path[]{webRoot().resolve(CUSTOM_MANIFEST), dataRoot().resolve(CUSTOM_MANIFEST)}) {
            if (Files.exists(p)) {
                try {
                    return (ArrayNode) objectMapper.readTree(Files.readString(p));
                } catch (Exception e) {
                    log.warn("failed to parse {}", p, e);
                }
            }
        }
        return objectMapper.createArrayNode();
    }

    private void writeManifest(ArrayNode manifest) throws IOException {
        byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        write(webRoot().resolve(CUSTOM_MANIFEST), bytes);
        write(dataRoot().resolve(CUSTOM_MANIFEST), bytes);
    }

    private String deriveSpiderKey(String filename) {
        String key = filename.toLowerCase().endsWith(".js") ? filename.substring(0, filename.length() - 3) : filename;
        if (key.endsWith("_open")) {
            key = key.substring(0, key.length() - 5);
        }
        return key;
    }

    Path webRoot() {
        return Utils.getWebPath("cat");
    }

    Path dataRoot() {
        return Utils.getDataPath("cat");
    }
}
