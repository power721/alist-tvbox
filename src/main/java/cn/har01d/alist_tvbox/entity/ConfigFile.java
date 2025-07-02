package cn.har01d.alist_tvbox.entity;

import cn.har01d.alist_tvbox.dto.FileDto;
import com.fasterxml.jackson.annotation.JsonIgnore;
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

@Getter
@Setter
@ToString
@NoArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
public class ConfigFile {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;
    private String dir;
    private String name;
    @Column(unique = true)
    private String path;
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String content;

    public ConfigFile(FileDto file) {
        this.id = file.getId();
        this.dir = file.getDir();
        this.name = file.getName();
        this.path = file.getPath();
        this.content = file.getContent();
    }
}
