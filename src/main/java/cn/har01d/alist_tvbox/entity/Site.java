package cn.har01d.alist_tvbox.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.Hibernate;

import java.util.Objects;

@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
@Table(indexes = {
    @Index(name = "idx_site_url", columnList = "url")
})
public class Site {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    private String name;
    private String url;
    private String password = "";
    private String token = "";
    private String indexFile;
    private String folder = "";
    private boolean searchable;
    private boolean disabled;
    @Column(columnDefinition = "boolean default false")
    private boolean xiaoya;
    @Column(name = "sort_order")
    @JsonProperty("order")
    private int sortOrder;
    @Column(name = "storage_version")
    @JsonProperty("version")
    private Integer storageVersion;

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
