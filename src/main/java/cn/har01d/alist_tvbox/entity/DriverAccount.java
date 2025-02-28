package cn.har01d.alist_tvbox.entity;

import cn.har01d.alist_tvbox.domain.DriverType;
import jakarta.persistence.Column;
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
    private String username = "";
    private String password = "";
    private String safePassword = "";
    private String folder = "";
    @Column(columnDefinition = "BOOLEAN DEFAULT false")
    private boolean useProxy;
    private boolean master;
}
