package cn.har01d.alist_tvbox.entity;


import cn.har01d.alist_tvbox.dto.AListAliasDto;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

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
