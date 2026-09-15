package cn.har01d.alist_tvbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;

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
    void parseXsSingleUrl_returnsFirstNonEmptyLine() {
        assertThat(FileDownloader.parseXsSingleUrl("https://x/236/single.json\n"))
                .isEqualTo("https://x/236/single.json");
    }

    @Test
    void parseXsSingleUrl_trimsAndSkipsBlankLines() {
        assertThat(FileDownloader.parseXsSingleUrl("\n  https://x/236/single.json  \n"))
                .isEqualTo("https://x/236/single.json");
    }

    @Test
    void parseXsSingleUrl_throwsOnEmpty() {
        assertThatThrownBy(() -> FileDownloader.parseXsSingleUrl(""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decryptXsContent_decryptsRealEncryptedInterface() throws Exception {
        String encrypted = Files.readString(Path.of(getClass().getResource("/xs/sun-encrypted.hex").toURI()));
        String json = FileDownloader.decryptXsContent(encrypted);

        var root = new ObjectMapper().readTree(json);
        assertThat(root.isObject()).isTrue();
        assertThat(root.has("sites")).isTrue();
        assertThat(root.has("spider")).isTrue();
        assertThat(root.path("spider").asText()).contains("md5");
    }

    @Test
    void decryptXsContent_passesPlainJsonThrough() {
        String market = "[{\"name\":\"本地包\",\"list\":[{\"name\":\"点击下载\",\"url\":\"https://x/单线路.zip\"}]}]";
        assertThat(FileDownloader.decryptXsContent(market)).isSameAs(market);
    }

    @Test
    void decryptXsContent_throwsOnHexWithoutKeyMarker() {
        assertThatThrownBy(() -> FileDownloader.decryptXsContent("2423deadbeef"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decryptXsContent_roundTripsSelfEncryptedVector() throws Exception {
        String plain = "{\"spider\":\"./spider.jar\",\"sites\":[{\"key\":\"豆瓣\"}]}";
        String key = "1234567890123";
        String padded = key + "000";
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(padded.getBytes(), "AES"),
                new IvParameterSpec(padded.getBytes()));
        String hexBody = HexFormat.of().formatHex(cipher.doFinal(plain.getBytes()));
        String hexText = HexFormat.of().formatHex(("$#" + key + "#$").getBytes()) + hexBody
                + HexFormat.of().formatHex(key.getBytes());

        assertThat(FileDownloader.decryptXsContent(hexText)).isEqualTo(plain);
    }

    @Test
    void findXsZipUrl_extractsMarketZipUrl() {
        String market = "[{\"name\":\"推荐\",\"list\":[]},{\"name\":\"本地包\",\"list\":["
                + "{\"name\":\"当前版本\",\"url\":\"\"},"
                + "{\"name\":\"点击下载\",\"url\":\"https://pizazz.us.ci/单线路.zip\"}]}]";
        assertThat(FileDownloader.findXsZipUrl(market)).isEqualTo("https://pizazz.us.ci/单线路.zip");
    }

    @Test
    void findXsZipUrl_returnsNullForConfigJson() {
        String config = "{\"spider\":\"https://x/s.png;md5;abc\",\"sites\":[]}";
        assertThat(FileDownloader.findXsZipUrl(config)).isNull();
    }

    @Test
    void findXsZipUrl_returnsNullForMarketWithoutDownload() {
        String market = "[{\"name\":\"本地包\",\"list\":[{\"name\":\"当前版本\",\"url\":\"\"}]}]";
        assertThat(FileDownloader.findXsZipUrl(market)).isNull();
    }

    @Test
    void findXsZipUrl_returnsNullForBlank() {
        assertThat(FileDownloader.findXsZipUrl(null)).isNull();
        assertThat(FileDownloader.findXsZipUrl("not json")).isNull();
    }

    @Test
    void encodeUrl_percentEncodesChinesePath() {
        assertThat(FileDownloader.encodeUrl("https://pizazz.us.ci/单线路.zip"))
                .isEqualTo("https://pizazz.us.ci/%E5%8D%95%E7%BA%BF%E8%B7%AF.zip");
    }

    @Test
    void encodeUrl_keepsAlreadyEncodedForm() {
        assertThat(FileDownloader.encodeUrl("https://pizazz.us.ci/%E5%8D%95%E7%BA%BF%E8%B7%AF.zip"))
                .isEqualTo("https://pizazz.us.ci/%E5%8D%95%E7%BA%BF%E8%B7%AF.zip");
    }

    @Test
    void encodeUrl_leavesAsciiUrlAndQueryUntouched() {
        assertThat(FileDownloader.encodeUrl("https://d.har01d.cn/diff.zip?v=1&x=%2B"))
                .isEqualTo("https://d.har01d.cn/diff.zip?v=1&x=%2B");
    }

    @Test
    void encodeUrl_returnsInputOnMalformedUrl() {
        assertThat(FileDownloader.encodeUrl("https://x/a b.zip")).isEqualTo("https://x/a b.zip");
    }
}
