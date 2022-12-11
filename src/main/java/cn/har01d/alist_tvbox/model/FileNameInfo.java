package cn.har01d.alist_tvbox.model;

import lombok.Data;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class FileNameInfo implements Comparable<FileNameInfo> {
    private static final Comparator<Object> comparator = Collator.getInstance(java.util.Locale.CHINA);
    private static final Pattern NUMBER = Pattern.compile("(\\d+\\.?\\d*)");

    private final String name;
    private final List<String> prefixes = new ArrayList<>();
    private final List<Double> numbers = new ArrayList<>();

    public FileNameInfo(String name) {
        this.name = name;
        Matcher matcher = NUMBER.matcher(name);
        while (matcher.find()) {
            this.prefixes.add(name.substring(0, matcher.start()));
            this.numbers.add(Double.parseDouble(matcher.group(1)));
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
        return comparator.compare(name, o.getName());
    }
}
