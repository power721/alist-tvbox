package cn.har01d.alist_tvbox.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * link_hash 生成口径:(subscription_id, link_hash) 唯一索引的数据源,JPA 落库回调统一计算,
 * 与 V34 迁移回填同算法(小写 hex SHA-256 / UTF-8)。
 */
class MediaSubscriptionResourceTest {

    @Test
    void persistCallbackComputesHashFromLink() {
        MediaSubscriptionResource resource = new MediaSubscriptionResource();
        assertNull(resource.getLinkHash());

        resource.setLink("https://pan.example/s/abc?pwd=1");
        resource.refreshLinkHash(); // @PrePersist/@PreUpdate 回调本体

        assertEquals(MediaSubscriptionResource.hashOf("https://pan.example/s/abc?pwd=1"), resource.getLinkHash());
        assertEquals(64, resource.getLinkHash().length());
    }

    @Test
    void longLinksWithSharedPrefixHashDifferently() {
        // 评审场景:前 760 字符一致的两条长链,旧 MySQL 前缀唯一索引会误判重复,哈希必须不同
        String prefix = "https://pan.example/s/" + "a".repeat(800) + "?token=";
        assertNotEquals(MediaSubscriptionResource.hashOf(prefix + "sig-a"), MediaSubscriptionResource.hashOf(prefix + "sig-b"));
        assertNull(MediaSubscriptionResource.hashOf(null));
    }
}
