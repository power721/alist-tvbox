package cn.har01d.alist_tvbox.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.Hibernate;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.TableGenerator;
import java.util.Objects;

@Getter
@Setter
@ToString
@NoArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
public class Navigation {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;
    private String name;
    @Column(name = "`value`")
    private String value;
    private int type;
    @Column(columnDefinition = "boolean default true")
    private boolean show;
    @Column(columnDefinition = "boolean default false")
    private boolean reserved;
    @Column(name = "`order`")
    private int order;
    private int parentId;

    public Navigation(String name, String value, int type, boolean show, boolean reserved, int order) {
        this.name = name;
        this.value = value;
        this.type = type;
        this.show = show;
        this.reserved = reserved;
        this.order = order;
    }

    public Navigation(int id, String name, String value, int type, boolean show, boolean reserved, int order) {
        this.id = id;
        this.name = name;
        this.value = value;
        this.type = type;
        this.show = show;
        this.reserved = reserved;
        this.order = order;
    }

    public Navigation(String name, String value, int type, boolean show, boolean reserved, int order, int parentId) {
        this.name = name;
        this.value = value;
        this.type = type;
        this.show = show;
        this.reserved = reserved;
        this.order = order;
        this.parentId = parentId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        Navigation task = (Navigation) o;
        return id != null && Objects.equals(id, task.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}