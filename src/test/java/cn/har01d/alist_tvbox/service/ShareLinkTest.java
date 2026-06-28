package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.ParseRequest;
import cn.har01d.alist_tvbox.dto.ShareLink;
import cn.har01d.alist_tvbox.dto.SharesDto;
import cn.har01d.alist_tvbox.entity.AListAliasRepository;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.storage.Storage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static cn.har01d.alist_tvbox.util.Constants.ALIST_LOGIN;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ShareLinkTest {
    @Spy
    private AppProperties appProperties = new AppProperties();

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
    private AccountRepository accountRepository;
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
    private RestTemplateBuilder builder = new RestTemplateBuilder();
    @Mock
    private Environment environment;
    private final ObjectMapper objectMapper = new ObjectMapper();

    //@InjectMocks
    private ShareService shareService;

    @BeforeEach
    void setUp() {
        Mockito.when(builder.rootUri(any())).thenReturn(builder);
        Mockito.when(builder.build()).thenReturn(restTemplate);
        shareService = new ShareService(appProperties, shareRepository, metaRepository, aliasRepository, settingRepository, siteRepository, accountRepository, driverAccountRepository, aListService, driverAccountService, accountService, aListLocalService, configFileService, pikPakService, offlineDownloadService
                , builder, environment, objectMapper);
    }

    @Test
    public void test () {
        Share share = new Share();
        share.setShareId("https://www.aliyundrive.com/s/hSUqKnBGJ3k/folder/635151fc53641440ad95492c8174c57584c56f68");
        parseShare(share);
    }


    private void parseShare(Share share) {
        String url = share.getShareId();
        if (url.startsWith("https://www.aliyundrive.com/s/")) {
            url = url.substring(30);
        }
        String[] parts = url.split("/");
        log.info("parts: {} {}", url, Arrays.asList(parts));
        if (parts.length == 1) {
            share.setShareId(parts[0]);
        } else if (parts.length == 3 && "folder".equals(parts[1])) {
            share.setShareId(parts[0]);
            share.setFolderId(parts[2]);
        }
        log.info("{}", share);
    }

    @Test
    void parseLink() {
        Share share = new Share();
        share.setShareId("//drive.uc.cn/s/c1ca2e2082ec4#/list/share");
        assertFalse(shareService.parseLink(share));

        share = new Share();
        share.setShareId("https://xxx.com/s/xxx");
        assertFalse(shareService.parseLink(share));

        share = new Share();
        share.setShareId("https://drive.uc.cn/s/c1ca2e2082ec4#/list/share");
        assertTrue(shareService.parseLink(share));
        assertEquals(7, share.getType());
        assertEquals("c1ca2e2082ec4", share.getShareId());

        share = new Share();
        share.setShareId("https://fast.uc.cn/s/42e08284433b4?password=NZQb");
        assertTrue(shareService.parseLink(share));
        assertEquals(7, share.getType());
        assertEquals("42e08284433b4", share.getShareId());
        assertEquals("NZQb", share.getPassword());

        share = new Share();
        share.setShareId("https://pan.quark.cn/s/f641f3f75451");
        assertTrue(shareService.parseLink(share));
        assertEquals(5, share.getType());
        assertEquals("f641f3f75451", share.getShareId());

        share = new Share();
        share.setShareId("https://www.alipan.com/s/Q7onFRYxfMs");
        assertTrue(shareService.parseLink(share));
        assertEquals(0, share.getType());
        assertEquals("Q7onFRYxfMs", share.getShareId());
        assertEquals("", share.getPassword());

        share = new Share();
        share.setShareId("https://www.alipan.com/s/cdqCsAWD9wC/folder/635151fc53641440ad95492c8174c57584c56f68?password=6666");
        assertTrue(shareService.parseLink(share));
        assertEquals(0, share.getType());
        assertEquals("cdqCsAWD9wC", share.getShareId());
        assertEquals("635151fc53641440ad95492c8174c57584c56f68", share.getFolderId());
        assertEquals("6666", share.getPassword());

        share = new Share();
        share.setShareId("https://pan.xunlei.com/s/VOLEr1s7Ro8VKZy3QcTbGGtDA1?pwd=b2te#");
        assertTrue(shareService.parseLink(share));
        assertEquals(2, share.getType());
        assertEquals("VOLEr1s7Ro8VKZy3QcTbGGtDA1", share.getShareId());
        assertEquals("b2te", share.getPassword());

        share = new Share();
        share.setShareId("https://115cdn.com/s/swh9gln3ffc?password=jce0#");
        assertTrue(shareService.parseLink(share));
        assertEquals(8, share.getType());
        assertEquals("swh9gln3ffc", share.getShareId());
        assertEquals("jce0", share.getPassword());

        share = new Share();
        share.setShareId("https://115.com/s/swh9gln3ffc?password=jce0");
        assertTrue(shareService.parseLink(share));
        assertEquals(8, share.getType());
        assertEquals("swh9gln3ffc", share.getShareId());
        assertEquals("jce0", share.getPassword());

        share = new Share();
        share.setShareId("https://www.123912.com/s/IpPUVv-Y5Nj?提取码:JZMM");
        assertTrue(shareService.parseLink(share));
        assertEquals(3, share.getType());
        assertEquals("IpPUVv-Y5Nj", share.getShareId());
        assertEquals("JZMM", share.getPassword());

        share = new Share();
        share.setShareId("https://www.123912.com/s/IpPUVv-OwNj?提取码：JMYP");
        assertTrue(shareService.parseLink(share));
        assertEquals(3, share.getType());
        assertEquals("IpPUVv-OwNj", share.getShareId());
        assertEquals("JMYP", share.getPassword());

        share = new Share();
        share.setShareId("https://www.123684.com/s/g4qXjv-yjFs3?提取码:xO4H");
        assertTrue(shareService.parseLink(share));
        assertEquals(3, share.getType());
        assertEquals("g4qXjv-yjFs3", share.getShareId());
        assertEquals("xO4H", share.getPassword());

        share = new Share();
        share.setShareId("https://www.123865.com/s/IpPUVv-rJNj提取码：JMYP");
        assertTrue(shareService.parseLink(share));
        assertEquals(3, share.getType());
        assertEquals("IpPUVv-rJNj", share.getShareId());
        assertEquals("JMYP", share.getPassword());

        share = new Share();
        share.setShareId("https://www.123684.com/s/jhjVTd-yey5v");
        assertTrue(shareService.parseLink(share));
        assertEquals(3, share.getType());
        assertEquals("jhjVTd-yey5v", share.getShareId());

        share = new Share();
        share.setShareId("https://www.123912.com/s/L4qVTd-Jabud.html");
        assertTrue(shareService.parseLink(share));
        assertEquals(3, share.getType());
        assertEquals("L4qVTd-Jabud", share.getShareId());

        share = new Share();
        share.setShareId("https://mypikpak.com/s/VOEN_iLOacbr2EMItNu4EW9Lo1");
        assertTrue(shareService.parseLink(share));
        assertEquals(1, share.getType());
        assertEquals("VOEN_iLOacbr2EMItNu4EW9Lo1", share.getShareId());

        share = new Share();
        share.setShareId("https://cloud.189.cn/web/share?code=UNVrUr67ZvQn");
        assertTrue(shareService.parseLink(share));
        assertEquals(9, share.getType());
        assertEquals("UNVrUr67ZvQn", share.getShareId());

        share = new Share();
        share.setShareId("https://cloud.189.cn/t/UNVrUr67ZvQn");
        assertTrue(shareService.parseLink(share));
        assertEquals(9, share.getType());
        assertEquals("UNVrUr67ZvQn", share.getShareId());
        assertEquals("", share.getPassword());

        share = new Share();
        share.setShareId("https://cloud.189.cn/t/FfyI3uEjeUVr（访问码：n65i）");
        assertTrue(shareService.parseLink(share));
        assertEquals(9, share.getType());
        assertEquals("FfyI3uEjeUVr", share.getShareId());
        assertEquals("n65i", share.getPassword());

//        share = new Share();
//        // https://cloud.189.cn/t/fQZr2e3QR3yu（访问码：b7o6）
//        share.setShareId("https://cloud.189.cn/t/fQZr2e3QR3yu%EF%BC%88%E8%AE%BF%E9%97%AE%E7%A0%81%EF%BC%9Ab7o6%EF%BC%89");
//        assertTrue(shareService.parseLink(share));
//        assertEquals(9, share.getType());
//        assertEquals("fQZr2e3QR3yu", share.getShareId());
//        assertEquals("b7o6", share.getPassword());

        share = new Share();
        share.setShareId("https://h5.cloud.189.cn/share.html#/t/ueey6zu2euim");
        assertTrue(shareService.parseLink(share));
        assertEquals(9, share.getType());
        assertEquals("ueey6zu2euim", share.getShareId());

        share = new Share();
        share.setShareId("https://pan.baidu.com/share/init?surl=LVj_pIer7nhJgm3mm0W9DQ&pwd=acvj");
        assertTrue(shareService.parseLink(share));
        assertEquals(10, share.getType());
        assertEquals("1LVj_pIer7nhJgm3mm0W9DQ", share.getShareId());
        assertEquals("acvj", share.getPassword());

        share = new Share();
        share.setShareId("https://pan.baidu.com/wap/init?surl=bJXuwSVsSVS72toKlRHU5g&pwd=1111");
        assertTrue(shareService.parseLink(share));
        assertEquals(10, share.getType());
        assertEquals("1bJXuwSVsSVS72toKlRHU5g", share.getShareId());
        assertEquals("1111", share.getPassword());

        share = new Share();
        share.setShareId("5@ueey6zu2euim@test");
        assertTrue(shareService.parseLink(share));
        assertEquals(5, share.getType());
        assertEquals("ueey6zu2euim", share.getShareId());
        assertEquals("test", share.getPassword());

        share = new Share();
        share.setShareId("10@LVj_pIer7nhJgm3mm0W9DQ@acvj");
        assertTrue(shareService.parseLink(share));
        assertEquals(10, share.getType());
        assertEquals("1LVj_pIer7nhJgm3mm0W9DQ", share.getShareId());
        assertEquals("acvj", share.getPassword());

        share = new Share();
        share.setShareId("10@abc@1234");
        assertTrue(shareService.parseLink(share));
        assertEquals(10, share.getType());
        assertEquals("abc", share.getShareId());
        assertEquals("1234", share.getPassword());

        share = new Share();
        share.setShareId("9@ueey6zu2euim@");
        assertTrue(shareService.parseLink(share));
        assertEquals(9, share.getType());
        assertEquals("ueey6zu2euim", share.getShareId());
    }

    @Test
    void importShares() {
        SharesDto dto = new SharesDto();
        dto.setType(0);
        dto.setContent("/AT分享/天翼/电视剧/凡人修仙传(2025) https://cloud.189.cn/t/aEZNj2nYBVFz\n" +
                "/AT分享/天翼/电视剧/定风波(2025) https://cloud.189.cn/t/ri6FbmquUNRz?pwd=1234");

        Mockito.when(restTemplate.exchange(anyString(), any(), any(), any(Class.class))).thenReturn(new ResponseEntity<>(Map.of("code", 200), HttpStatus.OK));
        Mockito.when(shareRepository.existsByPath(any())).thenReturn(false);
        int count = shareService.importShares(dto);
        assertEquals(2, count);

        ArgumentCaptor<Share> captor = ArgumentCaptor.forClass(Share.class);
        Mockito.verify(shareRepository, Mockito.times(2)).save(captor.capture());
        Share share = captor.getValue();
        assertEquals(9, share.getType());
        assertEquals("ri6FbmquUNRz", share.getShareId());
        assertEquals("1234", share.getPassword());

        dto.setType(0);
        dto.setContent("/AT分享/天翼/电视剧/凡人修仙传(2025) 9:aEZNj2nYBVFz\n" +
                "/AT分享/天翼/电视剧/定风波(2025) 9:ri6FbmquUNRz 7744 1234");
        count = shareService.importShares(dto);
        assertEquals(2, count);
        Mockito.verify(shareRepository, Mockito.times(4)).save(captor.capture());
        share = captor.getValue();
        assertEquals(9, share.getType());
        assertEquals("ri6FbmquUNRz", share.getShareId());
        assertEquals("7744", share.getFolderId());
        assertEquals("1234", share.getPassword());

        dto.setType(9);
        dto.setContent("/AT分享/天翼/电视剧/凡人修仙传(2025) aEZNj2nYBVFz\n" +
                "/AT分享/天翼/电视剧/定风波(2025) NrUZRf26fABj root asdf");
        count = shareService.importShares(dto);
        assertEquals(2, count);
        Mockito.verify(shareRepository, Mockito.times(6)).save(captor.capture());
        share = captor.getValue();
        assertEquals(9, share.getType());
        assertEquals("NrUZRf26fABj", share.getShareId());
        assertEquals("asdf", share.getPassword());

        dto.setType(9);
        dto.setContent("/AT分享/天翼/电视剧/凡人修仙传(2025) aEZNj2nYBVFz\n" +
                "/AT分享/天翼/电视剧/定风波(2025) FJriMrei26v2");
        count = shareService.importShares(dto);
        assertEquals(2, count);
        Mockito.verify(shareRepository, Mockito.times(8)).save(captor.capture());
        share = captor.getValue();
        assertEquals(9, share.getType());
        assertEquals("FJriMrei26v2", share.getShareId());
        assertEquals("", share.getPassword());

        dto.setType(0);
        dto.setContent("/AT分享/天翼/电视剧/凡人修仙传(2025) aEZNj2nYBVFz\n" +
                "/AT分享/天翼/电视剧/定风波(2025) https://cloud.189.cn/t/ri6FbmquUNRz");
        count = shareService.importShares(dto);
        assertEquals(2, count);
        Mockito.verify(shareRepository, Mockito.times(10)).save(captor.capture());
        share = captor.getValue();
        assertEquals(9, share.getType());
        assertEquals("ri6FbmquUNRz", share.getShareId());
        assertEquals("", share.getPassword());

    }

    @Test
    void addShouldUseOfflineDownloadForMagnetLink() {
        ShareLink dto = new ShareLink();
        dto.setLink("magnet:?xt=urn:btih:test");
        Mockito.when(offlineDownloadService.downloadPath(any(ParseRequest.class)))
                .thenReturn("/115云盘/🈲我的115云盘/alist-tvbox-offline/完成任务");

        String path = shareService.add(dto);

        assertEquals("/115云盘/🈲我的115云盘/alist-tvbox-offline/完成任务", path);
        Mockito.verify(offlineDownloadService).downloadPath(any(ParseRequest.class));
        Mockito.verifyNoInteractions(shareRepository, siteRepository, aListService);
    }

    @Test
    void addShouldUseOfflineDownloadForEd2kLink() {
        ShareLink dto = new ShareLink();
        dto.setLink("ed2k://|file|test.mkv|123|hash|/");
        Mockito.when(offlineDownloadService.downloadPath(any(ParseRequest.class)))
                .thenReturn("/115云盘/🈲我的115云盘/alist-tvbox-offline/test.mkv");

        String path = shareService.add(dto);

        assertEquals("/115云盘/🈲我的115云盘/alist-tvbox-offline/test.mkv", path);
        Mockito.verify(offlineDownloadService).downloadPath(any(ParseRequest.class));
        Mockito.verifyNoInteractions(shareRepository, siteRepository, aListService);
    }

    @Test
    void addShouldKeepParsingNormalShareLinks() {
        ShareLink dto = new ShareLink();
        dto.setLink("https://115.com/s/swh9gln3ffc?password=jce0");
        Site site = new Site();
        site.setId(1);
        site.setName("test");
        FsResponse response = new FsResponse();
        response.setTotal(0);
        Mockito.when(shareRepository.existsByPath(anyString())).thenReturn(true);
        Mockito.when(siteRepository.findById(1)).thenReturn(Optional.of(site));
        Mockito.when(aListService.listFiles(eq(site), anyString(), eq(1), eq(1))).thenReturn(response);

        String path = shareService.add(dto);

        assertEquals("/我的115分享/temp/115@swh9gln3ffc@jce0", path);
        Mockito.verifyNoInteractions(offlineDownloadService);
    }

    @Test
    void createStrmShareShouldForceSignWhenAListLoginEnabled() throws Exception {
        Share share = new Share();
        share.setPath("movies");
        share.setType(11);
        var config = objectMapper.createObjectNode();
        config.put("paths", "/115/电影");
        config.put("siteUrl", "http://localhost");
        config.put("pathPrefix", "/d");
        config.put("downloadFileTypes", "ass,srt,vtt,sub,strm");
        config.put("filterFileTypes", "mp4,mkv");
        config.put("encodePath", false);
        config.put("withoutUrl", false);
        config.put("withSign", false);
        config.put("saveStrmToLocal", false);
        config.put("saveStrmLocalPath", "");
        config.put("saveLocalMode", "update");
        share.setStrmConfig(config);

        Mockito.when(settingRepository.findById(ALIST_LOGIN)).thenReturn(Optional.of(new Setting(ALIST_LOGIN, "true")));
        Mockito.when(accountService.login()).thenReturn("token");
        Mockito.when(restTemplate.exchange(anyString(), any(), any(), any(Class.class)))
                .thenReturn(new ResponseEntity<>(Map.of("code", 200), HttpStatus.OK));

        shareService.create(share);

        assertTrue(objectMapper.readTree(share.getCookie()).get("withSign").asBoolean());
        ArgumentCaptor<Storage> captor = ArgumentCaptor.forClass(Storage.class);
        Mockito.verify(aListLocalService).saveStorage(captor.capture());
        assertTrue(objectMapper.readTree(captor.getValue().getAddition()).get("withSign").asBoolean());
    }
}
