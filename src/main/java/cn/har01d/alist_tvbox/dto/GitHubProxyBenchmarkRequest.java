package cn.har01d.alist_tvbox.dto;

import lombok.Data;
import java.util.List;

@Data
public class GitHubProxyBenchmarkRequest {
    private List<String> urls; // 要测速的代理 URL 列表
}
