package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.SourceArchiveImport;
import cn.har01d.atmaintenance.repository.SourceImportRepository;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

@Service
@RequiredArgsConstructor
public class SourceImportService {
    private static final int MAX_ARCHIVE_BYTES = 4_194_304;
    private static final int MAX_EXPANDED_BYTES = 8_388_608;
    private static final Set<String> REQUIRED_FILES = Set.of(
            "source.py", "source.json", "profile.json", "knowledge/catalog.json", "capability-nodes.json");
    private static final Set<String> ALLOWED_FILES = Set.of(
            "manifest.json", "source.py", "source.json", "profile.json", "knowledge/catalog.json",
            "capability-nodes.json", "at-binding.json", "schedule-policy.json", "schedule-state.json", "schedule-observations.json",
            "probe.json", "probe-profile.json", "probe-runtime.json", "probe-execution-policy.json",
            "probe-at-binding.json", "probe-schedule-policy.json");
    private static final Set<String> CANDIDATE_FILES = Set.of(
            "manifest.json", "baseline.py", "candidate.py", "proposal.json", "repair.json", "candidate.json",
            "rounds.json", "model-calls.json", "provenance.json", "frozen.json", "baseline-probe.json",
            "profile.json", "knowledge-context.json", "policy.json");
    private static final ObjectMapper ARCHIVE_JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
                    .maxStringLength(1_048_576).maxNumberLength(64).build()).build());
    private final SourceImportRepository imports;
    private final SourceService sources;
    private final ProfileService profiles;
    private final KnowledgeService knowledge;
    private final RepairPatch patches;
    private final Validator validator;

    public SourceImportPreview preview(PreviewSourceImportRequest input) {
        return inspect(decode(input.archiveBase64())).preview();
    }

    @Transactional
    public SourceImportSummary confirm(ConfirmSourceImportRequest input) {
        byte[] archive = decode(input.archiveBase64());
        String archiveSha = Hashes.sha256(archive);
        if (!archiveSha.equals(input.expectedArchiveSha256())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "IMPORT_ARCHIVE_CHANGED");
        }
        String requestSha = Hashes.sha256(ARCHIVE_JSON.createArrayNode().add(archiveSha)
                .add(input.name().trim()).add(input.filename()).toString());
        var existing = imports.findByRequestKey(input.requestKey());
        if (existing.isPresent()) {
            if (!existing.get().getRequestSha256().equals(requestSha)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "IMPORT_REQUEST_KEY_REUSED");
            }
            // Replays return the historical receipt without changing an edited source.
            return summary(existing.get());
        }
        var inspected = inspect(archive);
        var preview = inspected.preview();
        var source = sources.create(new CreateSourceRequest(input.name(), null, input.filename(), null,
                inspected.content()));
        var profile = preview.profile();
        var restored = profiles.update(source.id(), new UpdateProfileRequest(
                profiles.get(source.id()).sha256(), profile.path("runtime").asText(),
                profile.path("runtimeRevision").asText(), nodes(profile.get("requiredNodes")),
                nodes(profile.get("optionalNodes")), null, profile.get("publicSourceData"),
                profile.has("sourceConfig") ? profile.get("sourceConfig") : null,
                profile.has("httpPlaybackGroup") ? profile.get("httpPlaybackGroup").intValue() : null,
                profile.has("httpDetailSample") ? profile.get("httpDetailSample").textValue() : null,
                profile.has("paginationMode") ? profile.get("paginationMode").textValue() : null));
        SourceArchiveImport record = new SourceArchiveImport();
        record.setSourceId(source.id());
        record.setRequestKey(input.requestKey());
        record.setRequestSha256(requestSha);
        record.setArchiveSha256(archiveSha);
        record.setArchiveBase64(Base64.getEncoder().encodeToString(archive));
        record.setOriginalSourceId(preview.originalSourceId());
        record.setArtifactSha256(preview.artifactSha256());
        record.setOriginalProfileSha256(preview.profileSha256());
        record.setRestoredProfileSha256(restored.sha256());
        record.setConfigRebindRequired(preview.configRebindRequired());
        return summary(imports.saveAndFlush(record));
    }

    @Transactional(readOnly = true)
    public SourceImportSummary get(String id) {
        return summary(requireImport(id));
    }

    @Transactional(readOnly = true)
    public List<SourceImportSummary> list(String sourceId) {
        sources.requireSource(sourceId);
        return imports.findTop20BySourceIdOrderByCreatedAtDesc(sourceId).stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public byte[] archive(String id) {
        var record = requireImport(id);
        byte[] body = Base64.getDecoder().decode(record.getArchiveBase64());
        if (!Hashes.sha256(body).equals(record.getArchiveSha256())) {
            throw new IllegalStateException("Stored import archive identity mismatch");
        }
        return body;
    }

    @Transactional(readOnly = true)
    public List<ImportedCandidateSummary> candidates(String sourceId) {
        sources.requireSource(sourceId);
        var result = new ArrayList<ImportedCandidateSummary>();
        for (var record : imports.findTop20BySourceIdOrderByCreatedAtDesc(sourceId)) {
            var inspected = inspect(archive(record.getId()));
            if (inspected.proposal() != null) result.add(candidateSummary(record, inspected));
        }
        return List.copyOf(result);
    }

    @Transactional(readOnly = true)
    public ImportedCandidateSummary candidate(String id) {
        var record = requireImport(id);
        var inspected = inspect(archive(id));
        if (inspected.proposal() == null) throw invalid("IMPORT_CANDIDATE_REQUIRED");
        return candidateSummary(record, inspected);
    }

    private ImportedCandidateSummary candidateSummary(SourceArchiveImport record, Inspected inspected) {
        return new ImportedCandidateSummary(record.getId(), record.getSourceId(), record.getArchiveSha256(),
                inspected.candidate().path("id").asText(), inspected.candidate().path("modelCallId").textValue(),
                inspected.preview().artifactSha256(), inspected.preview().candidateSha256(),
                "archived_candidate_data_only");
    }

    @Transactional(readOnly = true)
    public RepairProposal proposal(String id) {
        var inspected = inspect(archive(id));
        if (inspected.proposal() == null) throw invalid("IMPORT_CANDIDATE_REQUIRED");
        return inspected.proposal();
    }

    @Transactional(readOnly = true)
    public ObjectNode provenance(String id) {
        var imported = candidate(id);
        return ARCHIVE_JSON.createObjectNode().put("origin", "imported_candidate")
                .put("sourceImportId", imported.importId()).put("archiveSha256", imported.archiveSha256())
                .put("originalCandidateId", imported.originalCandidateId())
                .put("originalModelCallId", imported.originalModelCallId())
                .put("authority", imported.authority()).put("localModelCall", false);
    }

    private SourceArchiveImport requireImport(String id) {
        return imports.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SOURCE_IMPORT_NOT_FOUND"));
    }

    private byte[] decode(String encoded) {
        if (encoded.length() > 5_592_408) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "IMPORT_ARCHIVE_TOO_LARGE");
        }
        byte[] archive;
        try {
            archive = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            throw invalid("IMPORT_BASE64_INVALID");
        }
        if (archive.length > MAX_ARCHIVE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "IMPORT_ARCHIVE_TOO_LARGE");
        }
        return archive;
    }

    private Inspected inspect(byte[] archive) {
        Map<String, byte[]> files = readArchive(archive);
        if (!files.containsKey("candidate.py")) {
            require(ALLOWED_FILES.containsAll(files.keySet()) && files.keySet().containsAll(REQUIRED_FILES),
                    "IMPORT_REQUIRED_FILE_MISSING");
        }
        var manifest = json(files.get("manifest.json"));
        require(manifest.isObject() && manifest.path("schemaVersion").isInt()
                && manifest.path("schemaVersion").intValue() == 1, "IMPORT_FORMAT_UNSUPPORTED");
        var records = manifest.path("files");
        require(records.isObject(), "IMPORT_MANIFEST_INVALID");
        Set<String> declared = new HashSet<>();
        records.fieldNames().forEachRemaining(declared::add);
        Set<String> actual = new HashSet<>(files.keySet());
        actual.remove("manifest.json");
        require(declared.equals(actual), "IMPORT_MANIFEST_INVALID");
        for (String name : actual) {
            var record = records.get(name);
            require(record.isObject() && record.size() == 2 && record.path("bytes").isIntegralNumber()
                    && record.path("bytes").canConvertToLong()
                    && record.path("bytes").longValue() == files.get(name).length
                    && record.path("sha256").isTextual()
                    && Hashes.sha256(files.get(name)).equals(record.path("sha256").textValue()),
                    "IMPORT_FILE_HASH_MISMATCH");
        }
        if (files.containsKey("candidate.py")) return inspectCandidate(archive, files, manifest);
        for (var entry : Map.of("artifactSha256", "source.py", "profileSha256", "profile.json",
                "knowledgeSha256", "knowledge/catalog.json", "nodeCatalogSha256", "capability-nodes.json").entrySet()) {
            require(manifest.path(entry.getKey()).isTextual()
                    && Hashes.sha256(files.get(entry.getValue())).equals(manifest.path(entry.getKey()).textValue()),
                    "IMPORT_IDENTITY_MISMATCH");
        }
        var metadata = json(files.get("source.json"));
        String artifactSha = manifest.path("artifactSha256").asText();
        require(metadata.isObject() && artifactSha.equals(metadata.path("artifactSha256").asText())
                && text(metadata.get("sourceId"), 36)
                && metadata.path("sourceId").asText().matches(
                        "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                && text(metadata.get("name"), 200) && metadata.path("state").asText().equals("saved_only"),
                "IMPORT_SOURCE_METADATA_INVALID");
        var profile = json(files.get("profile.json"));
        validateProfile(profile);
        byte[] sourceBytes = files.get("source.py");
        require(sourceBytes.length <= 1_048_576, "IMPORT_SOURCE_TOO_LARGE");
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(sourceBytes)).toString();
        } catch (CharacterCodingException error) {
            throw invalid("IMPORT_SOURCE_UTF8_INVALID");
        }
        require(!content.isBlank(), "IMPORT_SOURCE_EMPTY");
        int expandedBytes = files.values().stream().mapToInt(bytes -> bytes.length).sum();
        var preview = new SourceImportPreview(Hashes.sha256(archive), metadata.path("sourceId").asText(),
                metadata.path("name").asText(), artifactSha, manifest.path("profileSha256").asText(),
                profile, files.size(), archive.length, expandedBytes, !profile.path("extFingerprint").isNull(),
                files.containsKey("probe.json"), "archive_hashes_checked",
                "仅核对包内字节一致性，不认证作者或历史报告。确认后恢复为新本地源；"
                        + "原包完整保留，报告只归档，EXT 须重新绑定，不恢复 AT 绑定、巡检或模型授权。",
                "source", null);
        return new Inspected(preview, content, null, null);
    }

    private Inspected inspectCandidate(byte[] archive, Map<String, byte[]> files, JsonNode manifest) {
        Set<String> expected = new HashSet<>(CANDIDATE_FILES);
        JsonNode delivery = manifest.path("delivery");
        if (!delivery.isMissingNode()) {
            String entry = delivery.path("entryPath").asText();
            require(deliveryEntry(entry), "IMPORT_DELIVERY_INVALID");
            expected.add(entry);
            expected.add("交付说明.md");
        }
        require(files.keySet().equals(expected), "IMPORT_CANDIDATE_FILES_INVALID");
        var repair = json(files.get("repair.json"));
        var candidate = json(files.get("candidate.json"));
        var frozen = json(files.get("frozen.json"));
        var baseline = json(files.get("baseline-probe.json"));
        var profile = json(files.get("profile.json"));
        var proposalJson = json(files.get("proposal.json"));
        validateProfile(profile);
        String baselineSha = Hashes.sha256(files.get("baseline.py"));
        String candidateSha = Hashes.sha256(files.get("candidate.py"));
        String proposalSha = Hashes.sha256(files.get("proposal.json"));
        String profileSha = Hashes.sha256(files.get("profile.json"));
        require(uuid(repair.path("sourceId")) && uuid(repair.path("id")) && uuid(candidate.path("id"))
                && repair.path("id").equals(candidate.path("repairId"))
                && repair.path("sourceId").equals(baseline.path("sourceId"))
                && baselineSha.equals(manifest.path("baselineArtifactSha256").asText())
                && baselineSha.equals(repair.path("baselineArtifactSha256").asText())
                && baselineSha.equals(baseline.path("artifactSha256").asText())
                && candidateSha.equals(manifest.path("candidateSha256").asText())
                && candidateSha.equals(candidate.path("sha256").asText())
                && proposalSha.equals(manifest.path("proposalSha256").asText())
                && proposalSha.equals(candidate.path("proposalSha256").asText())
                && Hashes.sha256(files.get("frozen.json")).equals(manifest.path("frozenSha256").asText())
                && frozen.equals(repair.path("frozen")) && baseline.equals(frozen.path("baseline"))
                && profile.equals(baseline.path("profile")) && profileSha.equals(baseline.path("profileSha256").asText())
                && proposalJson.equals(candidate.path("proposal")), "IMPORT_CANDIDATE_IDENTITY_MISMATCH");
        String content = sourceText(files.get("baseline.py"));
        String candidateContent = sourceText(files.get("candidate.py"));
        RepairProposal proposal;
        try {
            proposal = ARCHIVE_JSON.treeToValue(proposalJson, RepairProposal.class);
        } catch (IOException | IllegalArgumentException error) {
            throw invalid("IMPORT_CANDIDATE_PROPOSAL_INVALID");
        }
        require(proposal != null && validator.validate(proposal).isEmpty()
                && proposalJson.equals(ARCHIVE_JSON.valueToTree(proposal))
                && baselineSha.equals(proposal.expectedBaselineSha256()), "IMPORT_CANDIDATE_PROPOSAL_INVALID");
        require(patches.apply(content, proposal).equals(candidateContent), "IMPORT_CANDIDATE_PATCH_MISMATCH");
        if (!delivery.isMissingNode()) {
            String entry = delivery.path("entryPath").asText();
            require(delivery.path("filename").asText().equals(entry.substring(3))
                    && delivery.path("version").asText().matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,9}")
                    && delivery.path("pluginName").isTextual()
                    && delivery.path("pluginName").asText().codePoints().anyMatch(
                        c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                    && delivery.path("candidateSha256").equals(manifest.path("candidateSha256"))
                    && Arrays.equals(files.get(entry), files.get("candidate.py")), "IMPORT_DELIVERY_INVALID");
        }
        var preview = new SourceImportPreview(Hashes.sha256(archive), repair.path("sourceId").asText(),
                "候选移交 " + candidate.path("id").asText().substring(0, 8), baselineSha, profileSha, profile,
                files.size(), archive.length, files.values().stream().mapToInt(bytes -> bytes.length).sum(),
                !profile.path("extFingerprint").isNull(), true, "archive_hashes_checked",
                "基线与候选补丁保存为新本地源，历史模型和验收结果仅归档；"
                        + "不恢复授权、额度或通过状态。重新检查后可复用同一候选，无需再次请求模型。",
                "repair_candidate", candidateSha);
        return new Inspected(preview, content, proposal, candidate);
    }

    private String sourceText(byte[] bytes) {
        require(bytes.length <= 1_048_576, "IMPORT_SOURCE_TOO_LARGE");
        try {
            String content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            require(!content.isBlank(), "IMPORT_SOURCE_EMPTY");
            return content;
        } catch (CharacterCodingException error) {
            throw invalid("IMPORT_SOURCE_UTF8_INVALID");
        }
    }

    private boolean uuid(JsonNode value) {
        return value.isTextual() && value.textValue().matches(
                "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }

    private Map<String, byte[]> readArchive(byte[] archive) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile("at-maintenance-source-import-", ".zip");
            Files.write(temporary, archive);
            Map<String, byte[]> files = new LinkedHashMap<>();
            try (var zip = new ZipFile(temporary.toFile(), StandardCharsets.UTF_8)) {
                int retained = 0;
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    String name = entry.getName();
                    require(!entry.isDirectory() && (ALLOWED_FILES.contains(name) || CANDIDATE_FILES.contains(name)
                            || name.equals("交付说明.md") || deliveryEntry(name))
                            && !files.containsKey(name),
                            "IMPORT_ENTRY_INVALID");
                    require(entry.getSize() >= 0 && entry.getSize() <= MAX_EXPANDED_BYTES - retained,
                            "IMPORT_EXPANDED_LIMIT");
                    byte[] body;
                    try (var stream = zip.getInputStream(entry)) {
                        body = stream.readNBytes(MAX_EXPANDED_BYTES - retained + 1);
                    }
                    require(body.length <= MAX_EXPANDED_BYTES - retained && body.length == entry.getSize(),
                            "IMPORT_EXPANDED_LIMIT");
                    CRC32 crc = new CRC32();
                    crc.update(body);
                    require(crc.getValue() == entry.getCrc(), "IMPORT_ZIP_INVALID");
                    retained += body.length;
                    files.put(name, body);
                }
            }
            require(files.containsKey("manifest.json"), "IMPORT_REQUIRED_FILE_MISSING");
            return files;
        } catch (IOException error) {
            throw invalid("IMPORT_ZIP_INVALID");
        } finally {
            if (temporary != null) {
                try {
                    Files.delete(temporary);
                } catch (IOException error) {
                    throw new IllegalStateException("Import temporary file cleanup failed", error);
                }
            }
        }
    }

    private boolean deliveryEntry(String name) {
        return name.matches("(交付|候选)/[^/\\\\<>:\"|?*\\p{Cntrl}]{1,160}\\.py")
                && !name.contains("..");
    }

    private JsonNode json(byte[] body) {
        try (var parser = ARCHIVE_JSON.getFactory().createParser(body)) {
            JsonNode value = ARCHIVE_JSON.readTree(parser);
            require(value != null && parser.nextToken() == null, "IMPORT_JSON_INVALID");
            return value;
        } catch (IOException error) {
            throw invalid("IMPORT_JSON_INVALID");
        }
    }

    private void validateProfile(JsonNode profile) {
        int optionalFields = (profile.has("publicSourceData") ? 1 : 0)
                + (profile.has("sourceConfig") ? 1 : 0)
                + (profile.has("httpPlaybackGroup") ? 1 : 0)
                + (profile.has("httpDetailSample") ? 1 : 0)
                + (profile.has("paginationMode") ? 1 : 0);
        require(profile.isObject() && profile.size() == 7 + optionalFields
                && profile.path("schemaVersion").isInt()
                && profile.path("schemaVersion").intValue() == 1
                && profile.path("authority").asText().equals("declared")
                && Set.of("atvp", "t4", "fongmi").contains(profile.path("runtime").asText())
                && text(profile.get("runtimeRevision"), 200)
                && profile.has("extFingerprint") && (profile.path("extFingerprint").isNull()
                || profile.path("extFingerprint").isTextual()
                && profile.path("extFingerprint").asText().matches("[a-f0-9]{64}")), "IMPORT_PROFILE_INVALID");
        if (profile.has("publicSourceData")) {
            try {
                PublicSourceData.validate(profile.get("publicSourceData"));
                require(!profile.get("publicSourceData").isEmpty(), "IMPORT_PROFILE_INVALID");
            } catch (IOException error) {
                throw invalid("IMPORT_PROFILE_INVALID");
            }
        }
        if (profile.has("sourceConfig")) {
            try {
                SourceConfig.validate(profile.get("sourceConfig"));
            } catch (IOException error) {
                throw invalid("IMPORT_PROFILE_INVALID");
            }
        }
        if (profile.has("httpPlaybackGroup")) {
            JsonNode group = profile.get("httpPlaybackGroup");
            require(group.isInt() && group.intValue() >= 0 && group.intValue() <= 31,
                    "IMPORT_PROFILE_INVALID");
        }
        if (profile.has("httpDetailSample")) {
            JsonNode sample = profile.get("httpDetailSample");
            require(text(sample, 512) && sample.textValue().chars().noneMatch(c -> Character.isISOControl(c)
                    || Character.isSurrogate((char) c)), "IMPORT_PROFILE_INVALID");
        }
        if (profile.has("paginationMode")) {
            require(profile.get("paginationMode").isTextual()
                    && Set.of("exact-total", "page-window").contains(profile.get("paginationMode").textValue()),
                    "IMPORT_PROFILE_INVALID");
        }
        var required = nodes(profile.get("requiredNodes"));
        var optional = nodes(profile.get("optionalNodes"));
        Set<String> known = new HashSet<>();
        knowledge.nodeCatalog().path("nodes").forEach(node -> known.add(node.path("id").asText()));
        require(new HashSet<>(required).size() == required.size() && new HashSet<>(optional).size() == optional.size()
                && required.containsAll(Set.of("S01", "S02", "S03")) && Collections.disjoint(required, optional)
                && known.containsAll(required) && known.containsAll(optional), "IMPORT_PROFILE_NODES_INVALID");
    }

    private List<String> nodes(JsonNode value) {
        require(value != null && value.isArray() && value.size() <= 44, "IMPORT_PROFILE_NODES_INVALID");
        List<String> result = new ArrayList<>();
        for (var node : value) {
            require(node.isTextual(), "IMPORT_PROFILE_NODES_INVALID");
            result.add(node.textValue());
        }
        return result;
    }

    private boolean text(JsonNode value, int maximum) {
        return value != null && value.isTextual() && !value.textValue().isBlank()
                && value.textValue().length() <= maximum;
    }

    private void require(boolean condition, String code) {
        if (!condition) throw invalid(code);
    }

    private ResponseStatusException invalid(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private SourceImportSummary summary(SourceArchiveImport record) {
        return new SourceImportSummary(record.getId(), record.getSourceId(), record.getArchiveSha256(),
                record.getRequestSha256(), record.getOriginalSourceId(), record.getArtifactSha256(),
                record.getOriginalProfileSha256(), record.getRestoredProfileSha256(),
                record.isConfigRebindRequired(), record.getCreatedAt(), "imported_data_only");
    }

    private record Inspected(SourceImportPreview preview, String content, RepairProposal proposal, JsonNode candidate) {
    }
}
