package cn.har01d.alist_tvbox.entity;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TelegramChannelRepository extends JpaRepository<TelegramChannel, Long> {
    List<TelegramChannel> findByWebAccessTrue(Sort sort);

    List<TelegramChannel> findByEnabledTrue(Sort sort);
}
