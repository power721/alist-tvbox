package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.Hibernate;

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