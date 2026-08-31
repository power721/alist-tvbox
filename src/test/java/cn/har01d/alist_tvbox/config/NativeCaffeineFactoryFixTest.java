package cn.har01d.alist_tvbox.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM 下验证修正器把 caffeine LocalCacheFactory 注册表预填为各类自身的 FACTORY:
 * SSMSW 键必须对应 SSMSW 的工厂(native 下 fast-path 会错填父类 SSMS 的工厂,即本次修复对象)。
 */
class NativeCaffeineFactoryFixTest {

    @Test
    void preRegistersFactoriesWithOwnImplementation() throws Exception {
        NativeCaffeineFactoryFix.apply();

        Class<?> lcf = Class.forName("com.github.benmanes.caffeine.cache.LocalCacheFactory");
        Field factoriesField = lcf.getDeclaredField("FACTORIES");
        factoriesField.setAccessible(true);
        Map<?, ?> factories = (Map<?, ?>) factoriesField.get(null);

        Object factory = factories.get("SSMSW");
        assertNotNull(factory, "SSMSW 工厂应被预填");
        assertTrue(factory.getClass().getName().startsWith("com.github.benmanes.caffeine.cache.SSMSW"),
                "SSMSW 键应持有 SSMSW 自己的工厂,实际: " + factory.getClass().getName());

        Object ssFactory = factories.get("SSMS");
        assertNotNull(ssFactory);
        assertTrue(ssFactory.getClass().getName().startsWith("com.github.benmanes.caffeine.cache.SSMS"),
                "SSMS 键应持有 SSMS 自己的工厂");
    }

    @Test
    void caffeinBehaviourStaysCorrectAfterFix() {
        NativeCaffeineFactoryFix.apply();
        var cache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .expireAfterWrite(java.time.Duration.ofNanos(1))
                .maximumSize(10)
                .build();
        cache.put("k", Boolean.TRUE);
        assertNull(cache.getIfPresent("k"), "1 纳秒 TTL 的条目在读取时应已过期");
    }
}
