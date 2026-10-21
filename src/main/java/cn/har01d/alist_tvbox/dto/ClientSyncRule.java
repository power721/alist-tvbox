package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * #1084 第三方插件网盘凭证同步矩阵的单行规则(设置键 client_cookie_scope 的值形态:
 * {@code {"fty":{"enabled":false,"drives":[]}, "xs":{"drives":["quark","bili"]}}}).
 * <p>
 * 行键分两组:SPIDER_PLUGINS(7 个)由 spring.jar 侧消费(经 /cookies 响应的 syncPolicy 下发);
 * SERVER_PLUGINS(真心 zx / PG pg / 猫源 cat)由服务端直接执行(/zx/config、/pg/lib/tokenm、
 * 猫源占位符替换与配置 URL 注入)。饭太硬(fty)两通道同管:spider 兼容写入 + /tvfan/config。
 * <p>
 * 出厂默认(2026-10-21 定规):饭太硬/真心/PG/猫源 = 开(存量用户在用的主动拉取通道,默认关会
 * 破坏既有行为);摸鱼/潇洒/王二小/VOX/OK影视/嗷呜 = 关(纯被动注入对象,#1084 的治理目标)。
 * 行未配置即按出厂默认,与出厂默认等价的行保存时剔除。
 * <p>
 * drives 词表(即 /cookies 顶层键):quark/uc/baidu/115/ali/xunlei/189/123/139/guangya/
 * quarkTv/ucTv/bili,另 pikpak 仅 pg 行有效(tokenm 特有)。
 */
@Data
public class ClientSyncRule {
    /** spring.jar 侧消费的插件键(/cookies 响应 syncPolicy 下发,与 Init.CookieTarget id 对应) */
    public static final List<String> SPIDER_PLUGINS = List.of("aowu", "fish", "vox", "xs", "ok", "wex", "fty");
    /** 服务端直接消费的插件键 */
    public static final List<String> SERVER_PLUGINS = List.of("zx", "pg", "cat");
    /** 出厂默认开的插件(存量用户在用的主动拉取通道);其余一律默认关 */
    public static final Set<String> DEFAULT_ON_PLUGINS = Set.of("fty", "zx", "pg", "cat");

    private boolean enabled = true;
    /** 允许同步的网盘列表:空 = 全部网盘 */
    private List<String> drives = new ArrayList<>();

    /** 出厂默认开关:仅 DEFAULT_ON_PLUGINS 内(含其行为的服务端通道)默认开,其余默认关。 */
    public static boolean factoryEnabled(String plugin) {
        return DEFAULT_ON_PLUGINS.contains(plugin);
    }

    /** 行的实际开关:未配置(null)按出厂默认。 */
    public static boolean effectiveEnabled(String plugin, ClientSyncRule rule) {
        return rule == null ? factoryEnabled(plugin) : rule.isEnabled();
    }

    /** drives 空 = 允许全部网盘;非空 = 仅允许列表内网盘(未配置行 = 全部)。 */
    public boolean allowsDrive(String drive) {
        return drives == null || drives.isEmpty() || drives.contains(drive);
    }

    /**
     * 与 spider 侧「行缺失」语义(启用+全部网盘)是否等价:等价则 syncPolicy 无需下发该行
     * (不下发与下发 enabled:true 空白名单效果相同)。出厂关的行未配置时 effectiveEnabled=false
     * 不等价 → 必须显式下发 enabled:false,否则 spider 缺省语义下该插件会全量写入。
     */
    public static boolean matchesSpiderDefault(String plugin, ClientSyncRule rule) {
        return effectiveEnabled(plugin, rule) && (rule == null || rule.getDrives() == null || rule.getDrives().isEmpty());
    }

    /**
     * 是否与"行缺失"(出厂默认)等价:null 行按定义等价;非 null 行须开关等于出厂值
     * 且 drives 为空。等价行不必落库(缺失即同效果)。
     */
    public static boolean isFactoryDefault(String plugin, ClientSyncRule rule) {
        return rule == null || (effectiveEnabled(plugin, rule) == factoryEnabled(plugin)
                && (rule.getDrives() == null || rule.getDrives().isEmpty()));
    }
}
