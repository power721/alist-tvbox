package cn.har01d.atmaintenance.repository;

import cn.har01d.atmaintenance.entity.AtPublication;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AtPublicationRepository extends JpaRepository<AtPublication, String> {
    Optional<AtPublication> findByRequestKey(String requestKey);
    List<AtPublication> findByCandidateIdOrderByCreatedAtDesc(String candidateId);
}
