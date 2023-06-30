package cn.har01d.alist_tvbox.dto;

import cn.har01d.alist_tvbox.entity.AListAlias;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AListAliasDto {
    private Integer id;
    private String path;
    private String content;

    public AListAliasDto(AListAlias alias) {
        this.id = alias.getId();
        this.path = alias.getPath();
        this.content = alias.getContent();
    }
}
