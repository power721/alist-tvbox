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
    /** 归属用户:0=全局(管理员所有);>0=该用户的个人账号。凭证只下发给归属人。 */
    @Column(name = "owner_uid")
    private int ownerUid;
    /** 仅全局账号有效:是否允许普通用户经服务端代理使用(凭证不下发)。 */
    @Column(columnDefinition = "BOOLEAN DEFAULT TRUE")
    private boolean shared = true;
}
