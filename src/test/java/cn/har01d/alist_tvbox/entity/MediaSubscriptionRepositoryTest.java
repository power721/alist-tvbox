package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(classes = MediaSubscriptionRepositoryTest.JpaTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class MediaSubscriptionRepositoryTest {

    @Autowired
    private MediaSubscriptionRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void coverSnapshotUpdateRequiresMatchingSeasonAndDoubanIdentity() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setName("测试剧");
        subscription.setMetaProvider("tmdb");
        subscription.setMetaId("233295");
        subscription.setSeason(1);
        subscription = repository.saveAndFlush(subscription);
        Integer id = subscription.getId();
        entityManager.clear();

        assertEquals(0, repository.updateCoverSnapshot(id, "tmdb", "233295", 2,
                null, 222, "https://img.example/tmdb-2.jpg",
                "https://img.example/douban-2.jpg", "MATCH"));
        entityManager.clear();
        MediaSubscription unchanged = repository.findById(id).orElseThrow();
        assertEquals(1, unchanged.getSeason());
        assertNull(unchanged.getDoubanId());
        assertNull(unchanged.getCoverFallbackUrl());

        assertEquals(1, repository.updateCoverSnapshot(id, "tmdb", "233295", 1,
                null, 111, "https://img.example/tmdb-1.jpg",
                "https://img.example/douban-1.jpg", "MATCH"));
        entityManager.clear();
        MediaSubscription bound = repository.findById(id).orElseThrow();
        assertEquals(111, bound.getDoubanId());
        assertEquals("https://img.example/douban-1.jpg", bound.getCoverFallbackUrl());

        assertEquals(0, repository.updateCoverSnapshot(id, "tmdb", "233295", 1,
                null, 222, "https://img.example/tmdb-2.jpg",
                "https://img.example/douban-2.jpg", "MATCH"));
        assertEquals(0, repository.updateCoverSnapshot(id, "tmdb", "233295", 1,
                222, 222, "https://img.example/tmdb-2.jpg",
                "https://img.example/douban-2.jpg", "MATCH"));
        assertEquals(1, repository.updateCoverSnapshot(id, "tmdb", "233295", 1,
                111, 111, "https://img.example/tmdb-current.jpg",
                "https://img.example/douban-current.jpg", "MATCH"));
    }

    @Test
    void metadataSnapshotUpdateMatchesNullSeasonWithoutOverwritingUserFields() {
        MediaSubscription subscription = new MediaSubscription();
        subscription.setName("无季号测试剧");
        subscription.setKeyword("用户关键词");
        subscription.setMetaProvider("tmdb");
        subscription.setMetaId("99");
        subscription.setDoubanId(123);
        subscription.setCoverFallbackUrl("https://img.example/douban-existing.jpg");
        subscription.setCoverFallbackStatus("MATCH");
        subscription = repository.saveAndFlush(subscription);
        Integer id = subscription.getId();
        entityManager.clear();

        assertEquals(1, repository.updateMetadataSnapshot(id, "tmdb", "99", null,
                123, "https://img.example/tmdb.jpg", 100L, 3, 8,
                "RETURNING", 200L, "别名", "[]"));

        entityManager.clear();
        MediaSubscription updated = repository.findById(id).orElseThrow();
        assertNull(updated.getSeason());
        assertEquals("用户关键词", updated.getKeyword());
        assertEquals(123, updated.getDoubanId());
        assertEquals(3, updated.getOfficialEpisodes());
        assertEquals("https://img.example/douban-existing.jpg", updated.getCoverFallbackUrl());
        assertEquals("MATCH", updated.getCoverFallbackStatus());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = MediaSubscription.class)
    @EnableJpaRepositories(basePackageClasses = MediaSubscriptionRepository.class)
    static class JpaTestApplication {
    }
}
