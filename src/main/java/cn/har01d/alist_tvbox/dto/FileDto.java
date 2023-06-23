package cn.har01d.alist_tvbox.dto;

import cn.har01d.alist_tvbox.entity.ConfigFile;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class FileDto {
    private Integer id;
    private String dir;
    private String name;
    private String path;
    private String content;

    public FileDto(ConfigFile file) {
        this.id = file.getId();
        this.dir = file.getDir();
        this.name = file.getName();
        this.path = file.getPath();
        this.content = file.getContent();
    }
}
