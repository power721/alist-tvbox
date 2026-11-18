package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.ModelNamedBudget;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelNamedBudgetRepository extends JpaRepository<ModelNamedBudget, String> {
}
