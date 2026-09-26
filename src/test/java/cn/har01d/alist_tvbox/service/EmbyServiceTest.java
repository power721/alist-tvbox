package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.emby.EmbyInfo;
import cn.har01d.alist_tvbox.dto.emby.EmbyItem;
import cn.har01d.alist_tvbox.dto.emby.EmbyItems;
import cn.har01d.alist_tvbox.entity.Emby;
import cn.har01d.alist_tvbox.entity.EmbyRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmbyServiceTest {
    private final EmbyService embyService = new EmbyService(
            Mockito.mock(EmbyRepository.class),
            new RestTemplateBuilder(),
            new ObjectMapper(),
            Mockito.mock(SettingRepository.class),
            Mockito.mock(cn.har01d.alist_tvbox.config.AppProperties.class),
            Mockito.mock(ProxyService.class)
    );

    @Test
    void searchShouldUseOfficialSearchTermParameterAndEncodeValue() {
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        ReflectionTestUtils.setField(embyService, "restTemplate", restTemplate);
        EmbyItems items = new EmbyItems();
        items.setItems(List.of());
        Mockito.when(restTemplate.exchange(Mockito.any(URI.class), Mockito.eq(HttpMethod.GET), Mockito.any(), Mockito.eq(EmbyItems.class)))
                .thenReturn(ResponseEntity.ok(items));

        Emby emby = new Emby();
        emby.setId(1);
        emby.setName("Emby");
        emby.setUrl("http://127.0.0.1:8096");
        emby.setClientName("Yamby");
        emby.setClientVersion("1.5.7.18");
        emby.setDeviceName("AList TvBox");
        emby.setDeviceId("device-1");
        EmbyInfo info = new EmbyInfo();
        EmbyInfo.User user = new EmbyInfo.User();
        user.setId("user-1");
        info.setUser(user);
        info.setAccessToken("token");

        List<MovieDetail> result = ReflectionTestUtils.invokeMethod(embyService, "search", emby, info, "#窨井盖", "Movie");

        ArgumentCaptor<URI> captor = ArgumentCaptor.forClass(URI.class);
        Mockito.verify(restTemplate).exchange(captor.capture(), Mockito.eq(HttpMethod.GET), Mockito.any(), Mockito.eq(EmbyItems.class));
        String query = captor.getValue().getRawQuery();
        assertThat(query).contains("SearchTerm=" + URLEncoder.encode("#窨井盖", StandardCharsets.UTF_8));
        assertThat(query).doesNotContain("searchTerm=");
        assertThat(query).contains("IncludeItemTypes=Movie");
        assertThat(result).isEmpty();
    }

    @Test
    void getMovieDetailShouldFormatRatingToOneDecimal() {
        Emby emby = new Emby();
        emby.setId(1);
        emby.setName("Emby");
        emby.setUrl("http://127.0.0.1:8096");

        EmbyItem item = new EmbyItem();
        item.setId("movie-id");
        item.setName("Movie");
        item.setType("Movie");
        item.setRating(7.783511685076998);

        MovieDetail movie = ReflectionTestUtils.invokeMethod(embyService, "getMovieDetail", item, emby);

        assertThat(movie.getVod_remarks()).isEqualTo("7.8");
    }

    @Test
    void getMovieDetailShouldHideZeroRating() {
        Emby emby = new Emby();
        emby.setId(1);
        emby.setName("Emby");
        emby.setUrl("http://127.0.0.1:8096");

        EmbyItem item = new EmbyItem();
        item.setId("movie-id");
        item.setName("Movie");
        item.setType("Movie");
        item.setRating(0.0);

        MovieDetail movie = ReflectionTestUtils.invokeMethod(embyService, "getMovieDetail", item, emby);

        assertThat(movie.getVod_remarks()).isEmpty();
    }
}
