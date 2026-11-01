package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.Movie;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.Tmdb;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T4DetailEnhancer 单测:源级策略(短剧/音乐忽略+三态)、混淆标题清洗、双源优先级链、
 * 严格匹配校验、选集名清洗(ID 不动/低解析率跳过)、异常兜底透传。
 * parseEpisode 打桩须空参安全(重打桩会以 null 实调旧 answer)。
 */
class T4DetailEnhancerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ==================== 源级策略 ====================

    @Test
    void skipsShortDramaPluginByName() {
        DoubanService douban = mock(DoubanService.class);
        TmdbService tmdb = mock(TmdbService.class);
        T4DetailEnhancer enhancer = newEnhancer(douban, tmdb, mockCheck());
        ObjectNode item = detail("庆余年", "第01集$1#第02集$2");

        enhancer.enhance(plugin("红果短剧"), wrap(item));

        verifyNoInteractions(douban, tmdb);
        assertThat(item.path("vod_content").asText()).isEmpty();
        assertThat(item.path("vod_play_url").asText()).isEqualTo("第01集$1#第02集$2");
    }

    @Test
    void skipsMusicPluginByName() {
        DoubanService douban = mock(DoubanService.class);
        TmdbService tmdb = mock(TmdbService.class);
        T4DetailEnhancer enhancer = newEnhancer(douban, tmdb, mockCheck());

        enhancer.enhance(plugin("QQ音乐会员版"), wrap(detail("晴天", "01$1#02$2")));

        verifyNoInteractions(douban, tmdb);
    }

    @Test
    void forcedOverrideBypassesDetection() {
        DoubanService douban = mock(DoubanService.class);
        when(douban.getByName(anyString(), any())).thenReturn(movie("庆余年", 2019, "强制增强简介"));
        TmdbService tmdb = mock(TmdbService.class);
        T4DetailEnhancer enhancer = newEnhancer(douban, tmdb, mockCheck());
        Plugin plugin = plugin("红果短剧");
        plugin.setDetailEnhance(Boolean.TRUE);
        ObjectNode item = detail("庆余年", "第01集$1");

        enhancer.enhance(plugin, wrap(item));

        assertThat(item.path("vod_content").asText()).isEqualTo("强制增强简介");
    }

    @Test
    void falseOverrideSkipsEvenForNormalSource() {
        DoubanService douban = mock(DoubanService.class);
        TmdbService tmdb = mock(TmdbService.class);
        T4DetailEnhancer enhancer = newEnhancer(douban, tmdb, mockCheck());
        Plugin plugin = plugin("影视源");
        plugin.setDetailEnhance(Boolean.FALSE);
        ObjectNode item = detail("庆余年", "第01集$1");

        enhancer.enhance(plugin, wrap(item));

        verifyNoInteractions(douban, tmdb);
        assertThat(item.path("vod_play_url").asText()).isEqualTo("第01集$1");
    }

    @Test
    void skipsByRuntimeTypeName() {
        DoubanService douban = mock(DoubanService.class);
        TmdbService tmdb = mock(TmdbService.class);
        T4DetailEnhancer enhancer = newEnhancer(douban, tmdb, mockCheck());
        ObjectNode item = detail("某个霸道总裁", "第01集$1");
        item.put("type_name", "短剧");

        enhancer.enhance(plugin("某站源"), wrap(item));

        verifyNoInteractions(douban, tmdb);
    }

    @Test
    void skipsByUpdateToManyEpisodes() {
        DoubanService douban = mock(DoubanService.class);
        TmdbService tmdb = mock(TmdbService.class);
        T4DetailEnhancer enhancer = newEnhancer(douban, tmdb, mockCheck());
        ObjectNode item = detail("某个霸总故事", "第01集$1");
        item.put("vod_remarks", "更新至128集");

        enhancer.enhance(plugin("某站源"), wrap(item));

        verifyNoInteractions(douban, tmdb);
    }

    @Test
    void skipsBySegmentFlood() {
        DoubanService douban = mock(DoubanService.class);
        TmdbService tmdb = mock(TmdbService.class);
        T4DetailEnhancer enhancer = newEnhancer(douban, tmdb, mockCheck());
        StringBuilder playUrl = new StringBuilder();
        for (int i = 1; i <= 201; i++) {
            if (i > 1) {
                playUrl.append('#');
            }
            playUrl.append("第").append(i).append("集$").append(i);
        }
        ObjectNode item = detail("海量更新", playUrl.toString());

        enhancer.enhance(plugin("某站源"), wrap(item));

        verifyNoInteractions(douban, tmdb);
    }

    // ==================== 标题清洗(混淆文件名) ====================

    @Test
    void cleanTitleStripsWatermarkBracketAndKeepsYear() {
        T4DetailEnhancer enhancer = newEnhancer();
        T4DetailEnhancer.Title title = enhancer.cleanTitle("庆余年[www.dyg7.com]", "2024");

        assertThat(title).isNotNull();
        assertThat(title.keyword()).isEqualTo("庆余年");
        assertThat(title.year()).isEqualTo(2024);
    }

    @Test
    void cleanTitleSplitsSeason() {
        T4DetailEnhancer enhancer = newEnhancer();
        T4DetailEnhancer.Title title = enhancer.cleanTitle("庆余年 第二季", null);

        assertThat(title).isNotNull();
        assertThat(title.season()).isEqualTo(2);
        assertThat(title.keyword()).isEqualTo("庆余年");
    }

    @Test
    void cleanTitleJunkNameReturnsNull() {
        T4DetailEnhancer enhancer = newEnhancer();
        assertThat(enhancer.cleanTitle("", null)).isNull();
        assertThat(enhancer.cleanTitle("???", null)).isNull();
    }

    @Test
    void dbidTagGoesThroughRawNameFastPath() {
        DoubanService douban = mock(DoubanService.class);
        when(douban.getByName(anyString(), any())).thenReturn(movie("一念永恒", 2020, "一念永恒简介"));
        TmdbService tmdb = mock(TmdbService.class);
        T4DetailEnhancer enhancer = newEnhancer(douban, tmdb, mockCheck());
        ObjectNode item = detail("一念永恒 [dbid-37448094]", "第01集$1");

        enhancer.enhance(plugin("影视源"), wrap(item));

        // dbid 标签保留在原始名里直传(getByName 的标签快路径),不因清洗剥掉
        verify(douban).getByName(eq("一念永恒 [dbid-37448094]"), any());
        assertThat(item.path("vod_content").asText()).isEqualTo("一念永恒简介");
    }

    // ==================== 双源优先级链 ====================

    @Test
    void doubanFirstPrefersLocalWithoutTmdbQuota() {
        DoubanService douban = mock(DoubanService.class);
        when(douban.getByName(anyString(), any())).thenReturn(movie("庆余年", 2019, "豆瓣简介"));
        TmdbService tmdb = mock(TmdbService.class);
        T4DetailEnhancer enhancer = newEnhancer(douban, tmdb, mockCheck());
        ObjectNode item = detail("庆余年", "第01集$1");

        enhancer.enhance(plugin("影视源"), wrap(item));

        verifyNoInteractions(tmdb);
        assertThat(item.path("vod_content").asText()).isEqualTo("豆瓣简介");
        assertThat(item.path("vod_year").asText()).isEqualTo("2019");
        assertThat(item.path("vod_name").asText()).isEqualTo("庆余年"); // 永不覆写
    }

    @Test
    void doubanMissFallsBackToTmdb() {
        DoubanService douban = mock(DoubanService.class);
        when(douban.getByName(anyString(), any())).thenReturn(null);
        TmdbService tmdb = mock(TmdbService.class);
        Tmdb result = tmdb("庆余年", 2019, "TMDB简介");
        when(tmdb.search(anyString(), anyString(), org.mockito.ArgumentMatchers.<Integer>any(), anyBoolean())).thenReturn(result);
        T4DetailEnhancer enhancer = newEnhancer(douban, tmdb, mockCheck());
        ObjectNode item = detail("庆余年", "第01集$1");

        enhancer.enhance(plugin("影视源"), wrap(item));

        assertThat(item.path("vod_content").asText()).isEqualTo("TMDB简介");
    }

    @Test
    void tmdbFirstPriorityPrefersTmdbOverLocalHit() {
        DoubanService douban = mock(DoubanService.class);
        when(douban.getByName(anyString(), any())).thenReturn(movie("庆余年", 2019, "豆瓣简介"));
        TmdbService tmdb = mock(TmdbService.class);
        when(tmdb.search(anyString(), anyString(), org.mockito.ArgumentMatchers.<Integer>any(), anyBoolean())).thenReturn(tmdb("庆余年", 2019, "TMDB简介"));
        AppProperties props = new AppProperties();
        props.setT4EnhancePriority("tmdb");
        T4DetailEnhancer enhancer = new T4DetailEnhancer(props, douban, tmdb, mockCheck());
        ObjectNode item = detail("庆余年", "第01集$1");

        enhancer.enhance(plugin("影视源"), wrap(item));

        assertThat(item.path("vod_content").asText()).isEqualTo("TMDB简介"); // 单一来源,不混拼
    }

    @Test
    void looseDoubanMatchRejectedThenTmdbTried() {
        DoubanService douban = mock(DoubanService.class);
        when(douban.getByName(anyString(), any())).thenReturn(movie("完全另一部作品", 2019, "错配简介"));
        TmdbService tmdb = mock(TmdbService.class);
        when(tmdb.search(anyString(), anyString(), org.mockito.ArgumentMatchers.<Integer>any(), anyBoolean())).thenReturn(null);
        T4DetailEnhancer enhancer = newEnhancer(douban, tmdb, mockCheck());
        ObjectNode item = detail("庆余年", "第01集$1");

        enhancer.enhance(plugin("影视源"), wrap(item));

        assertThat(item.path("vod_content").asText()).isEmpty(); // 严格校验拒绝松配
        verify(tmdb).search(eq("tv"), anyString(), org.mockito.ArgumentMatchers.<Integer>isNull(), eq(false));
    }

    @Test
    void yearMismatchRejected() {
        T4DetailEnhancer enhancer = newEnhancer();
        T4DetailEnhancer.Title title = new T4DetailEnhancer.Title("庆余年 第二季", "庆余年", 2, 2024, null, null);

        assertThat(enhancer.accepts(title, "庆余年", 2019)).isFalse();
        assertThat(enhancer.accepts(title, "庆余年", 2024)).isTrue();
        assertThat(enhancer.accepts(title, "庆余年", null)).isTrue(); // 候选无年份不拦
    }

    @Test
    void seasonSuffixComparedOnBothSides() {
        T4DetailEnhancer enhancer = newEnhancer();
        T4DetailEnhancer.Title title = new T4DetailEnhancer.Title("凡人修仙传", "凡人修仙传", null, null, null, null);

        // 关键词已剥季,候选名带季:两侧都过 stripSeasonSuffix 再比
        assertThat(enhancer.accepts(title, "凡人修仙传 第一季", null)).isTrue();
    }

    @Test
    void bothMissCachesNegativeResult() {
        DoubanService douban = mock(DoubanService.class);
        when(douban.getByName(anyString(), any())).thenReturn(null);
        TmdbService tmdb = mock(TmdbService.class);
        when(tmdb.search(anyString(), anyString(), org.mockito.ArgumentMatchers.<Integer>any(), anyBoolean())).thenReturn(null);
        T4DetailEnhancer enhancer = newEnhancer(douban, tmdb, mockCheck());
        Plugin plugin = plugin("影视源");
        ObjectNode item = detail("不知名作品", "第01集$1");

        enhancer.enhance(plugin, wrap(item));
        enhancer.enhance(plugin, wrap(item));

        verify(douban, times(1)).getByName(anyString(), any()); // 负缓存 1h,第二次不再打
    }

    @Test
    void providerExceptionPassthroughUnchanged() {
        DoubanService douban = mock(DoubanService.class);
        when(douban.getByName(anyString(), any())).thenThrow(new RuntimeException("db down"));
        TmdbService tmdb = mock(TmdbService.class);
        when(tmdb.search(anyString(), anyString(), org.mockito.ArgumentMatchers.<Integer>any(), anyBoolean())).thenThrow(new RuntimeException("tmdb down"));
        T4DetailEnhancer enhancer = newEnhancer(douban, tmdb, mockCheck());
        ObjectNode item = detail("庆余年", "第01集$1");

        JsonNode result = enhancer.enhance(plugin("影视源"), wrap(item));

        assertThat(result).isNotNull();
        assertThat(item.path("vod_content").asText()).isEmpty();
        assertThat(item.path("vod_play_url").asText()).isEqualTo("第01集$1");
    }

    // ==================== 选集名清洗(ID 永不动) ====================

    @Test
    void rewritesWatermarkedJunkNamesToEpisodeNumbers() {
        MediaSubscriptionCheckService check = mockCheck();
        T4DetailEnhancer enhancer = newEnhancer(mock(DoubanService.class), mock(TmdbService.class), check);
        ObjectNode item = detail("庆余年", "最新电影www.dyg7.com第03集$3#www.abc.com$4");

        enhancer.cleanEpisodeNames(item);

        // 域名水印段:能解析出集号的重写为第N集,解析不出的保留原名;ID 原样
        assertThat(item.path("vod_play_url").asText()).isEqualTo("第3集$3#www.abc.com$4");
    }

    @Test
    void keepsNormalEpisodeNamesUntouched() {
        T4DetailEnhancer enhancer = newEnhancer();
        ObjectNode item = detail("庆余年", "第01集$1#第02集$2#03$3");

        enhancer.cleanEpisodeNames(item);

        assertThat(item.path("vod_play_url").asText()).isEqualTo("第01集$1#第02集$2#03$3");
    }

    @Test
    void singleSegmentRouteUntouched() {
        T4DetailEnhancer enhancer = newEnhancer();
        ObjectNode item = detail("流浪地球2", "1080P正片$1");

        enhancer.cleanEpisodeNames(item);

        assertThat(item.path("vod_play_url").asText()).isEqualTo("1080P正片$1");
    }

    @Test
    void lowParseRateRouteSkipped() {
        MediaSubscriptionCheckService check = mockCheck();
        T4DetailEnhancer enhancer = newEnhancer(mock(DoubanService.class), mock(TmdbService.class), check);
        ObjectNode item = detail("某剧", "www.a.com$1#www.b.com$2#www.c.com$3#www.d.com$4");

        enhancer.cleanEpisodeNames(item);

        assertThat(item.path("vod_play_url").asText()).isEqualTo("www.a.com$1#www.b.com$2#www.c.com$3#www.d.com$4");
    }

    // ==================== 工具 ====================

    private T4DetailEnhancer newEnhancer() {
        return newEnhancer(mock(DoubanService.class), mock(TmdbService.class), mockCheck());
    }

    private T4DetailEnhancer newEnhancer(DoubanService douban, TmdbService tmdb, MediaSubscriptionCheckService check) {
        return new T4DetailEnhancer(new AppProperties(), douban, tmdb, check);
    }

    /** parseEpisode 桩:只认「第N集/期/话」形态;空参安全(重打桩会以 null 实调旧 answer)。 */
    private MediaSubscriptionCheckService mockCheck() {
        MediaSubscriptionCheckService check = mock(MediaSubscriptionCheckService.class);
        when(check.parseEpisode(any(), any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            if (name == null || name.isBlank()) {
                return -1;
            }
            Matcher matcher = Pattern.compile("第\\s*(\\d{1,4})\\s*[集期话]").matcher(name);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
        });
        return check;
    }

    private Plugin plugin(String name) {
        Plugin plugin = new Plugin();
        plugin.setId(9);
        plugin.setName(name);
        return plugin;
    }

    private JsonNode wrap(ObjectNode item) {
        ObjectNode root = mapper.createObjectNode();
        root.putArray("list").add(item);
        return root;
    }

    private ObjectNode detail(String name, String playUrl) {
        ObjectNode item = mapper.createObjectNode();
        item.put("vod_id", "abc123");
        item.put("vod_name", name);
        item.put("vod_play_from", "播放");
        item.put("vod_play_url", playUrl);
        return item;
    }

    private Movie movie(String name, Integer year, String description) {
        Movie movie = new Movie();
        movie.setName(name);
        movie.setYear(year);
        movie.setDescription(description);
        movie.setActors("张三/李四");
        movie.setDirectors("王导");
        movie.setGenre("剧情");
        movie.setCover("https://img.doubanio.com/x.jpg");
        return movie;
    }

    private Tmdb tmdb(String name, Integer year, String description) {
        Tmdb tmdb = new Tmdb();
        tmdb.setName(name);
        tmdb.setYear(year);
        tmdb.setDescription(description);
        tmdb.setGenre("剧情");
        tmdb.setCover("https://image.tmdb.org/t/p/w500/x.jpg");
        return tmdb;
    }
}
