package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.Hibernate;

import java.time.Instant;
import java.util.Objects;

@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
public class Share {
    @Id
    private Integer id;
    @Column(unique = true)
    private String path;
    private String shareId;
    private String folderId = "root";
    private String password = "";
    @Column(columnDefinition = "TEXT")
    private String cookie;
    private Integer type;
    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean temp;
    private Instant time = Instant.now();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        Share share = (Share) o;
        return id != null && Objects.equals(id, share.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
