package cn.har01d.alist_tvbox.dto.tg;

import lombok.Data;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class Message {
    private static final Pattern LINK = Pattern.compile("(https?:\\/\\/\\S+)");
    private int id;
    private Instant time;
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

    public String toPgString() {
        return time.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "\t" + channel + "\t" + content.replace('\n', ' ') + "\t" + id;
    }

    public String toZxString() {
        return link + "$$" + name;
    }

    private String parseName() {
        String name = content.split("\n")[0].replace("名称：", "");
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
            String link = m.group(1);
            type = parseType(link);
            if (type != null) {
                return link;
            }
        }
        return null;
    }

    private String parseType(String link) {
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
