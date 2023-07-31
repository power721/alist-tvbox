package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AListAliasRepository extends JpaRepository<AListAlias, Integer> {
    AListAlias findByPath(String path);
}
