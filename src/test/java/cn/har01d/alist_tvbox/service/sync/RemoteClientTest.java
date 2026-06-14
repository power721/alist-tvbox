package cn.har01d.alist_tvbox.service.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RemoteClientTest {

    private RemoteClient remoteClient;
    private ObjectMapper objectMapper;

    @Mock
    private OkHttpClient mockHttpClient;

    @Mock
    private Call mockCall;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        remoteClient = new RemoteClient(objectMapper);

        // 使用反射替换 httpClient
        var field = RemoteClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(remoteClient, mockHttpClient);
    }

    @Test
    void testLogin_UsesObjectMapperForJSON() throws IOException {
        // Given
        String remoteUrl = "http://remote:4567";
        String username = "admin";
        String password = "p@ssw\"ord\\special";  // 包含特殊字符

        String responseJson = "{\"token\":\"test-token-123\"}";
        ResponseBody responseBody = ResponseBody.create(responseJson, MediaType.get("application/json"));
        Response mockResponse = new Response.Builder()
            .request(new Request.Builder().url(remoteUrl + "/api/accounts/login").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody)
            .build();

        when(mockHttpClient.newCall(any(Request.class))).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(mockResponse);

        // When
        String token = remoteClient.login(remoteUrl, username, password);

        // Then
        assertEquals("test-token-123", token);

        // 验证请求体使用了 ObjectMapper 正确转义
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(mockHttpClient).newCall(requestCaptor.capture());

        Request capturedRequest = requestCaptor.getValue();
        assertNotNull(capturedRequest.body());

        // 读取请求体内容
        okio.Buffer buffer = new okio.Buffer();
        capturedRequest.body().writeTo(buffer);
        String requestBodyJson = buffer.readUtf8();

        // 验证 JSON 格式正确
        Map<String, String> requestMap = objectMapper.readValue(requestBodyJson, Map.class);
        assertEquals(username, requestMap.get("username"));
        assertEquals(password, requestMap.get("password"));

        // 验证特殊字符被正确转义
        assertTrue(requestBodyJson.contains("p@ssw\\\"ord\\\\special"));
    }

    @Test
    void testLogin_HandlesAuthenticationFailure() throws IOException {
        // Given
        String remoteUrl = "http://remote:4567";
        String username = "admin";
        String password = "wrong";

        Response mockResponse = new Response.Builder()
            .request(new Request.Builder().url(remoteUrl + "/api/accounts/login").build())
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body(ResponseBody.create("", MediaType.get("application/json")))
            .build();

        when(mockHttpClient.newCall(any(Request.class))).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(mockResponse);

        // When & Then
        IOException exception = assertThrows(IOException.class,
            () -> remoteClient.login(remoteUrl, username, password));

        assertTrue(exception.getMessage().contains("认证失败"));
    }

    @Test
    void testLogin_HandlesConnectionRefused() throws IOException {
        // Given
        String remoteUrl = "http://unreachable:4567";
        String username = "admin";
        String password = "password";

        when(mockHttpClient.newCall(any(Request.class))).thenReturn(mockCall);
        when(mockCall.execute()).thenThrow(new java.net.ConnectException("Connection refused"));

        // When & Then
        IOException exception = assertThrows(IOException.class,
            () -> remoteClient.login(remoteUrl, username, password));

        assertTrue(exception.getMessage().contains("连接被拒绝"));
    }

    @Test
    void testNormalizeUrl_RemovesTrailingSlash() throws Exception {
        // 使用反射调用私有方法
        var method = RemoteClient.class.getDeclaredMethod("normalizeUrl", String.class);
        method.setAccessible(true);

        // Test
        assertEquals("http://test.com", method.invoke(remoteClient, "http://test.com/"));
        assertEquals("http://test.com", method.invoke(remoteClient, "http://test.com"));
        assertEquals("http://test.com/path", method.invoke(remoteClient, "http://test.com/path/"));
    }

    @Test
    void testLogin_SpecialCharactersInCredentials() throws IOException {
        // Given - 测试各种特殊字符
        String remoteUrl = "http://remote:4567";
        String username = "user@example.com";
        String password = "p@ss\"w'o\\rd\n\r\t";

        String responseJson = "{\"token\":\"token123\"}";
        ResponseBody responseBody = ResponseBody.create(responseJson, MediaType.get("application/json"));
        Response mockResponse = new Response.Builder()
            .request(new Request.Builder().url(remoteUrl + "/api/accounts/login").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody)
            .build();

        when(mockHttpClient.newCall(any(Request.class))).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(mockResponse);

        // When
        String token = remoteClient.login(remoteUrl, username, password);

        // Then
        assertEquals("token123", token);

        // 验证 JSON 能被正确解析（说明转义正确）
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(mockHttpClient).newCall(requestCaptor.capture());

        okio.Buffer buffer = new okio.Buffer();
        requestCaptor.getValue().body().writeTo(buffer);
        String requestBodyJson = buffer.readUtf8();

        // ObjectMapper 应该能正确解析回来
        Map<String, String> parsed = objectMapper.readValue(requestBodyJson, Map.class);
        assertEquals(username, parsed.get("username"));
        assertEquals(password, parsed.get("password"));
    }
}
