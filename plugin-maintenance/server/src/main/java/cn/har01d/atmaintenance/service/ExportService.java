package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
public class ExportService {
    private final SourceService sources;
    private final ProbeStore probes;
    private final KnowledgeService knowledge;
    private final ObjectMapper mapper;
    private final ProfileService profiles;
    private final AtBindingService bindings;
    private final ProbeScheduleStore schedules;
    private final ScheduleObservationService scheduleObservations;

    public byte[] export(String sourceId, String requestedSha) throws IOException {
        var source = sources.get(sourceId);
        String sha = requestedSha == null ? source.artifactSha256() : requestedSha;
        var artifact = sources.artifact(sourceId, sha);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("source.py", artifact.getBody().getBytes(StandardCharsets.UTF_8));
        files.put("source.json", mapper.writeValueAsBytes(Map.of(
                "sourceId", sourceId, "name", source.name(), "artifactSha256", sha,
                "createdAt", artifact.getCreatedAt(), "state", "saved_only")));
        files.put("knowledge/catalog.json", knowledge.catalogBytes());
        files.put("capability-nodes.json", knowledge.nodeCatalogBytes());
        var profile = profiles.get(sourceId);
        files.put("profile.json", profile.snapshot().toString().getBytes(StandardCharsets.UTF_8));
        var binding = bindings.get(sourceId);
        if (binding != null) files.put("at-binding.json", binding.snapshot().toString().getBytes(StandardCharsets.UTF_8));
        var schedule = schedules.get(sourceId);
        if (schedule.sha256() != null) {
            files.put("schedule-policy.json", schedule.policy().toString().getBytes(StandardCharsets.UTF_8));
            files.put("schedule-state.json", mapper.writeValueAsBytes(schedule));
            files.put("schedule-observations.json", mapper.writeValueAsBytes(scheduleObservations.get(sourceId)));
        }
        var run = probes.latest(sourceId, sha);
        if (run != null) {
            files.put("probe.json", mapper.writeValueAsBytes(run));
            if (run.profile() != null) {
                files.put("probe-profile.json", run.profile().toString().getBytes(StandardCharsets.UTF_8));
            }
            if (run.runtime() != null) {
                files.put("probe-runtime.json", run.runtime().toString().getBytes(StandardCharsets.UTF_8));
            }
            if (run.executionPolicy() != null)
                files.put("probe-execution-policy.json", run.executionPolicy().toString().getBytes(StandardCharsets.UTF_8));
            if (run.binding() != null) files.put("probe-at-binding.json", run.binding().toString().getBytes(StandardCharsets.UTF_8));
            if (run.schedule() != null) files.put("probe-schedule-policy.json", run.schedule().toString().getBytes(StandardCharsets.UTF_8));
        }
        Map<String, Object> hashes = new LinkedHashMap<>();
        files.forEach((name, bytes) -> hashes.put(name, Map.of("sha256", Hashes.sha256(bytes), "bytes", bytes.length)));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("artifactSha256", sha);
        manifest.put("generatedAt", Instant.now());
        manifest.put("knowledgeSha256", knowledge.sha256());
        manifest.put("nodeCatalogSha256", knowledge.nodeCatalogSha256());
        manifest.put("profileSha256", profile.sha256());
        if (binding != null) manifest.put("bindingSha256", binding.sha256());
        if (schedule.sha256() != null) manifest.put("scheduleSha256", schedule.sha256());
        if (run != null) {
            manifest.put("probeKnowledgeSha256", run.knowledgeSha256());
            if (run.profileSha256() != null) {
                manifest.put("probeProfileSha256", run.profileSha256());
            }
            if (run.runtimeSha256() != null) manifest.put("probeRuntimeSha256", run.runtimeSha256());
            if (run.executionPolicySha256() != null) manifest.put("probeExecutionPolicySha256", run.executionPolicySha256());
            if (run.bindingSha256() != null) manifest.put("probeBindingSha256", run.bindingSha256());
            if (run.scheduleSha256() != null) manifest.put("probeScheduleSha256", run.scheduleSha256());
        }
        manifest.put("files", hashes);
        manifest.put("boundary", "源码与已取得证据包；不包含运行环境，不证明站点在线或播放成功。"
                + "目录与 profile.json 为导出时版本；历史报告使用 probe.json 内冻结的资料上下文与画像，"
                + "旧画像另见 probe-profile.json，冻结运行时清单见 probe-runtime.json（若有）。"
                + "容器预期执行配置见 probe-execution-policy.json（若有），并非实际隔离回执。"
                + "当前与报告冻结的 AT 绑定分别见 at-binding.json、probe-at-binding.json（若有）；"
                + "绑定仅证明管理元数据观察。巡检当前策略/状态与任务冻结策略分别见 schedule-policy.json、"
                + "schedule-state.json、probe-schedule-policy.json（若有）；状态仅为导出时快照，不是自动恢复指令。"
                + "schedule-observations.json（若有）保存最近14个UTC日期内有界历史，不授予试运行或发布资格。"
                + "EXT 正文、指纹盐、管理会话凭据及 AT 原始插件地址均未导出。");
        files.put("manifest.json", mapper.writeValueAsBytes(manifest));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
