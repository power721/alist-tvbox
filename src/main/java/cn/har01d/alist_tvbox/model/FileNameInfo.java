package cn.har01d.alist_tvbox.model;

import lombok.Data;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class FileNameInfo implements Comparable<FileNameInfo> {
    private static final Comparator<Object> comparator = Collator.getInstance(java.util.Locale.CHINA);
    private static final List<String> NUMBERS = Arrays.asList("零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十");
    private static final List<String> CHAPTER = Arrays.asList("上", "中", "下");
    private static final Pattern NUMBER = Pattern.compile("(\\d+\\.?\\d*)");
    private static final Pattern SEASON = Pattern.compile("S(\\d{1,3})E(\\d{1,3})");
    // variety-show air dates: "2026.07.02-xxx", "2026年7月2日", "2026-07-02", "2026/7/2", "2026_07_02"
    private static final Pattern DATE =
            Pattern.compile("(20\\d{2})[年._\\-/](\\d{1,2})[月._\\-/](\\d{1,2})日?");
    // compact form "20260708.xxx"; lookarounds avoid matching inside longer digit runs
    private static final Pattern COMPACT_DATE =
            Pattern.compile("(?<!\\d)(20\\d{2})(\\d{2})(\\d{2})(?!\\d)");
    private static final int[] DAYS = {31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    private final String name;
    private final Long date;
    private final List<String> prefixes = new ArrayList<>();
    private final List<Double> numbers = new ArrayList<>();

    public FileNameInfo(String name) {
        name = name.replaceAll(" ", "");
        this.name = name;
        int index = name.lastIndexOf('.');
        if (index != -1) {
            name = name.substring(0, index);
        }
        Matcher matcher = DATE.matcher(name);
        Long date = null;
        if (!matcher.find()) {
            matcher = COMPACT_DATE.matcher(name);
            if (!matcher.find()) {
                matcher = null;
            }
        }
        if (matcher != null) {
            date = parseDate(matcher);
            if (date != null) {
                name = name.substring(0, matcher.start()) + name.substring(matcher.end());
            }
        }
        this.date = date;
        Matcher seasonMatcher = SEASON.matcher(name);
        if (seasonMatcher.find()) {
            this.prefixes.add("");
            this.prefixes.add("");
            this.numbers.add(parseNumber(seasonMatcher.group(2)));
            this.numbers.add(parseNumber(seasonMatcher.group(1)));
        } else {
            Matcher numberMatcher = NUMBER.matcher(name);
            while (numberMatcher.find()) {
                this.prefixes.add(name.substring(0, numberMatcher.start()));
                this.numbers.add(parseNumber(numberMatcher.group(1)));
            }
        }
    }

    /** Returns yyyyMMdd, or null when month/day are out of range (then the digits stay
     *  ordinary numbers, e.g. "2026.99.99" or a 8-digit episode id). */
    private static Long parseDate(Matcher matcher) {
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        if (month < 1 || month > 12 || day < 1 || day > DAYS[month - 1]) {
            return null;
        }
        if (month == 2 && day == 29 && !java.time.Year.isLeap(year)) {
            return null;
        }
        return year * 10000L + month * 100 + day;
    }

    private Double parseNumber(String text) {
        try {
            if (text.startsWith("0") && text.length() > 1) {
                text = text.substring(1);
            }
            return Double.parseDouble(text);
        } catch (Exception e) {
            int index = NUMBERS.indexOf(text.substring(0, 1));
            if (index >= 0) {
                if (index == 10 && text.length() == 2) {
                    index = NUMBERS.indexOf(text.substring(1, 2));
                    return (double) (10 + index);
                }
                return (double) index;
            }
            return Double.NaN;
        }
    }

    @Override
    public int compareTo(FileNameInfo o) {
        if (date != null && o.date != null && !date.equals(o.date)) {
            return Long.compare(date, o.date);
        }

        int n = Math.min(prefixes.size(), o.prefixes.size());
        for (int i = n - 1; i >= 0; i--) {
            if (prefixes.get(i).equals(o.prefixes.get(i))) {
                int result = Double.compare(numbers.get(i), o.numbers.get(i));
                if (result != 0) {
                    return result;
                }
            }
        }

        int i = index(name);
        if (i > -1) {
            if (index(o.getName()) == i) {
                String name1 = name.substring(0, i) + name.substring(i + 1);
                String name2 = o.getName().substring(0, i) + o.getName().substring(i + 1);
                if (name1.equals(name2)) {
                    return CHAPTER.indexOf(name.substring(i, i + 1)) - CHAPTER.indexOf(o.getName().substring(i, i + 1));
                }
            }
        }

        return comparator.compare(name, o.getName());
    }

    private int index(String name) {
        for (String ch : CHAPTER) {
            int index = name.indexOf(ch);
            if (index > -1) {
                return index;
            }
        }
        return -1;
    }
}
