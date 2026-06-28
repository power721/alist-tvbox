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
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

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
}
