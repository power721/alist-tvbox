package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static cn.har01d.alist_tvbox.util.Constants.BILIBILI_TOKEN;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BiliCookieRefreshServiceTest {
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private BiliCookieRefreshService service;

    @BeforeEach
    void setUp() {
        service = new BiliCookieRefreshService(settingRepository, restTemplate);
    }

    @Test
    void refreshIfNeededShouldDecodeGzipCorrespondPage() throws Exception {
        String cookie = "SESSDATA=old; bili_jct=csrf; DedeUserID=1";
        when(settingRepository.findById(BILIBILI_TOKEN))
                .thenReturn(Optional.of(new Setting(BILIBILI_TOKEN, "old-refresh-token")));

        JsonNode info = objectMapper.readTree("""
                {
                  "code": 0,
                  "data": {
                    "refresh": true,
                    "timestamp": 1684466082562
                  }
                }
                """);
        when(restTemplate.exchange(eq(BiliCookieRefreshService.COOKIE_INFO_API), eq(HttpMethod.GET), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(info));

        HttpHeaders correspondHeaders = new HttpHeaders();
        correspondHeaders.add(HttpHeaders.CONTENT_ENCODING, "gzip");
        when(restTemplate.exchange(startsWith("https://www.bilibili.com/correspond/1/"),
                eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok()
                        .headers(correspondHeaders)
                        .body(gzip("""
                                <!DOCTYPE html>
                                <html lang="zh-Hans">
                                <body>
                                  <div id="1-name">b0cc8411ded2f9db2cff2edb3123acac</div>
                                </body>
                                </html>
                                """)));

        JsonNode refresh = objectMapper.readTree("""
                {
                  "code": 0,
                  "data": {
                    "refresh_token": "new-refresh-token"
                  }
                }
                """);
        HttpHeaders refreshHeaders = new HttpHeaders();
        refreshHeaders.add(HttpHeaders.SET_COOKIE, "SESSDATA=new; Path=/; Domain=bilibili.com");
        refreshHeaders.add(HttpHeaders.SET_COOKIE, "bili_jct=newcsrf; Path=/; Domain=bilibili.com");
        when(restTemplate.exchange(eq(BiliCookieRefreshService.COOKIE_REFRESH_API), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok().headers(refreshHeaders).body(refresh));

        when(restTemplate.exchange(eq(BiliCookieRefreshService.CONFIRM_REFRESH_API), eq(HttpMethod.POST), any(), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(objectMapper.readTree("{\"code\":0}")));

        String refreshed = service.refreshIfNeeded(cookie, true);

        assertTrue(refreshed.contains("SESSDATA=new"));
        assertTrue(refreshed.contains("bili_jct=newcsrf"));
        ArgumentCaptor<Setting> savedSetting = ArgumentCaptor.forClass(Setting.class);
        verify(settingRepository, org.mockito.Mockito.atLeastOnce()).save(savedSetting.capture());
        assertTrue(savedSetting.getAllValues().stream().anyMatch(setting ->
                "bilibili_token".equals(setting.getName()) && "new-refresh-token".equals(setting.getValue())));
    }

    private byte[] gzip(String value) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
            gzipOutputStream.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return outputStream.toByteArray();
    }
}
