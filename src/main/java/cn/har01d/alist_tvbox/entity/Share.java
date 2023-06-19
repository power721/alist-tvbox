package cn.har01d.alist_tvbox.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.Hibernate;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
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
