package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.Hibernate;

import java.util.Objects;

@Getter
@Setter
@ToString
@Entity
public class TelegramChannel {
    @Id
    private Long id;
    private String username;
    private String title;
    private long accessHash;

    @Column(name = "`order`")
    private int order;
    private int type = -1;
    private boolean valid = true;
    private boolean enabled = false;
    private boolean webAccess;

    public TelegramChannel() {
    }

    public TelegramChannel(telegram4j.tl.Channel channel) {
        this.id = channel.id();
        this.title = channel.title();
        this.username = channel.username();
        this.accessHash = channel.accessHash();
    }

    public TelegramChannel(telegram4j.tl.BaseChat chat) {
        this.id = chat.id();
        this.title = chat.title();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        TelegramChannel task = (TelegramChannel) o;
        return id != null && Objects.equals(id, task.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
