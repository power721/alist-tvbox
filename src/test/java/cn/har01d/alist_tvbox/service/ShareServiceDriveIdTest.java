package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.ShareLink;
import cn.har01d.alist_tvbox.dto.SharesDto;
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
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShareServiceDriveIdTest {

    private ShareRepository shareRepository;
    private SiteRepository siteRepository;
    private AListService aListService;
    private ShareService service;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = mock(AppProperties.class);
        shareRepository = mock(ShareRepository.class);
        siteRepository = mock(SiteRepository.class);
        aListService = mock(AListService.class);
        AListLocalService aListLocalService = mock(AListLocalService.class);
        when(aListLocalService.getInternalPort()).thenReturn(4567);

        service = spy(new ShareService(
                appProperties,
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
                new ObjectMapper()));

        doAnswer(invocation -> invocation.getArgument(0)).when(service).create(any(Share.class));
        when(shareRepository.existsByPath(any())).thenReturn(false);
    }

    @Test
    void importsSharesUsingDriveIdentifierFromDtoType() {
        SharesDto dto = new SharesDto();
        dto.setType("quark");
        dto.setContent("/Movies abc root pwd");

        int count = service.importShares(dto);

        ArgumentCaptor<Share> captor = ArgumentCaptor.forClass(Share.class);
        verify(service).create(captor.capture());
        Share share = captor.getValue();
        assertThat(count).isEqualTo(1);
        assertThat(share.getType()).isEqualTo(5);
        assertThat(share.getShareId()).isEqualTo("abc");
    }

    @Test
    void importsInlineDriveIdentifierAndLegacyNumericIdentifier() {
        SharesDto dto = new SharesDto();
        dto.setType("ali");
        dto.setContent("/Q quark:qid root\n/B 10:bdid root");

        int count = service.importShares(dto);

        ArgumentCaptor<Share> captor = ArgumentCaptor.forClass(Share.class);
        verify(service, times(2)).create(captor.capture());
        assertThat(captor.getAllValues()).extracting(Share::getType).containsExactly(5, 10);
        assertThat(captor.getAllValues()).extracting(Share::getShareId).containsExactly("qid", "bdid");
        assertThat(count).isEqualTo(2);
    }

    @Test
    void exportsDriveIdentifierPrefixesInsteadOfNumericTypePrefixes() {
        Share share = new Share();
        share.setId(1);
        share.setType(5);
        share.setPath("/Quark");
        share.setShareId("qid");
        share.setFolderId("0");
        share.setPassword("pwd");
        when(shareRepository.findByType(5)).thenReturn(List.of(share));

        String content = service.exportShare(new MockHttpServletResponse(), "quark");

        assertThat(content).contains("/Quark  quark:qid  0  pwd");
        assertThat(content).doesNotContain("5:qid");
    }

    @Test
    void tempShareLinksAcceptDriveIdentifiersAndLegacyNumericIds() {
        assertThat(service.getLinkByPath("/temp/quark@qid@pwd/movie.mkv"))
                .isEqualTo("https://pan.quark.cn/s/qid?password=pwd");
        assertThat(service.getLinkByPath("/temp/5@qid@pwd/movie.mkv"))
                .isEqualTo("https://pan.quark.cn/s/qid?password=pwd");
    }

    @Test
    void temporarySharePathsUseDriveIdentifier() {
        Site site = new Site();
        site.setId(1);
        when(siteRepository.findById(1)).thenReturn(Optional.of(site));
        when(aListService.listFiles(any(), any(), any(Integer.class), any(Integer.class))).thenReturn(new FsResponse());
        ShareLink dto = new ShareLink();
        dto.setLink("https://pan.quark.cn/s/qid");

        service.add(dto);

        ArgumentCaptor<Share> captor = ArgumentCaptor.forClass(Share.class);
        verify(service).create(captor.capture());
        assertThat(captor.getValue().getPath()).contains("/temp/quark@qid@");
        assertThat(captor.getValue().getPath()).doesNotContain("/temp/5@qid@");
    }
}
