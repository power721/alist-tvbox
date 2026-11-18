package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.ModelSourceConsent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelConsentRepository extends JpaRepository<ModelSourceConsent, String> {
}
