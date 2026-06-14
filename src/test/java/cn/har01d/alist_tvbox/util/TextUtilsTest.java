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