package cn.har01d.alist_tvbox.telegram;

import cn.har01d.alist_tvbox.service.SettingService;
import cn.har01d.alist_tvbox.service.metadata.MetadataHttp;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * api.telegram.org 出站 RestTemplate 的统一供给点(Bot 收发 + 追剧通知共用)。
 * <p>
 * 背景:JVM 不读 {@code HTTP_PROXY/HTTPS_PROXY} 环境变量(容器里设了也只对 Go 写的内嵌 AList 生效),
 * 墙内部署必须显式挂代理;而 {@code -Dhttps.proxyHost} 系统属性会把夸克/百度等国内网盘出站一并推过
 * 墙外代理,反而坏事。故代理只作用于 Telegram API:全局 Setting {@code msub_telegram_proxy}
 * ({@code http://host:port} / {@code socks5://host:port},留空直连),配置变化时整组换新实例
 * (网页保存后下一轮 getUpdates 即生效,无需重启);同配置下按读超时档位缓存复用。
 * 非法代理值在 {@link MetadataHttp#create} 抛 IllegalArgumentException —— 轮询日志直接可见配错,
 * 不静默回落直连。
 */
@Component
public class TelegramHttp {
    private static final Logger log = LoggerFactory.getLogger(TelegramHttp.class);
    static final String PROXY_KEY = "msub_telegram_proxy";

    private final MetadataHttp metadataHttp;
    private final SettingService settingService;
    /** 测试固定桩:非空时恒返回该实例(不读设置、不重建)。 */
    private final RestTemplate fixed;
    /** 键含 proxy:线程 B 读旧 proxy → 线程 A 完成热切换 → B 才 computeIfAbsent 时,纯 timeout 键会把旧代理实例
     *  灌回缓存且 snapshot 已追平永不重建(代理热切换静默失效);复合键下旧条目只残留不复发。 */
    private record CacheKey(Duration timeout, String proxy) {}

    private final Map<CacheKey, RestTemplate> cache = new ConcurrentHashMap<>();
    private volatile String proxySnapshot;

    @Autowired
    public TelegramHttp(MetadataHttp metadataHttp, SettingService settingService) {
        this.metadataHttp = metadataHttp;
        this.settingService = settingService;
        this.fixed = null;
    }

    /** 测试注入 RestTemplate 桩。 */
    public TelegramHttp(RestTemplate fixed) {
        this.metadataHttp = null;
        this.settingService = null;
        this.fixed = fixed;
    }

    public RestTemplate get(Duration readTimeout) {
        if (fixed != null) {
            return fixed;
        }
        String proxy = currentProxy();
        if (!Objects.equals(proxy, proxySnapshot)) {
            synchronized (this) {
                if (!Objects.equals(proxy, proxySnapshot)) {
                    log.info("telegram outbound proxy changed: {}", proxy == null ? "direct" : proxy);
                    cache.clear();
                    proxySnapshot = proxy;
                }
            }
        }
        return cache.computeIfAbsent(new CacheKey(readTimeout, proxy), k -> metadataHttp.create(k.timeout(), k.proxy()));
    }

    /** 设置读取失败(如库瞬时不可用)按上次快照,别让轮询线程因设置源抖动断流。 */
    private String currentProxy() {
        try {
            var setting = settingService.get(PROXY_KEY);
            return setting == null ? null : StringUtils.trimToNull(setting.getValue());
        } catch (Exception e) {
            log.warn("read telegram proxy setting failed: {}", e.getMessage());
            return proxySnapshot;
        }
    }
}
