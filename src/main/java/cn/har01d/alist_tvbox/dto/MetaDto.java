package cn.har01d.alist_tvbox.dto;

import cn.har01d.alist_tvbox.entity.Meta;
import lombok.Data;

@Data
public class MetaDto {
    private Integer id;
    private String path;
    private String name;
    private Integer year;
    private Integer score;
    private Integer movieId;

    public MetaDto() {
    }

    public MetaDto(Meta meta) {
        this.id = meta.getId();
        this.name = meta.getName();
        this.path = meta.getPath();
        this.year = meta.getYear();
        this.score = meta.getScore();
        this.movieId = meta.getMovie().getId();
    }
}
