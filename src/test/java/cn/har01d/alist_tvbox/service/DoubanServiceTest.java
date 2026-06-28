package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Movie;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DoubanServiceTest {

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

    private Movie movie(String name, Integer year) {
        Movie m = new Movie();
        m.setName(name);
        m.setYear(year);
        return m;
    }
}
