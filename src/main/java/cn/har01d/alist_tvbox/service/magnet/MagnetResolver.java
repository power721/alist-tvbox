package cn.har01d.alist_tvbox.service.magnet;

import cn.har01d.alist_tvbox.util.Bencode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 磁力元数据解析:提交离线下载前按 infohash 从公共 .torrent 镜像站拉种子,
 * bencode 解出文件列表供资源筛选规则预筛(体积/排除词/集号命中),避免烧配额下载到垃圾资源。
 * <p>
 * tg-search 侧不解析磁力元数据(size 恒 0),文件列表只能本地获取;镜像全部不可用时
 * 调用方按"解析失败"降级回落 dn 名匹配 —— 磁力兜底不被第三方可用性绑架。
 * 结果按 infohash 缓存(种子元数据不变,含负结果防镜像故障期反复打)。
 */
@Slf4j
@Service
public class MagnetResolver {

    /** %s = 40 位大写 hex infohash。btcache.me 已改 SPA(任何路径恒 200+HTML 首页),移除;
     *  torrage.info 活着且正确 404 未收录(线上实测 2026-09-03)。 */
    private static final List<String> MIRRORS = List.of(
            "https://itorrents.org/torrent/%s.torrent",
            "https://torrage.info/torrent/%s.torrent");

