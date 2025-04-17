package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PanAccountRepository extends JpaRepository<PanAccount, Integer> {
    List<PanAccount> findByMasterTrue();
}
