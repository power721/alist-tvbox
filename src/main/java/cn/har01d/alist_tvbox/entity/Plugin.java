package cn.har01d.alist_tvbox.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
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
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
@Table(indexes = {
    @Index(name = "idx_plugin_external_id", columnList = "external_id"),
    @Index(name = "idx_plugin_url", columnList = "url")
})
public class Plugin {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    private String name;

    @Column(name = "external_id")
    private String externalId;

    @Column(columnDefinition = "TEXT")
    private String url;

    private boolean enabled = true;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "\"EXTEND\"", columnDefinition = "TEXT")
    private String extend;

    @Column(name = "source_name")
    private String sourceName;

    @Column(name = "local_path")
    private String localPath;

    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "\"VERSION\"")
    private Integer version;

    @Column(name = "last_checked_at")
    private OffsetDateTime lastCheckedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
}
