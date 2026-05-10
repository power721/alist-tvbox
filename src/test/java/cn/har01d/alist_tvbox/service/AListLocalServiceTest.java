package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.model.Response;
import cn.har01d.alist_tvbox.model.SettingResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AListLocalServiceTest {
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private RestTemplateBuilder restTemplateBuilder;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private Environment environment;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private AListLocalService service;

    @BeforeEach
    void setUp() {
        when(restTemplateBuilder.rootUri(any())).thenReturn(restTemplateBuilder);
        when(restTemplateBuilder.build()).thenReturn(restTemplate);
        when(environment.matchesProfiles("standalone")).thenReturn(false);
        when(environment.matchesProfiles("host")).thenReturn(false);
        when(environment.getProperty("ALIST_PORT", "5244")).thenReturn("5244");
        service = new AListLocalService(
                settingRepository,
                siteRepository,
                new AppProperties(),
                restTemplateBuilder,
                environment,
                new ObjectMapper(),
                jdbcTemplate
        );
    }

    @Test
    void set115TempDirShouldOnlyWriteDatabaseWhenAListNotReady() {
        when(restTemplate.getForEntity("/api/public/settings", SettingResponse.class))
                .thenThrow(new RuntimeException("AList not ready"));

        Response<String> result = service.set115TempDir("/115云盘/🈲我的115云盘/alist-tvbox-offline");

        assertEquals(200, result.getCode());
        assertEquals("ok", result.getData());
        verify(restTemplate, never()).exchange(
                eq("/api/admin/setting/set_115"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)
        );
    }

    @Test
    void set115TempDirShouldHandleStringResponseData() {
        Site site = new Site();
        site.setId(1);
        site.setToken("Bearer test-token");
        SettingResponse settings = new SettingResponse();
        settings.setCode(200);
        Response<String> response = new Response<>();
        response.setCode(200);
        response.setMessage("success");
        response.setData("ok");
        when(siteRepository.findById(1)).thenReturn(Optional.of(site));
        doReturn(ResponseEntity.ok(settings))
                .when(restTemplate)
                .getForEntity("/api/public/settings", SettingResponse.class);
        when(restTemplate.exchange(
                eq("/api/admin/setting/set_115"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)
        )).thenReturn(ResponseEntity.ok(response));

        Response<String> result = service.set115TempDir("/115云盘/🈲我的115云盘/alist-tvbox-offline");

        assertEquals(200, result.getCode());
        assertEquals("ok", result.getData());
    }

    @Test
    void setThunderBrowserTempDirShouldOnlyWriteDatabaseWhenAListNotReady() {
        when(restTemplate.getForEntity("/api/public/settings", SettingResponse.class))
                .thenThrow(new RuntimeException("AList not ready"));

        Response<String> result = service.setThunderBrowserTempDir("/我的迅雷云盘/迅雷账号/alist-tvbox-offline");

        assertEquals(200, result.getCode());
        assertEquals("ok", result.getData());
        verify(restTemplate, never()).exchange(
                eq("/api/admin/setting/set_thunder_browser"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)
        );
    }

    @Test
    void setThunderBrowserTempDirShouldHandleStringResponseData() {
        Site site = new Site();
        site.setId(1);
        site.setToken("Bearer test-token");
        SettingResponse settings = new SettingResponse();
        settings.setCode(200);
        Response<String> response = new Response<>();
        response.setCode(200);
        response.setMessage("success");
        response.setData("ok");
        when(siteRepository.findById(1)).thenReturn(Optional.of(site));
        doReturn(ResponseEntity.ok(settings))
                .when(restTemplate)
                .getForEntity("/api/public/settings", SettingResponse.class);
        when(restTemplate.exchange(
                eq("/api/admin/setting/set_thunder_browser"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)
        )).thenReturn(ResponseEntity.ok(response));

        Response<String> result = service.setThunderBrowserTempDir("/我的迅雷云盘/迅雷账号/alist-tvbox-offline");

        assertEquals(200, result.getCode());
        assertEquals("ok", result.getData());
    }
}
