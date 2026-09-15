package cn.har01d.alist_tvbox.service.offline;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Pan115OfflineDownloadHandlerTest {
    private static final String HEX = "c140e4eaf4fd88decf40ed52156c209c9ca88a8b";
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private RestTemplateBuilder builder;
    @Mock
    private RestTemplate restTemplate;
    private Pan115OfflineDownloadHandler handler;

    @BeforeEach
    void setUp() {
        lenient().when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        lenient().when(builder.readTimeout(any(Duration.class))).thenReturn(builder);
        lenient().when(builder.build()).thenReturn(restTemplate);
        handler = new Pan115OfflineDownloadHandler(builder, objectMapper);
    }

    @Test
    void extractInfoHashLowercasesHexBtih() {
        assertEquals(HEX, Pan115OfflineDownloadHandler.extractInfoHash(
                "magnet:?xt=urn:btih:C140E4EAF4FD88DECF40ED52156C209C9CA88A8B"));
    }

    @Test
    void extractInfoHashIgnoresExtraMagnetParams() {
        assertEquals(HEX, Pan115OfflineDownloadHandler.extractInfoHash(
                "magnet:?xt=urn:btih:C140E4EAF4FD88DECF40ED52156C209C9CA88A8B&dn=movie&tr=udp://tracker"));
    }

    @Test
    void extractInfoHashReturnsEmptyWhenAbsent() {
        assertEquals("", Pan115OfflineDownloadHandler.extractInfoHash("https://example.com/file.mp4"));
        assertEquals("", Pan115OfflineDownloadHandler.extractInfoHash("magnet:?xt=urn:btih:"));
        assertEquals("", Pan115OfflineDownloadHandler.extractInfoHash(""));
    }

    @Test
    void findTaskMatchesByInfoHashWhenUrlStringDiffers() throws Exception {
        // submitted magnet uses uppercase btih + extra params; 115 stored lowercase url without params
        String submitted = "magnet:?xt=urn:btih:C140E4EAF4FD88DECF40ED52156C209C9CA88A8B&dn=movie&tr=udp://tracker";
        ObjectNode taskList = (ObjectNode) objectMapper.readTree(
                "{\"tasks\":[{\"url\":\"magnet:?xt=urn:btih:" + HEX + "\","
                        + "\"info_hash\":\"" + HEX + "\",\"status\":2,\"name\":\"movie.mkv\"}]}");

        ObjectNode task = Pan115OfflineDownloadHandler.findTaskInPage(taskList, submitted);

        assertNotNull(task);
        assertEquals(HEX, task.path("info_hash").asText());
    }

    @Test
    void findTaskFallsBackToExactUrlWhenInfoHashAbsent() throws Exception {
        String url = "https://example.com/file.mp4";
        ObjectNode taskList = (ObjectNode) objectMapper.readTree(
                "{\"tasks\":[{\"url\":\"https://example.com/file.mp4\",\"info_hash\":\"\","
                        + "\"status\":2,\"name\":\"file.mp4\"}]}");

        ObjectNode task = Pan115OfflineDownloadHandler.findTaskInPage(taskList, url);

        assertNotNull(task);
    }

    @Test
    void findTaskReturnsNullWhenNothingMatches() throws Exception {
        ObjectNode taskList = (ObjectNode) objectMapper.readTree(
                "{\"tasks\":[{\"url\":\"magnet:?xt=urn:btih:1111111111111111111111111111111111111111\","
                        + "\"info_hash\":\"1111111111111111111111111111111111111111\",\"status\":2}]}");

        ObjectNode task = Pan115OfflineDownloadHandler.findTaskInPage(taskList,
                "magnet:?xt=urn:btih:cccccccccccccccccccccccccccccccccccccccc");

        assertNull(task);
    }

    // ---------- 离线清理:任务定位 / task_del 契约 / 活体状态 ----------

    private DriverAccount account() {
        DriverAccount account = new DriverAccount();
        account.setId(12);
        account.setType(DriverType.PAN115);
        account.setName("115账号");
        account.setCookie("UID=6338615_A1_1778368227; CID=test");
        return account;
    }

    private static final String LIST_PAGE1 = "https://clouddownload.115.com/web/?ac=task_lists&page=1&page_size=1000&stat=11";
    private static final String LIST_PAGE2 = "https://clouddownload.115.com/web/?ac=task_lists&page=2&page_size=1000&stat=11";

    private void stubEmptyTaskList() {
        lenient().when(restTemplate.exchange(eq(LIST_PAGE2), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"state\":true,\"tasks\":[]}"));
    }

    private void stubExchange(String url, int times, String... bodies) {
        // 按调用序返回不同 body:task_del 失败回查任务列表等多步场景
        org.mockito.stubbing.OngoingStubbing<ResponseEntity<String>> stub =
                when(restTemplate.exchange(eq(url), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)));
        for (int i = 0; i < times; i++) {
            stub = stub.thenReturn(ResponseEntity.ok(bodies[Math.min(i, bodies.length - 1)]));
        }
    }

    @Test
    void findTaskByIdentityMatchesHashFirstThenName() throws Exception {
        ObjectNode taskList = (ObjectNode) objectMapper.readTree(
                "{\"tasks\":[{\"info_hash\":\"" + HEX + "\",\"status\":2,\"name\":\"甲\"},"
                        + "{\"info_hash\":\"\",\"status\":1,\"name\":\"乙\"}]}");

        assertEquals("甲", Pan115OfflineDownloadHandler.findTaskByIdentityInPage(taskList, HEX, null).path("name").asText());
        assertEquals("乙", Pan115OfflineDownloadHandler.findTaskByIdentityInPage(taskList, "unmatched", "乙").path("name").asText());
        assertNull(Pan115OfflineDownloadHandler.findTaskByIdentityInPage(taskList, "unmatched", "不存在"));
        assertNull(Pan115OfflineDownloadHandler.findTaskByIdentityInPage(taskList, null, null));
    }

    @Test
    void deleteTaskPostsHashAndFlagForm() {
        // 契约(115driver offline.go DeleteOfflineTasks):POST lixian task_del,form hash + flag(1=任务+文件)
        stubExchange("https://lixian.115.com/lixian/?ct=lixian&ac=task_del", 1,
                "{\"state\":true,\"errno\":0}");

        handler.deleteTask(account(), HEX, null, true);

        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://lixian.115.com/lixian/?ct=lixian&ac=task_del"),
                eq(HttpMethod.POST), captor.capture(), eq(String.class));
        assertEquals("hash=" + HEX + "&flag=1", captor.getValue().getBody());
    }

    @Test
    void deleteTaskFlagZeroKeepsFiles() {
        stubExchange("https://lixian.115.com/lixian/?ct=lixian&ac=task_del", 1,
                "{\"state\":true}");

        handler.deleteTask(account(), HEX, null, false);

        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));
        assertEquals("hash=" + HEX + "&flag=0", captor.getValue().getBody());
    }

    @Test
    void deleteTaskResolvesHashByNameWhenBlank() {
        // ed2k/无 btih:先从任务列表按产物名解析 info_hash 再删
        when(restTemplate.exchange(eq(LIST_PAGE1), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"state\":true,\"tasks\":[{\"info_hash\":\"" + HEX
                        + "\",\"status\":2,\"name\":\"产物目录\"}]}"));
        stubEmptyTaskList();
        stubExchange("https://lixian.115.com/lixian/?ct=lixian&ac=task_del", 1, "{\"state\":true}");

        handler.deleteTask(account(), "", "产物目录", true);

        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://lixian.115.com/lixian/?ct=lixian&ac=task_del"),
                eq(HttpMethod.POST), captor.capture(), eq(String.class));
        assertEquals("hash=" + HEX + "&flag=1", captor.getValue().getBody());
    }

    @Test
    void deleteTaskTreatsVanishedTaskAsSuccess() {
        // task_del 报错但任务列表已查无:幂等成功不抛(重试无意义)
        stubExchange("https://lixian.115.com/lixian/?ct=lixian&ac=task_del", 1,
                "{\"state\":false,\"error_msg\":\"任务不存在\"}");
        when(restTemplate.exchange(eq(LIST_PAGE1), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"state\":true,\"tasks\":[]}"));
        stubEmptyTaskList();

        assertDoesNotThrow(() -> handler.deleteTask(account(), HEX, null, true));
    }

    @Test
    void deleteTaskThrowsWhenDeleteFailsAndTaskStillThere() {
        stubExchange("https://lixian.115.com/lixian/?ct=lixian&ac=task_del", 1,
                "{\"state\":false,\"error_msg\":\"参数错误\"}");
        when(restTemplate.exchange(eq(LIST_PAGE1), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"state\":true,\"tasks\":[{\"info_hash\":\"" + HEX
                        + "\",\"status\":2,\"name\":\"产物\"}]}"));
        stubEmptyTaskList();

        assertThrows(BadRequestException.class, () -> handler.deleteTask(account(), HEX, null, true));
    }

    @Test
    void taskStatusMaps115StatusValues() {
        stubTaskListWithStatus(2);
        assertEquals(OfflineDownloadHandler.TaskStatus.SUCCEEDED, handler.taskStatus(account(), HEX, null));

        stubTaskListWithStatus(1);
        assertEquals(OfflineDownloadHandler.TaskStatus.RUNNING, handler.taskStatus(account(), HEX, null));

        stubTaskListWithStatus(-1);
        assertEquals(OfflineDownloadHandler.TaskStatus.FAILED, handler.taskStatus(account(), HEX, null));

        when(restTemplate.exchange(eq(LIST_PAGE1), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"state\":true,\"tasks\":[]}"));
        stubEmptyTaskList();
        assertEquals(OfflineDownloadHandler.TaskStatus.ABSENT, handler.taskStatus(account(), HEX, null));
    }

    private void stubTaskListWithStatus(int status) {
        String body = "{\"state\":true,\"tasks\":[{\"info_hash\":\"" + HEX + "\",\"status\":" + status
                + ",\"name\":\"产物\"}]}";
        lenient().when(restTemplate.exchange(eq(LIST_PAGE1), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));
        stubEmptyTaskList();
    }
}
