package cn.har01d.alist_tvbox.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class TextUtils {

    private static final List<String> NUMBERS = Arrays.asList("零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十");
    private static final Pattern NUMBER = Pattern.compile("第(\\d+\\.?\\d*)季");
    private static final Pattern NUMBER2 = Pattern.compile("(第.+季)");
    private static final Pattern NUMBER3 = Pattern.compile(" ?(S\\d{1,2})");
    private static final Pattern NUMBER4 = Pattern.compile("(\\.?\\d+集全?)");
    private static final Pattern NUMBER5 = Pattern.compile("(\\.[0-9-]+季(\\+番外)?)");
    private static final Pattern NUMBER6 = Pattern.compile("(.更新至\\d+)");
    private static final Pattern NAME1 = Pattern.compile("^【(.+)】$");
    private static final Pattern NAME2 = Pattern.compile("^\\w (.+)\\s+\\(\\d{4}\\).*");
    private static final Pattern NAME3 = Pattern.compile("^\\w (.+)\\.\\d{4} .+");

    public static boolean isChineseChar(int c) {
        return c >= 0x4E00 && c <= 0x9FA5;
    }

    public static boolean isChineseChar2(int c) {
        return (c >= 0x4E00 && c <= 0x9FA5) || (c >= '0' && c <= '9') || c == '：';
    }

    public static boolean isEnglishChar(int c) {
        return (c >= 'A' && c <= 'Z');
    }

    public static boolean isAlphabetic(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    public static boolean isChinese(String text) {
        return text.codePoints().allMatch(TextUtils::isChineseChar2);
    }

    public static boolean isEnglish(String text) {
        return text.codePoints().allMatch(TextUtils::isAlphabetic);
    }

    public static String fixName(String name) {
        int index = name.lastIndexOf('/');
        String newName = name.trim();
        if (index > -1) {
            newName = newName.substring(index + 1);
        }

        if (newName.endsWith(")")) {
            index = newName.lastIndexOf('(');
            if (index > 0) {
                newName = newName.substring(0, index);
            }
        }

        if (newName.endsWith("）")) {
            index = newName.lastIndexOf('（');
            if (index > 0) {
                newName = newName.substring(0, index);
            }
        }

        int start = newName.indexOf('《');
        if (start > -1) {
            int end = newName.indexOf('》', index + 1);
            if (end > start) {
                newName = newName.substring(start + 1, end);
            }
        }

        String[] parts = newName.replace("[", "").replace("]", " ").split("[. ]+");
        if (parts.length > 4 && (parts[0].length() > 1 && parts[1].length() > 1 && isChinese(parts[0]) && isEnglish(parts[1]))) {
            newName = parts[0];

        }

        newName = newName
                .replace("稀有国日双语版", " ")
                .replace("国日双语", " ")
                .replace("台日双语", " ")
                .replace("普通话版", " ")
                .replace("双语版", " ")
                .replace("外挂中文字幕", " ")
                .replace("中英字幕", " ")
                .replace("日语无字", " ")
                .replace("双语", " ")
                .replace("国语版", " ")
                .replace("国语", " ")
                .replace("国英", " ")
                .replace("中配", " ")
                .replace("粤语", " ")
                .replace("台配", " ")
                .replace(".中日双语", " ")
                .replace(".日语", " ")
                .replace("日语", " ")
                .replace("日语版", " ")
                .replace("系列合集", " ")
                .replace("大合集", " ")
                .replace("合集", " ")
                .replace(".内嵌", " ")
                .replace(".日配", " ")
                .replace(".720p", " ")
                .replace(".720P", " ")
                .replace("720P", " ")
                .replace(".1080p", " ")
                .replace(".1080P", " ")
                .replace("HD1080P", " ")
                .replace("1080p", " ")
                .replace("1080P", " ")
                .replace(".2160p", " ")
                .replace("2160p", " ")
                .replace("2160P", " ")
                .replace(".4k", " ")
                .replace("4K REMUX", " ")
                .replace("REMUX", " ")
                .replace("REMXU", " ")
                .replace("4K HDR", " ")
                .replace("4K版", " ")
                .replace("BluRay", " ")
                .replace("x265", " ")
                .replace("x264", " ")
                .replace("4K收藏版", " ")
                .replace("4K双版本", " ")
                .replace("[4K]", " ")
                .replace("4K", " ")
                .replace(".超高码率", " ")
                .replace("蓝光高清", " ")
                .replace("IMAX", " ")
                .replace("+外传", " ")
                .replace("+番外篇", " ")
                .replace("+番外", " ")
                .replace("+漫画", " ")
                .replace("+剧场版", " ")
                .replace("剧场版", " ")
                .replace("+真人版", " ")
                .replace("真人版", " ")
                .replace("动漫+真人", " ")
                .replace("导演剪辑版", " ")
                .replace("高码收藏版", " ")
                .replace("收藏版", " ")
                .replace("经典老剧", " ")
                .replace("未删减", " ")
                .replace("+Q版", " ")
                .replace("+OVA", " ")
                .replace("+SP", " ")
                .replace("+前传", " ")
                .replace("【美剧】", " ")
                .replace("【法国】", " ")
                .replace("【西班牙】", " ")
                .replace("【俄罗斯】", " ")
                .replace("【英剧】", " ")
                .replace("【爱情片】", " ")
                .replace("【纪录片】", " ")
                .replace("意大利", " ")
                .replace("恐怖剧", " ")
                .replace("科幻剧", " ")
                .replace("-系列", " ")
                .replace("系列", " ")
                .replace("全集", " ")
                .replace("中字", " ")
                .replace("无字", " ")
                .replace("GOTV", " ")
                .replace("DVD版", " ")
                .replace("[", " ")
                .replace("]", " ")
                .replace("【", " ")
                .replace("】", " ")
                .replace("（", "(")
                .replace("）", ")")
                .replace("《", " ")
                .replace("》", " ")
                .replace(",", " ")
                .replace("..", " ")
                .replace("|", " ")
                .replace("Ⅰ", "第一季")
                .replace("Ⅱ", "第二季")
                .replace("~", "");

        Matcher m = NAME1.matcher(newName);
        if (m.matches()) {
            newName = m.group(1);
        }

        m = NAME2.matcher(newName);
        if (m.matches()) {
            newName = m.group(1);
        }

        m = NAME3.matcher(newName);
        if (m.matches()) {
            newName = m.group(1);
        }

        newName = newName
                .replaceAll("(No.\\d+ ?)", " ")
                .replaceAll("\\d+、", " ")
                .replaceAll("\\.\\d{4}", " ")
                .replaceAll(" \\d{4}", " ")
                .replaceAll("\\s*全\\d+集", " ")
                .replaceAll("第?\\d-\\d+([季部])", " ")
                .replaceAll(".([季部])全", " ")
                .replaceAll("[0-9.]+GB", " ")
                .replaceAll("豆瓣评分：?[0-9.]+", " ")
                .replaceAll("\\(\\d{4}\\)", " ")
        ;

        m = NUMBER.matcher(newName);
        if (m.find()) {
            String text = m.group(1);
            if (m.start() > 1 && newName.charAt(m.start() - 1) != ' ') {
                newName = newName.replace("第" + text + "季", " 第" + text + "季");
            }
            String newNum = number2text(text);
            newName = newName.replace(text, newNum);
        } else {
            m = NUMBER2.matcher(newName);
            if (m.find()) {
                String text = m.group(1);
                if (m.start() > 0 && newName.charAt(m.start() - 1) != ' ') {
                    newName = newName.replace(text, " " + text);
                }
            } else {
                m = NUMBER3.matcher(newName);
                if (m.find()) {
                    String text = m.group(1);
                    String newNum = number2text(text.substring(1));
                    newName = newName.replace(text, " 第" + newNum + "季");
                }
            }
        }

        m = NUMBER4.matcher(newName);
        if (m.find()) {
            String text = m.group(1);
            newName = newName.replace(text, "");
        } else {
            m = NUMBER5.matcher(newName);
            if (m.find()) {
                String text = m.group(1);
                newName = newName.replace(text, "");
            } else {
                m = NUMBER6.matcher(newName);
                if (m.find()) {
                    String text = m.group(1);
                    newName = newName.replace(text, "");
                }
            }
        }

        index = newName.indexOf('.');
        if (index > 0 && newName.substring(0, index).codePoints().allMatch(Character::isDigit)) {
            newName = newName.substring(index + 1);
        }

        if (newName.endsWith(".")) {
            newName = newName.substring(0, newName.length() - 1);
        }

        newName = newName.replaceAll("\\s+", " ").trim();
        if (!name.equals(newName)) {
            log.debug("name: {} -> {}", name, newName);
        }
        return newName;
    }

    private static String number2text(String text) {
        if (text.startsWith("0") && text.length() > 1) {
            text = text.substring(1);
        }
        int num = Integer.parseInt(text);
        String newNum;
        if (num <= 10) {
            newNum = NUMBERS.get(num);
        } else if (num < 20) {
            newNum = "十" + NUMBERS.get(num % 10);
        } else if (num % 10 == 0) {
            newNum = NUMBERS.get(num / 10) + "十";
        } else {
            newNum = NUMBERS.get(num / 10) + "十" + NUMBERS.get(num % 10);
        }
        return newNum;
    }

    public static String updateName(String name) {
        int n = name.length();
        if (n > 1 && (TextUtils.isEnglishChar(name.codePointAt(0)) && TextUtils.isChineseChar(name.codePointAt(1)))) {
                name = name.substring(1);
                n = name.length();

        }

        if (n > 2) {
            if (TextUtils.isEnglishChar(name.codePointAt(0))
                    && (name.charAt(1) == ' ' || name.charAt(1) == '.')
                    && TextUtils.isChineseChar(name.codePointAt(2))) {
                name = name.substring(2);
                n = name.length();
            }

            if (name.charAt(n - 1) == '1' && TextUtils.isChineseChar(name.codePointAt(n - 2))) {
                name = name.substring(0, n - 1);
            }
        }

        if (name.endsWith(" 第一季")) {
            name = name.substring(0, name.length() - 4);
        }

        int start = name.indexOf('.');
        if (start == 4) {
            try {
                Integer.parseInt(name.substring(0, 4));
                int end = name.indexOf('.', start + 1);
                if (end > start + 1) {
                    name = name.substring(start + 1, end);
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return name;
    }

    public static String truncate(CharSequence charSequence, int threshold) {
        return charSequence.length() > threshold ? charSequence.subSequence(0, threshold) + "..." : charSequence.toString();
    }

}
