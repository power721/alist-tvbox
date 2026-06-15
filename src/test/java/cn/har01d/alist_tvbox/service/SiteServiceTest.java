package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteServiceTest {
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

    @Test
    void initShouldRestoreBuiltInAListSiteWhenIdOneIsMissing() {
        AppProperties appProperties = new AppProperties();
        cn.har01d.alist_tvbox.tvbox.Site configured = new cn.har01d.alist_tvbox.tvbox.Site();
        configured.setName("本地");
        configured.setUrl("http://localhost");
        configured.setVersion(3);
        configured.setSearchable(true);
        appProperties.setSites(List.of(configured));

        when(siteRepository.count()).thenReturn(1L);
        when(siteRepository.findById(1)).thenReturn(Optional.empty());
        when(settingRepository.findById("fix_site_id")).thenReturn(Optional.of(new Setting("fix_site_id", "true")));

        SiteService service = new SiteService(
                appProperties,
                siteRepository,
                settingRepository,
                aListLocalService,
                jdbcTemplate,
                new RestTemplateBuilder(),
                alistJdbcTemplate
        );

        service.init();

        verify(siteRepository).save(argThat(site ->
                Integer.valueOf(1).equals(site.getId())
                        && "本地".equals(site.getName())
                        && "http://localhost".equals(site.getUrl())
                        && site.isSearchable()
                        && Integer.valueOf(3).equals(site.getVersion())
                        && site.getToken() != null
                        && site.getToken().startsWith("openlist-")));
    }
}
