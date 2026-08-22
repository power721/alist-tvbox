package cn.har01d.alist_tvbox.util;

import cn.har01d.alist_tvbox.entity.Share;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class TextUtilsTest {

    @Test
    void parseMetaIdTagSupportsBracketAndBraceForms() {
        // 追剧转存目录方括号格式
        assertEquals("37448094", TextUtils.parseMetaIdTag("一念永恒-完结季 [dbid-37448094]", "dbid"));
        assertEquals("123", TextUtils.parseMetaIdTag("剧名 [tmdbid-123]", "tmdbid"));
        assertEquals("456", TextUtils.parseMetaIdTag("番剧[bgmid-456]", "bgmid"));
        // 削刮命名花括号格式
        assertEquals("123", TextUtils.parseMetaIdTag("剧名 {tmdbid-123}", "tmdbid"));
        // key 不匹配 / 无标记 / 空输入
        assertNull(TextUtils.parseMetaIdTag("剧名 [dbid-1]", "tmdbid"));
        assertNull(TextUtils.parseMetaIdTag("剧名 无标记", "dbid"));
        assertNull(TextUtils.parseMetaIdTag(null, "dbid"));
    }

    @Test
    void stripMetaIdTagsRemovesAllMarkers() {
        assertEquals("一念永恒-完结季  ", TextUtils.stripMetaIdTags("一念永恒-完结季 [dbid-37448094]"));
        String stripped = TextUtils.stripMetaIdTags("剧 [dbid-1][tmdbid-2]");
        assertFalse(stripped.contains("dbid"));
        assertFalse(stripped.contains("tmdbid"));
        assertNull(TextUtils.stripMetaIdTags(null));
    }

    @Test
    public void test() {
        String name = TextUtils.updateName("2010.虹猫蓝兔勇者归来.99集全.720p");
        log.info("{}", name);
    }

    @Test
    void testFixName() {
        String name = TextUtils.fixName("完美世界 第四季\u200E (2023) 第131-182集");
        log.info("{}", name);
    }

    @Test
    void stripLeadingNoise_stripsEmojiPrefix() {
        assertEquals("【万米危机】", TextUtils.stripLeadingNoise("·✅✅✅【万米危机】"));
    }

    @Test
    void stripLeadingNoise_stripsEmojiPrefixWithVariationSelector() {
        // Telegram emoji usually carry U+FE0F; the leading-noise class includes it.
        assertEquals("【万米危机】", TextUtils.stripLeadingNoise("·✅️✅️✅️【万米危机】"));
    }

    @Test
    void stripLeadingNoise_stripsAlternateCheckmark() {
        // Different channels use different check emoji (U+2714 here).
        assertEquals("【万米危机】", TextUtils.stripLeadingNoise("·✔️✔️✔️【万米危机】"));
    }

    @Test
    void fixNameAfterStripLeadingNoise_removesPrefixAndBrackets() {
        // end-to-end: the tg-search vod_name path composes stripLeadingNoise + fixName.
        assertEquals("万米危机", TextUtils.fixName(TextUtils.stripLeadingNoise("·✅✅✅【万米危机】")));
    }

    @Test
    void cleanMediaTitleKeepsYearAndRemovesQualityAndUpdateStatus() {
        assertEquals("Alpha", TextUtils.cleanMediaTitle("Alpha"));
        assertEquals("天才，女友(2026)", TextUtils.cleanMediaTitle("天才，女友(2026) 4K 更新至12集"));
        assertEquals("天才，女友(2026)", TextUtils.cleanMediaTitle(
                "天才，女友/天才女友 (2026) 4K 更新至12集【田曦薇/胡一天】"));
        assertEquals("天才，女友(2026)", TextUtils.cleanMediaTitle(
                "天才，女友/天才女友 (2026) [WEB-4K] [国语中字] [更至12集]"));
    }

    @Test
    public void testNumber() {
        String name = TextUtils.fixName("重紫 第10季");
        log.info("{}", name);
        name = TextUtils.fixName("重紫 第5季");
        log.info("{}", name);
        name = TextUtils.fixName("重紫 第15季");
        log.info("{}", name);
        name = TextUtils.fixName("重紫 第21季");
        log.info("{}", name);
        name = TextUtils.fixName("重紫 第30季");
        log.info("{}", name);
        name = TextUtils.fixName("重紫 第34季");
        log.info("{}", name);
    }

    @Test
    void collapseCjkSpacesJoinsDottedCjkNames() {
        // fixName turns the anti-detection dots in "百.花.杀" into spaces; collapse them
        // back so the real title "百花杀" (stored without spaces) can match.
        assertEquals("百花杀", TextUtils.collapseCjkSpaces("百 花 杀"));
        assertEquals("百花杀", TextUtils.collapseCjkSpaces("百花杀"));
        // spaces between Latin words are preserved
        assertEquals("Show Name", TextUtils.collapseCjkSpaces("Show Name"));
        // only space between two Han characters is removed; Latin<->Han space is kept
        assertEquals("X 战警", TextUtils.collapseCjkSpaces("X 战 警"));
        assertNull(TextUtils.collapseCjkSpaces(null));
    }


    @Test
    void loadShares() {
        List<Share> list = new ArrayList<>();

        Path path = Paths.get("data/alishare_list.txt");
        if (Files.exists(path)) {
            try {
                log.info("loading share list from file");
                List<String> lines = Files.readAllLines(path);
                for (String line : lines) {
                    String[] parts = line.trim().split("\\s+", 3);
                    Share share = new Share();
                    //share.setId(shareId++);
                    share.setPath(parts[0]);
                    share.setShareId(parts[1]);
                    share.setFolderId(parts[2]);
                    log.info("{}", share);
                    list.add(share);
                }
            } catch (IOException e) {
                log.warn("", e);
            }
        }
    }

    @Test
    void minDistance() {
        System.out.println(TextUtils.minDistance("03 - 200 A.M", "01"));
        System.out.println(TextUtils.minDistance("03 - 200 A.M", "02"));
        System.out.println(TextUtils.minDistance("03 - 200 A.M", "03"));
        System.out.println(TextUtils.minDistance("03 - 200 A.M", "04"));

        System.out.println(TextUtils.minDistance("03", "01"));
        System.out.println(TextUtils.minDistance("03", "02"));
        System.out.println(TextUtils.minDistance("03", "03"));
        System.out.println(TextUtils.minDistance("03", "04"));
    }

    // ---------- 季号解析与兜底(自 MediaSubscriptionCheckService 迁入) ----------

    @Test
    void parseTitleSeasonVariants() {
        assertEquals(2, TextUtils.parseTitleSeason("剧名 第二季 全12集"));
        assertEquals(2, TextUtils.parseTitleSeason("剧名 S02 更新至08"));
        assertEquals(2, TextUtils.parseTitleSeason("Show S02E05 1080p"));
        assertEquals(3, TextUtils.parseTitleSeason("Show Season 3"));
        assertEquals(12, TextUtils.parseTitleSeason("第12季 全24集"));
        assertEquals(4, TextUtils.parseTitleSeason("诛仙 第四季"));
        assertNull(TextUtils.parseTitleSeason("剧名 第1-2季 合集")); // 跨季区间不判定
        assertNull(TextUtils.parseTitleSeason("剧名 第一季+第二季 合集"));
        assertNull(TextUtils.parseTitleSeason("剧名 更新至08集"));
        assertNull(TextUtils.parseTitleSeason(null));
    }

    @Test
    void parseChineseNumberConversion() {
        assertEquals(1, TextUtils.parseChineseNumber("一"));
        assertEquals(4, TextUtils.parseChineseNumber("四"));
        assertEquals(10, TextUtils.parseChineseNumber("十"));
        assertEquals(11, TextUtils.parseChineseNumber("十一"));
        assertEquals(21, TextUtils.parseChineseNumber("二十一"));
        assertEquals(0, TextUtils.parseChineseNumber("百"));
    }

    @Test
    void stripSeasonSuffixYieldsBareShowName() {
        // 缺陷 5:剧名带季号后缀时,裸剧名必须能被还原出来参与匹配
        assertEquals("诛仙", TextUtils.stripSeasonSuffix("诛仙 第四季"));
        assertEquals("苍兰诀", TextUtils.stripSeasonSuffix("苍兰诀 第2季"));
        assertEquals("Show", TextUtils.stripSeasonSuffix("Show Season 3"));
        assertEquals("Show", TextUtils.stripSeasonSuffix("Show S02"));
        // 无季号标记:原样返回
        assertEquals("诛仙", TextUtils.stripSeasonSuffix("诛仙"));
        // 剥完为空:不返回空串(否则会变成"匹配一切")
        assertEquals("第四季", TextUtils.stripSeasonSuffix("第四季"));
        assertNull(TextUtils.stripSeasonSuffix(null));
    }

    @Test
    void resolveSeasonFallsBackToNameWhenDefaulted() {
        // 缺陷 8:片单追更硬编码 season=1,剧名却写着第四季 —— 以剧名为准
        assertEquals(4, TextUtils.resolveSeason(1, "诛仙 第四季"));
        assertEquals(4, TextUtils.resolveSeason(null, "诛仙 第四季"));
        // 用户显式指定的非默认值优先(不猜测用户意图)
        assertEquals(2, TextUtils.resolveSeason(2, "诛仙 第四季"));
        // 剧名无季号标记:保持原值
        assertEquals(1, TextUtils.resolveSeason(1, "诛仙"));
        assertNull(TextUtils.resolveSeason(null, "诛仙"));
        assertNull(TextUtils.resolveSeason(null, null));
    }
}
