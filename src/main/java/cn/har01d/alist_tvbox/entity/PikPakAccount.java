package cn.har01d.alist_tvbox.entity;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(exclude = "password")
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
@Table(indexes = {
    @Index(name = "idx_pikpak_account_username", columnList = "username")
})
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
