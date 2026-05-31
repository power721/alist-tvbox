package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.AListAliasRepository;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShareServiceGuangYaTest {
    @Mock
    private ShareRepository shareRepository;
    @Mock
    private MetaRepository metaRepository;
    @Mock
    private AListAliasRepository aliasRepository;
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private DriverAccountRepository driverAccountRepository;
    @Mock
    private AccountService accountService;
    @Mock
    private AListLocalService aListLocalService;
    @Mock
    private AListService aListService;
    @Mock
    private ConfigFileService configFileService;
    @Mock
    private PikPakService pikPakService;
    @Mock
    private DriverAccountService driverAccountService;
    @Mock
    private OfflineDownloadService offlineDownloadService;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private RestTemplateBuilder builder;
    @Mock
    private Environment environment;

    private ShareService shareService;

    @BeforeEach
    void setUp() {
        when(builder.rootUri(any())).thenReturn(builder);
        when(builder.build()).thenReturn(restTemplate);
        shareService = new ShareService(new AppProperties(), shareRepository, metaRepository, aliasRepository,
                settingRepository, siteRepository, driverAccountRepository, aListService, driverAccountService,
                accountService, aListLocalService, configFileService, pikPakService, offlineDownloadService,
                builder, environment, new ObjectMapper());
    }

    @Test
    void parseGuangYaShareLinks() {
        Share share = new Share();
        share.setShareId("https://www.guangyapan.com/s/1894369771769081942_aeWVzywV3ZOZly47");
        assertTrue(shareService.parseLink(share));
        assertEquals(12, share.getType());
        assertEquals("1894369771769081942_aeWVzywV3ZOZly47", share.getShareId());
        assertEquals("", share.getPassword());

        share = new Share();
        share.setShareId("https://guangyapan.com/s/1894369771769081942_aeWVzywV3ZOZly47#/folder");
        assertTrue(shareService.parseLink(share));
        assertEquals(12, share.getType());
        assertEquals("1894369771769081942_aeWVzywV3ZOZly47", share.getShareId());

        share = new Share();
        share.setShareId("https://www.guangyapan.com/s/1901811855301689412_aeWVVxu726g3waa-#/share");
        assertTrue(shareService.parseLink(share));
        assertEquals(12, share.getType());
        assertEquals("1901811855301689412_aeWVVxu726g3waa-", share.getShareId());

        share = new Share();
        share.setShareId("https://www.guangyapan.com/s/1907798321501487148_aeXdsJwocgzRgE62?code=ujzm");
        assertTrue(shareService.parseLink(share));
        assertEquals(12, share.getType());
        assertEquals("1907798321501487148_aeXdsJwocgzRgE62", share.getShareId());
        assertEquals("ujzm", share.getPassword());

        share = new Share();
        share.setShareId("not-a-valid-guangya-share");
        assertFalse(shareService.parseLink(share));
    }
}
