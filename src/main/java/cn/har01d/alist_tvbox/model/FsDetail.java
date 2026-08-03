package cn.har01d.alist_tvbox.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class FsDetail {
    private String name;
    private int type;
    @JsonProperty("is_dir")
    private boolean isDir;
    private String modified;
    private long size;
    private String sign;
    private String thumb;
    private String provider;
    @JsonProperty("raw_url")
    private String rawUrl;
    // 多账号分片下载的各源直连(开关开启时 PowerList /api/fs/get 返回),供 spider.jar 客户端多账号分片加速。
    @JsonProperty("multi_source")
    private List<MultiSource> multiSource;

    @Data
    public static class MultiSource {
        private String url;
        // 对应 Go http.Header = map[string][]string;Java 用 Map<String, List<String>> 接收。
        private Map<String, List<String>> header;
    }
}
