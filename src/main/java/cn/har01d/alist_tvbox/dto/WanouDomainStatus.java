package cn.har01d.alist_tvbox.dto;

/**
 * 玩偶聚合单域名探测状态(状态面板展示):首页 GET 可达性与完整请求延迟。
 */
public record WanouDomainStatus(
        String url,
        boolean ok,
        /** 完整请求耗时(毫秒);不可达时无意义 */
        long latencyMs,
        /** 失败原因:HTTP 码 / challenge / 异常摘要;可达为 null */
        String error) {
}
