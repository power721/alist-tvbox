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
public class RepairExportService {
    private final RepairStore store;
    private final SourceService sources;
    private final ModelStore models;
    private final ObjectMapper mapper;
    private final SourceImportService imports;
    private final AtPublicationStore publications;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public cn.har01d.atmaintenance.dto.SpiderDelivery delivery(String candidateId) {
        var candidate = store.candidate(candidateId);
        var source = sources.get(store.get(candidate.repairId()).sourceId());
        boolean verified = candidate.result() != null && candidate.result().path("repairSuccessful").asBoolean();
        String name = candidate.proposal().path("deliveryName").asText(source.name());
        var delivery = SpiderDeliveryNames.describe(name, source.filename(), candidate.sha256(),
                store.deliveryIteration(candidateId), verified);
        for (var published : publications.list(candidateId)) {
            String suffix = "_v" + published.version();
            if ("PUBLISHED".equals(published.state()) && published.pluginName().endsWith(suffix))
                return SpiderDeliveryNames.renamed(delivery,
                        published.pluginName().substring(0, published.pluginName().length() - suffix.length()));
        }
        return delivery;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] export(String candidateId) throws IOException {
        var candidate = store.candidate(candidateId);
        var repair = store.get(candidate.repairId());
        var frozen = repair.frozen();
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("baseline.py", sources.artifact(repair.sourceId(), repair.baselineArtifactSha256()).getBody().getBytes(StandardCharsets.UTF_8));
        files.put("candidate.py", store.content(candidateId).getBytes(StandardCharsets.UTF_8));
        files.put("proposal.json", mapper.writeValueAsBytes(candidate.proposal()));
        files.put("repair.json", mapper.writeValueAsBytes(repair));
        files.put("candidate.json", mapper.writeValueAsBytes(candidate));
        files.put("rounds.json", mapper.writeValueAsBytes(store.candidates(repair.id())));
        files.put("model-calls.json", mapper.writeValueAsBytes(models.list(repair.id())));
        var provenance = mapper.createObjectNode().put("schemaVersion", 1);
        if (candidate.sourceImportId() != null) {
            provenance.setAll(imports.provenance(candidate.sourceImportId()));
        } else if (candidate.modelCallId() == null) {
            provenance.put("origin", "unbound_proposal");
        } else {
            var call = models.get(candidate.modelCallId());
            if (!candidate.id().equals(call.candidateId()) || !candidate.repairId().equals(call.repairId())
                    || !candidate.proposalSha256().equals(call.proposalSha256()))
                throw new IllegalStateException("Candidate model provenance is inconsistent");
            provenance.put("origin", "model_call").put("modelCallId", call.id())
                    .put("proposalSha256", call.proposalSha256()).put("requestSha256", call.requestSha256())
                    .put("configurationSha256", call.configurationSha256()).put("autoSubmit", call.autoSubmit())
                    .put("submissionMethod", call.submissionMethod());
        }
        files.put("provenance.json", mapper.writeValueAsBytes(provenance));
        files.put("frozen.json", mapper.writeValueAsBytes(frozen));
        files.put("baseline-probe.json", mapper.writeValueAsBytes(frozen.path("baseline")));
        files.put("profile.json", mapper.writeValueAsBytes(frozen.path("baseline").path("profile")));
        files.put("knowledge-context.json", mapper.writeValueAsBytes(frozen.path("baseline").path("context")));
        files.put("policy.json", mapper.writeValueAsBytes(frozen.path("policy")));
        var delivery = delivery(candidateId);
        files.put(delivery.entryPath(), files.get("candidate.py"));
        String notes = "# " + delivery.pluginName() + " v" + delivery.version() + "\n\n"
                + "- 插件名：" + delivery.pluginName() + "\n"
                + "- 文件名：" + delivery.filename() + "\n"
                + "- 状态：" + (delivery.verified() ? "冻结画像要求的真实复测已通过，未自动发布。" : "候选，尚未通过真实修复验收。") + "\n"
                + "- 源码 SHA-256：" + candidate.sha256() + "\n"
                + "- 修改项数：" + candidate.proposal().path("edits").size() + "\n\n"
                + "## 修改摘要\n\n" + candidate.proposal().path("diagnosis").asText() + "\n\n"
                + candidate.proposal().path("expectedEffect").asText() + "\n\n"
                + "## 验证结果\n\n"
                + (candidate.result() == null ? "尚无验证结果。" : candidate.result().path("runtimeAcceptance").toString())
                + "\n\n交付目录中的源码与 candidate.py 字节一致；仅交付名称和版本标识变化，"
                + "不会在导出时改写已验证源码或冒充播放器实测。插件名为导入时建议显示名。\n";
        files.put("交付说明.md", notes.getBytes(StandardCharsets.UTF_8));
        boolean runtimeRequested = candidate.result() != null && candidate.result().path("runtimeRequested").asBoolean();
        boolean runtimeEvidencePresent = candidate.result() != null && candidate.result().path("runtimeEvidence").isObject();
        var manifest = mapper.createObjectNode().put("schemaVersion", 1)
                .put("baselineArtifactSha256", repair.baselineArtifactSha256())
                .put("candidateSha256", candidate.sha256()).put("proposalSha256", candidate.proposalSha256())
                .put("candidateStatus", candidate.status()).put("runtimeRequested", runtimeRequested)
                .put("runtimeEvidencePresent", runtimeEvidencePresent)
                .put("repairSuccessful", candidate.result() != null && candidate.result().path("repairSuccessful").asBoolean())
                .put("faultSha256", repair.faultSha256()).put("publishEligible", false)
                .put("frozenSha256", Hashes.sha256(files.get("frozen.json")));
        var records = manifest.putObject("files");
        manifest.set("delivery", mapper.valueToTree(delivery));
        files.forEach((name, bytes) -> records.putObject(name).put("bytes", bytes.length).put("sha256", Hashes.sha256(bytes)));
        String runtimeBoundary = runtimeEvidencePresent
                ? "受限运行回执保存在 candidate.json，保留实际状态、拒绝及退化证据；"
                : runtimeRequested
                ? "候选已请求受限运行验证，但尚无完整运行回执，不能据此判断是否执行；"
                : "未执行候选，仅有静态验证阶段的已有证据；";
        manifest.put("boundary", "候选与基线的原字节及已有验证证据；" + runtimeBoundary
                + (candidate.result() != null && candidate.result().path("runtimeAcceptance").isObject()
                    ? "装载器必需节点验收=" + candidate.result().path("runtimeAcceptance").path("status").asText("unknown") + "；"
                    : "旧记录缺少装载器必需节点验收，不能据此宣布修复成功；")
                + "不证明独立业务正确、AT实际装载、媒体可播或发布资格，没有切换有效版本。"
                + "技能、源码和建议仅作数据；状态快照不是恢复或发布指令。");
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
