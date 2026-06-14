package cn.har01d.alist_tvbox.service.sync;

import cn.har01d.alist_tvbox.dto.sync.SyncData;
import cn.har01d.alist_tvbox.dto.sync.SyncRequest;
import cn.har01d.alist_tvbox.dto.sync.SyncResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RemoteClient {
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RemoteClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 规范化 URL：移除末尾的斜杠
     */
    private String normalizeUrl(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public String login(String remoteUrl, String username, String password) throws IOException {
        // 规范化 URL：移除末尾的斜杠
        String normalizedUrl = normalizeUrl(remoteUrl);
        String loginUrl = normalizedUrl + "/api/accounts/login";

        log.info("尝试登录远端: {}", loginUrl);

        // 构建登录请求体
        String loginJson = String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);
        RequestBody body = RequestBody.create(loginJson, MediaType.get("application/json"));

        Request request = new Request.Builder()
                .url(loginUrl)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            log.info("收到登录响应: {} {}", response.code(), response.message());

            if (!response.isSuccessful()) {
                if (response.code() == 401 || response.code() == 403) {
                    // 尝试解析错误消息
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.error("认证失败: {}", errorBody);
                    throw new IOException("认证失败：用户名或密码错误");
                }
                throw new IOException("登录失败：HTTP " + response.code() + " - " + response.message());
            }

            // 解析响应获取 token
            String responseBody = response.body().string();
            log.debug("登录响应: {}", responseBody);

            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
            String token = (String) result.get("token");

            if (token == null || token.isEmpty()) {
                throw new IOException("登录成功但未返回 token");
            }

            log.info("成功登录远端: {}", normalizedUrl);
            return token;
        } catch (java.net.ConnectException e) {
            log.error("连接被拒绝: {} - {}", normalizedUrl, e.getMessage());
            throw new IOException("无法连接到远端服务器：连接被拒绝，请检查地址和端口是否正确");
        } catch (java.net.UnknownHostException e) {
            log.error("主机不存在: {} - {}", normalizedUrl, e.getMessage());
            throw new IOException("无法连接到远端服务器：主机不存在或 DNS 解析失败");
        } catch (java.net.SocketTimeoutException e) {
            log.error("连接超时: {} - {}", normalizedUrl, e.getMessage());
            throw new IOException("连接超时，请检查网络或远端服务器是否正常运行");
        } catch (IOException e) {
            // 如果已经是我们抛出的友好错误，直接重新抛出
            if (e.getMessage().startsWith("认证失败") || e.getMessage().startsWith("登录失败") || e.getMessage().startsWith("连接失败")) {
                throw e;
            }
            log.error("登录远端失败: {} - {}", normalizedUrl, e.getClass().getName() + ": " + e.getMessage(), e);
            throw new IOException("连接失败：" + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    public SyncData fetchRemoteData(String remoteUrl, String token, List<String> modules) throws IOException {
        String normalizedUrl = normalizeUrl(remoteUrl);
        String modulesParam = String.join(",", modules);
        String url = normalizedUrl + "/api/sync/export?modules=" + modulesParam;

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", token)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("获取远端数据失败：HTTP " + response.code());
            }

            String body = response.body().string();
            return objectMapper.readValue(body, SyncData.class);
        } catch (IOException e) {
            log.error("从远端获取数据失败: {}", normalizedUrl, e);
            throw new IOException("远端服务器不支持同步功能或版本不兼容");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, SyncResult> pushToRemote(String remoteUrl, String token, SyncData data,
                                                 String strategy, boolean force) throws IOException {
        String normalizedUrl = normalizeUrl(remoteUrl);

        SyncRequest request = new SyncRequest();
        request.setData(data);
        request.setStrategy(strategy != null ?
            cn.har01d.alist_tvbox.dto.sync.MergeStrategy.valueOf(strategy.toUpperCase()) : null);
        request.setForce(force);

        String json = objectMapper.writeValueAsString(request);
        RequestBody body = RequestBody.create(json, MediaType.get("application/json"));

        Request httpRequest = new Request.Builder()
                .url(normalizedUrl + "/api/sync/import")
                .header("Authorization", token)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("推送数据到远端失败：HTTP " + response.code());
            }

            String responseBody = response.body().string();
            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
            return (Map<String, SyncResult>) result.get("results");
        } catch (IOException e) {
            log.error("推送数据到远端失败: {}", normalizedUrl, e);
            throw e;
        }
    }
}
