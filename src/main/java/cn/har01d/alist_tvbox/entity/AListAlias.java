package cn.har01d.alist_tvbox.entity;


import cn.har01d.alist_tvbox.dto.AListAliasDto;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;

@Getter
@Setter
@ToString
@NoArgsConstructor
@Entity
public class AListAlias {
    @Id
    private Integer id;
    @Column(unique = true)
    private String path;
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String content;

    public AListAlias(AListAliasDto dto) {
        this.path = dto.getPath();
        this.content = dto.getContent();
    }
}
