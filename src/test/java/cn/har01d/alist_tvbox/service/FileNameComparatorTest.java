package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.model.FileNameInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

class FileNameComparatorTest {

    @Test
    void sort() {
        List<String> list = Arrays.asList("04  1  10  2  20  20.0 03.1 20.1  2.1  3 test1 config 03 t1 中文1 中文2 中文10 中文3.4 中文0.txt t10  t11  t2  t2.2  t3  t4.4.txt  t4.txt".split("\\s+"));
        List<FileNameInfo> files = list.stream().map(FileNameInfo::new).collect(Collectors.toList());
        System.out.println(files);
        Collections.sort(files);
        System.out.println(files);
        list.sort(Comparator.comparing(FileNameInfo::new));
        System.out.println(list);
    }

    @Test
    void sortX() {
        List<String> list = Arrays.asList("12、银河铁道系列", "32、神风怪盗贞德", "8、阿基拉", "三国演义 (2009)", "刀剑神域-序列之争 (2017)", "圣斗士星矢", "失落的宇宙", "少女革命", "中华小当家全集 国粤日语中字1080P", "夜行神龙二季全字幕版", "woshixiaotiantian", "太空丹迪(2014) [两季全,高码收藏版]", "12、十二国记", "8、凡尔赛玫瑰", "32、魔神凯撒", "22、潮与虎", "在下坂本，有何贵干？ (2016)", "1、相聚一刻", "东方神娃(2005) [高清修复版]", "10、甜心战士F", "三毛合集", "4、无限的未知", "攻壳机动队2-无罪 (2004)", "1、樱花大战", "23、棋魂", "8、秀逗魔导师", "吹响吧！上低音号", "33、交响诗篇", "《美少女战士 第1-2季+Crystal 》(1992) 国语配音", "宇宙兄弟", "42、花田少年史", "[VCB-Studio＆mawen1250] 龙与虎 10-bit 1080p FLAC BDRip [Reseed Fin]", "我的女神(2005) [台日双语,高码收藏版]", "攻壳机动队(1995) [多版本TV与剧场版系列合集]", "回忆三部曲 (1995)", "10、未来少年柯南", "小和尚 1080P修复版", "星之声 (2002)", "1、全职猎人", "乱马(1989) [粤日双语，共161集]", "悠久之翼(2007) [两季全]", "奇奇颗颗历险记(2006)", "天上天下全集 国粤日语中字1080P", "攻壳机动队SAC 2nd GIG-个别的十一人 (2006)", "17、第一神拳", "Disney动画《怪诞小镇》", "46、爆漫王", "2、天空之艾斯嘉科尼", "叛逆的鲁鲁修", "10、纯情房东俏房客", "凉宫春日", "10、橙路", "少年骇客(2005) [全四季,国语]", "未来日记(2011) [高码收藏版]", "1981版阿拉蕾 104集 辽艺国语", "乒乓 [2014]［BDrip][1080p]", "千年女优 (2002)", "替身", "全职猎人2011tv高码版", "暗杀教室", "1、超时空要塞", "怪盗圣少女(1995)", "5、魔法骑士", "十二生肖守护神(1995) [国语标清]", "[1985-1996] Dirty Pair_搞怪拍檔(ダーティペア)_TV.OVA.MOVIE", "乌龙派出所(1996) [国语+剧场版+特别版]", "Q版三国之三小强 (2010)", "18、人造人009", "娜娜 (2006)", "天书奇谭4K [无水印版]", "剑风传奇(1997) [97版+16版+剧场版合集]", "四月一日灵异事件簿 [高码收藏版,两季全+剧场版]", "攻壳机动队 (1995)", "16、妖兽都市", "合集 中二病也要谈恋爱 [中二病でも恋がしたい！]", "23、笑面推销员", "幸运星", "10、横山光辉三国志", "Q版三国之刘关张1080P (2003)", "命运石之门0 (2018)", "哆啦A梦大山版收藏整理版", "43、魔法少女小麦", "大闹天宫 (2012)", "11、红发少女安妮", "三眼神童 台配繁中 全48集1080P", "东京食尸鬼", "家庭教师(2006) [203集全]", "大剑(2007) [日语中字]", " 日常nichijou 全集日语中字1080P", " 夏目友人帳 TV+OVA+剧场版 Natsume Yuujinchou [BDrip 1080p HEVC FLAC][10bits][DDD]", "4、超音战士", "命运石之门 (2011)", "10、逮捕令", "东京教父 (2003)", "1993.怪医黑杰克", "夜行神龙第一季 国粤日英语中字1080P", "23、GS美神", "55、超时空要塞F", "卡门圣地亚哥", "10、罗密欧的蓝天");
//        List<FileNameInfo> files = list.stream().map(FileNameInfo::new).collect(Collectors.toList());
//        System.out.println(files);
//        Collections.sort(files);
//        System.out.println(files);
        list.sort(Comparator.comparing(FileNameInfo::new));
        System.out.println(list);
    }

