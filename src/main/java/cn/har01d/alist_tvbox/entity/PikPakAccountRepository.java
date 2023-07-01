package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PikPakAccountRepository extends JpaRepository<PikPakAccount, Integer> {
    Optional<PikPakAccount> getFirstByMasterTrue();

    boolean existsByUsername(String username);

    boolean existsByNickname(String nickname);

    PikPakAccount findByUsername(String username);

    PikPakAccount findByNickname(String nickname);
}
