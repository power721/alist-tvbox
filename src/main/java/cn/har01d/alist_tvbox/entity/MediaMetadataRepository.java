package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MediaMetadataRepository extends JpaRepository<MediaMetadata, Integer> {
    Optional<MediaMetadata> findByProviderAndMetaIdAndSeason(String provider, String metaId, int season);
}
