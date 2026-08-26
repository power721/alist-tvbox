package cn.har01d.alist_tvbox.service.sitesearch;

import org.apache.commons.lang3.StringUtils;

/**
 * 需登录站点搜索源的凭证口径(盘链/观影/蜗牛公共):直接配 Cookie 或账号密码对,
 * 任一即视为已配置;只有账号密码对才支持自动登录。
 */
interface SiteCredentials {
    String username();

    String password();

    String cookie();

    default boolean hasCredentials() {
        return StringUtils.isNotBlank(cookie()) || (StringUtils.isNotBlank(username()) && StringUtils.isNotBlank(password()));
    }

    default boolean canLogin() {
        return StringUtils.isNotBlank(username()) && StringUtils.isNotBlank(password());
    }
}
