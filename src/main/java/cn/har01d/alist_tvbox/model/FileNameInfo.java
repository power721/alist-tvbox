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

    private final String name;
    private final List<String> prefixes = new ArrayList<>();
    private final List<Double> numbers = new ArrayList<>();

    public FileNameInfo(String name) {
        this.name = name;
        Matcher matcher = SEASON.matcher(name);
        if (matcher.find()) {
            this.prefixes.add("");
            this.prefixes.add("");
            this.numbers.add(parseNumber(matcher.group(2)));
            this.numbers.add(parseNumber(matcher.group(1)));
        } else {
            matcher = NUMBER.matcher(name);
            while (matcher.find()) {
                this.prefixes.add(name.substring(0, matcher.start()));
                this.numbers.add(parseNumber(matcher.group(1)));
            }
        }
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
