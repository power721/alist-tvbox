package cn.har01d.alist_tvbox.dto;

import cn.har01d.alist_tvbox.entity.Meta;
import cn.har01d.alist_tvbox.entity.TmdbMeta;
import lombok.Data;

@Data
public class MetaDto {
    private Integer id;
    private String path;
    private String name;
    private Integer year;
    private Integer score;
    private Integer movieId;
    private Integer tmId;
    private Integer siteId;
    private String type = "movie";

    public MetaDto() {
    }

    public MetaDto(Meta meta) {
        this.id = meta.getId();
        this.name = meta.getName();
        this.path = meta.getPath();
        this.year = meta.getYear();
        this.score = meta.getScore();
        this.siteId = meta.getSiteId();
        if (meta.getMovie() != null) {
            this.movieId = meta.getMovie().getId();
        }
        if (meta.getTmdb() != null) {
            this.tmId = meta.getTmdb().getTmdbId();
            this.type = meta.getTmdb().getType();
        }
    }

    public MetaDto(TmdbMeta meta) {
        this.id = meta.getId();
        this.name = meta.getName();
        this.path = meta.getPath();
        this.year = meta.getYear();
        this.score = meta.getScore();
        this.siteId = meta.getSiteId();
        if (meta.getTmdb() != null) {
            this.tmId = meta.getTmdb().getTmdbId();
            this.type = meta.getTmdb().getType();
        }
    }

}
