package cn.har01d.alist_tvbox.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * GraalVM native 兼容修正:Caffeine LocalCacheFactory 的 fast-path 在 native 下
 * {@code findStaticVarHandle(SSMSW, "FACTORY")} 会解析到继承链父类(SSMS)的同名静态字段,
 * 导致 {@code expireAfterWrite + maximumSize} 组合静默拿到无过期能力的工厂——
 * 所有此类缓存(登录限速/TG bot 冷却/频道搜索缓存等)永不过期,只能重启清空。
 * <p>
 * 修正:在第一个缓存构建之前,用传统 Field 反射逐类读取自身的 {@code FACTORY} 静态字段
 * (该路径在 native 下取值正确),直接填充 {@code FACTORIES} 注册表;loadFactory 命中缓存后
 * 不再进入有缺陷的 fast-path。JVM 下填入相同正确值,行为不变。
 * 必须在任何 Caffeine.build() 之前调用(AListApplication.main 首行)。
 */
public final class NativeCaffeineFactoryFix {
    private static final Logger log = LoggerFactory.getLogger(NativeCaffeineFactoryFix.class);
    private static final String PKG = "com.github.benmanes.caffeine.cache.";

    /** caffeine 3.2.x jar 内全部 BoundedLocalCache 实现类(不含嵌套类);非工厂类无 FACTORY 字段,自动跳过。 */
    public static final String[] IMPL_CLASSES = {
            "FDA", "FDAMS", "FDAMW", "FDAR", "FDARMS", "FDARMW", "FDAW", "FDAWMS", "FDAWMW", "FDAWR",
            "FDAWRMS", "FDAWRMW", "FDMS", "FDMW", "FDR", "FDRMS", "FDRMW", "FDW", "FDWMS", "FDWMW",
            "FDWR", "FDWRMS", "FDWRMW", "FSA", "FSAMS", "FSAMW", "FSAR", "FSARMS", "FSARMW", "FSAW",
            "FSAWMS", "FSAWMW", "FSAWR", "FSAWRMS", "FSAWRMW", "FSMS", "FSMW", "FSR", "FSRMS", "FSRMW",
            "FSW", "FSWMS", "FSWMW", "FSWR", "FSWRMS", "FSWRMW", "FWA", "FWAMS", "FWAMW", "FWAR",
            "FWARMS", "FWARMW", "FWAW", "FWAWMS", "FWAWMW", "FWAWR", "FWAWRMS", "FWAWRMW", "FWMS", "FWMW",
            "FWR", "FWRMS", "FWRMW", "FWW", "FWWMS", "FWWMW", "FWWR", "FWWRMS", "FWWRMW", "PDA",
            "PDAMS", "PDAMW", "PDAR", "PDARMS", "PDARMW", "PDAW", "PDAWMS", "PDAWMW", "PDAWR", "PDAWRMS",
            "PDAWRMW", "PDMS", "PDMW", "PDR", "PDRMS", "PDRMW", "PDW", "PDWMS", "PDWMW", "PDWR",
            "PDWRMS", "PDWRMW", "PSA", "PSAMS", "PSAMW", "PSAR", "PSARMS", "PSARMW", "PSAW", "PSAWMS",
            "PSAWMW", "PSAWR", "PSAWRMS", "PSAWRMW", "PSMS", "PSMW", "PSR", "PSRMS", "PSRMW", "PSW",
            "PSWMS", "PSWMW", "PSWR", "PSWRMS", "PSWRMW", "PWA", "PWAMS", "PWAMW", "PWAR", "PWARMS",
            "PWARMW", "PWAW", "PWAWMS", "PWAWMW", "PWAWR", "PWAWRMS", "PWAWRMW", "PWMS", "PWMW", "PWR",
            "PWRMS", "PWRMW", "PWW", "PWWMS", "PWWMW", "PWWR", "PWWRMS", "PWWRMW", "SIA", "SIAR",
            "SIAW", "SIAWR", "SIL", "SILA", "SILAR", "SILAW", "SILAWR", "SILMS", "SILMSA", "SILMSAR",
            "SILMSAW", "SILMSAWR", "SILMSR", "SILMSW", "SILMSWR", "SILMW", "SILMWA", "SILMWAR", "SILMWAW", "SILMWAWR",
            "SILMWR", "SILMWW", "SILMWWR", "SILR", "SILS", "SILSA", "SILSAR", "SILSAW", "SILSAWR", "SILSMS",
            "SILSMSA", "SILSMSAR", "SILSMSAW", "SILSMSAWR", "SILSMSR", "SILSMSW", "SILSMSWR", "SILSMW", "SILSMWA", "SILSMWAR",
            "SILSMWAW", "SILSMWAWR", "SILSMWR", "SILSMWW", "SILSMWWR", "SILSR", "SILSW", "SILSWR", "SILW", "SILWR",
            "SIMS", "SIMSA", "SIMSAR", "SIMSAW", "SIMSAWR", "SIMSR", "SIMSW", "SIMSWR", "SIMW", "SIMWA",
            "SIMWAR", "SIMWAW", "SIMWAWR", "SIMWR", "SIMWW", "SIMWWR", "SIR", "SIS", "SISA", "SISAR",
            "SISAW", "SISAWR", "SISMS", "SISMSA", "SISMSAR", "SISMSAW", "SISMSAWR", "SISMSR", "SISMSW", "SISMSWR",
            "SISMW", "SISMWA", "SISMWAR", "SISMWAW", "SISMWAWR", "SISMWR", "SISMWW", "SISMWWR", "SISR", "SISW",
            "SISWR", "SIW", "SIWR", "SSA", "SSAR", "SSAW", "SSAWR", "SSL", "SSLA", "SSLAR",
            "SSLAW", "SSLAWR", "SSLMS", "SSLMSA", "SSLMSAR", "SSLMSAW", "SSLMSAWR", "SSLMSR", "SSLMSW", "SSLMSWR",
            "SSLMW", "SSLMWA", "SSLMWAR", "SSLMWAW", "SSLMWAWR", "SSLMWR", "SSLMWW", "SSLMWWR", "SSLR", "SSLS",
            "SSLSA", "SSLSAR", "SSLSAW", "SSLSAWR", "SSLSMS", "SSLSMSA", "SSLSMSAR", "SSLSMSAW", "SSLSMSAWR", "SSLSMSR",
            "SSLSMSW", "SSLSMSWR", "SSLSMW", "SSLSMWA", "SSLSMWAR", "SSLSMWAW", "SSLSMWAWR", "SSLSMWR", "SSLSMWW", "SSLSMWWR",
            "SSLSR", "SSLSW", "SSLSWR", "SSLW", "SSLWR", "SSMS", "SSMSA", "SSMSAR", "SSMSAW", "SSMSAWR",
            "SSMSR", "SSMSW", "SSMSWR", "SSMW", "SSMWA", "SSMWAR", "SSMWAW", "SSMWAWR", "SSMWR", "SSMWW",
            "SSMWWR", "SSR", "SSS", "SSSA", "SSSAR", "SSSAW", "SSSAWR", "SSSMS", "SSSMSA", "SSSMSAR",
            "SSSMSAW", "SSSMSAWR", "SSSMSR", "SSSMSW", "SSSMSWR", "SSSMW", "SSSMWA", "SSSMWAR", "SSSMWAW", "SSSMWAWR",
            "SSSMWR", "SSSMWW", "SSSMWWR", "SSSR", "SSSW", "SSSWR", "SSW", "SSWR", "WIA", "WIAR",
            "WIAW", "WIAWR", "WIL", "WILA", "WILAR", "WILAW", "WILAWR", "WILMS", "WILMSA", "WILMSAR",
            "WILMSAW", "WILMSAWR", "WILMSR", "WILMSW", "WILMSWR", "WILMW", "WILMWA", "WILMWAR", "WILMWAW", "WILMWAWR",
            "WILMWR", "WILMWW", "WILMWWR", "WILR", "WILS", "WILSA", "WILSAR", "WILSAW", "WILSAWR", "WILSMS",
            "WILSMSA", "WILSMSAR", "WILSMSAW", "WILSMSAWR", "WILSMSR", "WILSMSW", "WILSMSWR", "WILSMW", "WILSMWA", "WILSMWAR",
            "WILSMWAW", "WILSMWAWR", "WILSMWR", "WILSMWW", "WILSMWWR", "WILSR", "WILSW", "WILSWR", "WILW", "WILWR",
            "WIMS", "WIMSA", "WIMSAR", "WIMSAW", "WIMSAWR", "WIMSR", "WIMSW", "WIMSWR", "WIMW", "WIMWA",
            "WIMWAR", "WIMWAW", "WIMWAWR", "WIMWR", "WIMWW", "WIMWWR", "WIR", "WIS", "WISA", "WISAR",
            "WISAW", "WISAWR", "WISMS", "WISMSA", "WISMSAR", "WISMSAW", "WISMSAWR", "WISMSR", "WISMSW", "WISMSWR",
            "WISMW", "WISMWA", "WISMWAR", "WISMWAW", "WISMWAWR", "WISMWR", "WISMWW", "WISMWWR", "WISR", "WISW",
            "WISWR", "WIW", "WIWR", "WSA", "WSAR", "WSAW", "WSAWR", "WSL", "WSLA", "WSLAR",
            "WSLAW", "WSLAWR", "WSLMS", "WSLMSA", "WSLMSAR", "WSLMSAW", "WSLMSAWR", "WSLMSR", "WSLMSW", "WSLMSWR",
            "WSLMW", "WSLMWA", "WSLMWAR", "WSLMWAW", "WSLMWAWR", "WSLMWR", "WSLMWW", "WSLMWWR", "WSLR", "WSLS",
            "WSLSA", "WSLSAR", "WSLSAW", "WSLSAWR", "WSLSMS", "WSLSMSA", "WSLSMSAR", "WSLSMSAW", "WSLSMSAWR", "WSLSMSR",
            "WSLSMSW", "WSLSMSWR", "WSLSMW", "WSLSMWA", "WSLSMWAR", "WSLSMWAW", "WSLSMWAWR", "WSLSMWR", "WSLSMWW", "WSLSMWWR",
            "WSLSR", "WSLSW", "WSLSWR", "WSLW", "WSLWR", "WSMS", "WSMSA", "WSMSAR", "WSMSAW", "WSMSAWR",
            "WSMSR", "WSMSW", "WSMSWR", "WSMW", "WSMWA", "WSMWAR", "WSMWAW", "WSMWAWR", "WSMWR", "WSMWW",
            "WSMWWR", "WSR", "WSS", "WSSA", "WSSAR", "WSSAW", "WSSAWR", "WSSMS", "WSSMSA", "WSSMSAR",
            "WSSMSAW", "WSSMSAWR", "WSSMSR", "WSSMSW", "WSSMSWR", "WSSMW", "WSSMWA", "WSSMWAR", "WSSMWAW", "WSSMWAWR",
            "WSSMWR", "WSSMWW", "WSSMWWR", "WSSR", "WSSW", "WSSWR", "WSW", "WSWR",
    };

    private NativeCaffeineFactoryFix() {
    }

    public static void apply() {
        try {
            Class<?> lcf = Class.forName(PKG + "LocalCacheFactory");
            Field factoriesField = lcf.getDeclaredField("FACTORIES");
            factoriesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> factories = (Map<String, Object>) factoriesField.get(null);
            int fixed = 0;
            for (String name : IMPL_CLASSES) {
                try {
                    Class<?> impl = Class.forName(PKG + name);
                    Field factoryField = impl.getDeclaredField("FACTORY");
                    factoryField.setAccessible(true);
                    Object factory = factoryField.get(null);
                    if (factory != null) {
                        factories.put(name, factory);
                        fixed++;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // 未进 image 的实现类 / 无工厂字段的非末端类:跳过
                }
            }
            log.info("caffeine native factory fix applied: {} implementations pre-registered", fixed);
        } catch (Throwable t) {
            log.warn("caffeine native factory fix failed (JVM 行为不受影响)", t);
        }
    }
}
