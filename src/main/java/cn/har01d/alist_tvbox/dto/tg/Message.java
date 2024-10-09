package cn.har01d.alist_tvbox.dto.tg;

import lombok.Data;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class Message {
    private static final Pattern LINK = Pattern.compile("(https?:\\/\\/(?:www\\.)?[-a-zA-Z0-9@:%._\\+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b(?:[-a-zA-Z0-9()@:%_\\+.~#?&\\/=]*))");
    private int id;
    private Integer views;
    private Instant time;
    private String content;
    private String author;
    private String channel;

    public Message() {
    }

    public Message(String channel, telegram4j.tl.BaseMessage message) {
        this.id = message.id();
        this.time = Instant.ofEpochSecond(message.date());
        this.content = message.message();
        this.views = message.views();
        this.author = message.postAuthor();
        this.channel = channel;
    }

    public String toPgString() {
        return time.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "\t" + channel + "\t" + content.replace('\n', ' ') + "\t" + id;
    }

    public String toZxString() {
        return getLink() + "$$" + getName();
    }

    private String getName() {
        return content.split("\n")[0].replace("名称：", "");
    }

    private String getLink() {
        Matcher m = LINK.matcher(content);
        if (m.find()) {
            return m.group(1);
        }
        return content;
    }
}
