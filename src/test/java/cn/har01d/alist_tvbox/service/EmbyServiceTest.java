package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.emby.EmbyItem;
import cn.har01d.alist_tvbox.entity.Emby;
import cn.har01d.alist_tvbox.entity.EmbyRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;

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
