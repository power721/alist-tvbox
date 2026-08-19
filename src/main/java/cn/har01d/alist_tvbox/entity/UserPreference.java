package cn.har01d.alist_tvbox.entity;

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

/**
 * 用户默认偏好(JSON):订阅 filter_config 留空的维度继承此表,此表为空用系统默认。
 */
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
@jakarta.persistence.Table(name = "user_preference", uniqueConstraints = @jakarta.persistence.UniqueConstraint(name = "uk_user_preference_uid", columnNames = "uid"))
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    @Column(nullable = false)
    private int uid;

    @Column(columnDefinition = "TEXT")
    private String config;

    @Column(name = "updated_time")
    private Long updatedTime;
}
