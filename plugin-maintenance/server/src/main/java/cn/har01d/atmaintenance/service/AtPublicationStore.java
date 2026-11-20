package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.AtPublicationSummary;
import cn.har01d.atmaintenance.entity.AtPublication;
import cn.har01d.atmaintenance.repository.AtPublicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AtPublicationStore {
    private final AtPublicationRepository repository;

    @Transactional(readOnly = true)
    public AtPublication previous(String key) { return repository.findByRequestKey(key).orElse(null); }

    @Transactional(readOnly = true)
    public AtPublication get(String id) { return repository.findById(id).orElseThrow(() ->
            new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "AT_PUBLICATION_NOT_FOUND")); }

    @Transactional
    public AtPublication save(AtPublication record) { return repository.saveAndFlush(record); }

    @Transactional(readOnly = true)
    public List<AtPublicationSummary> list(String candidateId) {
        return repository.findByCandidateIdOrderByCreatedAtDesc(candidateId).stream().map(this::summary).toList();
    }

    public AtPublicationSummary summary(AtPublication record) {
        return new AtPublicationSummary(record.getId(), record.getCandidateId(), record.getMode(), record.getState(),
                record.getOriginalPluginId(), record.getPublishedPluginId(), record.getPluginName(),
                record.getDeliveryVersion(), record.getCandidateSha256(), record.getBaselineSha256(),
                record.getErrorCode(), record.getCreatedAt());
    }
}
