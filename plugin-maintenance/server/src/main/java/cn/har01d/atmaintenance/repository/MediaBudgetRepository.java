package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.MediaBudget;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface MediaBudgetRepository extends JpaRepository<MediaBudget, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from MediaBudget b where b.id = 'global'")
    MediaBudget lockGlobal();
}