    @Test
    void sort0() {
        List<String> list = Arrays.asList("中文 第十二集 测试 Test Hello 第十三集 第五集 第零集 第一集 第二集 第四集 第六集 第三集 第七集 第八集 第九集 第十集 第十一集".split("\\s+"));
        List<FileNameInfo> files = list.stream().map(FileNameInfo::new).collect(Collectors.toList());
        System.out.println(files);
        Collections.sort(files);
        System.out.println(files);
        list.sort(Comparator.comparing(FileNameInfo::new));
        System.out.println(list);
    }

    @Test
    void sort1() {
        List<String> list = Arrays.asList("S02E01 中文 3 03 test S01E01 test1 测试".split("\\s+"));
        List<FileNameInfo> files = list.stream().map(FileNameInfo::new).collect(Collectors.toList());
        System.out.println(files);
        Collections.sort(files);
        System.out.println(files);
        list.sort(Comparator.comparing(FileNameInfo::new));
        System.out.println(list);
    }

    @Test
    void sort2() {
        List<String> list = Arrays.asList("03.1 3 03".split("\\s+"));
        List<FileNameInfo> files = list.stream().map(FileNameInfo::new).collect(Collectors.toList());
        System.out.println(files);
        Collections.sort(files);
        System.out.println(files);
        list.sort(Comparator.comparing(FileNameInfo::new));
        System.out.println(list);
    }

    @Test
    void sort3() {
        List<String> list = Arrays.asList("T3 T3A T3.0 T3.0X".split("\\s+"));
        List<FileNameInfo> files = list.stream().map(FileNameInfo::new).collect(Collectors.toList());
        System.out.println(files);
        Collections.sort(files);
        System.out.println(files);
        list.sort(Comparator.comparing(FileNameInfo::new));
        System.out.println(list);
    }

    @Test
    void sort4() {
        List<String> list = Arrays.asList("S01E02 S02E01 S02E02 S01E01 S01E03 S01E04".split("\\s+"));
        List<FileNameInfo> files = list.stream().map(FileNameInfo::new).collect(Collectors.toList());
        System.out.println(files);
        Collections.sort(files);
        System.out.println(files);
        list.sort(Comparator.comparing(FileNameInfo::new));
        List<String> expected = Arrays.asList("S01E01 S01E02 S01E03 S01E04 S02E01 S02E02".split("\\s+"));
        Assertions.assertEquals(expected, list);
        System.out.println(list);
    }

    @Test
    void sort5() {
        List<String> list = Arrays.asList("测试（2022）S01E02 测试（2022）S02E01 测试（2022）S02E02 测试（2022）S01E1 测试（2022）S01E03 测试（2022）S01E04".split("\\s+"));
        List<FileNameInfo> files = list.stream().map(FileNameInfo::new).collect(Collectors.toList());
        System.out.println(files);
        Collections.sort(files);
        System.out.println(files);
        list.sort(Comparator.comparing(FileNameInfo::new));
        List<String> expected = Arrays.asList("测试（2022）S01E1 测试（2022）S01E02 测试（2022）S01E03 测试（2022）S01E04 测试（2022）S02E01 测试（2022）S02E02".split("\\s+"));
        Assertions.assertEquals(expected, list);
        System.out.println(list);
    }

    @Test
    void sort6() {
        List<String> list = Arrays.asList("测试（2022）S01E02 测试（2022）S02E01 测试（2022）S02E02 测试（2022）S01E1 测试（2022）S01E03_1 测试（2022）S01E014".split("\\s+"));
        List<FileNameInfo> files = list.stream().map(FileNameInfo::new).collect(Collectors.toList());
        System.out.println(files);
        Collections.sort(files);
        System.out.println(files);
        list.sort(Comparator.comparing(FileNameInfo::new));
        List<String> expected = Arrays.asList("测试（2022）S01E1 测试（2022）S01E02 测试（2022）S01E03_1 测试（2022）S01E014 测试（2022）S02E01 测试（2022）S02E02".split("\\s+"));
        Assertions.assertEquals(expected, list);
        System.out.println(list);
    }

