package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettingRepository extends JpaRepository<Setting, String> {
    boolean existsByName(String name);

    /** 用户级配置行反查:如 msub_telegram_chat_id:u{uid}(Telegram 绑定解析)。 */
    List<Setting> findByNameStartingWith(String prefix);
}
