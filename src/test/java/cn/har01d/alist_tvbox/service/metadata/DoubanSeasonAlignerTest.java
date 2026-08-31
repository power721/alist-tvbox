package cn.har01d.alist_tvbox.service.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 豆瓣分季集数累推起始集号(离线:suggest/episodes_count 注入桩)。 */
class DoubanSeasonAlignerTest {

    /** 线上形态:一念永恒 豆瓣分季条目 S1=52 / S2=52 / S3=48 → S4(完结季)起始 153。 */
    private static final class YiNianStub extends DoubanSeasonAligner {
        YiNianStub() {
            super(null);
        }

        @Override
        public List<DoubanCandidate> suggest(String keyword) {
            return List.of(
                    new DoubanCandidate("1", "一念永恒", "", "2020"),
                    new DoubanCandidate("2", "一念永恒 第二季", "", "2021"),
                    new DoubanCandidate("3", "一念永恒 第三季", "", "2023"),
                    // 同名异剧/无关条目:剥季缀后与裸剧名不等,必须被拒
                    new DoubanCandidate("9", "一念永恒之仙路争锋", "", "2020"),
                    new DoubanCandidate("8", "凡人修仙传", "", "2020"));
        }

        @Override
        public Optional<Integer> fetchEpisodeCount(String doubanId) {
            return switch (doubanId) {
                case "1" -> Optional.of(52);
                case "2" -> Optional.of(52);
                case "3" -> Optional.of(48);
                default -> Optional.empty();
            };
        }
    }

    @Test
    void infersStartFromFinaleMark() {
        // 「完结季」无季号且豆瓣尚无 S4 条目:已播 173 > 已登记各季之和 152 → 目标季 = 3+1 = 4,
        // 累加 52+52+48 → 153
        assertEquals(153, new YiNianStub().inferSeasonStart("一念永恒", 2020, "一念永恒 完结季 4K臻彩MAX [更新至08集]", 173));
    }

    @Test
    void infersStartFromDeclaredSeason() {
        assertEquals(153, new YiNianStub().inferSeasonStart("一念永恒", 2020, "一念永恒 第4季 2160P", 173));
        assertEquals(53, new YiNianStub().inferSeasonStart("一念永恒", 2020, "一念永恒 S02 国语", 173));
        // 已播 100 ≤ 已登记之和 152:完结季 = 已登记最后一季(3),起始 52+52+1 = 105
        assertEquals(105, new YiNianStub().inferSeasonStart("一念永恒", 2020, "一念永恒 完结季", 100));
    }

    @Test
    void rejectsWhenTitleDeclaresNoSeason() {
        assertNull(new YiNianStub().inferSeasonStart("一念永恒", 2020, "一念永恒 4K合集 更新至168集", 173));
        assertNull(new YiNianStub().inferSeasonStart("一念永恒", 2020, "一念永恒 第一季", 173)); // S1 无偏移
    }

    @Test
    void rejectsWhenPreviousSeasonIncomplete() {
        // 豆瓣缺 S2 条目:S3 起始无从累加 → 宁可不推
        DoubanSeasonAligner stub = new DoubanSeasonAligner(null) {
            @Override
            public List<DoubanCandidate> suggest(String keyword) {
                return List.of(
                        new DoubanCandidate("1", "一念永恒", "", "2020"),
                        new DoubanCandidate("3", "一念永恒 第三季", "", "2023"));
            }

            @Override
            public Optional<Integer> fetchEpisodeCount(String doubanId) {
                return "1".equals(doubanId) ? Optional.of(52) : Optional.of(48);
            }
        };
        assertNull(stub.inferSeasonStart("一念永恒", 2020, "一念永恒 第三季", 173));
    }

    @Test
    void yearGateBlocksSameNameDifferentShow() {
        // S1 裸名条目年份与首播年差 >1:同名异剧,拒
        DoubanSeasonAligner stub = new DoubanSeasonAligner(null) {
            @Override
            public List<DoubanCandidate> suggest(String keyword) {
                return List.of(new DoubanCandidate("1", "一念永恒", "", "2010"));
            }

            @Override
            public Optional<Integer> fetchEpisodeCount(String doubanId) {
                return Optional.of(52);
            }
        };
        assertNull(stub.inferSeasonStart("一念永恒", 2020, "一念永恒 第二季", 173));
    }

    @Test
    void seasonStartsTableForms() {
        // 各季起点表:S1=1 / S2=53 / S3=105(52+52+1),多季合一包文件级映射用
        var starts = new YiNianStub().seasonStarts("一念永恒", 2020);
        assertEquals(1, starts.get(1));
        assertEquals(53, starts.get(2));
        assertEquals(105, starts.get(3));
        assertEquals(3, starts.size(), "豆瓣尚无 S4 条目:起点表只含已登记季");
    }

    @Test
    void finaleSeasonForms() {
        DoubanSeasonAligner stub = new YiNianStub();
        assertEquals(4, stub.finaleSeason("一念永恒", 2020, 173), "已播 173 > 已登记之和 152:目标 = 3+1");
        assertEquals(3, stub.finaleSeason("一念永恒", 2020, 100), "已播 100 ≤ 152:目标 = 最后一季");
        assertEquals(3, stub.finaleSeason("一念永恒", 2020, null), "无已播数据:已登记最后一季");
    }

    @Test
    void seasonMarkInSubTitleCounts() {
        // 豆瓣部分条目季标在 sub_title(标题是裸名)
        DoubanSeasonAligner stub = new DoubanSeasonAligner(null) {
            @Override
            public List<DoubanCandidate> suggest(String keyword) {
                return List.of(
                        new DoubanCandidate("1", "一念永恒", "", "2020"),
                        new DoubanCandidate("2", "一念永恒", "第二季", "2021"));
            }

            @Override
            public Optional<Integer> fetchEpisodeCount(String doubanId) {
                return "1".equals(doubanId) ? Optional.of(52) : Optional.of(52);
            }
        };
        assertEquals(53, stub.inferSeasonStart("一念永恒", 2020, "一念永恒 完结季", 100),
                "已播 100 ≤ 已登记之和 104:完结季 = 第 2 季(sub_title 季标),起始 52+1");
    }
}
