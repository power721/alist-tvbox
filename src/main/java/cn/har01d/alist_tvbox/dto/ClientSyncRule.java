package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * #1084 第三方插件网盘凭证同步矩阵的单行规则(设置键 client_cookie_scope 的值形态:
 * {@code {"fty":{"enabled":false,"drives":[]}, "xs":{"drives":["quark","bili"]}}}).
 * <p>
 * plugin 键:fty(饭太硬)/fish(摸鱼)/xs(潇洒)/wex(王二小)/vox/ok(OK影视)/aowu(嗷呜)由 spring.jar
 * 侧消费(经 /cookies 响应的 syncPolicy 下发);pg(PG/猫源 tokenm)由服务端直接消费。
 * drives 词表(即 /cookies 顶层键):quark/uc/baidu/115/ali/xunlei/189/123/139/guangya/
 * quarkTv/ucTv/bili,另 pikpak 仅 pg 行有效(tokenm 特有)。行缺失或全默认 = 允许全部(现行为)。
 */
@Data
public class ClientSyncRule {
    /** spring.jar 侧消费的插件键(/cookies 响应 syncPolicy 下发,与 Init.CookieTarget id 对应) */
    public static final List<String> SPIDER_PLUGINS = List.of("aowu", "fish", "vox", "xs", "ok", "wex", "fty");
    /** PG/猫源 tokenm 通道,服务端直接消费 */
    public static final String PLUGIN_PG = "pg";

    private boolean enabled = true;
    /** 允许同步的网盘列表:空 = 全部网盘 */
    private List<String> drives = new ArrayList<>();

    /** drives 为空 = 允许全部网盘;非空 = 仅允许列表内网盘。 */
    public boolean allowsDrive(String drive) {
        return drives == null || drives.isEmpty() || drives.contains(drive);
    }

    /** 是否与"行缺失"等价(允许+全部网盘):是则不必下发/落库。 */
    public boolean isDefault() {
        return enabled && (drives == null || drives.isEmpty());
    }
}
