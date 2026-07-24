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
}