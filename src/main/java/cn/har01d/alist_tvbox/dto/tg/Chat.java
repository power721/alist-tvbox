package cn.har01d.alist_tvbox.dto.tg;

import lombok.Data;

@Data
public class Chat {
    private long id;
    private String title;
    private String username;
    private Long accessHash;
    private Integer participantsCount;

    public Chat() {
    }

    public Chat(telegram4j.tl.Channel channel) {
        this.id = channel.id();
        this.title = channel.title();
        this.username = channel.username();
        this.accessHash = channel.accessHash();
        this.participantsCount = channel.participantsCount();
    }
}
