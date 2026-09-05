package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionEpisodeSourceRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionRepository;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResource;
import cn.har01d.alist_tvbox.entity.MediaSubscriptionResourceRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.metadata.MetadataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 播放期字幕查找门禁:外挂字幕查找要列一次文件所在目录,网盘分享的外挂字幕几乎只出现在非华语资源 ——
 * 元数据地区明确非中国(番剧/欧美剧)才查,中国/港台/未绑元数据/无地区数据都不查。
 */
class MediaSubscriptionSubtitleGateTest {

    private final MediaSubscriptionRepository subscriptionRepository = Mockito.mock(MediaSubscriptionRepository.class);
    private final MediaSubscriptionResourceRepository resourceRepository = Mockito.mock(MediaSubscriptionResourceRepository.class);
    private final MediaSubscriptionEpisodeSourceRepository episodeSourceRepository = Mockito.mock(MediaSubscriptionEpisodeSourceRepository.class);
    private final SettingRepository settingRepository = Mockito.mock(SettingRepository.class);
    private final MediaSubscriptionCheckService checkService = Mockito.mock(MediaSubscriptionCheckService.class);
    private final TvBoxService tvBoxService = Mockito.mock(TvBoxService.class);
    private final MetadataService metadataService = Mockito.mock(MetadataService.class);

    private final MediaSubscriptionService service = new MediaSubscriptionService(
            subscriptionRepository, resourceRepository, null, null, episodeSourceRepository,
            null, null, null, tvBoxService, null, metadataService, checkService, null, settingRepository,
            new AppProperties(), new ObjectMapper(), null, null, null);

    private final MediaSubscription subscription = subscription();

    private static MediaSubscription subscription() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setId(7);
        subscription.setUid(1);
        subscription.setName("测试剧");
        subscription.setStatus(MediaSubscription.STATUS_ACTIVE);
        subscription.setMountPath("/追剧/7-测试剧");
        subscription.setShareId(123);
        subscription.setMetaProvider("tmdb");
        subscription.setMetaId("12345");
        return subscription;
    }

    @BeforeEach
    void setUp() {
        Mockito.when(subscriptionRepository.findById(7)).thenReturn(Optional.of(subscription));
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        resource.setId(11);
        resource.setState(MediaSubscriptionResource.STATE_MOUNTED);
        resource.setMountPath("/追剧/7-测试剧");
        MediaSubscriptionEpisodeSource source = new MediaSubscriptionEpisodeSource();
        source.setResourceId(11);
        source.setRelPath("第01集.mkv");
        source.setState(MediaSubscriptionEpisodeSource.STATE_VERIFIED);
        Mockito.when(checkService.playCandidates(subscription, 1)).thenReturn(List.of(
                new MediaSubscriptionCheckService.PlayCandidate(resource, source)));
        Mockito.when(tvBoxService.getPlayUrl(Mockito.eq(1), Mockito.anyString(), Mockito.anyBoolean(),
                Mockito.any(), Mockito.any())).thenReturn(Map.of("url", "https://example.com/v.mp4"));
    }

    private void stubCountries(List<String> countries) {
        MetadataDetails details = new MetadataDetails();
        details.setCountries(countries);
        Mockito.when(metadataService.cachedDetails(Mockito.eq("tmdb"), Mockito.eq("12345"), Mockito.any()))
                .thenReturn(details);
    }

    private boolean capturedGetSub() {
        service.playEpisode(1, 7, 1, null, null);
        ArgumentCaptor<Boolean> captor = ArgumentCaptor.forClass(Boolean.class);
        Mockito.verify(tvBoxService).getPlayUrl(Mockito.eq(1), Mockito.anyString(), captor.capture(),
                Mockito.any(), Mockito.any());
        return captor.getValue();
    }

    @Test
    void chineseShowSkipsSubtitleLookup() {
        stubCountries(List.of("CN"));
        assertFalse(capturedGetSub());
    }

    @Test
    void foreignShowLooksUpSubtitles() {
        stubCountries(List.of("US", "GB"));
        assertTrue(capturedGetSub());
    }

    @Test
    void missingMetadataOrCountriesSkipsSubtitleLookup() {
        assertFalse(service.wantsSubtitles(subscription)); // cachedDetails 无快照
        Mockito.when(metadataService.cachedDetails(Mockito.eq("tmdb"), Mockito.eq("12345"), Mockito.any()))
                .thenReturn(new MetadataDetails()); // 有快照但无地区
        assertFalse(service.wantsSubtitles(subscription));
        subscription.setMetaProvider(null);
        assertFalse(service.wantsSubtitles(subscription));
    }

    @Test
    void chineseMarketForms() {
        assertTrue(MediaSubscriptionService.isChineseMarket("CN"));
        assertTrue(MediaSubscriptionService.isChineseMarket("tw"));
        assertTrue(MediaSubscriptionService.isChineseMarket("HK"));
        assertTrue(MediaSubscriptionService.isChineseMarket("中国大陆"));
        assertTrue(MediaSubscriptionService.isChineseMarket("China"));
        assertTrue(MediaSubscriptionService.isChineseMarket("Hong Kong SAR China"));
        assertTrue(MediaSubscriptionService.isChineseMarket("Taiwan"));
        assertFalse(MediaSubscriptionService.isChineseMarket("US"));
        assertFalse(MediaSubscriptionService.isChineseMarket("Japan"));
        assertFalse(MediaSubscriptionService.isChineseMarket(""));
        assertFalse(MediaSubscriptionService.isChineseMarket(null));
    }
}
