package cn.har01d.alist_tvbox.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.Hibernate;

import javax.persistence.*;
import java.util.Objects;

@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
public class Site {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String name;
    private String url;
    private String password;
    private String indexFile;
    private boolean searchable;
    private boolean disabled;
    @Column(name = "`order`")
    private int order;
    @Column(name = "`version`")
    private Integer version;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        Site site = (Site) o;
        return id != null && Objects.equals(id, site.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
