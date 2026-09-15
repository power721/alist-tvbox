package cn.har01d.alist_tvbox.service.offline;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 123 网盘离线下载契约:两步提交(resolve→submit)/ 任务列表轮询 / 任务删除 / 超时携带任务 id。 */
@ExtendWith(MockitoExtension.class)
class Pan123OfflineDownloadHandlerTest {
    private static final String RESOLVE = "https://yun.123pan.com/api/v2/offline_download/task/resolve";
    private static final String SUBMIT = "https://yun.123pan.com/api/v2/offline_download/task/submit";
    private static final String TASK_LIST = "https://yun.123pan.com/api/offline_download/task/list";
    private static final String TASK_DELETE = "https://yun.123pan.com/api/offline_download/task/delete";
    private static final String FILE_LIST = "https://yun.123pan.com/api/file/list/new";
    private static final String MKDIR = "https://yun.123pan.com/api/file/upload_request";

    @Mock
    private DriverAccountRepository driverAccountRepository;
    @Mock
    private RestTemplateBuilder builder;
    @Mock
    private RestTemplate restTemplate;

    private Pan123OfflineDownloadHandler handler;
    private DriverAccount account;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        lenient().when(builder.readTimeout(any(Duration.class))).thenReturn(builder);
        lenient().when(builder.build()).thenReturn(restTemplate);
        handler = new Pan123OfflineDownloadHandler(driverAccountRepository, builder, objectMapper);
        account = new DriverAccount();
        account.setId(31);
        account.setType(DriverType.PAN123);
        account.setName("123账号");
        account.setToken("token-1");
        account.setFolder("7001");
        account.setUsername("user@example.com");
        account.setPassword("pass");
    }

    private void stubPost(String urlPrefix, String... bodies) {
        var stub = when(restTemplate.exchange(startsWith(urlPrefix),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)));
        for (String body : bodies) {
            stub = stub.thenReturn(ResponseEntity.ok(body));
        }
    }

    private void stubGet(String urlPrefix, String body) {
        lenient().when(restTemplate.exchange(startsWith(urlPrefix),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    @Test
    void submitAndWaitResolvesThenSubmitsAndPolls() {
        // 两步契约:resolve {"urls":...} → submit {resource_list:[{resource_id,select_file_id}],upload_dir}
        stubPost(RESOLVE, "{\"code\":0,\"data\":{\"list\":[{\"result\":0,\"id\":5001,"
                + "\"files\":[{\"id\":11},{\"id\":12}]}]}}");
        stubPost(SUBMIT, "{\"code\":0,\"data\":{\"task_list\":[{\"task_id\":901,\"result\":0}]}}");
        stubPost(TASK_LIST, "{\"code\":0,\"data\":{\"total\":1,\"list\":[{\"task_id\":901,"
                + "\"status\":2,\"name\":\"种子名\",\"upload_name\":\"产物目录\"}]}}");

        OfflineDownloadHandler.TaskResult result = handler.submitAndWait(account, "magnet:?xt=urn:btih:abc", "7001", 5);

        assertEquals("产物目录", result.taskName());
        assertEquals("901", result.infoHash()); // infoHash 位存任务 id:清理侧零匹配成本
        assertTrue(result.folder()); // 多文件产物走目录形态

        ArgumentCaptor<HttpEntity<String>> resolve = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(startsWith(RESOLVE),
                eq(HttpMethod.POST), resolve.capture(), eq(String.class));
        assertTrue(resolve.getValue().getBody().contains("\"urls\":\"magnet:?xt=urn:btih:abc\""));
        ArgumentCaptor<HttpEntity<String>> submit = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(startsWith(SUBMIT),
                eq(HttpMethod.POST), submit.capture(), eq(String.class));
        String body = submit.getValue().getBody();
        assertTrue(body.contains("\"resource_id\":5001"));
        assertTrue(body.contains("\"select_file_id\":[11,12]"));
        assertTrue(body.contains("\"upload_dir\":7001"));
    }

    @Test
    void submitAndWaitSingleFileProductIsNotFolder() {
        stubPost(RESOLVE, "{\"code\":0,\"data\":{\"list\":[{\"result\":0,\"id\":5002,"
                + "\"files\":[{\"id\":21}]}]}}");
        stubPost(SUBMIT, "{\"code\":0,\"data\":{\"task_list\":[{\"task_id\":902,\"result\":0}]}}");
        stubPost(TASK_LIST, "{\"code\":0,\"data\":{\"total\":1,\"list\":[{\"task_id\":902,"
                + "\"status\":2,\"upload_name\":\"单文件.mkv\"}]}}");

        OfflineDownloadHandler.TaskResult result = handler.submitAndWait(account, "magnet:?xt=urn:btih:abc", "7001", 5);

        assertEquals("单文件.mkv", result.taskName());
        assertEquals("902", result.infoHash());
        assertTrue(!result.folder());
    }

    @Test
    void submitAndWaitTimeoutCarriesTaskId() {
        // 超时抛 OfflineTaskPendingException 携带任务 id:落行进 info_hash,清理活体检查按 id 直查
        stubPost(RESOLVE, "{\"code\":0,\"data\":{\"list\":[{\"result\":0,\"id\":5003,"
                + "\"files\":[{\"id\":31}]}]}}");
        stubPost(SUBMIT, "{\"code\":0,\"data\":{\"task_list\":[{\"task_id\":903,\"result\":0}]}}");
        stubPost(TASK_LIST, "{\"code\":0,\"data\":{\"total\":1,\"list\":[{\"task_id\":903,\"status\":0}]}}");

        OfflineTaskPendingException exception = assertThrows(OfflineTaskPendingException.class,
                () -> handler.submitAndWait(account, "magnet:?xt=urn:btih:abc", "7001", 1));

        assertEquals("903", exception.getTaskId());
        assertTrue(exception.getMessage().contains("未在") && exception.getMessage().contains("内完成"));
    }

    @Test
    void submitAndWaitFailsOnResolveError() {
        stubPost(RESOLVE, "{\"code\":0,\"data\":{\"list\":[{\"result\":1,\"err_msg\":\"链接违规\"}]}}");

        cn.har01d.alist_tvbox.exception.BadRequestException exception = assertThrows(
                cn.har01d.alist_tvbox.exception.BadRequestException.class,
                () -> handler.submitAndWait(account, "magnet:?xt=urn:btih:abc", "7001", 5));

        assertTrue(exception.getMessage().contains("链接违规"));
    }

    @Test
    void mapTaskStatusCovers123StatusValues() {
        assertEquals(OfflineDownloadHandler.TaskStatus.SUCCEEDED, Pan123OfflineDownloadHandler.mapTaskStatus(2));
        assertEquals(OfflineDownloadHandler.TaskStatus.FAILED, Pan123OfflineDownloadHandler.mapTaskStatus(1));
        assertEquals(OfflineDownloadHandler.TaskStatus.RUNNING, Pan123OfflineDownloadHandler.mapTaskStatus(0));
        assertEquals(OfflineDownloadHandler.TaskStatus.RUNNING, Pan123OfflineDownloadHandler.mapTaskStatus(3)); // 重试中
        assertEquals(OfflineDownloadHandler.TaskStatus.ABSENT, Pan123OfflineDownloadHandler.mapTaskStatus(9)); // 未知按查无
    }

    @Test
    void taskStatusFindsTaskByIdAndFallsBackToName() {
        stubPost(TASK_LIST, "{\"code\":0,\"data\":{\"total\":1,\"list\":[{\"task_id\":905,"
                + "\"status\":2,\"upload_name\":\"产物甲\"}]}}");
        assertEquals(OfflineDownloadHandler.TaskStatus.SUCCEEDED, handler.taskStatus(account, "905", null));
        assertEquals(OfflineDownloadHandler.TaskStatus.SUCCEEDED, handler.taskStatus(account, null, "产物甲"));

        stubPost(TASK_LIST, "{\"code\":0,\"data\":{\"total\":0,\"list\":[]}}");
        assertEquals(OfflineDownloadHandler.TaskStatus.ABSENT, handler.taskStatus(account, "905", null));
    }

    @Test
    void deleteTaskPostsTaskIds() {
        // 数值任务 id 直接删,不查任务列表(零匹配成本)
        stubPost(TASK_DELETE, "{\"code\":0}");

        handler.deleteTask(account, "906", null, true);

        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(startsWith(TASK_DELETE),
                eq(HttpMethod.POST), captor.capture(), eq(String.class));
        assertEquals("{\"task_ids\":[906]}", captor.getValue().getBody());
    }

    @Test
    void deleteTaskIsIdempotentWhenTaskMissing() {
        // 无任务 id 且产物名对不上:任务查无,无需删除
        stubPost(TASK_LIST, "{\"code\":0,\"data\":{\"total\":0,\"list\":[]}}");

        handler.deleteTask(account, "", "不存在产物", true);

        verify(restTemplate, never()).exchange(startsWith(TASK_DELETE),
                any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void ensureOfflineFolderFindsExistingFolder() {
        stubGet(FILE_LIST, "{\"code\":0,\"data\":{\"FileList\":[{\"FileId\":8001,\"FileName\":\"别的\"},"
                + "{\"FileId\":8002,\"FileName\":\"alist-tvbox-offline\",\"Type\":1}]}}");

        assertEquals("8002", handler.ensureOfflineFolder(account));
    }

    @Test
    void ensureOfflineFolderCreatesWhenMissing() {
        stubGet(FILE_LIST, "{\"code\":0,\"data\":{\"FileList\":[{\"FileId\":8003,\"FileName\":\"别的\"}]}}");
        stubPost(MKDIR, "{\"code\":0,\"data\":{\"Info\":{\"FileId\":8009}}}");

        assertEquals("8009", handler.ensureOfflineFolder(account));

        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(startsWith(MKDIR),
                eq(HttpMethod.POST), captor.capture(), eq(String.class));
        String body = captor.getValue().getBody();
        assertTrue(body.contains("\"fileName\":\"alist-tvbox-offline\""));
        assertTrue(body.contains("\"parentFileId\":7001"));
        assertTrue(body.contains("\"type\":1"));
    }

    @Test
    void reloginOnUnauthorizedTokenAndPersist() {
        // 首次 401 → passport/mail 双形态重登 → 换新 token 重试,token 回写账号
        stubPost(RESOLVE,
                "{\"code\":401,\"message\":\"未登录\"}",
                "{\"code\":0,\"data\":{\"list\":[{\"result\":1,\"err_msg\":\"链接违规\"}]}}");
        when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"code\":200,\"data\":{\"token\":\"token-2\"}}"));

        assertThrows(cn.har01d.alist_tvbox.exception.BadRequestException.class,
                () -> handler.submitAndWait(account, "magnet:?xt=urn:btih:abc", "7001", 5));

        ArgumentCaptor<HttpEntity<String>> login = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(startsWith("https://login.123pan.com/api/user/sign_in"),
                login.capture(), eq(String.class));
        assertTrue(login.getValue().getBody().contains("\"mail\"")); // 邮箱形态走 mail+type=2
        assertEquals("token-2", account.getToken());
        verify(driverAccountRepository).save(account);
    }

}
