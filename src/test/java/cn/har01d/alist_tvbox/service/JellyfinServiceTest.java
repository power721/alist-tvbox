package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.emby.EmbyItem;
import cn.har01d.alist_tvbox.entity.Jellyfin;
import cn.har01d.alist_tvbox.entity.JellyfinRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class JellyfinServiceTest {
    private final JellyfinService jellyfinService = new JellyfinService(
            Mockito.mock(JellyfinRepository.class),
            Mockito.mock(SettingRepository.class),
            new RestTemplateBuilder(),
            new ObjectMapper()
    );

    @Test
    void getMovieDetailShouldFormatRatingToOneDecimal() {
        Jellyfin jellyfin = new Jellyfin();
        jellyfin.setId(1);
        jellyfin.setName("Jellyfin");
        jellyfin.setUrl("http://127.0.0.1:8097");

        EmbyItem item = new EmbyItem();
        item.setId("movie-id");
        item.setName("Movie");
        item.setType("Movie");
        item.setRating(7.783511685076998);

        MovieDetail movie = ReflectionTestUtils.invokeMethod(jellyfinService, "getMovieDetail", item, jellyfin);

        assertThat(movie.getVod_remarks()).isEqualTo("7.8");
    }

    @Test
    void getMovieDetailShouldHideZeroRating() {
        Jellyfin jellyfin = new Jellyfin();
        jellyfin.setId(1);
        jellyfin.setName("Jellyfin");
        jellyfin.setUrl("http://127.0.0.1:8097");

        EmbyItem item = new EmbyItem();
        item.setId("movie-id");
        item.setName("Movie");
        item.setType("Movie");
        item.setRating(0.0);

        MovieDetail movie = ReflectionTestUtils.invokeMethod(jellyfinService, "getMovieDetail", item, jellyfin);

        assertThat(movie.getVod_remarks()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void playShouldAuthenticateStreamUrlWithApiKeyParam() throws Exception {
        Jellyfin jellyfin = new Jellyfin();
        jellyfin.setId(1);
        jellyfin.setName("Jellyfin");
        jellyfin.setUrl("http://127.0.0.1:8097");
        jellyfin.setClientName("Jellyfin Android");
        jellyfin.setClientVersion("2.6.2");
        jellyfin.setDeviceId("f37abe7199c5c17e");
        jellyfin.setDeviceName("AList TvBox");

        JellyfinRepository repository = Mockito.mock(JellyfinRepository.class);
        Mockito.when(repository.findById(1)).thenReturn(Optional.of(jellyfin));
        JellyfinService service = new JellyfinService(
                repository,
                Mockito.mock(SettingRepository.class),
                new RestTemplateBuilder(),
                new ObjectMapper()
        );
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

        server.expect(requestTo("http://127.0.0.1:8097/Users/authenticatebyname"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"AccessToken":"token123","User":{"Id":"u1"},"SessionInfo":{"DeviceId":"dev1"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://127.0.0.1:8097/UserViews?userId=u1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"Items\":[],\"TotalRecordCount\":0}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://127.0.0.1:8097/Items/item1/PlaybackInfo"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"PlaySessionId":"ps1","MediaSources":[{"Id":"ms1","Name":"1080p","ETag":"tag1","RunTimeTicks":6000000000,"MediaStreams":[]}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://127.0.0.1:8097/Sessions/Playing"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://127.0.0.1:8097/Sessions/Playing/Progress"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        Map<String, Object> result = (Map<String, Object>) service.play("1-item1");

        List<String> urls = (List<String>) result.get("url");
        assertThat(urls.get(1))
                .contains("/Videos/item1/stream.mp4?Static=true")
                .contains("&mediaSourceId=item1")
                .contains("&deviceId=dev1")
                .contains("&ApiKey=token123")
                .contains("&Tag=tag1")
                .doesNotContain("api_key=");
        server.verify();
    }
}
