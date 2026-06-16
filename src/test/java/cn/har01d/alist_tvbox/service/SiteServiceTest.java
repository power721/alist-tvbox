package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

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
}
