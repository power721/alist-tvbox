package cn.har01d.alist_tvbox.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
public class Plugin {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    private String name;

    @Column(columnDefinition = "TEXT")
    private String url;

    private boolean enabled = true;

    @Column(name = "sort_order")
    private int sortOrder;

    @Column(name = "`extend`", columnDefinition = "TEXT")
    private String extend;

    @Column(name = "source_name")
    private String sourceName;

    @Column(name = "local_path")
    private String localPath;

    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "last_checked_at")
    private OffsetDateTime lastCheckedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
}
