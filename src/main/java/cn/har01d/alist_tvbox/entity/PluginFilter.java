package cn.har01d.alist_tvbox.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.OffsetDateTime;

@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@Table(name = "plugin_filter")
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
public class PluginFilter {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    private String name;

    @Column(columnDefinition = "TEXT")
    private String url;

    private boolean enabled = true;

    @Column(name = "sort_order")
    private int sortOrder;

    private String stages = "";

    @Column(name = "`extend`", columnDefinition = "TEXT")
    private String extend;

    @Column(name = "error_strategy")
    private String errorStrategy = "skip";

    @Column(name = "source_name")
    private String sourceName;

    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "`version`")
    private Integer version;

    @Column(name = "last_checked_at")
    private OffsetDateTime lastCheckedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
}
