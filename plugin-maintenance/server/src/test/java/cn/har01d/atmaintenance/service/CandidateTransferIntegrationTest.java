package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:candidate-transfer;DB_CLOSE_DELAY=-1",
        "maintenance.at.origin=", "maintenance.model.enabled=false",
        "maintenance.scheduling.enabled=false", "maintenance.container.enabled=false"
})
class CandidateTransferIntegrationTest {
    @Autowired SourceImportService imports;
    @Autowired SourceService sources;
    @Autowired ProbeService probes;
    @Autowired ProbeStore probeStore;
    @Autowired RepairStore repairs;
    @Autowired RepairService repairService;
    @Autowired RepairExportService exports;
    @Autowired ReleasePlanService plans;
    @Autowired ReleasePlanExportService planExports;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void exportedCandidateTransfersAsDataThenUsesCurrentStaticValidationWithoutAnotherModelCall() throws Exception {
        byte[] archive = originalArchive();
        var preview = imports.preview(new PreviewSourceImportRequest(encoded(archive)));
        assertEquals("repair_candidate", preview.archiveKind());
        var originalFiles = files(archive);
        var delivery = mapper.readTree(originalFiles.get("manifest.json")).path("delivery");
        assertFalse(delivery.isMissingNode());
        assertArrayEquals(originalFiles.get("candidate.py"), originalFiles.get(delivery.path("entryPath").asText()));
        var saved = save(archive);
        assertArrayEquals(archive, imports.archive(saved.id()));
        var historical = imports.candidate(saved.id());
        assertEquals(preview.candidateSha256(), historical.candidateSha256());
        assertEquals(List.of(historical), imports.candidates(saved.sourceId()));
        assertEquals(preview.artifactSha256(), sources.get(saved.sourceId()).artifactSha256());
        assertTrue(probeStore.list(saved.sourceId()).isEmpty());
        assertTrue(repairs.list(saved.sourceId()).isEmpty());
        for (String table : List.of("maintenance_at_binding", "maintenance_model_consent",
                "maintenance_probe_schedule"))
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table + " where source_id=?",
                    Integer.class, saved.sourceId()));
        long modelCalls = jdbc.queryForObject("select count(*) from maintenance_model_call", Long.class);
        var currentRepair = freshRepair(saved.sourceId());
        var input = new ImportedCandidateRequest(saved.id(), saved.archiveSha256());
        var started = repairService.proposeImported(currentRepair.id(), input);
        var checked = awaitCandidate(started.id());
        assertEquals("STATIC_VALIDATED", checked.status());
        assertEquals(historical.candidateSha256(), checked.sha256());
        assertEquals(saved.id(), checked.sourceImportId());
        assertNull(checked.modelCallId());
        assertFalse(checked.result().path("runtimeRequested").asBoolean());
        assertFalse(checked.result().path("repairSuccessful").asBoolean());
        assertEquals(1, repairs.get(currentRepair.id()).usedRounds());
        assertEquals(checked.id(), repairService.proposeImported(currentRepair.id(), input).id());
        assertEquals(1, repairs.get(currentRepair.id()).usedRounds());
        assertEquals(modelCalls, jdbc.queryForObject("select count(*) from maintenance_model_call", Long.class));
        var reexported = files(exports.export(checked.id()));
        var provenance = mapper.readTree(reexported.get("provenance.json"));
        assertEquals("imported_candidate", provenance.path("origin").asText());
        assertEquals(saved.archiveSha256(), provenance.path("archiveSha256").asText());
        assertEquals(historical.originalCandidateId(), provenance.path("originalCandidateId").asText());
        assertFalse(provenance.path("localModelCall").asBoolean());
        assertArrayEquals(files(archive).get("candidate.py"), reexported.get("candidate.py"));
    }

    @Test
    void wrongArchiveSourceAndChangedBaselineCannotReserveACandidateOrConsumeARound() throws Exception {
        byte[] archive = originalArchive();
        var saved = save(archive);
        var repair = freshRepair(saved.sourceId());
        var another = save(archive);
        assertConflict("IMPORTED_CANDIDATE_INPUTS_CHANGED", () -> repairs.reserveImported(repair.id(),
                new ImportedCandidateRequest(saved.id(), "0".repeat(64))));
        assertConflict("IMPORTED_CANDIDATE_INPUTS_CHANGED", () -> repairs.reserveImported(repair.id(),
                new ImportedCandidateRequest(another.id(), another.archiveSha256())));
        var source = sources.get(saved.sourceId());
        sources.addArtifact(source.id(), new AddArtifactRequest(source.artifactSha256(),
                sources.artifact(source.id(), source.artifactSha256()).getBody() + "\n# changed\n"));
        assertConflict("REPAIR_INPUTS_CHANGED", () -> repairs.reserveImported(repair.id(),
                new ImportedCandidateRequest(saved.id(), saved.archiveSha256())));
        assertTrue(repairs.candidates(repair.id()).isEmpty());
        assertEquals(0, repairs.get(repair.id()).usedRounds());
    }

    @Test
    void releasePreparationKeepsTheImportedProvenanceWithoutGrantingRuntimeOrDeploymentAcceptance() throws Exception {
        byte[] archive = originalArchive();
        var saved = save(archive);
        var repair = freshRepair(saved.sourceId());
        var candidate = awaitCandidate(repairService.proposeImported(repair.id(),
                new ImportedCandidateRequest(saved.id(), saved.archiveSha256())).id());
        var plan = plans.create(saved.sourceId(), new StartReleasePlanRequest(candidate.id(),
                saved.artifactSha256(), candidate.sha256(), UUID.randomUUID().toString()));
        assertFalse(plan.localApplyEligible());
        assertFalse(plan.publishEligible());
        assertTrue(plan.blockers().contains("ISOLATED_RUNTIME_VALIDATION_REQUIRED"));
        var provenance = mapper.readTree(files(planExports.export(plan.id())).get("provenance.json"));
        assertEquals(imports.provenance(saved.id()), provenance);
        assertEquals("imported_candidate", plans.frozen(plan.id()).path("provenance").path("origin").asText());
        assertEquals(saved.archiveSha256(), provenance.path("archiveSha256").asText());
    }

    @Test
    void rehashingUnrelatedCandidateBytesDoesNotMakeThemTheArchivedPatch() throws Exception {
        var files = files(originalArchive());
        byte[] changed = "class Spider: pass\n".getBytes(StandardCharsets.UTF_8);
        files.put("candidate.py", changed);
        var candidate = (ObjectNode) mapper.readTree(files.get("candidate.json"));
        candidate.put("sha256", Hashes.sha256(changed));
        files.put("candidate.json", mapper.writeValueAsBytes(candidate));
        var manifest = (ObjectNode) mapper.readTree(files.get("manifest.json"));
        manifest.put("candidateSha256", Hashes.sha256(changed));
        byte[] tampered = rehash(files, manifest);
        var error = assertThrows(ResponseStatusException.class,
                () -> imports.preview(new PreviewSourceImportRequest(encoded(tampered))));
        assertEquals("IMPORT_CANDIDATE_PATCH_MISMATCH", error.getReason());
    }

    @Test
    void frozenProfileAndProposalMustMatchTheirClaimedCandidate() throws Exception {
        var files = files(originalArchive());
        var profile = (ObjectNode) mapper.readTree(files.get("profile.json"));
        profile.put("runtimeRevision", "changed");
        files.put("profile.json", mapper.writeValueAsBytes(profile));
        byte[] tampered = rehash(files, (ObjectNode) mapper.readTree(files.get("manifest.json")));
        assertEquals("IMPORT_CANDIDATE_IDENTITY_MISMATCH", assertThrows(ResponseStatusException.class,
                () -> imports.preview(new PreviewSourceImportRequest(encoded(tampered)))).getReason());
    }

    @Test
    @EnabledIfSystemProperty(named = "maintenance.test.import-archive", matches = ".+")
    void ownedRealModelArchiveRestoresExactBytesAndHistoryWithoutExecutingTheSpider() throws Exception {
        byte[] archive = Files.readAllBytes(Path.of(System.getProperty("maintenance.test.import-archive")));
        var saved = save(archive);
        var candidate = imports.candidate(saved.id());
        assertEquals(System.getProperty("maintenance.test.candidate-sha"), candidate.candidateSha256());
        assertEquals("d11c0ce3-7ff4-42d6-8556-780668fa7da1", candidate.originalModelCallId());
        assertEquals("26778b88-6500-4543-89f9-47670f71fdae", candidate.originalCandidateId());
        assertTrue(probeStore.list(saved.sourceId()).isEmpty());
        assertTrue(repairs.list(saved.sourceId()).isEmpty());
        assertArrayEquals(files(archive).get("baseline.py"), sources.artifact(saved.sourceId(),
                saved.artifactSha256()).getBody().getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(archive, imports.archive(saved.id()));
    }

    private byte[] originalArchive() throws Exception {
        var fixture = mapper.readTree(new ClassPathResource("worker/repair-demo.json").getContentAsByteArray());
        var source = sources.create(new CreateSourceRequest("transfer fixture", null, "source.py", null,
                fixture.path("content").asText()));
        var repair = freshRepair(source.id());
        var proposal = (ObjectNode) fixture.path("proposal");
        proposal.put("expectedBaselineSha256", source.artifactSha256());
        var candidate = awaitCandidate(repairService.propose(repair.id(),
                mapper.treeToValue(proposal, RepairProposal.class)).id());
        assertEquals("STATIC_VALIDATED", candidate.status());
        return exports.export(candidate.id());
    }

    private RepairSummary freshRepair(String sourceId) throws Exception {
        String id = probes.start(sourceId).id();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            var run = probeStore.get(id);
            if (!Set.of("QUEUED", "RUNNING").contains(run.status())) {
                assertEquals("COMPLETED", run.status(), run.errorCode());
                return repairs.create(sourceId, new StartRepairRequest(id, sources.get(sourceId).artifactSha256()));
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Static baseline timed out");
    }

    private RepairCandidateSummary awaitCandidate(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            var candidate = repairs.candidate(id);
            if (!Set.of("QUEUED", "RUNNING").contains(candidate.status())) return candidate;
            Thread.sleep(25);
        }
        throw new AssertionError("Candidate static validation timed out");
    }

    private SourceImportSummary save(byte[] archive) {
        return imports.confirm(new ConfirmSourceImportRequest(encoded(archive), Hashes.sha256(archive),
                UUID.randomUUID().toString(), "transferred candidate", "source.py"));
    }

    private String encoded(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }

    private void assertConflict(String code, org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(ResponseStatusException.class, action);
        assertEquals(409, error.getStatusCode().value());
        assertEquals(code, error.getReason());
    }

    private Map<String, byte[]> files(byte[] bytes) throws Exception {
        var result = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) result.put(entry.getName(), zip.readAllBytes());
        }
        return result;
    }

    private byte[] rehash(Map<String, byte[]> files, ObjectNode manifest) throws Exception {
        var records = manifest.putObject("files");
        files.forEach((name, bytes) -> {
            if (!name.equals("manifest.json")) records.putObject(name)
                    .put("bytes", bytes.length).put("sha256", Hashes.sha256(bytes));
        });
        files.put("manifest.json", mapper.writeValueAsBytes(manifest));
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
