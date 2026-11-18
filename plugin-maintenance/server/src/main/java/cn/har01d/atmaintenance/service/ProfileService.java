package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.ProfileSummary;
import cn.har01d.atmaintenance.dto.UpdateProfileRequest;
import cn.har01d.atmaintenance.entity.MaintenanceProfile;
import cn.har01d.atmaintenance.entity.MaintenanceSource;
import cn.har01d.atmaintenance.repository.ProfileRepository;
import cn.har01d.atmaintenance.repository.SourceRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ProfileService {
    private static final Set<String> FIXED_NODES = Set.of("S01", "S02", "S03");
    private static final List<String> LIVE_DEFAULT_NODES = List.of(
            "S01", "S02", "S03", "S06", "C01", "C03", "C07", "C10", "R01");
    private final ProfileRepository profiles;
    private final SourceRepository sources;
    private final KnowledgeService knowledge;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true)
    public ProfileSummary get(String sourceId) {
        return current(requireSource(sourceId));
    }

    @Transactional(readOnly = true)
    public List<ProfileSummary> history(String sourceId) {
        MaintenanceSource source = requireSource(sourceId);
        var saved = profiles.findBySourceIdOrderByCreatedAtDesc(sourceId).stream().map(this::summary).toList();
        return saved.isEmpty() ? List.of(current(source)) : saved;
    }

    public ProfileSummary current(MaintenanceSource source) {
        if (source.getCurrentProfileId() != null) {
            return summary(profiles.findById(source.getCurrentProfileId()).orElseThrow());
        }
        // V1 sources receive a deterministic declared default, without rewriting
        // their historical runs or making a write during a read request.
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("schemaVersion", 1);
        snapshot.put("runtime", "t4");
        snapshot.put("runtimeRevision", "unverified");
        snapshot.put("authority", "declared");
        var required = snapshot.putArray("requiredNodes");
        FIXED_NODES.stream().sorted().forEach(required::add);
        var optional = snapshot.putArray("optionalNodes");
        nodeIds().stream().filter(id -> !FIXED_NODES.contains(id)).sorted().forEach(optional::add);
        snapshot.putNull("extFingerprint");
        JsonNode canonical = canonical(snapshot);
        return new ProfileSummary(null, source.getId(), Hashes.sha256(canonical.toString()), canonical, null);
    }

    @Transactional
    public ProfileSummary prepareLiveDefault(String sourceId, String runtimeRevision) {
        ProfileSummary previous = get(sourceId);
        // Only the implicit static-only default is replaced. Saved source-specific
        // requirements and configuration remain authoritative, including on retries.
        if (previous.id() != null) return previous;
        return update(sourceId, new UpdateProfileRequest(previous.sha256(), "t4", runtimeRevision,
                LIVE_DEFAULT_NODES, List.of(), null, null, null, null, null, "page-window"));
    }

    @Transactional
    public ProfileSummary update(String sourceId, UpdateProfileRequest input) {
        MaintenanceSource source = requireSource(sourceId);
        ProfileSummary previous = current(source);
        if (!previous.sha256().equals(input.expectedSha256())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PROFILE_CHANGED");
        }
        Set<String> required = new HashSet<>(input.requiredNodes());
        Set<String> optional = new HashSet<>(input.optionalNodes());
        Set<String> known = nodeIds();
        if (required.size() != input.requiredNodes().size() || optional.size() != input.optionalNodes().size()
                || !required.containsAll(FIXED_NODES) || !Collections.disjoint(required, optional)
                || !known.containsAll(required) || !known.containsAll(optional)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PROFILE_NODES");
        }
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("schemaVersion", 1);
        snapshot.put("runtime", input.runtime());
        snapshot.put("runtimeRevision", input.runtimeRevision().trim());
        snapshot.put("authority", "declared");
        required.stream().sorted().forEach(snapshot.putArray("requiredNodes")::add);
        optional.stream().sorted().forEach(snapshot.putArray("optionalNodes")::add);
        String purpose = input.sourcePurpose() == null
                ? previous.snapshot().path("sourcePurpose").asText("video") : input.sourcePurpose();
        if (!Set.of("video", "metadata-navigation").contains(purpose))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SOURCE_PURPOSE_INVALID");
        if (purpose.equals("metadata-navigation") && (!required.contains("C07")
                || Collections.disjoint(required, Set.of("C03", "C05"))
                || java.util.stream.Stream.concat(required.stream(), optional.stream())
                    .anyMatch(ProfileService::playbackNode)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "METADATA_PROFILE_NODES_INVALID");
        if (input.sourcePurpose() != null || previous.snapshot().has("sourcePurpose"))
            snapshot.put("sourcePurpose", purpose);
        String keyword = input.searchKeyword() == null
                ? previous.snapshot().path("searchKeyword").asText("") : input.searchKeyword();
        if (keyword.length() > 200 || !keyword.isEmpty() && keyword.isBlank()
                || keyword.chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SEARCH_KEYWORD_INVALID");
        if (!keyword.isEmpty()) snapshot.put("searchKeyword", keyword);
        if (input.paginationMode() != null) {
            if (!Set.of("exact-total", "page-window").contains(input.paginationMode())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PAGINATION_MODE_INVALID");
            }
            snapshot.put("paginationMode", input.paginationMode());
        } else if (previous.snapshot().has("paginationMode")) {
            snapshot.set("paginationMode", previous.snapshot().get("paginationMode"));
        }
        if (input.httpPlaybackGroup() != null) {
            if (input.httpPlaybackGroup() < 0 || input.httpPlaybackGroup() > 31) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HTTP_PLAYBACK_GROUP_INVALID");
            }
            snapshot.put("httpPlaybackGroup", input.httpPlaybackGroup());
        } else if (previous.snapshot().has("httpPlaybackGroup")) {
            snapshot.set("httpPlaybackGroup", previous.snapshot().get("httpPlaybackGroup"));
        }
        if (input.httpDetailSample() != null) {
            if (input.httpDetailSample().length() > 512
                    || !input.httpDetailSample().isEmpty() && input.httpDetailSample().isBlank()
                    || input.httpDetailSample().chars().anyMatch(c -> Character.isISOControl(c)
                        || Character.isSurrogate((char) c))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HTTP_DETAIL_SAMPLE_INVALID");
            }
            if (!input.httpDetailSample().isEmpty()) snapshot.put("httpDetailSample", input.httpDetailSample());
        } else if (previous.snapshot().has("httpDetailSample")) {
            snapshot.set("httpDetailSample", previous.snapshot().get("httpDetailSample"));
        }
        JsonNode publicData = input.publicSourceData() == null
                ? previous.snapshot().path("publicSourceData") : input.publicSourceData();
        if (!publicData.isMissingNode()) {
            try {
                PublicSourceData.validate(publicData);
                if (!publicData.isEmpty()) snapshot.set("publicSourceData", publicData.deepCopy());
            } catch (java.io.IOException error) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PUBLIC_SOURCE_DATA_INVALID");
            }
        }
        JsonNode sourceConfig = input.sourceConfig() == null
                ? previous.snapshot().path("sourceConfig") : input.sourceConfig();
        if (!sourceConfig.isMissingNode()) {
            try {
                SourceConfig.validate(sourceConfig);
                // Presence is meaningful: an explicit empty config must not fall back
                // to the legacy publicSourceData projection at runtime.
                snapshot.set("sourceConfig", sourceConfig.deepCopy());
            } catch (java.io.IOException error) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SOURCE_CONFIG_INVALID");
            }
        }
        JsonNode ext = input.ext();
        if (ext == null || ext.isNull()) {
            snapshot.set("extFingerprint", previous.snapshot().path("extFingerprint"));
        } else {
            if (!ext.isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EXT_MUST_BE_OBJECT");
            }
            String canonicalExt = canonical(ext).toString();
            if (canonicalExt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65_536) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "EXT_TOO_LARGE");
            }
            if (source.getConfigFingerprintSalt() == null) {
                byte[] salt = new byte[32];
                new SecureRandom().nextBytes(salt);
                source.setConfigFingerprintSalt(HexFormat.of().formatHex(salt));
            }
            // Persist neither EXT nor its keys. A per-source salt stays in H2;
            // it is excluded from DTOs, reports, knowledge contexts and exports.
            snapshot.put("extFingerprint", Hashes.sha256(source.getConfigFingerprintSalt() + "\n" + canonicalExt));
        }
        JsonNode canonical = canonical(snapshot);
        String encoded = canonical.toString();
        String sha = Hashes.sha256(encoded);
        if (sha.equals(previous.sha256())) {
            return previous;
        }
        MaintenanceProfile profile = profiles.findBySourceIdAndSha256(sourceId, sha).orElseGet(() -> {
            MaintenanceProfile created = new MaintenanceProfile();
            created.setSourceId(sourceId);
            created.setSha256(sha);
            created.setSnapshotJson(encoded);
            return profiles.saveAndFlush(created);
        });
        source.setCurrentProfileId(profile.getId());
        sources.flush();
        return summary(profile);
    }

    private MaintenanceSource requireSource(String id) {
        return sources.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND"));
    }

    static boolean playbackNode(String id) {
        return Set.of("C10", "C11").contains(id) || id.matches("R0[1-8]|M(?:0[1-9]|10)|P0[1-5]");
    }

    private Set<String> nodeIds() {
        Set<String> result = new HashSet<>();
        knowledge.nodeCatalog().path("nodes").forEach(node -> result.add(node.path("id").asText()));
        return result;
    }

    private ProfileSummary summary(MaintenanceProfile profile) {
        try {
            return new ProfileSummary(profile.getId(), profile.getSourceId(), profile.getSha256(),
                    mapper.readTree(profile.getSnapshotJson()), profile.getCreatedAt());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored profile JSON is invalid", e);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<String> keys = new ArrayList<>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.sort(Comparator.naturalOrder());
            keys.forEach(key -> result.set(key, canonical(value.get(key))));
            return result;
        }
        if (value.isArray()) {
            var result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value.deepCopy();
    }
}
