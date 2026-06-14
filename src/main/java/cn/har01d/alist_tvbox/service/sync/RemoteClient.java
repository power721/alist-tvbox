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

    public String login(String remoteUrl, String username, String password) throws IOException {
        String credentials = username + ":" + password;
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());

        Request request = new Request.Builder()
                .url(remoteUrl + "/api/settings")  // 测试端点
                .header("Authorization", basicAuth)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 401) {
                    throw new IOException("认证失败：用户名或密码错误");
                }
                throw new IOException("连接失败：HTTP " + response.code());
            }
            // Basic Auth 成功，返回 credentials 作为 token
            return basicAuth;
        } catch (IOException e) {
            log.error("登录远端失败: {}", remoteUrl, e);
            throw new IOException("无法连接到远端服务器，请检查地址和网络");
        }
    }

    public SyncData fetchRemoteData(String remoteUrl, String token, List<String> modules) throws IOException {
        String modulesParam = String.join(",", modules);
        String url = remoteUrl + "/api/sync/export?modules=" + modulesParam;

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
            log.error("从远端获取数据失败: {}", remoteUrl, e);
            throw new IOException("远端服务器不支持同步功能或版本不兼容");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, SyncResult> pushToRemote(String remoteUrl, String token, SyncData data,
                                                 String strategy, boolean force) throws IOException {
        SyncRequest request = new SyncRequest();
        request.setData(data);
        request.setStrategy(strategy != null ?
            cn.har01d.alist_tvbox.dto.sync.MergeStrategy.valueOf(strategy.toUpperCase()) : null);
        request.setForce(force);

        String json = objectMapper.writeValueAsString(request);
        RequestBody body = RequestBody.create(json, MediaType.get("application/json"));

        Request httpRequest = new Request.Builder()
                .url(remoteUrl + "/api/sync/import")
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
            log.error("推送数据到远端失败: {}", remoteUrl, e);
            throw e;
        }
    }
}
