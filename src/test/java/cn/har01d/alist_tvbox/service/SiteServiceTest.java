package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteServiceTest {
    @Mock
    private AppProperties appProperties;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private AListLocalService aListLocalService;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private JdbcTemplate alistJdbcTemplate;

    private SiteService siteService;

    @BeforeEach
    void setUp() {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        RestTemplateBuilder chained = mock(RestTemplateBuilder.class);
        // 第二参是 appProperties.getUserAgent(),mock 默认 null —— anyString() 不匹配 null 须用 any()
        when(builder.defaultHeader(anyString(), any())).thenReturn(chained);
        when(chained.defaultHeader(anyString(), any())).thenReturn(chained);
        when(chained.build()).thenReturn(null);
        siteService = new SiteService(appProperties, siteRepository, settingRepository,
                aListLocalService, jdbcTemplate, builder, alistJdbcTemplate);
    }

    // 小雅数据布局门禁 = 镜像级 profile ∨ 用户级站点标志:
    // 「纯净卷被小雅镜像复用」形态下站点表是无标志的「本地」(SiteService 只在空表时建站),
    // 单看站点标志会让小雅镜像持续误判纯净 —— profile 信号兜底。
    @Test
    void hasXiaoyaDataCombinesProfileAndSiteFlag() {
        when(appProperties.isXiaoya()).thenReturn(true);
        assertTrue(siteService.hasXiaoyaData());          // 小雅镜像:站点标志缺失也算

        when(appProperties.isXiaoya()).thenReturn(false);
        when(siteRepository.existsByXiaoyaTrue()).thenReturn(true);
        assertTrue(siteService.hasXiaoyaData());          // 纯净镜像:用户勾选站点标志即生效

        when(siteRepository.existsByXiaoyaTrue()).thenReturn(false);
        assertFalse(siteService.hasXiaoyaData());         // 纯净部署:门禁开启
    }
}
