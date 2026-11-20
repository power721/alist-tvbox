package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.MaintenanceArtifact;
import cn.har01d.atmaintenance.entity.MaintenanceSource;
import cn.har01d.atmaintenance.repository.ArtifactRepository;
import cn.har01d.atmaintenance.repository.AtBindingRepository;
import cn.har01d.atmaintenance.repository.SourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SourceService {
    private final SourceRepository sources;
    private final ArtifactRepository artifacts;
    private final ProfileService profiles;
    private final SourceCredentialStore credentials;
    private final AtBindingRepository bindings;
    private final AtReadClient at;

    public void requireMaintenanceAllowed(String sourceId) {
        bindings.findById(sourceId)
                .filter(binding -> binding.getInstanceKey().equals(at.connection().instanceKey()))
                .ifPresent(binding -> at.requireMaintenanceAllowed(binding.getPluginId()));
    }

    @Transactional
    public SourceSummary create(CreateSourceRequest input) {
        validateContent(input.content());
        MaintenanceSource source = new MaintenanceSource();
        source.setName(input.name().trim());
        source.setExternalId(input.externalId() == null || input.externalId().isBlank()
                ? null : input.externalId().trim());
        source.setFilename(input.filename());
        source.setOriginUrl(input.originUrl());
        source = sources.saveAndFlush(source);
        MaintenanceArtifact artifact = saveArtifact(source.getId(), input.content(), null);
        source.setCurrentArtifactId(artifact.getId());
        sources.flush();
        return summary(source, artifact);
    }

    @Transactional(readOnly = true)
    public List<SourceSummary> list() {
        return sources.findAll(org.springframework.data.domain.Sort.by("createdAt").descending())
                .stream().map(source -> summary(source, currentArtifact(source))).toList();
    }

    @Transactional(readOnly = true)
    public SourceSummary get(String id) {
        MaintenanceSource source = requireSource(id);
        return summary(source, currentArtifact(source));
    }

    @Transactional
    public SourceSummary addArtifact(String id, AddArtifactRequest input) {
        validateContent(input.content());
        MaintenanceSource source = sources.findForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND"));
        MaintenanceArtifact current = currentArtifact(source);
        if (!current.getSha256().equals(input.expectedSha256())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SOURCE_CHANGED");
        }
        String sha = Hashes.sha256(input.content());
        MaintenanceArtifact artifact = artifacts.findBySourceIdAndSha256(id, sha)
                .orElseGet(() -> saveArtifact(id, input.content(), current.getSha256()));
        source.setCurrentArtifactId(artifact.getId());
        sources.flush();
        return summary(source, artifact);
    }

    @Transactional(readOnly = true)
    public List<ArtifactSummary> versions(String id) {
        requireSource(id);
        return artifacts.findBySourceIdOrderByCreatedAtDesc(id).stream()
                .map(a -> new ArtifactSummary(a.getId(), a.getSha256(), a.getParentSha256(), a.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public MaintenanceArtifact artifact(String sourceId, String sha) {
        return artifacts.findBySourceIdAndSha256(sourceId, sha)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND"));
    }

    public MaintenanceSource requireSource(String id) {
        return sources.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND"));
    }

    public MaintenanceSource requireForUpdate(String id) {
        return sources.findForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND"));
    }

    public MaintenanceArtifact currentArtifact(MaintenanceSource source) {
        return artifacts.findById(source.getCurrentArtifactId()).orElseThrow();
    }

    private MaintenanceArtifact saveArtifact(String sourceId, String body, String parent) {
        MaintenanceArtifact artifact = new MaintenanceArtifact();
        artifact.setSourceId(sourceId);
        artifact.setBody(body);
        artifact.setSha256(Hashes.sha256(body));
        artifact.setParentSha256(parent);
        return artifacts.saveAndFlush(artifact);
    }

    private void validateContent(String content) {
        if (content.getBytes(StandardCharsets.UTF_8).length > 1_048_576) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "SOURCE_TOO_LARGE");
        }
    }

    private SourceSummary summary(MaintenanceSource source, MaintenanceArtifact artifact) {
        return new SourceSummary(source.getId(), source.getName(), source.getExternalId(), source.getFilename(),
                source.getOriginUrl(), artifact.getSha256(), source.getRowVersion(), source.getCreatedAt(),
                profiles.current(source).sha256(), credentials.summary(source.getId()));
    }
}
