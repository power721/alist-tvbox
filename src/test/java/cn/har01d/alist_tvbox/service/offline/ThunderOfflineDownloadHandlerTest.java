package cn.har01d.alist_tvbox.service.offline;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThunderOfflineDownloadHandlerTest {
    private static final String HEX = "c140e4eaf4fd88decf40ed52156c209c9ca88a8b";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String LIST_URL = "https://x-api-pan.xunlei.com/drive/v1/tasks?type=offline&limit=10000&page_token=";

    @Mock
    private DriverAccountRepository driverAccountRepository;
    @Mock
    private RestTemplateBuilder builder;
    @Mock
    private RestTemplate restTemplate;

    private ThunderOfflineDownloadHandler handler;
    private DriverAccount account;

    @BeforeEach
    void setUpHandler() {
        lenient().when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        lenient().when(builder.readTimeout(any(Duration.class))).thenReturn(builder);
        lenient().when(builder.build()).thenReturn(restTemplate);
        handler = new ThunderOfflineDownloadHandler(driverAccountRepository, builder, objectMapper);
        account = new DriverAccount();
        account.setId(21);
        account.setType(DriverType.THUNDER);
        account.setName("迅雷账号");
        account.setToken("header.payload");
    }

    @Test
    void extractInfoHashLowercasesBtih() {
        assertEquals(HEX, ThunderOfflineDownloadHandler.extractInfoHash(
                "magnet:?xt=urn:btih:C140E4EAF4FD88DECF40ED52156C209C9CA88A8B&dn=movie&tr=udp://tracker"));
    }

    @Test
    void extractInfoHashAcceptsBase32() {
        assertEquals("bc4qzcnfo3m3f7gkcxstjrxqe7g5dnp4", ThunderOfflineDownloadHandler.extractInfoHash(
                "magnet:?xt=urn:btih:BC4QZCNFO3M3F7GKCXSTJRXQE7G5DNP4"));
    }

    @Test
    void extractInfoHashReturnsEmptyWhenAbsentOrTooShort() {
        assertEquals("", ThunderOfflineDownloadHandler.extractInfoHash("https://example.com/file.torrent"));
        assertEquals("", ThunderOfflineDownloadHandler.extractInfoHash("magnet:?xt=urn:btih:short"));
        assertEquals("", ThunderOfflineDownloadHandler.extractInfoHash(""));
    }

    @Test
    void matchesTaskByExactUrl() throws Exception {
        ObjectNode task = task("{\"params\":{\"url\":\"https://example.com/file.torrent\"}}");
        assertTrue(ThunderOfflineDownloadHandler.matchesTask(task, "https://example.com/file.torrent", ""));
    }

    @Test
    void matchesTaskByInfoHashWhenTrackerParamsDiffer() throws Exception {
        // 提交磁力带 tracker 参数;网盘侧任务只存了裸磁力——infohash 桥接两种形态
        ObjectNode task = task("{\"params\":{\"url\":\"magnet:?xt=urn:btih:" + HEX + "\"}}");
        String submitted = "magnet:?xt=urn:btih:" + HEX.toUpperCase() + "&dn=movie&tr=udp://tracker";
        assertTrue(ThunderOfflineDownloadHandler.matchesTask(task, submitted, ThunderOfflineDownloadHandler.extractInfoHash(submitted)));
    }

    @Test
    void matchesTaskReturnsFalseWhenNoOverlap() throws Exception {
        ObjectNode task = task("{\"params\":{\"url\":\"magnet:?xt=urn:btih:" + "a".repeat(40) + "\"}}");
        assertFalse(ThunderOfflineDownloadHandler.matchesTask(task,
                "magnet:?xt=urn:btih:" + "b".repeat(40), "b".repeat(40)));
    }

    @Test
    void matchesTaskReturnsFalseWhenTaskUrlBlank() throws Exception {
        ObjectNode task = task("{\"id\":\"t1\"}");
        assertFalse(ThunderOfflineDownloadHandler.matchesTask(task, "https://example.com/file.torrent", ""));
    }

    @Test
    void buildTaskResultPrefersFileNameAndKeepsInfoHash() throws Exception {
        ObjectNode task = task("{\"file_name\":\"movie.mkv\",\"name\":\"fallback\",\"reference_resource\":{\"kind\":\"drive#file\"}}");
        OfflineDownloadHandler.TaskResult result = ThunderOfflineDownloadHandler.buildTaskResult(task, HEX);
        assertEquals("movie.mkv", result.taskName());
        assertEquals(HEX, result.infoHash());
        assertFalse(result.folder());
    }

    @Test
    void buildTaskResultFallsBackToNameAndDetectsFolder() throws Exception {
        ObjectNode task = task("{\"name\":\"season-01\",\"reference_resource\":{\"kind\":\"drive#folder\"}}");
        OfflineDownloadHandler.TaskResult result = ThunderOfflineDownloadHandler.buildTaskResult(task, "");
        assertEquals("season-01", result.taskName());
        assertTrue(result.folder());
    }

    @Test
    void buildTaskResultThrowsWhenNameMissing() throws Exception {
        ObjectNode task = task("{\"id\":\"t1\"}");
        assertThrows(Exception.class, () -> ThunderOfflineDownloadHandler.buildTaskResult(task, ""));
    }

    private ObjectNode task(String json) throws Exception {
        return (ObjectNode) objectMapper.readTree(json);
    }

    // ---------- 离线清理契约:活体检查(phase 映射)与删除(task_ids + delete_files) ----------

    private void stubTaskList(String tasksJson) {
        when(restTemplate.exchange(eq(LIST_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"tasks\":[" + tasksJson + "]}"));
    }

    @Test
    void supportsTaskManagement() {
        assertTrue(handler.supportsTaskManagement());
    }

    @Test
    void taskStatusMapsPhases() {
        stubTaskList("{\"id\":\"t1\",\"params\":{\"url\":\"magnet:?xt=urn:btih:" + HEX + "\"},\"phase\":\"PHASE_TYPE_COMPLETE\",\"file_name\":\"产物\"}");
        assertEquals(OfflineDownloadHandler.TaskStatus.SUCCEEDED, handler.taskStatus(account, HEX, null));

        stubTaskList("{\"id\":\"t1\",\"params\":{\"url\":\"magnet:?xt=urn:btih:" + HEX + "\"},\"phase\":\"PHASE_TYPE_RUNNING\",\"file_name\":\"产物\"}");
        assertEquals(OfflineDownloadHandler.TaskStatus.RUNNING, handler.taskStatus(account, HEX, null));

        stubTaskList("{\"id\":\"t1\",\"params\":{\"url\":\"magnet:?xt=urn:btih:" + HEX + "\"},\"phase\":\"PHASE_TYPE_ERROR\",\"file_name\":\"产物\"}");
        assertEquals(OfflineDownloadHandler.TaskStatus.FAILED, handler.taskStatus(account, HEX, null));

        stubTaskList("");
        assertEquals(OfflineDownloadHandler.TaskStatus.ABSENT, handler.taskStatus(account, HEX, null));
    }

    @Test
    void taskStatusFallsBackToNameForTasksWithoutBtih() {
        // ed2k 行无 btih:按产物名兜底定位
        stubTaskList("{\"id\":\"t1\",\"params\":{\"url\":\"ed2k://|file|产物.mkv|1|hash|/\"},\"phase\":\"PHASE_TYPE_COMPLETE\",\"file_name\":\"产物.mkv\"}");

        assertEquals(OfflineDownloadHandler.TaskStatus.SUCCEEDED, handler.taskStatus(account, null, "产物.mkv"));
    }

    @Test
    void deleteTaskResolvesByBtihAndDeletesWithFiles() {
        stubTaskList("{\"id\":\"task-9\",\"params\":{\"url\":\"magnet:?xt=urn:btih:" + HEX + "\"},\"phase\":\"PHASE_TYPE_COMPLETE\",\"file_name\":\"产物\"}");
        when(restTemplate.exchange(eq("https://x-api-pan.xunlei.com/drive/v1/tasks?task_ids=task-9&space=&delete_files=true"),
                eq(HttpMethod.DELETE), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        handler.deleteTask(account, HEX, null, true);

        verify(restTemplate).exchange(eq("https://x-api-pan.xunlei.com/drive/v1/tasks?task_ids=task-9&space=&delete_files=true"),
                eq(HttpMethod.DELETE), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void deleteTaskKeepsFilesWhenFlagFalse() {
        stubTaskList("{\"id\":\"task-9\",\"params\":{\"url\":\"magnet:?xt=urn:btih:" + HEX + "\"},\"phase\":\"PHASE_TYPE_COMPLETE\",\"file_name\":\"产物\"}");
        when(restTemplate.exchange(eq("https://x-api-pan.xunlei.com/drive/v1/tasks?task_ids=task-9&space="),
                eq(HttpMethod.DELETE), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        handler.deleteTask(account, HEX, null, false);

        verify(restTemplate).exchange(eq("https://x-api-pan.xunlei.com/drive/v1/tasks?task_ids=task-9&space="),
                eq(HttpMethod.DELETE), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void deleteTaskIsIdempotentWhenTaskMissing() {
        stubTaskList("");

        handler.deleteTask(account, HEX, null, true);

        verify(restTemplate, never()).exchange(any(String.class), eq(HttpMethod.DELETE),
                any(HttpEntity.class), eq(String.class));
    }
}
