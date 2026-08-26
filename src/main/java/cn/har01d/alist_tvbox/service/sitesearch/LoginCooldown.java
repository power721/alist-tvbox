package cn.har01d.alist_tvbox.service.sitesearch;

import lombok.extern.slf4j.Slf4j;

/**
 * 登录失败冷却(盘链/观影/蜗牛公共):账号错误或站点故障时冷却期内不再撞登录接口,
 * 防每轮巡检都试一遍密码把账号撞墙。冷却时长因源而异,由调用方传入。
 */
@Slf4j
final class LoginCooldown {
    private volatile long until;

    /** 冷却期内返回 true,调用方应直接放弃本次登录。 */
    boolean blocked() {
        return System.currentTimeMillis() < until;
    }

    /** 记一次失败并进入冷却;恒返回 false,供 loginFailed 链路直接透传。 */
    boolean fail(String site, String reason, long cooldownMs) {
        until = System.currentTimeMillis() + cooldownMs;
        log.warn("{}登录失败:{},{} 分钟内不再重试", site, reason, cooldownMs / 60_000);
        return false;
    }
}
