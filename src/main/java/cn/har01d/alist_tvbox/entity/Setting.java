package cn.har01d.alist_tvbox.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class Setting {
    @Id
    private String name;
    @Column(name = "`svalue`", columnDefinition = "TEXT")
    private String value;
}