    private static final long MAX_TORRENT_BYTES = 4L * 1024 * 1024; // 种子元数据上限,防畸形大响应

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Cache<String, Optional<MagnetInfo>> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofDays(7))
            .build();

    public record MagnetFile(String path, long size) {
    }

    /** @param files 多文件种子的文件清单;单文件种子为 name 一个条目 */
    public record MagnetInfo(String infoHash, String name, long totalSize, List<MagnetFile> files) {
    }

    /** @return 磁力的种子元数据;uri 无 infohash / 镜像全不可用 / 种子畸形 → empty。
     *  ed2k 链接(115/迅雷/光鸭离线支持)自带文件名与字节数,纯本地解析不走镜像。 */
    public Optional<MagnetInfo> resolve(String magnetUri) {
        if (StringUtils.startsWithIgnoreCase(StringUtils.trimToEmpty(magnetUri), "ed2k:")) {
            return parseEd2k(magnetUri);
        }
        String infoHash = extractInfoHash(magnetUri);
        if (StringUtils.isBlank(infoHash)) {
            return Optional.empty();
        }
        return cache.get(infoHash, this::fetch);
    }

    /** ed2k://|file|文件名|字节数|md4|/ → 单文件元数据(本地零网络)。 */
    static Optional<MagnetInfo> parseEd2k(String uri) {
        // 分隔符 '|' 在文件名里是合法字符,只取前 5 段定位 name/size/hash
        String[] parts = uri.split("\\|", 6);
        if (parts.length < 5 || !"file".equals(parts[1])) {
            return Optional.empty();
        }
        String name = parts[2];
        long size;
        try {
            size = Long.parseLong(parts[3].trim());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        String hash = parts[4];
        if (StringUtils.isBlank(name) || size <= 0 || StringUtils.isBlank(hash)) {
            return Optional.empty();
        }
        return Optional.of(new MagnetInfo(hash.toLowerCase(), name, size, List.of(new MagnetFile(name, size))));
    }

    private Optional<MagnetInfo> fetch(String infoHash) {
        for (String template : MIRRORS) {
            String url = String.format(template, infoHash.toUpperCase()); // 日志打实际 URL,别打 %s 模板误导排查
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                byte[] body = response.body();
                if (response.statusCode() != 200 || body == null || body.length == 0 || body.length > MAX_TORRENT_BYTES) {
                    log.debug("magnet mirror {} no torrent for {}: status={}", url, infoHash, response.statusCode());
                    continue;
                }
                if (!looksLikeBencode(body)) {
                    log.debug("magnet mirror {} non-bencode body for {}: {} bytes", url, infoHash, body.length);
                    continue;
                }
                Optional<MagnetInfo> info = parseTorrent(body, infoHash);
                if (info.isPresent()) {
                    return info;
                }
            } catch (Exception e) {
                log.debug("magnet mirror {} failed for {}: {}", url, infoHash, e.getMessage());
            }
        }
        log.info("magnet metadata unavailable for {}", infoHash);
        return Optional.empty();
    }

    /** bencode 顶层只可能是字典(d)/列表(l)/整数(i)/字节串(数字长度前缀)开头 —— HTML/JSON 错误页直接判非种子,
     *  别让 Bencode 解码器抛"invalid bencode token at 0"混淆镜像故障与未收录。 */
    static boolean looksLikeBencode(byte[] body) {
        if (body == null || body.length == 0) {
            return false;
        }
        byte first = body[0];
        return first == 'd' || first == 'l' || first == 'i' || (first >= '0' && first <= '9');
    }

    static Optional<MagnetInfo> parseTorrent(byte[] body, String infoHash) {
        Map<String, Object> root = Bencode.asDict(Bencode.decode(body));
        if (root == null) {
            return Optional.empty();
        }
        Map<String, Object> info = Bencode.asDict(root.get("info"));
        if (info == null) {
            return Optional.empty();
        }
        String name = StringUtils.defaultString(Bencode.asString(info.get("name")));
        List<MagnetFile> files = new ArrayList<>();
        List<Object> rawFiles = Bencode.asList(info.get("files"));
        if (rawFiles != null) {
            for (Object raw : rawFiles) {
                Map<String, Object> file = Bencode.asDict(raw);
                if (file == null) {
                    continue;
                }
                Long length = asLong(file.get("length"));
                List<Object> segments = Bencode.asList(file.get("path"));
                if (length == null || segments == null || segments.isEmpty()) {
                    continue;
                }
                StringBuilder path = new StringBuilder();
                for (Object segment : segments) {
                    String part = Bencode.asString(segment);
                    if (StringUtils.isNotBlank(part)) {
                        if (path.length() > 0) {
                            path.append('/');
                        }
                        path.append(part);
                    }
                }
                if (path.length() > 0) {
                    files.add(new MagnetFile(path.toString(), length));
                }
            }
        } else {
            Long length = asLong(info.get("length"));
            if (length != null && length > 0) {
                files.add(new MagnetFile(name, length));
            }
        }
        if (files.isEmpty()) {
            return Optional.empty();
        }
        long total = files.stream().mapToLong(MagnetFile::size).sum();
        return Optional.of(new MagnetInfo(infoHash.toLowerCase(), name, total, files));
    }

    private static Long asLong(Object value) {
        return value instanceof Long number ? number : null;
    }

    /** xt=urn:btih: 的 infohash(40 位 hex 或 32 位 base32,统一转 40 位小写 hex);无 xt 返回 null。 */
    static String extractInfoHash(String magnetUri) {
        if (StringUtils.isBlank(magnetUri)) {
            return null;
        }
        for (String param : magnetUri.split("&")) {
            int idx = param.indexOf("urn:btih:");
            if (idx < 0) {
                continue;
            }
            String hash = param.substring(idx + "urn:btih:".length()).trim();
            if (hash.matches("(?i)[0-9a-f]{40}")) {
                return hash.toLowerCase();
            }
            if (hash.matches("(?i)[a-z2-7]{32}")) {
                return base32ToHex(hash);
            }
            return null;
        }
        return null;
    }

    /** RFC 4648 base32 → 小写 hex(磁力 base32 infohash 固定 32 字符 = 20 字节)。 */
    private static String base32ToHex(String value) {
        int buffer = 0;
        int bits = 0;
        StringBuilder hex = new StringBuilder(40);
        for (char c : value.toUpperCase().toCharArray()) {
            int digit = c - (c >= 'A' && c <= 'Z' ? 'A' : '2' - 26);
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                hex.append(String.format("%02x", (buffer >> bits) & 0xFF));
            }
        }
        return hex.length() == 40 ? hex.toString() : null;
    }
}
