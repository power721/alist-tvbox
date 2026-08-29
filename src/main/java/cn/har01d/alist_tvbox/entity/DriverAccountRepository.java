package cn.har01d.alist_tvbox.entity;

import cn.har01d.alist_tvbox.domain.DriverType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DriverAccountRepository extends JpaRepository<DriverAccount, Integer> {
    boolean existsByNameAndType(String name, DriverType type);

    DriverAccount findByNameAndType(String name, DriverType type);

    long countByType(DriverType type);

    Optional<DriverAccount> findByTypeAndMasterTrue(DriverType type);

    Optional<DriverAccount> findByTypeAndUsername(DriverType type, String username);

    Optional<DriverAccount> findByTypeAndName(DriverType type, String name);

    /** 用户自己的某类型账号(取最早创建的一个;master 是全局标记,个人账号凭证下发不限 master)。 */
    Optional<DriverAccount> findFirstByOwnerUidAndTypeOrderByIdAsc(int ownerUid, DriverType type);

    List<DriverAccount> findByOwnerUid(int ownerUid);
}
