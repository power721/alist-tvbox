package cn.har01d.alist_tvbox.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileDownloaderTest {
    @Mock
    private TaskService taskService;
    @Mock
    private RestTemplateBuilder builder;
    @Mock
    private RestTemplate restTemplate;
    @Mock
    private GitHubProxyService gitHubProxyService;

    private FileDownloader fileDownloader;
//
//    @BeforeEach
//    void setUp() {
//        when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
//        when(builder.readTimeout(any(Duration.class))).thenReturn(builder);
//        when(builder.build()).thenReturn(restTemplate);
//        fileDownloader = new FileDownloader(taskService, builder, gitHubProxyService);
//    }
//
//    @Test
//    void pgVersionUsesConfiguredGitHubProxyBeforeDirectGitHub() {
//        when(gitHubProxyService.readProxyListFromFile()).thenReturn(List.of("https://gh.llkk.cc/", ""));
//        when(restTemplate.getForObject("https://gh.llkk.cc/https://github.com/power721/PG/releases/latest", String.class))
//                .thenReturn("<a href=\"/power721/pg/releases/tag/2026-06\">latest</a>");
//
//        String version = fileDownloader.getPgVersion();
//
//        assertThat(version).isEqualTo("2026-06");
//        verify(restTemplate).getForObject("https://gh.llkk.cc/https://github.com/power721/PG/releases/latest", String.class);
//    }
//
//    @Test
//    void zxVersionFallsBackToDirectGitHubWhenProxyDoesNotReturnVersion() {
//        when(gitHubProxyService.readProxyListFromFile()).thenReturn(List.of("https://gh.llkk.cc/", ""));
//        when(restTemplate.getForObject("https://gh.llkk.cc/https://github.com/power721/ZX/releases/latest", String.class))
//                .thenReturn("rate limited");
//        when(restTemplate.getForObject("https://github.com/power721/ZX/releases/latest", String.class))
//                .thenReturn("<a href=\"/power721/ZX/releases/tag/2026-07\">latest</a>");
//
//        String version = fileDownloader.getZxVersion();
//
//        assertThat(version).isEqualTo("2026-07");
//        InOrder inOrder = inOrder(restTemplate);
//        inOrder.verify(restTemplate).getForObject("https://gh.llkk.cc/https://github.com/power721/ZX/releases/latest", String.class);
//        inOrder.verify(restTemplate).getForObject("https://github.com/power721/ZX/releases/latest", String.class);
//    }

    @Test
    void deriveVersionUrl_swapsSingleJsonForVersionTxt() {
        assertThat(FileDownloader.deriveVersionUrl("https://oss-v1.wangmeipo.cn/236/single.json"))
                .isEqualTo("https://oss-v1.wangmeipo.cn/236/version.txt");
    }

    @Test
    void deriveVersionUrl_dropsQueryWhenTakingDirname() {
        assertThat(FileDownloader.deriveVersionUrl("https://x/236/single.json?v=1"))
                .isEqualTo("https://x/236/version.txt");
    }
}
