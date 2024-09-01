package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PanAccountRepository extends JpaRepository<PanAccount, Integer> {
    boolean existsByName(String name);
}
