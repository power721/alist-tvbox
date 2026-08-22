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

import java.time.Instant;

/**
 * 失效黑名单:判定为失效的分享链接的<b>全局</b>记录(跨订阅共享的唯一内容)。
 * <p>
 * 任何订阅判死即写入(取链/挂载事实,非标题猜测);入池前先查,同一死链不再被其它订阅
 * 重复试错。覆盖关系天然是 (订阅,资源) 二元的,因此只有黑名单值得全局 —— 挂载不能共享
 * (Share.path 唯一约束),候选打分也不该替别的订阅做决定。
 */
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
@jakarta.persistence.Table(name = "dead_link")
public class DeadLink {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    @Column(nullable = false, length = 1024, unique = true)
    private String link;

    @Column(length = 255)
    private String reason;

    /** 累计判死次数(跨订阅累积,反映该链的历史) */
    @Column(name = "fail_count")
    private int failCount;

    /** 最后判死时间(epoch ms) */
    @Column(nullable = false)
    private long time = Instant.now().toEpochMilli();
}
