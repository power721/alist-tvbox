package cn.har01d.alist_tvbox.service.metadata;

import cn.har01d.alist_tvbox.dto.MetadataDetails;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.service.TmdbEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 背景图高清轮播候选(详情页头部横幅,atv-player 同款口径):官方主图恒置顶,
 * 其余按投票/票数/分辨率加成、16:9 偏差惩罚排序取 8 张,全部 w1280 预生成尺寸 ——
 * 旧实现只有主图且是 w780(糊);original 虽最清但动辄数 MB 走代理加载慢,w1280 对 ~1100px 横幅已超采样。
 */
class TmdbMetadataProviderBackdropTest {
    private static final String BASE = "https://media.themoviedb.org/t/p/w1280";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final TmdbMetadataProvider provider;

    TmdbMetadataProviderBackdropTest() {
        MetadataHttp metadataHttp = Mockito.mock(MetadataHttp.class);
        Mockito.when(metadataHttp.create()).thenReturn(restTemplate);
        provider = new TmdbMetadataProvider(new TmdbEndpoint(Mockito.mock(SettingRepository.class)), metadataHttp, new MetadataHealth(),
                null, null, null, null);
    }

    @Test
    void detailsReturnsOriginalBackdropsWithPrimaryFirst() {
        String key = cn.har01d.alist_tvbox.util.Constants.TMDB_API_KEY;
        // images 随详情一次带回(append_to_response),候选按分排序、主图置顶
        server.expect(once(), requestTo("https://api.themoviedb.org/3/tv/9521?language=zh-CN&append_to_response=images&api_key=" + key))
                .andRespond(withSuccess("{\"id\":9521,\"name\":\"测试剧\",\"backdrop_path\":\"/primary.jpg\",\"images\":{\"backdrops\":["
                        + backdrop("/highvote.jpg", 5.5, 200, 1920, 1080) + ","
                        + backdrop("/primary.jpg", 0, 0, 1920, 1080) + ","
                        + backdrop("/lowvote.jpg", 5.4, 1, 1280, 720) + "]}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.themoviedb.org/3/tv/9521/alternative_titles?api_key=" + key))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.themoviedb.org/3/tv/9521/credits?language=zh-CN&api_key=" + key))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.themoviedb.org/3/tv/9521/season/1?language=zh-CN&api_key=" + key))
                .andRespond(withSuccess("{\"episodes\":[]}", MediaType.APPLICATION_JSON));

        MetadataDetails details = provider.details("9521", 1);

        assertEquals(BASE + "/primary.jpg", details.getBackdrop(), "主图升级 w1280(旧口径 w780)");
        assertEquals(List.of(BASE + "/primary.jpg", BASE + "/highvote.jpg", BASE + "/lowvote.jpg"),
                details.getBackdrops(), "主图置顶,其余按投票分降序,全部 w1280");
    }

    @Test
    void primaryOutranksHigherVotedCandidates() throws Exception {
        JsonNode tv = mapper.readTree("{\"backdrop_path\":\"/primary.jpg\",\"images\":{\"backdrops\":["
                + backdrop("/hero.jpg", 10.0, 500, 3840, 2160) + "]}}");

        List<String> urls = TmdbMetadataProvider.bestBackdropUrls(tv);

        assertEquals(List.of(BASE + "/primary.jpg", BASE + "/hero.jpg"), urls);
    }

    @Test
    void sixteenNineDeviationRankedLast() throws Exception {
        // 同投票下 16:9 满分图在前,竖版海报形态图受比例惩罚垫底
        JsonNode tv = mapper.readTree("{\"backdrop_path\":\"/primary.jpg\",\"images\":{\"backdrops\":["
                + backdrop("/portrait.jpg", 5.5, 200, 1000, 1600) + ","
                + backdrop("/widescreen.jpg", 5.5, 200, 1920, 1080) + "]}}");

        List<String> urls = TmdbMetadataProvider.bestBackdropUrls(tv);

        assertEquals(List.of(BASE + "/primary.jpg", BASE + "/widescreen.jpg", BASE + "/portrait.jpg"), urls);
    }

    @Test
    void cappedAtEightCandidates() throws Exception {
        StringBuilder candidates = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            candidates.append(backdrop("/img" + i + ".jpg", 5.0, 10, 1920, 1080));
            if (i < 10) {
                candidates.append(',');
            }
        }
        JsonNode tv = mapper.readTree("{\"backdrop_path\":\"/primary.jpg\",\"images\":{\"backdrops\":["
                + candidates + "]}}");

        List<String> urls = TmdbMetadataProvider.bestBackdropUrls(tv);

        assertEquals(8, urls.size(), "atv-player 同款上限");
        assertEquals(BASE + "/primary.jpg", urls.getFirst());
    }

    @Test
    void missingImagesFallsBackToPrimaryOnly() throws Exception {
        JsonNode tv = mapper.readTree("{\"backdrop_path\":\"/primary.jpg\"}");

        assertEquals(List.of(BASE + "/primary.jpg"), TmdbMetadataProvider.bestBackdropUrls(tv));
    }

    @Test
    void emptyPayloadYieldsNoBackdrops() throws Exception {
        JsonNode tv = mapper.readTree("{\"images\":{\"backdrops\":[]}}");

        assertTrue(TmdbMetadataProvider.bestBackdropUrls(tv).isEmpty(),
                "无主图无候选为空(详情组装回落主图/单图列表)");
    }

    private static String backdrop(String path, double voteAverage, int voteCount, int width, int height) {
        return "{\"file_path\":\"" + path + "\",\"vote_average\":" + voteAverage
                + ",\"vote_count\":" + voteCount + ",\"width\":" + width + ",\"height\":" + height + "}";
    }
}
