package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Feiniu;
import cn.har01d.alist_tvbox.entity.FeiniuRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeiniuServiceTest {
    @Mock
    private FeiniuRepository feiniuRepository;
    @Mock
    private FeiniuApiClient apiClient;

    @InjectMocks
    private FeiniuService feiniuService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void listShouldUseParentGuidOnlyForDirectoryRequests() {
        Feiniu feiniu = new Feiniu();
        feiniu.setId(1);
        feiniu.setName("飞牛");
        feiniu.setUrl("http://127.0.0.1:5666");
        feiniu.setToken("token");

        when(feiniuRepository.findById(1)).thenReturn(Optional.of(feiniu));
        when(apiClient.getItemList(org.mockito.ArgumentMatchers.eq(feiniu), org.mockito.ArgumentMatchers.eq("token"), anyMap()))
                .thenReturn(new ObjectMapper().createObjectNode().put("total", 0).putArray("list"));

        feiniuService.list("1-1-3f7af58a48fc4641b3cc11b3496f9c62", null, 1);

        ArgumentCaptor<java.util.Map<String, Object>> captor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(apiClient).getItemList(org.mockito.ArgumentMatchers.eq(feiniu), org.mockito.ArgumentMatchers.eq("token"), captor.capture());
        var body = captor.getValue();

        assertThat(body)
                .containsEntry("parent_guid", "3f7af58a48fc4641b3cc11b3496f9c62")
                .containsEntry("sort_type", "ASC")
                .containsEntry("sort_column", "sort_title");
        assertThat(body).doesNotContainKey("ancestor_guid");
        assertThat(body.get("tags")).isEqualTo(java.util.Map.of());
    }

    @Test
    void listShouldFormatVoteAverageToOneDecimal() throws Exception {
        Feiniu feiniu = new Feiniu();
        feiniu.setId(1);
        feiniu.setName("飞牛");
        feiniu.setUrl("http://127.0.0.1:5666");
        feiniu.setToken("token");

        when(feiniuRepository.findById(1)).thenReturn(Optional.of(feiniu));
        when(apiClient.getItemList(org.mockito.ArgumentMatchers.eq(feiniu), org.mockito.ArgumentMatchers.eq("token"), anyMap()))
                .thenReturn(objectMapper.readTree("""
                        {
                          "total":1,
                          "list":[
                            {
                              "guid":"movie-guid",
                              "title":"超人总动员2",
                              "type":"Movie",
                              "vote_average":"7.783511685076998",
                              "release_date":"2018-06-14"
                            }
                          ]
                        }
                        """));

        var result = feiniuService.list("1-0-library-guid", null, 1);

        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getVod_remarks()).isEqualTo("7.8");
    }

    @Test
    void listShouldHideZeroVoteAverage() throws Exception {
        Feiniu feiniu = new Feiniu();
        feiniu.setId(1);
        feiniu.setName("飞牛");
        feiniu.setUrl("http://127.0.0.1:5666");
        feiniu.setToken("token");

        when(feiniuRepository.findById(1)).thenReturn(Optional.of(feiniu));
        when(apiClient.getItemList(org.mockito.ArgumentMatchers.eq(feiniu), org.mockito.ArgumentMatchers.eq("token"), anyMap()))
                .thenReturn(objectMapper.readTree("""
                        {
                          "total":1,
                          "list":[
                            {
                              "guid":"movie-guid",
                              "title":"超人总动员2",
                              "type":"Movie",
                              "vote_average":"0",
                              "release_date":"2018-06-14"
                            }
                          ]
                        }
                        """));

        var result = feiniuService.list("1-0-library-guid", null, 1);

        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getVod_remarks()).isEmpty();
    }

    @Test
    void categoryShouldUseDashStyleLibraryIdsForSingleSite() throws Exception {
        Feiniu feiniu = new Feiniu();
        feiniu.setId(1);
        feiniu.setName("飞牛");
        feiniu.setUrl("http://127.0.0.1:5666");
        feiniu.setToken("token");

        when(feiniuRepository.findAll()).thenReturn(List.of(feiniu));
        when(apiClient.getMediaDbList(feiniu, "token")).thenReturn(objectMapper.readTree("""
                [
                  {
                    "guid":"636b0918be9a4b50beaab50778554407",
                    "title":"影视"
                  }
                ]
                """));

        var result = feiniuService.category();

        assertThat(result.getCategories()).hasSize(1);
        assertThat(result.getCategories().get(0).getType_id()).isEqualTo("1-0-636b0918be9a4b50beaab50778554407");
    }

    @Test
    void categoryShouldUseImageProxyForCoverWhenTokenProvided() throws Exception {
        Feiniu feiniu = new Feiniu();
        feiniu.setId(1);
        feiniu.setName("飞牛");
        feiniu.setUrl("http://127.0.0.1:5666");
        feiniu.setToken("token");

        when(feiniuRepository.findAll()).thenReturn(List.of(feiniu));
        when(apiClient.getMediaDbList(feiniu, "token")).thenReturn(objectMapper.readTree("""
                [
                  {
                    "guid":"636b0918be9a4b50beaab50778554407",
                    "title":"影视",
                    "posters":[
                      "/18/19/demo.webp"
                    ]
                  }
                ]
                """));

        var result = feiniuService.category("test-token", "http://127.0.0.1:4567");

        assertThat(result.getCategories()).hasSize(1);
        assertThat(result.getCategories().get(0).getCover())
                .isEqualTo("http://127.0.0.1:4567/feiniu-img/test-token?site=1&path=/18/19/demo.webp");
    }

    @Test
    void playShouldUseMediaRangePlaybackForSingleVideo() throws Exception {
        Feiniu feiniu = new Feiniu();
        feiniu.setId(1);
        feiniu.setName("飞牛");
        feiniu.setUrl("http://127.0.0.1:5666");
        feiniu.setUsername("harold");
        feiniu.setUserAgent("Mozilla/5.0");
        feiniu.setToken("token");
        feiniu.setFnosToken("fnos-token");
        feiniu.setFnosLongToken("fnos-long-token");

        when(feiniuRepository.findById(1)).thenReturn(Optional.of(feiniu));
        when(apiClient.getPlayInfo(feiniu, "token", "video-guid")).thenReturn(objectMapper.readTree("""
                {
                  "media_guid":"2116760586d4445790af1e7978d0b91e",
                  "video_guid":"b975cb0d99ab4cbfb98ac6b672fb7f82",
                  "audio_guid":"8ec2df28892c4f0c8985a3ca8fc91a9b",
                  "subtitle_guid":"",
                  "duration":50,
                  "item":{"duration":50}
                }
                """));
        when(apiClient.getStream(feiniu, "token", "2116760586d4445790af1e7978d0b91e")).thenReturn(objectMapper.readTree("""
                {
                  "video_stream":{
                    "media_guid":"2116760586d4445790af1e7978d0b91e",
                    "guid":"7668f600a82f4ea5979af2318b87aa10",
                    "codec_name":"h264",
                    "wrapper":"MP4"
                  },
                  "audio_streams":[
                    {
                      "guid":"750b8c0dc78143d7a9691fd99bc3a508",
                      "codec_name":"aac",
                      "channels":2
                    }
                  ]
                }
                """));
        when(apiClient.getStreamList(feiniu, "token", "video-guid")).thenReturn(objectMapper.readTree("""
                {
                  "video_streams":[
                    {
                      "media_guid":"2116760586d4445790af1e7978d0b91e",
                      "guid":"b975cb0d99ab4cbfb98ac6b672fb7f82",
                      "resolution_type":"1080p"
                    }
                  ],
                  "subtitle_streams":[]
                }
                """));
        when(apiClient.getMediaRangeUrl(feiniu, "2116760586d4445790af1e7978d0b91e"))
                .thenReturn("http://127.0.0.1:5666/v/api/v1/media/range/2116760586d4445790af1e7978d0b91e");

        @SuppressWarnings("unchecked")
        var result = (java.util.Map<String, Object>) feiniuService.play("1-2-video-guid", "test-token", "http://127.0.0.1:4567");

        assertThat(result.get("parse")).isEqualTo(0);
        assertThat(result.get("url")).isEqualTo(java.util.List.of(
                "1080p",
                "http://127.0.0.1:5666/v/api/v1/media/range/2116760586d4445790af1e7978d0b91e"
        ));
        verify(apiClient, never()).getUserInfo(feiniu, "token");
        verify(apiClient, never()).startPlay(eq(feiniu), eq("token"), anyMap());
    }

    @Test
    void playShouldUseDirectLinkQualitiesForStrmPlayback() throws Exception {
        Feiniu feiniu = new Feiniu();
        feiniu.setId(1);
        feiniu.setName("飞牛");
        feiniu.setUrl("http://127.0.0.1:5666");
        feiniu.setUsername("harold");
        feiniu.setUserAgent("Mozilla/5.0");
        feiniu.setToken("token");

        when(feiniuRepository.findById(1)).thenReturn(Optional.of(feiniu));
        when(apiClient.getPlayInfo(feiniu, "token", "video-guid")).thenReturn(objectMapper.readTree("""
                {
                  "media_guid":"d8cfd40ac5284db680263f19e797aab8",
                  "video_guid":"8b908101d494487b9508d8d21c1ba6b5",
                  "audio_guid":"78062882c138473aa0af6b3e3537a932",
                  "subtitle_guid":"",
                  "duration":7550,
                  "item":{"duration":7550}
                }
                """));
        when(apiClient.getStream(feiniu, "token", "d8cfd40ac5284db680263f19e797aab8")).thenReturn(objectMapper.readTree("""
                {
                  "file_stream":{
                    "file_name":"飞驰人生3.strm"
                  },
                  "video_stream":{
                    "media_guid":"d8cfd40ac5284db680263f19e797aab8",
                    "guid":"8b908101d494487b9508d8d21c1ba6b5",
                    "codec_name":"hevc",
                    "wrapper":"MKV"
                  },
                  "audio_streams":[
                    {
                      "guid":"78062882c138473aa0af6b3e3537a932",
                      "codec_name":"flac",
                      "channels":2
                    }
                  ],
                  "direct_link_qualities":[
                    {
                      "resolution":"原画",
                      "url":"https://ykj.example.com/feiniu.mkv?X-Amz-Signature=abc",
                      "is_m3u8":false
                    }
                  ]
                }
                """));
        when(apiClient.getStreamList(feiniu, "token", "video-guid")).thenReturn(objectMapper.readTree("""
                {
                  "subtitle_streams":[]
                }
                """));

        @SuppressWarnings("unchecked")
        var result = (java.util.Map<String, Object>) feiniuService.play("1-2-video-guid", "test-token", "http://127.0.0.1:4567");

        assertThat(result.get("parse")).isEqualTo(0);
        assertThat(result.get("url")).isEqualTo(java.util.List.of(
                "原画",
                "https://ykj.example.com/feiniu.mkv?X-Amz-Signature=abc"
        ));
        assertThat(result.get("header")).isEqualTo("{\"User-Agent\":\"Mozilla/5.0\"}");
        verify(apiClient, never()).getMediaRangeUrl(eq(feiniu), eq("d8cfd40ac5284db680263f19e797aab8"));
        verify(apiClient, never()).startPlay(eq(feiniu), eq("token"), anyMap());
    }

    @Test
    void updateProgressShouldUseActualPlayLinkFromTranscodeSession() throws Exception {
        Feiniu feiniu = new Feiniu();
        feiniu.setId(1);
        feiniu.setName("飞牛");
        feiniu.setUrl("http://127.0.0.1:5666");
        feiniu.setUsername("harold");
        feiniu.setUserAgent("Mozilla/5.0");
        feiniu.setToken("token");
        feiniu.setFnosToken("fnos-token");
        feiniu.setFnosLongToken("fnos-long-token");

        when(feiniuRepository.findById(1)).thenReturn(Optional.of(feiniu));
        when(apiClient.getPlayInfo(feiniu, "token", "video-guid")).thenReturn(objectMapper.readTree("""
                {
                  "media_guid":"2116760586d4445790af1e7978d0b91e",
                  "video_guid":"b975cb0d99ab4cbfb98ac6b672fb7f82",
                  "audio_guid":"8ec2df28892c4f0c8985a3ca8fc91a9b",
                  "subtitle_guid":"",
                  "duration":542,
                  "item":{"duration":542}
                }
                """));
        when(apiClient.getStream(feiniu, "token", "2116760586d4445790af1e7978d0b91e")).thenReturn(objectMapper.readTree("""
                {
                  "video_stream":{
                    "media_guid":"2116760586d4445790af1e7978d0b91e",
                    "guid":"b975cb0d99ab4cbfb98ac6b672fb7f82",
                    "codec_name":"av1",
                    "wrapper":"MKV",
                    "bps":715579
                  },
                  "audio_streams":[
                    {
                      "guid":"8ec2df28892c4f0c8985a3ca8fc91a9b",
                      "codec_name":"opus",
                      "channels":2
                    }
                  ],
                  "qualities":[
                    {
                      "bitrate":715579,
                      "resolution":"1080"
                    }
                  ]
                }
                """));
        when(apiClient.getStreamList(feiniu, "token", "video-guid")).thenReturn(objectMapper.readTree("""
                {
                  "subtitle_streams":[]
                }
                """));
        when(apiClient.getMediaRangeUrl(feiniu, "2116760586d4445790af1e7978d0b91e"))
                .thenReturn("http://127.0.0.1:5666/v/api/v1/media/range/2116760586d4445790af1e7978d0b91e");
        when(apiClient.startPlay(eq(feiniu), eq("token"), anyMap())).thenReturn(objectMapper.readTree("""
                {
                  "play_link":"/v/media/play-1080/preset.m3u8",
                  "media_guid":"2116760586d4445790af1e7978d0b91e",
                  "video_guid":"b975cb0d99ab4cbfb98ac6b672fb7f82",
                  "audio_guid":"8ec2df28892c4f0c8985a3ca8fc91a9b",
                  "subtitle_guid":""
                }
                """));
        when(apiClient.absoluteUrl(feiniu, "/v/media/play-1080/preset.m3u8"))
                .thenReturn("http://127.0.0.1:5666/v/media/play-1080/preset.m3u8");

        @SuppressWarnings("unchecked")
        var result = (java.util.Map<String, Object>) feiniuService.play("1-2-video-guid", "test-token", "http://127.0.0.1:4567");
        assertThat(result.get("url")).isEqualTo(java.util.List.of(
                "原画",
                "http://127.0.0.1:5666/v/api/v1/media/range/2116760586d4445790af1e7978d0b91e",
                "1080p",
                "http://127.0.0.1:5666/v/media/play-1080/preset.m3u8"
        ));

        feiniuService.updateProgress("1-2-video-guid", 23L);

        ArgumentCaptor<java.util.Map<String, Object>> captor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(apiClient).recordPlay(eq(feiniu), eq("token"), captor.capture());
        assertThat(captor.getValue())
                .containsEntry("play_link", "/v/media/play-1080/preset.m3u8")
                .containsEntry("ts", 23L)
                .containsEntry("duration", 542L);
        verify(apiClient, never()).getUserInfo(feiniu, "token");
    }

    @Test
    void playShouldOnlyPrefetchFirstTranscodeQuality() throws Exception {
        Feiniu feiniu = new Feiniu();
        feiniu.setId(1);
        feiniu.setName("飞牛");
        feiniu.setUrl("http://127.0.0.1:5666");
        feiniu.setUserAgent("Mozilla/5.0");
        feiniu.setToken("token");

        when(feiniuRepository.findById(1)).thenReturn(Optional.of(feiniu));
        when(apiClient.getPlayInfo(feiniu, "token", "video-guid")).thenReturn(objectMapper.readTree("""
                {
                  "media_guid":"2116760586d4445790af1e7978d0b91e",
                  "video_guid":"b975cb0d99ab4cbfb98ac6b672fb7f82",
                  "audio_guid":"8ec2df28892c4f0c8985a3ca8fc91a9b",
                  "subtitle_guid":"",
                  "duration":542,
                  "item":{"duration":542}
                }
                """));
        when(apiClient.getStream(feiniu, "token", "2116760586d4445790af1e7978d0b91e")).thenReturn(objectMapper.readTree("""
                {
                  "video_stream":{
                    "media_guid":"2116760586d4445790af1e7978d0b91e",
                    "guid":"b975cb0d99ab4cbfb98ac6b672fb7f82",
                    "codec_name":"av1",
                    "wrapper":"MKV",
                    "bps":715579
                  },
                  "audio_streams":[
                    {
                      "guid":"8ec2df28892c4f0c8985a3ca8fc91a9b",
                      "codec_name":"opus",
                      "channels":2
                    }
                  ],
                  "qualities":[
                    {
                      "bitrate":1715579,
                      "resolution":"2160"
                    },
                    {
                      "bitrate":715579,
                      "resolution":"1080"
                    },
                    {
                      "bitrate":515579,
                      "resolution":"720"
                    }
                  ]
                }
                """));
        when(apiClient.getStreamList(feiniu, "token", "video-guid")).thenReturn(objectMapper.readTree("""
                {
                  "subtitle_streams":[]
                }
                """));
        when(apiClient.getMediaRangeUrl(feiniu, "2116760586d4445790af1e7978d0b91e"))
                .thenReturn("http://127.0.0.1:5666/v/api/v1/media/range/2116760586d4445790af1e7978d0b91e");
        when(apiClient.startPlay(eq(feiniu), eq("token"), anyMap())).thenReturn(objectMapper.readTree("""
                {
                  "play_link":"/v/media/play-2160/preset.m3u8",
                  "media_guid":"2116760586d4445790af1e7978d0b91e",
                  "video_guid":"b975cb0d99ab4cbfb98ac6b672fb7f82",
                  "audio_guid":"8ec2df28892c4f0c8985a3ca8fc91a9b",
                  "subtitle_guid":""
                }
                """));
        when(apiClient.absoluteUrl(feiniu, "/v/media/play-2160/preset.m3u8"))
                .thenReturn("http://127.0.0.1:5666/v/media/play-2160/preset.m3u8");

        @SuppressWarnings("unchecked")
        var result = (java.util.Map<String, Object>) feiniuService.play("1-2-video-guid", "test-token", "http://127.0.0.1:4567");

        assertThat(result.get("url")).isEqualTo(java.util.List.of(
                "原画",
                "http://127.0.0.1:5666/v/api/v1/media/range/2116760586d4445790af1e7978d0b91e",
                "2160p",
                "http://127.0.0.1:5666/v/media/play-2160/preset.m3u8"
        ));
        verify(apiClient, times(1)).startPlay(eq(feiniu), eq("token"), anyMap());
    }

    @Test
    void playShouldLoginWhenTokenMissing() throws Exception {
        Feiniu feiniu = new Feiniu();
        feiniu.setId(1);
        feiniu.setName("飞牛");
        feiniu.setUrl("http://127.0.0.1:5666");
        feiniu.setUsername("harold");
        feiniu.setPassword("secret");
        feiniu.setUserAgent("Mozilla/5.0");
        feiniu.setToken("");

        when(feiniuRepository.findById(1)).thenReturn(Optional.of(feiniu));
        when(apiClient.login(feiniu)).thenAnswer(invocation -> {
            feiniu.setFnosToken("fnos-token");
            feiniu.setFnosLongToken("fnos-long-token");
            return "token";
        });
        when(apiClient.getPlayInfo(feiniu, "token", "video-guid")).thenReturn(objectMapper.readTree("""
                {
                  "media_guid":"2116760586d4445790af1e7978d0b91e",
                  "video_guid":"b975cb0d99ab4cbfb98ac6b672fb7f82",
                  "audio_guid":"8ec2df28892c4f0c8985a3ca8fc91a9b",
                  "subtitle_guid":"",
                  "duration":50,
                  "item":{"duration":50}
                }
                """));
        when(apiClient.getStream(feiniu, "token", "2116760586d4445790af1e7978d0b91e")).thenReturn(objectMapper.readTree("""
                {
                  "video_stream":{
                    "media_guid":"2116760586d4445790af1e7978d0b91e",
                    "guid":"7668f600a82f4ea5979af2318b87aa10",
                    "codec_name":"h264",
                    "wrapper":"MP4"
                  },
                  "audio_streams":[
                    {
                      "guid":"750b8c0dc78143d7a9691fd99bc3a508",
                      "codec_name":"aac",
                      "channels":2
                    }
                  ]
                }
                """));
        when(apiClient.getStreamList(feiniu, "token", "video-guid")).thenReturn(objectMapper.readTree("""
                {
                  "video_streams":[
                    {
                      "media_guid":"2116760586d4445790af1e7978d0b91e",
                      "guid":"b975cb0d99ab4cbfb98ac6b672fb7f82",
                      "resolution_type":"1080p"
                    }
                  ],
                  "subtitle_streams":[]
                }
                """));
        when(apiClient.getMediaRangeUrl(feiniu, "2116760586d4445790af1e7978d0b91e"))
                .thenReturn("http://127.0.0.1:5666/v/api/v1/media/range/2116760586d4445790af1e7978d0b91e");

        feiniuService.play("1-2-video-guid", "test-token", "http://127.0.0.1:4567");

        verify(apiClient).login(feiniu);
    }

    @Test
    void detailShouldUseDashStyleItemIds() throws Exception {
        Feiniu feiniu = new Feiniu();
        feiniu.setId(1);
        feiniu.setName("飞牛");
        feiniu.setUrl("http://127.0.0.1:5666");
        feiniu.setToken("token");

        when(feiniuRepository.findById(1)).thenReturn(Optional.of(feiniu));
        when(apiClient.getItem(feiniu, "token", "video-guid")).thenReturn(objectMapper.readTree("""
                {
                  "guid":"video-guid",
                  "title":"姐姐",
                  "type":"TV",
                  "overview":"test overview"
                }
                """));
        when(apiClient.getSeasonList(feiniu, "token", "video-guid")).thenReturn(objectMapper.readTree("""
                [
                  {
                    "guid":"season-guid",
                    "title":"第 1 季"
                  }
                ]
                """));
        when(apiClient.getEpisodeList(feiniu, "token", "season-guid")).thenReturn(objectMapper.readTree("""
                [
                  {
                    "guid":"episode-guid",
                    "title":"第 1 集",
                    "episode_number":1
                  }
                ]
                """));

        var result = feiniuService.detail("1-2-video-guid");

        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getVod_play_url()).isEqualTo("第 1 集$1-2-episode-guid");
    }

    @Test
    void rewritePlaylistShouldProxyRelativeSegments() {
        String playlist = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-MAP:URI="init.mp4"
                #EXT-X-KEY:METHOD=AES-128,URI="enc.key"
                seg-1.ts
                nested/seg-2.ts?x=1
                """;

        String rewritten = feiniuService.rewritePlaylist(playlist, 1, "test-token", "http://127.0.0.1:4567",
                "http://127.0.0.1:5666/v/media/demo/preset.m3u8");

        assertThat(rewritten).contains("#EXT-X-MAP:URI=\"http://127.0.0.1:4567/feiniu-proxy/test-token?site=1&path=http%3A%2F%2F127.0.0.1%3A5666%2Fv%2Fmedia%2Fdemo%2Finit.mp4\"");
        assertThat(rewritten).contains("#EXT-X-KEY:METHOD=AES-128,URI=\"http://127.0.0.1:4567/feiniu-proxy/test-token?site=1&path=http%3A%2F%2F127.0.0.1%3A5666%2Fv%2Fmedia%2Fdemo%2Fenc.key\"");
        assertThat(rewritten).contains("http://127.0.0.1:4567/feiniu-proxy/test-token?site=1&path=http%3A%2F%2F127.0.0.1%3A5666%2Fv%2Fmedia%2Fdemo%2Fseg-1.ts");
        assertThat(rewritten).contains("http://127.0.0.1:4567/feiniu-proxy/test-token?site=1&path=http%3A%2F%2F127.0.0.1%3A5666%2Fv%2Fmedia%2Fdemo%2Fnested%2Fseg-2.ts%3Fx%3D1");
    }

    void proxyImageShouldCacheBytesAndSkipUserInfoValidation() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v/api/v1/sys/img/18/19/demo.webp", exchange -> {
            hits.incrementAndGet();
            byte[] body = "image-body".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "image/webp");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Feiniu feiniu = new Feiniu();
            feiniu.setId(1);
            feiniu.setName("飞牛");
            feiniu.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
            feiniu.setToken("token");

            when(feiniuRepository.findById(1)).thenReturn(Optional.of(feiniu));

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/feiniu-img/test-token");
            MockHttpServletResponse response1 = new MockHttpServletResponse();
            MockHttpServletResponse response2 = new MockHttpServletResponse();

            feiniuService.proxyImage(1, "/18/19/demo.webp", "test-token", request, response1);
            feiniuService.proxyImage(1, "/18/19/demo.webp", "test-token", request, response2);

            assertThat(response1.getContentType()).isEqualTo("image/webp");
            assertThat(response1.getContentAsString()).isEqualTo("image-body");
            assertThat(response2.getContentAsString()).isEqualTo("image-body");
            assertThat(hits.get()).isEqualTo(1);
            verify(apiClient, never()).getUserInfo(feiniu, "token");
        } finally {
            server.stop(0);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode mockPlayResponseForResolution(InvocationOnMock invocation) throws Exception {
        @SuppressWarnings("unchecked")
        var body = (java.util.Map<String, Object>) invocation.getArgument(2);
        String resolution = String.valueOf(body.get("resolution"));
        return objectMapper.readTree("""
                {
                  "play_link":"/v/media/play-RESOLUTION/preset.m3u8",
                  "media_guid":"2116760586d4445790af1e7978d0b91e",
                  "video_guid":"b975cb0d99ab4cbfb98ac6b672fb7f82",
                  "audio_guid":"8ec2df28892c4f0c8985a3ca8fc91a9b",
                  "subtitle_guid":""
                }
                """.replace("RESOLUTION", resolution));
    }
}