    @Test
    void sort7() {
        List<String> list = Arrays.asList("001 002 003 004 010 011 012 020 021 022 023 030 100 101 102 110 111 112".split("\\s+"));
        List<FileNameInfo> files = list.stream().map(FileNameInfo::new).collect(Collectors.toList());
        System.out.println(files);
        Collections.sort(files);
        System.out.println(files);
        list.sort(Comparator.comparing(FileNameInfo::new));
        List<String> expected = Arrays.asList("001 002 003 004 010 011 012 020 021 022 023 030 100 101 102 110 111 112".split("\\s+"));
        Assertions.assertEquals(expected, list);
        System.out.println(list);
    }

    @Test
    void sort8() {
        List<String> list = new ArrayList<>(List.of("仙逆.2023.S01E81.第 81 集.2160p.WEB-DL.DDP2.0.H265.mkv", "仙逆2023.S01E80.第 80 集.2160p.WEB-DL.DDP2.0.H265.mkv", "仙逆2023.S01E01.离乡.2160p.WEB-DL.DDP2.0.H265.mkv"));
        List<FileNameInfo> files = list.stream().map(FileNameInfo::new).collect(Collectors.toList());
        System.out.println(files);
        Collections.sort(files);
        System.out.println(files);
        list.sort(Comparator.comparing(FileNameInfo::new));
        List<String> expected = List.of("仙逆2023.S01E01.离乡.2160p.WEB-DL.DDP2.0.H265.mkv", "仙逆2023.S01E80.第 80 集.2160p.WEB-DL.DDP2.0.H265.mkv", "仙逆.2023.S01E81.第 81 集.2160p.WEB-DL.DDP2.0.H265.mkv");
        Assertions.assertEquals(expected, list);
        System.out.println(list);
    }

    @Test
    void sort9() {
        List<String> list = new ArrayList<>(List.of("20250321-第1期下.mp4", "20250321-第1期中.mp4", "20250321-第1期上.mp4"));
        List<FileNameInfo> files = list.stream().map(FileNameInfo::new).collect(Collectors.toList());
        System.out.println(files);
        Collections.sort(files);
        System.out.println(files);
        list.sort(Comparator.comparing(FileNameInfo::new));
        List<String> expected = List.of("20250321-第1期上.mp4", "20250321-第1期中.mp4", "20250321-第1期下.mp4");
        Assertions.assertEquals(expected, list);
        System.out.println(list);
    }

    @Test
    void sort10() {
        List<String> list = new ArrayList<>(List.of("吞噬星空 - S01E39 - 第 39 集.mkv", "吞噬星空 - S01E101 - 第 101 集.mkv", "吞噬星空 - S01E40 - 第 40 集.mkv"));
        List<FileNameInfo> files = list.stream().map(FileNameInfo::new).collect(Collectors.toList());
        System.out.println(files);
        Collections.sort(files);
        System.out.println(files);
        list.sort(Comparator.comparing(FileNameInfo::new));
        List<String> expected = List.of("吞噬星空 - S01E39 - 第 39 集.mkv", "吞噬星空 - S01E40 - 第 40 集.mkv", "吞噬星空 - S01E101 - 第 101 集.mkv");
        Assertions.assertEquals(expected, list);
        System.out.println(list);
    }

    @Test
    void sort11() {
        List<String> list = new ArrayList<>(List.of("假面骑士加布 普通话 第1集.mp4", "假面骑士加布 普通话 第2集.mp4", "假面骑士加布 普通话 第3集.mp4", "假面骑士加布普通话 第36集.mp4", "假面骑士加布普通话 第37集.mp4", "假面骑士加布普通话 第38集.mp4", "假面骑士加布普通话 第39集.mp4", "假面骑士加布普通话 第40集.mp4", "假面骑士加布普通话 第41集.mp4", "假面骑士加布普通话 第43集.mp4", "假面骑士加布普通话 第44集.mp4", "假面骑士加布普通话 第45集.mp4"));
        List<FileNameInfo> files = list.stream().map(FileNameInfo::new).collect(Collectors.toList());

        for (int i = 0; i < files.size(); i++) {
            for (int j = 0; j < files.size(); j++) {
                int ret1 = files.get(i).compareTo(files.get(j));
                if (ret1 == 0) {
                    continue;
                }
                int ret2 = files.get(j).compareTo(files.get(i));
                Assertions.assertEquals(ret2, -ret1);
            }
        }
        System.out.println(files);
        Collections.sort(files);
        System.out.println(files);

        list.sort(Comparator.comparing(FileNameInfo::new));
        System.out.println(list);

    }

}
