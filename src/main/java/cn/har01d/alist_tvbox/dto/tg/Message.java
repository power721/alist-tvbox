package cn.har01d.alist_tvbox.dto.tg;

import lombok.Data;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
    private String link;

    public Message() {
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
        return content.split("\n")[0].replace("名称：", "");
    }

    private String parseLink() {
        Matcher m = LINK.matcher(content);
        if (m.find()) {
            return m.group(1);
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
