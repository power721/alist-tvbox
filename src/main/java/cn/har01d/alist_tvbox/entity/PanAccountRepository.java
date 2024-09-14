package cn.har01d.alist_tvbox.entity;

import cn.har01d.alist_tvbox.domain.DriverType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PanAccountRepository extends JpaRepository<PanAccount, Integer> {
    boolean existsByNameAndType(String name, DriverType type);

    PanAccount findByNameAndType(String name, DriverType type);

    long countByType(DriverType type);
}
