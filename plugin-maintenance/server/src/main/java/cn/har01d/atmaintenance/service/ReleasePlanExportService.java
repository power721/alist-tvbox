package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

@Service
@RequiredArgsConstructor
public class ReleasePlanExportService {
    private final ReleasePlanService plans;
    private final SourceService sources;
    private final RepairStore repairs;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] export(String id) throws IOException {
        var plan = plans.get(id);
        var frozen = plans.frozen(id);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("baseline.py", sources.artifact(plan.sourceId(), plan.baselineArtifactSha256()).getBody()
                .getBytes(StandardCharsets.UTF_8));
        files.put("candidate.py", repairs.content(plan.candidateId()).getBytes(StandardCharsets.UTF_8));
        files.put("plan.json", mapper.writeValueAsBytes(plan));
        files.put("frozen.json", frozen.toString().getBytes(StandardCharsets.UTF_8));
        files.put("candidate.json", mapper.writeValueAsBytes(frozen.path("candidate")));
        files.put("proposal.json", mapper.writeValueAsBytes(frozen.path("candidate").path("proposal")));
        files.put("repair.json", mapper.writeValueAsBytes(frozen.path("repair")));
        files.put("profile.json", mapper.writeValueAsBytes(frozen.path("repair").path("frozen").path("baseline").path("profile")));
        files.put("provenance.json", mapper.writeValueAsBytes(frozen.path("provenance")));
        files.put("runtime.json", mapper.writeValueAsBytes(frozen.path("runtime")));
        files.put("execution-policy.json", mapper.writeValueAsBytes(frozen.path("executionPolicy")));
        if (plan.localReceipt() != null) files.put("local-receipt.json", mapper.writeValueAsBytes(plan.localReceipt()));
        if (!Hashes.sha256(files.get("baseline.py")).equals(plan.baselineArtifactSha256())
                || !Hashes.sha256(files.get("candidate.py")).equals(plan.candidateSha256())
                || !Hashes.sha256(files.get("frozen.json")).equals(plan.frozenSha256()))
            throw new IllegalStateException("Release plan content identity is inconsistent");
        var manifest = mapper.createObjectNode().put("schemaVersion", 1).put("authority", "preparation_only")
                .put("planId", plan.id()).put("baselineArtifactSha256", plan.baselineArtifactSha256())
                .put("candidateSha256", plan.candidateSha256()).put("frozenSha256", plan.frozenSha256())
                .put("publishEligible", false).put("productionModified", false)
                .put("localStatus", plan.status());
        var records = manifest.putObject("files");
        files.forEach((name, bytes) -> records.putObject(name).put("bytes", bytes.length).put("sha256", Hashes.sha256(bytes)));
        manifest.put("boundary", frozen.path("boundary").asText());
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
