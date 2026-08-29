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

@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
public class History {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;

    @Column(name = "\"key\"", columnDefinition = "TEXT")
    private String key;
    private String vodPic;
    private String vodName;
    private String vodFlag;
    private String vodRemarks;
    @Column(columnDefinition = "TEXT")
    private String episodeUrl;
    private boolean revSort;
    private boolean revPlay;
    private long createTime;
    private long opening;
    private long ending;
    private long position;
    private long duration;
    private float speed = 1;
    private int scale = -1;
    private int cid;

    private int episode = -1;
    /** 归属用户,写入方必须显式赋值(不再默认 1:默认值会把来路不明的记录挂到首个用户名下) */
    private Integer uid;

    // 多端播放记录同步:规范化身份与冲突时钟(可空;仅新协议路径填充)
    private String syncScope;
    private String sourceKind;
    private String sourceKey;
    private String sourceName;
    // 网盘/电报源的 vod_id 是 URL 编码的 JSON,可达数百字符,VARCHAR(255) 会溢出;身份列不可截断
    @Column(columnDefinition = "TEXT")
    private String vodId;
    private Long updatedAt;
    private Long changeSeq;
    private String clientKey;
    private Integer playlistIndex;
    private Integer sourceGroupIndex;
    private Integer sourceIndex;
    private Integer sourceSubgroupIndex;
    private String sourceSubgroupName;
    @Column(columnDefinition = "TEXT")
    private String driveDirId;
    // 网盘播放内容的跨端规范标识:分享身份(盘类型@分享ID@提取码)+ 资源内相对路径(含文件名),
    // 由服务端从网盘播放 id 的 proxyId 解析生成,不随会话/列表顺序漂移
    private String driveShareKey;
    @Column(columnDefinition = "TEXT")
    private String drivePath;
}
