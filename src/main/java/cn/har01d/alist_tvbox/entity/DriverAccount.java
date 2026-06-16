package cn.har01d.alist_tvbox.entity;

import cn.har01d.alist_tvbox.domain.DriverType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(exclude = {"cookie", "token", "password", "safePassword"})
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
@Table(indexes = {
    @Index(name = "idx_driver_account_type_username", columnList = "type, username"),
    @Index(name = "idx_driver_account_type_name", columnList = "type, name")
})
public class DriverAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;
    private DriverType type;
    private String name;
    @Column(columnDefinition = "TEXT")
    private String cookie = "";
    @Column(columnDefinition = "TEXT")
    private String token = "";
    @Column(columnDefinition = "TEXT")
    private String addition = "";
    private String username = "";
    private String password = "";
    private String safePassword = "";
    private String folder = "";
    private Integer concurrency = 1;
    @Column(columnDefinition = "BOOLEAN DEFAULT false")
    private boolean disabled;
    @Column(columnDefinition = "BOOLEAN DEFAULT false")
    private boolean useProxy;
    private boolean master;
}
