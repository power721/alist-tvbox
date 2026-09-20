package cn.har01d.alist_tvbox.dto;

/**
 * 站点搜索源 Cookie 有效性检查结果:valid=false 时 message 带失效原因
 * (重定向到登录页/站点判定未登录/站点不可达等),只读探测不触发签到/回复。
 */
public record SiteCredentialCheckResult(
        String site,
        boolean valid,
        String message) {
}
