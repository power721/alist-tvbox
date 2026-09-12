package cn.har01d.alist_tvbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * PowerList {@code POST /api/fs/share/create}(115 永久分享)响应数据:
 * share/send + updateshare(-1) 两步在驱动侧串好,返回的分享是目录快照,
 * 建后可删盘内源文件;追加内容须新建分享(新 share_code)。
 */
@Data
public class ShareCreateData {
    @JsonProperty("share_code")
    private String shareCode;

    @JsonProperty("receive_code")
    private String receiveCode;

    @JsonProperty("share_url")
    private String shareUrl;

    @JsonProperty("share_title")
    private String shareTitle;
}
