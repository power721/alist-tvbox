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
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Pageable;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

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

    private JsonNode failedPage(int... ids) throws Exception {
        StringBuilder sb = new StringBuilder("{\"data\":{\"content\":[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(ids[i]).append('}');
        }
        return objectMapper.readTree(sb.append("]}}").toString());
    }

    private JsonNode failedPageRange(int from, int to) throws Exception {
        int[] ids = IntStream.rangeClosed(from, to).toArray();
        return failedPage(ids);
    }

    @Test
    void collectsAllFailedStorageIdsAcrossPages() throws Exception {
        doAnswer(inv -> {
            Pageable pageable = inv.getArgument(0);
            return pageable.getPageNumber() == 1
                    ? failedPageRange(1, 500)
                    : failedPage(501, 502);
        }).when(service).listStorages(any(Pageable.class));

        assertThat(service.collectFailedStorageIds()).hasSize(502);
    }

    @Test
    void countsSuccessAndFailurePerStorage() throws Exception {
        doAnswer(inv -> failedPage(1, 2, 3)).when(service).listStorages(any(Pageable.class));
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
        assertThat(progress.isCancelled()).isFalse();
        assertThat(progress.getFinishedTime()).isPositive();
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
        doAnswer(inv -> failedPage(1, 2, 3)).when(service).listStorages(any(Pageable.class));
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
