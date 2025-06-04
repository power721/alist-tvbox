package cn.har01d.alist_tvbox.dto.tg;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class Message {
    private static final Pattern LINK = Pattern.compile("(https?:\\/\\/\\S+)");
    private int id;
    private Instant time;
    @JsonIgnore
    private String content;
    private String channel;
    private String name;
    private String type;
    private String link;

    public Message() {
    }

    public Message(String channel, String content, String time) {
        parseTime(time);
        this.content = content;
        this.link = parseLink();
        this.name = parseName();
        this.channel = channel;
    }

    private void parseTime(String time) {
        if (time == null) {
            this.time = Instant.now();
            return;
        }

        try {
            this.time = Instant.parse(time);
        } catch (Exception e) {
            this.time = Instant.now();
        }
    }

    public Message(String channel, telegram4j.tl.BaseMessage message) {
        this.id = message.id();
        this.time = Instant.ofEpochSecond(message.date());
        this.content = message.message();
        this.link = parseLink();
        this.name = parseName();
        this.channel = channel;
    }

    public Message(String channel, telegram4j.tl.BaseMessage message, String link) {
        this.id = message.id();
        this.time = Instant.ofEpochSecond(message.date());
        this.content = message.message();
        this.link = link;
        this.type = parseType(link);
        this.name = parseName();
        this.channel = channel;
    }

    public Message(SearchResult message, String link) {
        this.id = message.getId();
        this.time = Instant.ofEpochSecond(message.getTime());
        this.content = message.getContent();
        this.link = link;
        this.type = parseType(link);
        this.name = parseName();
        this.channel = message.getChannel();
    }

    public String toPgString() {
        return time.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "\t" + channel + "\t" + content.replace('\n', ' ') + "\t" + id;
    }

    public String toZxString() {
        return link + "$$" + name;
    }

    private String parseName() {
        String[] lines = content.split("\n");
        String line = lines[0];
        if (line.startsWith("#") && lines.length > 1) {
            line = lines[1];
        }
        if (line.startsWith("https://") && lines.length > 1) {
            line = lines[1];
        }
        String name = line.replace("名称：", "").replace("名称:", "").replace("资源标题：", "");
        int index = name.indexOf("描述：");
        if (index > 0) {
            return name.substring(0, index);
        }
        index = name.indexOf("简介：");
        if (index > 0) {
            return name.substring(0, index);
        }
        return name;
    }

    private String parseLink() {
        Matcher m = LINK.matcher(content);
        while (m.find()) {
            String link = fixLink(m.group(1));
            type = parseType(link);
            if (type != null) {
                return link;
            }
        }
        return null;
    }

    public static List<String> parseLinks(String content) {
        List<String> links = new ArrayList<>();
        Matcher m = LINK.matcher(content);
        while (m.find()) {
            String link = fixLink(m.group(1));
            String type = parseType(link);
            if (type != null) {
                links.add(link);
            }
        }
        return links;
    }

    private static String fixLink(String link) {
        if (link.endsWith("**")) {
            return link.substring(0, link.length() - 2);
        }
        return link;
    }

    private static String parseType(String link) {
        if (link.contains("alipan.com") || link.contains("aliyundrive.com")) {
            return "0";
        }
        if (link.contains("mypikpak.com")) {
            return "1";
        }
        if (link.contains("xunlei.com")) {
            return "2";
        }
        if (link.contains("123pan.com") || link.contains("123684.com") || link.contains("123865.com") || link.contains("123912.com")) {
            return "3";
        }
        if (link.contains("quark.cn")) {
            return "5";
        }
        if (link.contains("uc.cn")) {
            return "7";
        }
        if (link.contains("115.com") || link.contains("115cdn.com")) {
            return "8";
        }
        if (link.contains("189.cn")) {
            return "9";
        }
        if (link.contains("139.com")) {
            return "6";
        }
        if (link.contains("baidu.com")) {
            return "10";
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Message message = (Message) o;
        return Objects.equals(link, message.link);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(link);
    }
}
