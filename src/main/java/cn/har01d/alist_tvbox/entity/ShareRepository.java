package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShareRepository extends JpaRepository<Share, Integer> {
    boolean existsByPath(String path);

    int countByType(int type);

    List<Share> findByType(int type);
}
