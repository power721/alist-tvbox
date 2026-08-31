package cn.har01d.alist_tvbox.service.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 腾讯分季集数累推起始集号(离线:MbSearch 响应注入桩,形态取自线上实测一念永恒)。 */
class TencentSeasonAlignerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 线上实测(2026-08-31):S1=52 / S2=54 / S3=59 / 完结季=16(更新中);
     * 混入同名异剧(2010)与「合集篇/小剧场」衍生条目,多源 totalEpisode 取最大。 */
    private static TencentSeasonAligner stub() {
        return new TencentSeasonAligner(null) {
            @Override
            public JsonNode search(String keyword) {
                try {
                    return MAPPER.readTree("{\"normalList\":{\"itemList\":[" + items() + "]}}");
                } catch (Exception e) {
                    return null;
                }
            }
        };
    }

    private static String items() {
        return items(true);
    }

    /** withFinale=false:腾讯缺完结季条目(已播 173 > 已登记之和 165 的形态)。 */
    private static String items(boolean withFinale) {
        String finale = withFinale ? ",\n" + item("w4", "一念永恒 完结季", 2026, 16) : "";
        return String.join(",",
                item("x1", "一念永恒", 2010, 40),                 // 同名异剧:裸名年份门禁拒
                item("x2", "一念永恒合集篇", 2024, 53),          // 衍生条目:剥季缀不等名,出局
                item("x3", "一念永恒小剧场", 2021, 30),
                item("w1", "<em>一念永恒</em> 第1季", 2020, 52), // 标题带 <em> 高亮,须剥
                item("w2", "<em>一念永恒</em> 第2季", 2022, 54),
                item("w3", "<em>一念永恒</em> 第3季", 2024, 59))
                + finale;
    }

    /** 腾讯缺完结季条目时的归位 stub(离线注入)。 */
    private static TencentSeasonAligner stubWithoutFinale() {
        return new TencentSeasonAligner(null) {
            @Override
            public JsonNode search(String keyword) {
                try {
                    return MAPPER.readTree("{\"normalList\":{\"itemList\":[" + items(false) + "]}}");
                } catch (Exception e) {
                    return null;
                }
            }
        };
    }

    private static String item(String id, String title, int year, int total) {
        return String.format(
                "{\"doc\":{\"dataType\":2,\"id\":\"%s\"},\"videoInfo\":{\"title\":\"%s\",\"year\":%d,"
                        + "\"playSites\":[{\"totalEpisode\":%d,\"episodeInfoList\":[{\"url\":\"https://v.qq.com/x/cover/%s/e.html\"}]},"
                        + "{\"totalEpisode\":%d,\"episodeInfoList\":[]}]}}",
                id, title, year, total, id, Math.max(1, total - 2));
    }

    @Test
    void seasonStartsMatchAbsoluteNumbering() {
        // 与 Bangumi 绝对集号严格对齐:S2 起 53、S3 起 107、完结季(S4)起 166
        Map<Integer, Integer> starts = stub().seasonStarts("一念永恒", 2020);
        assertEquals(1, starts.get(1));
        assertEquals(53, starts.get(2));
        assertEquals(107, starts.get(3));
        assertEquals(166, starts.get(4), "完结季归位到最大季+1,起点 52+54+59+1");
        assertEquals(4, starts.size(), "异剧/衍生条目全部出局");
    }

    @Test
    void inferSeasonStartForms() {
        assertEquals(166, stub().inferSeasonStart("一念永恒", 2020, "一念永恒 完结季 4K臻彩MAX [更新至08集]", 173));
        assertEquals(53, stub().inferSeasonStart("一念永恒", 2020, "一念永恒 第2季 2160P", 173));
        assertNull(stub().inferSeasonStart("一念永恒", 2020, "一念永恒 4K合集 更新至168集", 173), "标题不声明季:无锚点");
        assertNull(stub().inferSeasonStart("一念永恒", 2020, "一念永恒 第一季", 173), "S1 无偏移");
    }

    @Test
    void finaleEntryMissingFallsBeyondRegistered() {
        TencentSeasonAligner stub = stubWithoutFinale();
        // 已播 173 > 已登记之和 165:完结季目标 = 最大季+1,不冒领给第 3 季
        assertEquals(4, stub.finaleSeason("一念永恒", 2020, 173));
        // seasonStarts 无 S4 起点 → infer 返 null,调用方回落豆瓣兜底
        assertNull(stub.inferSeasonStart("一念永恒", 2020, "一念永恒 完结季 4K", 173));
        assertEquals(3, stub.finaleSeason("一念永恒", 2020, 150), "已播未超登记之和:维持最大季");
    }

    @Test
    void emptySearchReturnsNull() {
        TencentSeasonAligner stub = new TencentSeasonAligner(null) {
            @Override
            public JsonNode search(String keyword) {
                return null;
            }
        };
        assertNull(stub.seasonStarts("一念永恒", 2020));
        assertNull(stub.finaleSeason("一念永恒", 2020, 173));
    }
}
