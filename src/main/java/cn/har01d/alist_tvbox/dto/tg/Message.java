package cn.har01d.alist_tvbox.dto.tg;

import lombok.Data;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Data
public class Message {
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
        return time.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))  + " "+ channel + " " + content.replace('\n', ' ');
    }
}
