package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.UniqueConstraint;
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
@jakarta.persistence.Table(name = "live_follow", uniqueConstraints = @UniqueConstraint(name = "uk_live_follow", columnNames = {"uid", "platform", "room_id"}))
public class LiveFollow {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    @Column(nullable = false)
    private int uid;

    @Column(nullable = false, length = 32)
    private String platform;

    @Column(nullable = false, name = "room_id", length = 64)
    private String roomId;

    private String roomName;

    private String anchorName;

    @Column(length = 512)
    private String cover;

    private long createdTime;
}
