package cn.har01d.alist_tvbox.live.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 把直播平台分享短链/落地页(b23.tv、v.douyin.com、v.kuaishou.com、iesdouyin 分享页)
 * 展开为平台+房间号:逐跳跟随重定向并尝试 {@link LiveUrlParser} 解析。
 * 抖音分享链最终落地页只带 room_id(房间真实 id,不是 URL 里的 webRid),
 * 经 webcast reflow 接口换算成 web_rid(同 pure_live 的处理)。
 */
@Slf4j
@Component
public class LiveShortLinkResolver {
    private static final Pattern REFLOW_PATH = Pattern.compile("reflow/(\\d+)");
    private static final int MAX_REDIRECTS = 5;
    private static final int TIMEOUT_MS = 5000;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";

    private final ObjectMapper objectMapper;

    public LiveShortLinkResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @return {platform, roomId};展开失败返回 null */
    public String[] resolve(String url) {
        String current = url;
        try {
            for (int i = 0; i < MAX_REDIRECTS && current != null; i++) {
                String[] parsed = LiveUrlParser.parse(current);
                if (parsed != null) {
                    return parsed;
                }
                String roomId = douyinRoomId(current);
                if (roomId != null) {
                    String webRid = webRidByRoomId(roomId);
                    return webRid == null ? null : new String[]{"douyin", webRid};
                }
                current = redirectLocation(current);
            }
        } catch (Exception e) {
            log.debug("expand live share link failed: {} {}", url, e.getMessage());
        }
        return null;
    }

    /** 抖音系分享落地页携带的 room_id:reflow 路径或纯数字 room_id 查询参数。 */
    static String douyinRoomId(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!(host.endsWith(".douyin.com") || host.equals("douyin.com")
                || host.endsWith(".amemv.com") || host.endsWith(".iesdouyin.com"))) {
            return null;
        }
        Matcher matcher = REFLOW_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
        if (matcher.find()) {
            return matcher.group(1);
        }
        String roomId = uri.getRawQuery() == null ? null : queryParam(uri.getRawQuery(), "room_id");
        return roomId != null && roomId.matches("\\d{5,25}") ? roomId : null;
    }

    private static String queryParam(String rawQuery, String name) {
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx < 0 ? pair : pair.substring(0, idx);
            if (name.equals(key)) {
                return idx < 0 || idx == pair.length() - 1 ? "" : pair.substring(idx + 1);
            }
        }
        return null;
    }

    /** reflow 接口用 room_id 反查房间,取主播 web_rid(即直播间 URL 的房间号)。 */
    private String webRidByRoomId(String roomId) {
        String url = "https://webcast.amemv.com/webcast/room/reflow/info/?type_id=0&live_id=1&room_id="
                + roomId + "&sec_user_id=&version_code=99.99.99&app_id=6383";
        try {
            JsonNode webRid = objectMapper.readTree(httpGet(url)).path("data").path("room").path("owner").path("web_rid");
            String value = webRid.asText(null);
            return value != null && !value.isEmpty() ? value : null;
        } catch (Exception e) {
            log.debug("douyin reflow api failed: {} {}", roomId, e.getMessage());
            return null;
        }
    }

    /** 不跟随重定向,取 3xx 的 Location(支持相对地址);非重定向返回 null。 */
    private String redirectLocation(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try {
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                if (StringUtils.isEmpty(location)) {
                    return null;
                }
                return new URL(new URL(url), location).toString();
            }
            return null;
        } finally {
            connection.disconnect();
        }
    }

    private String httpGet(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try (InputStream in = connection.getInputStream()) {
            byte[] body = in.readAllBytes();
            // 部分 CDN 无视 Accept-Encoding 强推 gzip,HttpURLConnection 不会自动解压
            if (body.length > 2 && body[0] == 0x1f && body[1] == (byte) 0x8b) {
                body = new GZIPInputStream(new ByteArrayInputStream(body)).readAllBytes();
            }
            return new String(body, StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }
}
