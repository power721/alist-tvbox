package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.ScheduleBudget;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface ScheduleBudgetRepository extends JpaRepository<ScheduleBudget, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ScheduleBudget b where b.id = 1")
    ScheduleBudget lockBudget();
}
