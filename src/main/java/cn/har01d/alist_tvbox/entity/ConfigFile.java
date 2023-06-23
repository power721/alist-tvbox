package cn.har01d.alist_tvbox.entity;

import cn.har01d.alist_tvbox.dto.FileDto;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

@Getter
@Setter
@ToString
@NoArgsConstructor
@Entity
public class ConfigFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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
