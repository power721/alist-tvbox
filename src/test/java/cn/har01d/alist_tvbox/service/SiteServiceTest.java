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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
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

        verify(siteRepository, never()).save(any(Site.class));
        verify(jdbcTemplate).update(
                argThat(sql -> sql.startsWith("INSERT INTO site")
                        && sql.contains("sort_order")
                        && sql.contains("storage_version")),
                eq(1),
                eq("本地"),
                eq("http://localhost"),
                eq(""),
                startsWith("openlist-"),
                eq(null),
                eq(""),
                eq(true),
                eq(false),
                eq(false),
                eq(1),
                eq(3)
        );
        verify(settingRepository).save(any(Setting.class));
        verify(aListLocalService).setSetting(eq("token"), startsWith("openlist-"), eq("string"));
    }
}
