package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 集源:「某一集在某个资源里的那个文件」。剧集↔资源多对多连接,(episode, resource) 唯一。
 * <p>
 * <b>全系统唯一记录可用性的地方。</b>列得出目录 ≠ 取得到链(缺陷 4),所以行状态只认取链事实:
 * <ul>
 *   <li>{@link #STATE_LISTED} — 列目录发现,尚未取过链(弱信号)</li>
 *   <li>{@link #STATE_VERIFIED} — 取链成功过(最强信号)</li>
 *   <li>{@link #STATE_FAILED} — 取链失败(被和谐/分享失效);同资源另一集取链成功则仅为单集损坏</li>
 *   <li>{@link #STATE_MISSING} — 曾存在,重列目录后文件消失</li>
 * </ul>
 * 资源级可用性是派生量(整源死 = 该资源全部行 ∈ {FAILED, MISSING}),不落列。
 */
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
@jakarta.persistence.Table(name = "msub_episode_source", uniqueConstraints = @UniqueConstraint(name = "uk_msub_episode_source", columnNames = {"episode_id", "resource_id"}), indexes = {
        @Index(name = "idx_msub_es_resource", columnList = "resource_id")
})
public class MediaSubscriptionEpisodeSource {
    public static final String STATE_LISTED = "LISTED";
    public static final String STATE_VERIFIED = "VERIFIED";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_MISSING = "MISSING";

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    @Column(nullable = false, name = "episode_id")
    private int episodeId;

    @Column(nullable = false, name = "resource_id")
    private int resourceId;

    /** 分享内相对路径(不含挂载点前缀):换挂载路径不失效,播放时 mount_path + rel_path 取链 */
    @Column(nullable = false, length = 512, name = "rel_path")
    private String relPath;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(nullable = false, length = 16)
    private String state = STATE_LISTED;

    @Column(name = "success_count")
    private int successCount;

    @Column(name = "fail_count")
    private int failCount;

    @Column(name = "last_verified_time")
    private Long lastVerifiedTime;
}
