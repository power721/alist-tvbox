package cn.har01d.alist_tvbox.entity;

import cn.har01d.alist_tvbox.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Integer> {
    User findByUsername(String username);

    Optional<User> findFirstByRoleOrderByIdAsc(Role role);
}
