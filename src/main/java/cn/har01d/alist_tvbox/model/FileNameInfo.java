package cn.har01d.alist_tvbox.model;

import lombok.Data;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class FileNameInfo {
    private static final Pattern NUMBER = Pattern.compile("(\\d+\\.?\\d*)");

    private final String name;
    private final String prefix;
    private final Double number;

    public FileNameInfo(String name) {
        this.name = name;
        Matcher matcher = NUMBER.matcher(name);
        if (matcher.find()) {
            this.prefix = name.substring(0, matcher.start());
            this.number = Double.parseDouble(matcher.group(1));
        } else {
            this.prefix = name;
            this.number = Double.NaN;
        }
    }
}
