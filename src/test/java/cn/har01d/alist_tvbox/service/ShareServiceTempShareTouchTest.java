package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.ShareLink;
import cn.har01d.alist_tvbox.entity.AListAliasRepository;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.model.FsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * add() 命中既有挂载(Share 行已存在)时给 temp 分享滑动续期:
 * 过期清理以 time 为准,不续期则 72h(自首次挂载)后必定清理,
 * 追更剧集下一次播放要重新 enable 存储拖慢网盘起播(实测 ~4.8s)。
 */
class ShareServiceTempShareTouchTest {

    private ShareRepository shareRepository;
    private SiteRepository siteRepository;
    private AListService aListService;
    private AListLocalService aListLocalService;
    private ShareService service;

    @BeforeEach
    void setUp() {
        shareRepository = mock(ShareRepository.class);
        siteRepository = mock(SiteRepository.class);
        aListService = mock(AListService.class);
        aListLocalService = mock(AListLocalService.class);
        when(aListLocalService.getInternalPort()).thenReturn(4567);

        service = new ShareService(
                mock(AppProperties.class),
                shareRepository,
                mock(MetaRepository.class),
                mock(AListAliasRepository.class),
                mock(SettingRepository.class),
                siteRepository,
                mock(AccountRepository.class),
                mock(DriverAccountRepository.class),
                aListService,
                mock(DriverAccountService.class),
                mock(AccountService.class),
                aListLocalService,
                mock(ConfigFileService.class),
                mock(PikPakService.class),
                mock(OfflineDownloadService.class),
                new RestTemplateBuilder(),
                mock(Environment.class),
                new ObjectMapper(),
                mock(UserService.class));
    }

    private Share tempRow(String path, Instant time) {
        Share share = new Share();
        share.setId(28321);
        share.setType(10);
        share.setTemp(true);
        share.setPath(path);
        share.setTime(time);
        return share;
    }

    /** 复用既有挂载:temp 行 time 刷新为当下,且不触发 create()(不重挂存储)。 */
    @Test
    void addRefreshesTempShareTimeWhenMountReused() {
        ShareLink dto = new ShareLink();
        dto.setLink("https://pan.baidu.com/s/1hOq-Pgc8O35KL06iUGSw1g?pwd=8buw");

        Instant stale = Instant.now().minus(Duration.ofHours(100));
        ArgumentCaptor<String> mountPath = ArgumentCaptor.forClass(String.class);
        when(shareRepository.existsByPath(mountPath.capture())).thenReturn(true);
        when(shareRepository.findByPath(anyString())).thenReturn(tempRow("/我的百度分享/temp/baidu@1hOq-Pgc8O35KL06iUGSw1g@8buw", stale));
        when(siteRepository.findById(1)).thenReturn(Optional.of(new Site()));
        FsResponse listing = new FsResponse();
        when(aListService.listFiles(any(), anyString(), anyInt(), anyInt())).thenReturn(listing);

        String path = service.add(dto);

        // 命中的是按链接推导出的百度 temp 挂载路径
        assertThat(mountPath.getValue()).contains("我的百度分享/temp/baidu@1hOq-Pgc8O35KL06iUGSw1g@8buw");
        assertThat(path).contains("我的百度分享/temp/baidu@");

        ArgumentCaptor<Share> saved = ArgumentCaptor.forClass(Share.class);
        verify(shareRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().isTemp()).isTrue();
        assertThat(saved.getValue().getTime()).isAfter(stale);
        // 未走 create():不重挂存储
        verify(aListLocalService, never()).validateAListStatus();
    }

    /** 非 temp(持久挂载)行不续期:其生命周期由用户显式管理。 */
    @Test
    void addDoesNotTouchPersistentShareRows() {
        ShareLink dto = new ShareLink();
        dto.setLink("https://pan.baidu.com/s/1hOq-Pgc8O35KL06iUGSw1g?pwd=8buw");

        Share persistent = tempRow("/我的百度分享/temp/baidu@1hOq-Pgc8O35KL06iUGSw1g@8buw", Instant.now());
        persistent.setTemp(false);
        when(shareRepository.existsByPath(anyString())).thenReturn(true);
        when(shareRepository.findByPath(anyString())).thenReturn(persistent);
        when(siteRepository.findById(1)).thenReturn(Optional.of(new Site()));
        when(aListService.listFiles(any(), anyString(), anyInt(), anyInt())).thenReturn(new FsResponse());

        service.add(dto);

        verify(shareRepository, never()).save(any(Share.class));
    }
}
