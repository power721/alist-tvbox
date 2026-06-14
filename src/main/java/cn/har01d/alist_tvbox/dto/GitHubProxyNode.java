package cn.har01d.alist_tvbox.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitHubProxyNode {
    private String label;      // 显示标签（如：默认节点、公益贡献、高速节点）
    private String url;        // 完整代理 URL（已带 /）
    private String host;       // 主机名
    private String tag;        // 原始标签（donate/search）
    private Integer latency;   // 延迟（毫秒）
    private Double speed;      // 速度（KB/s）
    private Boolean success;   // 测速是否成功
    private String error;      // 错误信息
}
