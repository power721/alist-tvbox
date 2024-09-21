package cn.har01d.alist_tvbox.entity;


import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
public class PikPakAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;
    private String nickname;
    private String platform = "pc";
    private String refreshTokenMethod = "oauth2";
    private String username = "";
    private String password = "";
    private boolean master;
}
