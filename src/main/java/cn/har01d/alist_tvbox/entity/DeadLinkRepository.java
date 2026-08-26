package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeadLinkRepository extends JpaRepository<DeadLink, Integer> {
    Optional<DeadLink> findByLink(String link);

    void deleteByLink(String link);
}
