package cn.har01d.alist_tvbox.dto;

import java.util.List;

/**
 * 玩偶聚合单站域名探测状态(状态面板展示):domains 已按采用优先级排序
 * (可达按延迟升序在前,不可达垫底),首条即当前采用域名。
 */
public record WanouSiteStatus(
        String siteId,
        String siteName,
        /** 本站是否有任一可达域名 */
        boolean ok,
        /** 当前采用域名(延迟最低的可达域名);全域名不可达为 null */
        String bestUrl,
        List<WanouDomainStatus> domains) {
}
