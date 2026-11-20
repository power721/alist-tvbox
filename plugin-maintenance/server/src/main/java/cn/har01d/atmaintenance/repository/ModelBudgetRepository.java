package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.ModelBudget;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;

public interface ModelBudgetRepository extends JpaRepository<ModelBudget, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ModelBudget b where b.id = 1")
    Optional<ModelBudget> lock();
}
