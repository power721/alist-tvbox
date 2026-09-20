package cn.har01d.alist_tvbox.dto;

/**
 * 站点搜索源凭证有效性检查请求(网页追剧设置页):site 取
 * woniu/guanying/zencang/pan123community/kuafu;cookie/host/username/password 为
 * 表单当前值,未保存也可先验(后端不读、不写任何 Setting,账号密码实测登录除外 ——
 * 登录成功会话按服务自身策略保存,与搜索自动登录同链路)。
 */
public record SiteCredentialCheckRequest(
        /** 站点 key(与 Setting 前缀一致) */
        String site,
        /** 待验 Cookie 头原文(k=v; k=v) */
        String cookie,
        /** 可选自定义站点地址,空用内置 */
        String host,
        /** 可选账号(观影/蜗牛:Cookie 未填时用账号密码实测登录) */
        String username,
        /** 可选密码(与账号配套) */
        String password) {
}
