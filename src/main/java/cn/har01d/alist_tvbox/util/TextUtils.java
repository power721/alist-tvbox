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

    public static boolean isChineseChar(int c) {
        return c >= 0x4E00 && c <= 0x9FA5;
    }

    public static boolean isEnglishChar(int c) {
        return (c >= 'A' && c <= 'Z');
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

        newName = newName.replace("普通话版", "").replace("日语版", "").replace("系列合集", "").replace("合集", "");

        Matcher m = NUMBER.matcher(newName);
        if (m.find()) {
            String text = m.group(1);
            if (newName.charAt(m.start() - 1) != ' ') {
                newName = newName.replace("第" + text + "季", " 第" + text + "季");
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
            newName = newName.replace(text, newNum);
        } else {
            m = NUMBER2.matcher(newName);
            if (m.find()) {
                String text = m.group(1);
                if (newName.charAt(m.start() - 1) != ' ') {
                    newName = newName.replace(text, " " + text);
                }
            }
        }

        index = newName.indexOf('.');
        if (index > 0 && newName.substring(0, index).codePoints().allMatch(Character::isDigit)) {
            newName = newName.substring(index + 1);
        }

        newName = newName.trim();
        if (!name.equals(newName)) {
            log.debug("name: {} -> {}", name, newName);
        }
        return newName;
    }

    public static String updateName(String name) {
        if (TextUtils.isEnglishChar(name.codePointAt(0)) && TextUtils.isChineseChar(name.codePointAt(1))) {
            name = name.substring(1);
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
