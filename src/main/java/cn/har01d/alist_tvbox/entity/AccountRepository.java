package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Integer> {
    boolean existsByRefreshToken(String token);

    Account findByRefreshToken(String token);

    Optional<Account> getFirstByMasterTrue();

    Optional<Account> findByNickname(String nickname);

    Optional<Account> findFirstByOwnerUidOrderByIdAsc(int ownerUid);

    List<Account> findByOwnerUid(int ownerUid);
}
