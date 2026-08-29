package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TmdbMetadataProviderCacheTest {

    @Test
    void retryRefreshInvalidatesPreviouslyCachedSnapshot() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        MetadataHttp metadataHttp = Mockito.mock(MetadataHttp.class);
        Mockito.when(metadataHttp.create()).thenReturn(restTemplate);
        RatingBridge ratingBridge = Mockito.mock(RatingBridge.class);
        AtomicInteger enrichCalls = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            MetadataDetails details = invocation.getArgument(0);
            int call = enrichCalls.incrementAndGet();
            details.setExternalStatuses(new LinkedHashMap<>(Map.of("douban",
                    call == 2 ? MetadataDetails.EXTERNAL_RETRY
                            : call == 1 ? MetadataDetails.EXTERNAL_NO_MATCH : MetadataDetails.EXTERNAL_MATCH)));
            return null;
        }).when(ratingBridge).enrich(Mockito.any(MetadataDetails.class), Mockito.eq(1));
        TmdbMetadataProvider provider = new TmdbMetadataProvider(
                Mockito.mock(SettingRepository.class), metadataHttp, new MetadataHealth(),
                ratingBridge, null, null, null);
        String key = cn.har01d.alist_tvbox.util.Constants.TMDB_API_KEY;
        server.expect(ExpectedCount.times(3), requestTo("https://api.themoviedb.org/3/tv/9521?api_key=" + key
                        + "&language=zh-CN&append_to_response=images"))
                .andRespond(withSuccess("{\"id\":9521,\"name\":\"测试剧\"}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.times(3), requestTo(
                        "https://api.themoviedb.org/3/tv/9521/alternative_titles?api_key=" + key))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.times(3), requestTo(
                        "https://api.themoviedb.org/3/tv/9521/credits?api_key=" + key + "&language=zh-CN"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.times(3), requestTo(
                        "https://api.themoviedb.org/3/tv/9521/season/1?api_key=" + key + "&language=zh-CN"))
                .andRespond(withSuccess("{\"episodes\":[]}", MediaType.APPLICATION_JSON));

        assertEquals(MetadataDetails.EXTERNAL_NO_MATCH,
                provider.details("9521", 1).getExternalStatuses().get("douban"));
        assertEquals(MetadataDetails.EXTERNAL_RETRY,
                provider.refreshDetails("9521", 1).getExternalStatuses().get("douban"));
        assertEquals(MetadataDetails.EXTERNAL_MATCH,
                provider.details("9521", 1).getExternalStatuses().get("douban"));
        assertEquals(3, enrichCalls.get(), "RETRY 后普通读取必须重新请求，而不是复用旧 NO_MATCH");
        server.verify();
    }
}
