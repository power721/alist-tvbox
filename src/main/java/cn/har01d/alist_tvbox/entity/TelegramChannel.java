package cn.har01d.alist_tvbox.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    @Column(name = "sort_order")
    @JsonProperty("order")
    private Integer sortOrder;
    private int type = -1;
    private boolean valid = true;
    private boolean enabled = false;
    private boolean webAccess;

    public TelegramChannel() {
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
