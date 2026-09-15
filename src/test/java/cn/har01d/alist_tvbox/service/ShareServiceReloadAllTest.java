package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.StorageReloadProgress;
import cn.har01d.alist_tvbox.entity.AListAliasRepository;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ShareServiceReloadAllTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ShareService service;

    @BeforeEach
    void setUp() {
        service = spy(new ShareService(
                mock(AppProperties.class),
                mock(ShareRepository.class),
                mock(MetaRepository.class),
                mock(AListAliasRepository.class),
                mock(SettingRepository.class),
                mock(SiteRepository.class),
                mock(AccountRepository.class),
                mock(DriverAccountRepository.class),
                mock(AListService.class),
                mock(DriverAccountService.class),
                mock(AccountService.class),
                mock(AListLocalService.class),
                mock(ConfigFileService.class),
                mock(PikPakService.class),
                mock(OfflineDownloadService.class),
                new RestTemplateBuilder(),
                mock(Environment.class),
                objectMapper,
                mock(UserService.class)));
    }

    private JsonNode failedPage(Object[]... storages) throws Exception {
        StringBuilder sb = new StringBuilder("{\"data\":{\"content\":[");
        for (int i = 0; i < storages.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(storages[i][0]).append(",\"driver\":\"").append(storages[i][1]).append("\"}");
        }
        return objectMapper.readTree(sb.append("]}}").toString());
    }

    private JsonNode failedPageRange(int from, int to) throws Exception {
        Object[][] storages = IntStream.rangeClosed(from, to)
                .mapToObj(id -> new Object[]{id, "BaiduShare2"})
                .toArray(Object[][]::new);
        return failedPage(storages);
    }

    @Test
    void collectsAllFailedStoragesAcrossPages() throws Exception {
        doAnswer(inv -> {
            Pageable pageable = inv.getArgument(0);
            return pageable.getPageNumber() == 1
                    ? failedPageRange(1, 500)
                    : failedPage(new Object[]{501, "QuarkShare"}, new Object[]{502, "BaiduShare2"});
        }).when(service).listStorages(any(Pageable.class));

        List<ShareService.FailedStorageRef> storages = service.collectFailedStorages();
        assertThat(storages).hasSize(502);
        assertThat(storages.get(0).driver()).isEqualTo("BaiduShare2");
        assertThat(storages.get(500).driver()).isEqualTo("QuarkShare");
    }

    @Test
    void countsSuccessAndFailurePerStorage() throws Exception {
        doAnswer(inv -> failedPage(new Object[]{1, "BaiduShare2"}, new Object[]{2, "BaiduShare2"},
                new Object[]{3, "QuarkShare"})).when(service).listStorages(any(Pageable.class));
        doAnswer(inv -> {
            int id = inv.getArgument(0);
            if (id == 2) {
                throw new RuntimeException("boom");
            }
            Response<Void> response = new Response<>();
            response.setCode(id == 3 ? 500 : 200);
            return response;
        }).when(service).reloadStorage(anyInt());

        service.doReloadAllStorages(0);

        StorageReloadProgress progress = service.getReloadAllProgress();
        assertThat(progress.isRunning()).isFalse();
        assertThat(progress.getTotal()).isEqualTo(3);
        assertThat(progress.getProcessed()).isEqualTo(3);
        assertThat(progress.getSuccess()).isEqualTo(1);
        assertThat(progress.getFailed()).isEqualTo(2);
        assertThat(progress.getThrottled()).isZero();
        assertThat(progress.isCancelled()).isFalse();
        assertThat(progress.getFinishedTime()).isPositive();
    }

    @Test
    void throttledDriverSkipsRemainingSameDriverStorages() throws Exception {
        doAnswer(inv -> failedPage(
                new Object[]{1, "BaiduShare2"},
                new Object[]{2, "BaiduShare2"},
                new Object[]{3, "BaiduShare2"},
                new Object[]{4, "QuarkShare"},
                new Object[]{5, "QuarkShare"})).when(service).listStorages(any(Pageable.class));
        doAnswer(inv -> {
            int id = inv.getArgument(0);
            Response<Void> response = new Response<>();
            if (id == 1) {
                response.setCode(500);
                response.setMessage("failed init storage: 触发百度风控,请稍后重试(errno=-62)");
            } else if (id == 4) {
                response.setCode(500);
                response.setMessage("failed init storage: 访问频率太快,请稍后重试(errno=-19)");
            } else {
                response.setCode(200);
            }
            return response;
        }).when(service).reloadStorage(anyInt());

        service.doReloadAllStorages(0);

        StorageReloadProgress progress = service.getReloadAllProgress();
        // id1/id2/id3 百度整盘跳过,id4 夸克风控跳过,id5 夸克也被跳过(同盘不再请求)
        assertThat(progress.getSuccess()).isZero();
        assertThat(progress.getFailed()).isZero();
        assertThat(progress.getThrottled()).isEqualTo(5);
        assertThat(progress.getProcessed()).isEqualTo(5);
        assertThat(progress.getThrottledDrivers()).containsExactlyInAnyOrder("BaiduShare2", "QuarkShare");
        // 只发起了两次请求(id1、id4),其余同盘条目直接跳过
        verify(service, times(2)).reloadStorage(anyInt());
    }

    @Test
    void throttledDriverDoesNotAffectOtherDrivers() throws Exception {
        doAnswer(inv -> failedPage(
                new Object[]{1, "BaiduShare2"},
                new Object[]{2, "BaiduShare2"},
                new Object[]{3, "QuarkShare"},
                new Object[]{4, "UCShare"})).when(service).listStorages(any(Pageable.class));
        doAnswer(inv -> {
            int id = inv.getArgument(0);
            Response<Void> response = new Response<>();
            if (id == 1) {
                response.setCode(500);
                response.setMessage("failed load storage: 触发百度风控,请稍后重试(errno=-62)");
            } else {
                response.setCode(200);
            }
            return response;
        }).when(service).reloadStorage(anyInt());

        service.doReloadAllStorages(0);

        StorageReloadProgress progress = service.getReloadAllProgress();
        // 百度(id1)风控→id2 跳过;夸克(id3)、UC(id4)正常复活
        assertThat(progress.getSuccess()).isEqualTo(2);
        assertThat(progress.getThrottled()).isEqualTo(2);
        assertThat(progress.getFailed()).isZero();
        assertThat(progress.getProcessed()).isEqualTo(4);
        assertThat(progress.getThrottledDrivers()).containsExactly("BaiduShare2");
        verify(service, times(3)).reloadStorage(anyInt());
        ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
        verify(service, times(3)).reloadStorage(captor.capture());
        assertThat(captor.getAllValues()).containsExactly(1, 3, 4);
    }

    @Test
    void recordsErrorWhenListingFailedStoragesThrows() {
        doThrow(new RuntimeException("alist down")).when(service).listStorages(any(Pageable.class));

        service.doReloadAllStorages(0);

        StorageReloadProgress progress = service.getReloadAllProgress();
        assertThat(progress.isRunning()).isFalse();
        assertThat(progress.getError()).contains("alist down");
        assertThat(progress.getTotal()).isZero();
        assertThat(progress.getFinishedTime()).isPositive();
    }

    @Test
    void rejectsInvalidInterval() {
        assertThatThrownBy(() -> service.startReloadAllStorages(-1)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.startReloadAllStorages(600_001)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsSecondStartAndCancelStopsRunningTask() throws Exception {
        doAnswer(inv -> failedPage(new Object[]{1, "BaiduShare2"}, new Object[]{2, "BaiduShare2"},
                new Object[]{3, "QuarkShare"})).when(service).listStorages(any(Pageable.class));
        doAnswer(inv -> {
            Response<Void> response = new Response<>();
            response.setCode(200);
            return response;
        }).when(service).reloadStorage(anyInt());

        service.startReloadAllStorages(600_000);
        assertThatThrownBy(() -> service.startReloadAllStorages(600_000)).isInstanceOf(BadRequestException.class);

        service.cancelReloadAllStorages();

        StorageReloadProgress progress = service.getReloadAllProgress();
        for (int i = 0; i < 100 && progress.isRunning(); i++) {
            Thread.sleep(50);
        }
        assertThat(progress.isRunning()).isFalse();
        assertThat(progress.isCancelled()).isTrue();
        assertThat(progress.getProcessed()).isLessThanOrEqualTo(3);
        assertThat(progress.getFinishedTime()).isPositive();
    }

    @Test
    void finishStaysConsistentWhenNothingRunning() {
        service.cancelReloadAllStorages();
        assertThat(service.getReloadAllProgress().isRunning()).isFalse();
    }
}
