package cn.har01d.alist_tvbox.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import cn.har01d.alist_tvbox.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YouTube 站点测试:sp 筛选编码以官方筛选面板抓取值为准(网上标签表时间位错位),
 * 格式选择与条目映射用真实 yt-dlp 输出 fixture。
 */
class YoutubeServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/youtube/" + name)) {
            return objectMapper.readTree(in);
        }
    }

    @Test
    void buildSpSort() {
        assertThat(YoutubeService.buildSp("VIEW_COUNT", null, null, null)).isEqualTo("CAM");
        assertThat(YoutubeService.buildSp("RATING", null, null, null)).isEqualTo("CAE");
        assertThat(YoutubeService.buildSp("UPLOAD_DATE", null, null, null)).isEqualTo("CAI");
        assertThat(YoutubeService.buildSp(null, null, null, null)).isEmpty();
        assertThat(YoutubeService.buildSp("RELEVANCE", null, null, null)).isEmpty();
    }

    @Test
    void buildSpDateMatchesOfficialUICodes() {
        // 官方筛选面板:今日 EgIIAg== / 本星期 EgIIAw== / 本月 EgIIBA== / 今年 EgIIBQ==
        assertThat(YoutubeService.buildSp(null, "HOUR", null, null)).isEqualTo("EgIIAQ");
        assertThat(YoutubeService.buildSp(null, "DAY", null, null)).isEqualTo("EgIIAg");
        assertThat(YoutubeService.buildSp(null, "WEEK", null, null)).isEqualTo("EgIIAw");
        assertThat(YoutubeService.buildSp(null, "MONTH", null, null)).isEqualTo("EgIIBA");
        assertThat(YoutubeService.buildSp(null, "YEAR", null, null)).isEqualTo("EgIIBQ");
    }

    @Test
    void buildSpTypeAndFeatures() {
        assertThat(YoutubeService.buildSp(null, null, "VIDEO", null)).isEqualTo("EgIQAQ");
        assertThat(YoutubeService.buildSp(null, null, "CHANNEL", null)).isEqualTo("EgIQAg");
        assertThat(YoutubeService.buildSp(null, null, "PLAYLIST", null)).isEqualTo("EgIQAw");
        assertThat(YoutubeService.buildSp(null, null, "MOVIE", null)).isEqualTo("EgIQBA");
        assertThat(YoutubeService.buildSp(null, null, null, "HD")).isEqualTo("EgIgAQ");
        assertThat(YoutubeService.buildSp(null, null, null, "_4K")).isEqualTo("EgJwAQ");
        assertThat(YoutubeService.buildSp(null, null, null, "HDR")).isEqualTo("EgPIAQE");
    }

    @Test
    void buildSpCombined() {
        assertThat(YoutubeService.buildSp("VIEW_COUNT", "WEEK", null, null)).isEqualTo("CAMSAggD");
        assertThat(YoutubeService.buildSp("RATING", null, "MOVIE", null)).isEqualTo("CAESAhAE");
    }

    @Test
    void buildSpLive() {
        // 官方筛选面板「直播」= EgJAAQ==(Filters field18:1)
        assertThat(YoutubeService.buildSp(null, null, null, null, true)).isEqualTo("EgJAAQ");
    }

    @Test
    void pickMasterManifest() throws Exception {
        JsonNode info = fixture("video.json");
        assertThat(YoutubeService.findMasterManifest(info))
                .startsWith("https://manifest.googlevideo.com/api/manifest/hls_variant");
    }

    @Test
    void pickMuxedFormats() throws Exception {
        JsonNode info = fixture("video.json");
        List<JsonNode> muxed = YoutubeService.muxedFormats(info);
        assertThat(muxed).extracting(f -> f.path("format_id").asText()).containsExactly("18");
    }

    @Test
    void pickVideoOnlyAndAudio() throws Exception {
        JsonNode info = fixture("video.json");
        List<JsonNode> videos = YoutubeService.videoOnlyFormats(info, 1080);
        assertThat(videos).isNotEmpty();
        assertThat(videos).allSatisfy(f -> assertThat(f.path("height").asInt()).isGreaterThanOrEqualTo(1080));
        assertThat(YoutubeService.videoOnlyFormats(info, 2160))
                .allSatisfy(f -> assertThat(f.path("height").asInt()).isGreaterThanOrEqualTo(2160));
        List<JsonNode> audio = YoutubeService.audioFormats(info);
        assertThat(audio).isNotEmpty();
        assertThat(audio).allSatisfy(f -> assertThat(f.path("acodec").asText()).isNotEqualTo("none"));
    }

    @Test
    void liveManifest() throws Exception {
        JsonNode live = fixture("live.json");
        assertThat(YoutubeService.findMasterManifest(live)).startsWith("https://manifest.googlevideo.com");
        assertThat(YoutubeService.muxedFormats(live)).isEmpty();
    }

    @Test
    void searchEntryMapping() throws Exception {
        JsonNode entries = fixture("search-flat.json").path("entries");
        // 条目0=视频,1=频道,2=播放列表(真实 yt-dlp flat 输出)
        assertThat(YoutubeService.channelOrHandleOf(entries.get(0))).isNull();
        assertThat(YoutubeService.playlistIdOf(entries.get(0))).isNull();
        assertThat(YoutubeService.channelOrHandleOf(entries.get(1))).startsWith("UC");
        assertThat(YoutubeService.playlistIdOf(entries.get(1))).isNull();
        assertThat(YoutubeService.channelOrHandleOf(entries.get(2))).isNull();
        assertThat(YoutubeService.playlistIdOf(entries.get(2))).startsWith("PL");
    }

    @Test
    void proxyUrlRoundTrip() {
        String url = "https://rr5---sn-x.googlevideo.com/videoplayback?id=x&range=0-1023";
        assertThat(YoutubeService.decodeProxyUrl(YoutubeService.encodeProxyUrl(url))).isEqualTo(url);
    }

    @Test
    void parseTagFromMirrorLandingUrl() {
        assertThat(YoutubeDlpRuntime.parseTag("https://ghproxy.net/https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26"))
                .isEqualTo("2026.10.26");
        assertThat(YoutubeDlpRuntime.parseTag("https://github.com/yt-dlp/yt-dlp/releases/latest")).isNull();
    }

    @Test
    void subtitlePicksPreferManualChineseThenAutoTranslation() throws Exception {
        // TED fixture:人工仅 en;自动翻译 zh-CN/zh-TW → English(人工) + 简体中文(自动翻译)
        JsonNode info = fixture("subtitles.json");
        var picks = YoutubeService.pickSubtitlePicks(info);
        assertThat(picks).extracting(YoutubeService.SubPick::lang, YoutubeService.SubPick::auto)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("en", false),
                        org.assertj.core.groups.Tuple.tuple("zh-CN", true));
        assertThat(YoutubeService.subtitleName("zh-CN")).isEqualTo("简体中文");
        // 人工字幕 URL 是 timedtext 域(白名单内)
        assertThat(picks.get(0).url()).startsWith("https://www.youtube.com/api/timedtext");
    }

    @Test
    void subtitlePicksSkipAutoWhenManualChineseExists() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode info = mapper.readTree("{\"subtitles\":{\"zh-Hans\":[{\"ext\":\"vtt\",\"url\":\"https://www.youtube.com/api/timedtext?a=1\"}]},\"automatic_captions\":{\"zh-CN\":[{\"ext\":\"vtt\",\"url\":\"https://www.youtube.com/api/timedtext?b=2\"}]}}");
        var picks = YoutubeService.pickSubtitlePicks(info);
        assertThat(picks).extracting(YoutubeService.SubPick::lang, YoutubeService.SubPick::auto)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("zh-Hans", false));
    }

    @Test
    void extractCuesKeepsBlocksAndDedupeIsByAdjacent() throws Exception {
        // 真实形态:YouTube timedtext 导出每 cue 原样两遍(q6fgVVmdpgY 实录)
        String vtt = """
                WEBVTT
                Kind: captions
                Language: zh-Hans

                00:00:06.834 --> 00:00:08.033
                <c.color000000>​​</c><c.colorF913BF><b>​ ​我们上​ ​</b></c><c.color000000>​</c>

                00:00:06.834 --> 00:00:08.033
                <c.color000000>​​</c><c.colorF913BF><b>​ ​我们上​ ​</b></c><c.color000000>​</c>

                00:00:08.100 --> 00:00:09.300
                <c.color000000>​​</c><c.color13F4F9><b>​ ​好耶​ ​</b></c><c.color000000>​</c>
                """;
        List<String> cues = YoutubeService.extractCues(vtt);
        assertThat(cues).hasSize(3);
        // 相邻去重后 cue0 == cue1 → 输出只剩 2 条
        StringBuilder out = new StringBuilder();
        String previous = null;
        int kept = 0;
        for (String cue : cues) {
            if (cue.equals(previous)) continue;
            kept++;
            previous = cue;
        }
        assertThat(kept).isEqualTo(2);
        // 样式/头部块(无 -->)不进 cue
        assertThat(cues.get(0)).startsWith("00:00:06.834");
    }

    @Test
    void proxyHostAllowlistMatchesRegisteredAndSubDomains() {
        ProxyHostAllowlist allowlist = new ProxyHostAllowlist();
        allowlist.register("v16-webapp-prime.tiktok.com");
        assertThat(allowlist.allows("v16-webapp-prime.tiktok.com")).isTrue();
        // CDN 子域分片:登记域的后缀匹配
        assertThat(allowlist.allows("v19-webapp-prime.tiktok.com")).isFalse();
        assertThat(allowlist.allows("chunk-v16-webapp-prime.tiktok.com.edge.tiktokcdn.com")).isFalse();
        allowlist.register("tiktokcdn.com");
        assertThat(allowlist.allows("chunk.any.tiktokcdn.com")).isTrue();
        assertThat(allowlist.allows(null)).isFalse();
        assertThat(allowlist.allows("evil.com")).isFalse();
    }

    @Test
    void resolveDownloadUrlsWithGitHubProxies() {
        AppProperties properties = new AppProperties();
        YoutubeDlpRuntime runtime = new YoutubeDlpRuntime(properties, new cn.har01d.alist_tvbox.service.GitHubProxyService(), objectMapper);

        // 未配置代理(github_proxy.txt 不存在):仅直连
        assertThat(runtime.resolveDownloadUrls(null))
                .containsExactly("https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.tar.gz");

        // 用户显式配置 youtubeDlpUrl:直接用之,代理不介入
        properties.setYoutubeDlpUrl("https://example.com/yt-dlp.tar.gz");
        assertThat(runtime.resolveDownloadUrls(null)).containsExactly("https://example.com/yt-dlp.tar.gz");

        // 代理列表:逐个拼前缀(自动补尾斜杠/去空行),末尾直连兜底
        assertThat(YoutubeDlpRuntime.applyProxies("https://github.com/a/b.tar.gz",
                        List.of("https://gh-proxy.com", "", "https://ghproxy.net/")))
                .containsExactly(
                        "https://gh-proxy.com/https://github.com/a/b.tar.gz",
                        "https://ghproxy.net/https://github.com/a/b.tar.gz",
                        "https://github.com/a/b.tar.gz");
    }
}
