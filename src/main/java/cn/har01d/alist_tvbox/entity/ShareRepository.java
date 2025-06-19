package cn.har01d.alist_tvbox.entity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShareRepository extends JpaRepository<Share, Integer> {
    boolean existsByPath(String path);

    Share findByPath(String path);

    int countByType(int type);

    List<Share> findByType(int type);

    Page<Share> findByType(int type, Pageable pageable);

    Page<Share> findByPathContains(String keyword, Pageable pageable);

    Page<Share> findByTypeAndPathContains(int type, String keyword, Pageable pageable);

    List<Share> findByTempTrue();
}
