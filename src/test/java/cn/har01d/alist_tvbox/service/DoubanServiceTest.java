package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.AliasRepository;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.entity.Movie;
import cn.har01d.alist_tvbox.entity.MovieRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoubanServiceTest {

    @Mock
    private AppProperties appProperties;
    @Mock
    private MetaRepository metaRepository;
    @Mock
    private MovieRepository movieRepository;
    @Mock
    private AliasRepository aliasRepository;
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private SiteService siteService;
    @Mock
    private TaskService taskService;
    @Mock
    private FileDownloader fileDownloader;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private Environment environment;

    private DoubanService doubanService;

    @BeforeEach
    void setUp() {
        doubanService = new DoubanService(appProperties, metaRepository, movieRepository, aliasRepository,
                settingRepository, siteService, taskService, fileDownloader, new RestTemplateBuilder(),
                jdbcTemplate, environment);
    }

    @Test
    void pickBestReturnsClosestYear() {
        Movie a = movie("同名", 1994);
        Movie b = movie("同名", 2001);

        assertThat(DoubanService.pickBest(List.of(a, b), 1995)).isSameAs(a);   // |1994-1995|=1 < |2001-1995|=6
        assertThat(DoubanService.pickBest(List.of(a, b), 2000)).isSameAs(b);   // |2001-2000|=1 < |1994-2000|=6
    }

    @Test
    void pickBestSkipsNullYearsAndFallsBackToFirst() {
        Movie dated = movie("同名", 2010);
        Movie unknown = movie("同名", null);

        assertThat(DoubanService.pickBest(List.of(unknown, dated), 2010)).isSameAs(dated);
        assertThat(DoubanService.pickBest(List.of(unknown, unknown), 2010)).isSameAs(unknown); // all null → first
    }

    @Test
    void pickBestWithoutYearKeepsFirstMatch() {
        Movie a = movie("同名", 1994);
        Movie b = movie("同名", 2001);
        assertThat(DoubanService.pickBest(List.of(a, b), null)).isSameAs(a);
        assertThat(DoubanService.pickBest(List.of(b), 1994)).isSameAs(b);   // single candidate
        assertThat(DoubanService.pickBest(List.of(), 1994)).isNull();
        assertThat(DoubanService.pickBest(null, 1994)).isNull();
    }

    @Test
    void pickBestNamePrefersExactThenShortest() {
        Movie exact = movie("肖申克的救赎", 1994);
        Movie longName = movie("肖申克的救赎 蓝光修复版", 1994);

        // exact name wins even when it is not first
        assertThat(DoubanService.pickBestName(List.of(longName, exact), "肖申克的救赎")).isSameAs(exact);
        // no exact → shortest name (exact's name is shorter than longName's)
        assertThat(DoubanService.pickBestName(List.of(longName, exact), "肖申克")).isSameAs(exact);
        assertThat(DoubanService.pickBestName(List.of(), "x")).isNull();
        assertThat(DoubanService.pickBestName(null, "x")).isNull();
    }

    @Test
    void getYearFromTextExtractsFromTitle() {
        assertThat(DoubanService.getYearFromText("肖申克的救赎 (1994)")).isEqualTo(1994);
        assertThat(DoubanService.getYearFromText("肖申克的救赎 2008 BluRay")).isEqualTo(2008);
        // parenthesized year is preferred over a bare one
        assertThat(DoubanService.getYearFromText("某片 (2010) 2008")).isEqualTo(2010);
        // out-of-range / no year
        assertThat(DoubanService.getYearFromText("1234")).isNull();
        assertThat(DoubanService.getYearFromText("没有年份的标题")).isNull();
        assertThat(DoubanService.getYearFromText(null)).isNull();
    }

    @Test
    void isSeasonOnlyDetectsBareSeasonTokens() {
        // Chinese season markers (from fixName transforming S01/S02 -> 第一季/第二季)
        assertThat(DoubanService.isSeasonOnly("第一季")).isTrue();
        assertThat(DoubanService.isSeasonOnly("第二季")).isTrue();
        assertThat(DoubanService.isSeasonOnly("第十二季")).isTrue();
        assertThat(DoubanService.isSeasonOnly("第3季")).isTrue();
        // English / code forms (defensive)
        assertThat(DoubanService.isSeasonOnly("Season 1")).isTrue();
        assertThat(DoubanService.isSeasonOnly("S01")).isTrue();
        assertThat(DoubanService.isSeasonOnly("SE01")).isTrue();
        // real titles, even season-suffixed ones, are NOT bare season tokens
        assertThat(DoubanService.isSeasonOnly("百花杀")).isFalse();
        assertThat(DoubanService.isSeasonOnly("百花杀 第一季")).isFalse();
        assertThat(DoubanService.isSeasonOnly("证言 第一季")).isFalse();
        assertThat(DoubanService.isSeasonOnly("")).isFalse();
        assertThat(DoubanService.isSeasonOnly(null)).isFalse();
    }

    // Regression: a season-only folder ("S01") must not match a random season-N show.
    // Previously fixName("S01") -> "第一季", then LIKE '%第一季%' returned "证言 第一季".
    @Test
    void getByNameSkipsBroadMatchForBareSeasonToken() {
        when(aliasRepository.findById(anyString())).thenReturn(Optional.empty());
        when(movieRepository.getByName("第一季")).thenReturn(List.of());

        assertThat(doubanService.getByName("S01", 2026)).isNull();
        // the year-scoped name-contains fallback must never run for a bare season token
        verify(movieRepository, never()).findByYearAndNameContains(anyInt(), anyString(), any());
    }

    // Regression: a dot-separated CJK folder ("百.花.杀（2026）") must resolve to the real
    // title. Previously fixName -> "百 花 杀" never matched the stored "百花杀".
    @Test
    void getByNameCollapsesCjkSpacesSoDottedFolderMatches() {
        Movie baihuasha = movie("百花杀", 2026);
        baihuasha.setId(34815020);
        when(aliasRepository.findById(anyString())).thenReturn(Optional.empty());
        when(movieRepository.getByName("百花杀")).thenReturn(List.of());
        when(movieRepository.findByYearAndNameContains(eq(2026), eq("百花杀"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(baihuasha)));

        Movie result = doubanService.getByName("百.花.杀（2026）", 2026);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("百花杀");
    }

    private Movie movie(String name, Integer year) {
        Movie m = new Movie();
        m.setName(name);
        m.setYear(year);
        return m;
    }
}
