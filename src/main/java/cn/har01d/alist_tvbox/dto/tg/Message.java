package cn.har01d.alist_tvbox.dto.tg;

import cn.har01d.alist_tvbox.dto.pansou.Link;
import cn.har01d.alist_tvbox.dto.pansou.MergedLink;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class Message {
    private static final Logger LOGGER = LoggerFactory.getLogger(Message.class);
    private static final Pattern LINK = Pattern.compile("(https?://\\S+)");
    private int id;
    private String mid;
    private Instant time;
    private Long size;
    @JsonIgnore
    private String content;
    private String channel;
    private String name;
    private String type;
    private String link;
    private String cover;
    private List<String> images = List.of();
    private Map<String, Object> media;
    private String validityState;
    private String validitySummary;

    public Message() {
    }

    public Message(int id, String channel, String content, String time, String cover) {
        parseTime(time);
        this.id = id;
        this.content = content;
        this.link = parseLink();
        this.name = parseName();
        this.channel = channel;
        this.cover = cover;
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

    public Message(SearchResult message, String link) {
        this.id = message.getId();
        this.time = Instant.ofEpochSecond(message.getTime());
        this.content = message.getContent();
        this.link = link;
        this.type = parseType(link);
        this.name = parseName();
        this.channel = message.getChannel();
    }

    public Message(cn.har01d.alist_tvbox.dto.pansou.SearchResult message, Link link) {
        this.mid = message.getMessageId();
        this.time = message.getDatetime();
        this.content = message.getContent();
        this.link = link.getUrl();
        this.type = parseType(link.getUrl());
        this.name = message.getTitle();
        this.channel = message.getChannel();
    }

    public Message(String type, MergedLink link) {
        this.time = link.getDatetime() == null ? Instant.now() : link.getDatetime();
        this.content = link.getNote();
        this.link = link.getUrl();
        this.type = type;
        this.name = parseName();
        this.channel = link.getSource();
    }

    public Message(String type, String url, Long size, String note, Instant datetime, List<String> images, Map<String, Object> media) {
        this.time = datetime == null ? Instant.now() : datetime;
        this.content = note == null ? "" : note;
        this.link = url;
        this.type = type;
        this.size = size;
        this.images = images == null ? List.of() : images;
        this.media = media;
        this.cover = this.images.isEmpty() ? null : this.images.getFirst();
        Object title = media == null ? null : media.get("title");
        this.name = title == null || String.valueOf(title).isBlank() ? parseName() : String.valueOf(title);
        this.channel = "tg-search";
    }

    public String toPgString() {
        return time.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "\t" + channel + "\t" + content.replace('\n', ' ') + "\t" + (mid == null ? id : mid);
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
        String name = line.replace("名称：", "")
                .replace("名称:", "")
                .replace("资源标题：", "")
                .replace("\uD83C\uDFAC 电影：", "")
                .replace("#剧集\uD83D\uDDC4 ", "")
                .replace("\uD83D\uDCFA 电视剧：", "");
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
            } else if (!link.startsWith("https://t.me/")) {
                LOGGER.debug("ignore link: {}", link);
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
            } else if (!link.startsWith("https://t.me/")) {
                LOGGER.debug("ignore link: {}", link);
            }
        }
        return links;
    }

    public static List<String> parseLinks(List<String> urls) {
        List<String> links = new ArrayList<>();
        for (String url : urls) {
            String link = fixLink(url);
            String type = parseType(link);
            if (type != null) {
                links.add(link);
            } else if (!link.startsWith("https://t.me/")) {
                LOGGER.debug("ignore link: {}", link);
            }
        }
        return links;
    }

    private static final String TRAILING_GARBAGE = "，。；：；,.;:?！？、）》」』】)]}·…—–​‌‍﻿";

    private static String fixLink(String link) {
        if (link.endsWith("**")) {
            return link.substring(0, link.length() - 2);
        }
        // Strip trailing punctuation, emoji and other garbage characters
        int end = link.length();
        while (end > 0) {
            char ch = link.charAt(end - 1);
            if (isTrailingGarbage(ch)) {
                end--;
            } else {
                break;
            }
        }
        if (end < link.length()) {
            return link.substring(0, end);
        }
        return link;
    }

    private static boolean isTrailingGarbage(char ch) {
        // Common punctuation and invisible characters
        if (TRAILING_GARBAGE.indexOf(ch) >= 0) {
            return true;
        }
        // CJK punctuation range
        if (ch >= '　' && ch <= '〿') {
            return true;
        }
        // Emoji ranges
        if (ch >= 0x1F300 && ch <= 0x1FAFF) {
            return true;
        }
        // Emoji modifier and variation selector
        if (ch == 0xFE0F || ch == 0xFE0E || ch >= 0x1F3FB && ch <= 0x1F3FF) {
            return true;
        }
        return false;
    }

    private static String parseType(String link) {
        if (link.startsWith("magnet:")) {
            return "magnet";
        }
        if (link.startsWith("ed2k:")) {
            return "ed2k";
        }
        if (link.contains("alipan.com") || link.contains("aliyundrive.com")) {
            return "0";
        }
        if (link.contains("mypikpak.com")) {
            return "1";
        }
        if (link.contains("xunlei.com")) {
            return "2";
        }
        if (link.contains("123pan.com") || link.contains("123pan.cn") || link.contains("123684.com") || link.contains("123685.com") || link.contains("123865.com") || link.contains("123912.com") || link.contains("123592.com")) {
            return "3";
        }
        if (link.contains("quark.cn")) {
            return "5";
        }
        if (link.contains("139.com") || link.contains("caiyun.feixin.10086.cn")) {
            return "6";
        }
        if (link.contains("uc.cn")) {
            return "7";
        }
        if (link.contains("115.com") || link.contains("115cdn.com") || link.contains("anxia.com")) {
            return "8";
        }
        if (link.contains("189.cn")) {
            return "9";
        }
        if (link.contains("guangyapan.com")) {
            return "12";
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
