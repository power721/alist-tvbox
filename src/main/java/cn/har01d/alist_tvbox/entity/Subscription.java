package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.*;
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
@Table(indexes = {
    @Index(name = "idx_subscription_url", columnList = "url")
})
public class Subscription {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;
    private String name;
    private String sid;
    private String url;
    @Column(columnDefinition = "TEXT")
    private String override;
    private String sort;
    /** 归属用户:0=全局默认订阅(所有用户可用);>0=该用户的个人订阅。 */
    @Column(name = "owner_uid")
    private int ownerUid;
}
