package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.entity.PluginInput;
import cn.har01d.atmaintenance.repository.PluginInputRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PluginInputService {
    private final PluginInputInspector inspector;
    private final PluginInputRepository inputs;
    private final SourceService sources;
    private final ObjectMapper mapper;
    private final AtReadClient at;
    private final AtBindingService bindings;

    public PluginInputSummary preview(PreviewPluginInputRequest input) {
        byte[] body = decode(input.inputBase64(), input.authorized());
        var prepared = inspect(body);
        return new PluginInputSummary(null, null, prepared.evidence().path("plaintextSha256").asText(),
                prepared.evidenceSha256(), prepared.evidence(), null);
    }

    public PluginInputSummary previewAt(PreviewAtPluginInputRequest input) {
        var prepared = prepareAt(input);
        return new PluginInputSummary(null, null, prepared.evidence().path("plaintextSha256").asText(),
                prepared.evidenceSha256(), prepared.evidence(), null);
    }

    PluginInputInspector.Prepared prepareAt(PreviewAtPluginInputRequest input) {
        requireAuthorization(input.authorized());
        return inspectAt(at.readPythonSource(input.observationSha256(), input.pluginId()),
                input.observationSha256(), input.pluginId());
    }

    @Transactional
    public PluginInputSummary confirmAt(ConfirmAtPluginInputRequest input) {
        requireAuthorization(input.authorized());
        at.requireMaintenanceAllowed(input.pluginId());
        String requestSha = Hashes.sha256(mapper.createArrayNode().add("at_static_input_v1")
                .add(input.observationSha256()).add(input.pluginId()).add(input.expectedEvidenceSha256())
                .add(input.name().trim()).add(input.filename()).toString());
        var previous = previous(input.requestKey(), requestSha);
        if (previous != null) return previous;
        byte[] body = at.readPythonSource(input.observationSha256(), input.pluginId());
        var prepared = inspectAt(body, input.observationSha256(), input.pluginId());
        requirePreview(prepared, input.expectedEvidenceSha256());
        var saved = store(body, prepared, input.requestKey(), requestSha, input.name(), input.filename());
        bindings.bind(saved.sourceId(), new BindAtRequest(null, input.observationSha256(), input.pluginId()));
        return saved;
    }

    @Transactional
    public PluginInputSummary confirm(ConfirmPluginInputRequest input) {
        byte[] body = decode(input.inputBase64(), input.authorized());
        String requestSha = Hashes.sha256(mapper.createArrayNode().add(Hashes.sha256(body))
                .add(input.expectedEvidenceSha256()).add(input.name().trim()).add(input.filename()).toString());
        var previous = previous(input.requestKey(), requestSha);
        if (previous != null) return previous;
        var prepared = inspect(body);
        requirePreview(prepared, input.expectedEvidenceSha256());
        return store(body, prepared, input.requestKey(), requestSha, input.name(), input.filename());
    }

    private void requirePreview(PluginInputInspector.Prepared prepared, String expectedSha) {
        if (!prepared.evidenceSha256().equals(expectedSha)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PLUGIN_INPUT_PREVIEW_CHANGED");
        }
    }

    private PluginInputSummary previous(String requestKey, String requestSha) {
        var previous = inputs.findByRequestKey(requestKey);
        if (previous.isEmpty()) return null;
        if (!previous.get().getRequestSha256().equals(requestSha)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PLUGIN_INPUT_REQUEST_KEY_REUSED");
        }
        return summary(previous.get());
    }

    private PluginInputSummary store(byte[] body, PluginInputInspector.Prepared prepared,
                                     String requestKey, String requestSha, String name, String filename) {
        var source = sources.create(new CreateSourceRequest(name, null, filename, null,
                prepared.content()));
        if (!source.artifactSha256().equals(prepared.evidence().path("plaintextSha256").asText())) {
            throw new IllegalStateException("PLUGIN_INPUT_ARTIFACT_MISMATCH");
        }
        PluginInput record = new PluginInput();
        record.setSourceId(source.id());
        record.setArtifactSha256(source.artifactSha256());
        record.setRequestKey(requestKey);
        record.setRequestSha256(requestSha);
        record.setInputSha256(Hashes.sha256(body));
        record.setInputBase64(Base64.getEncoder().encodeToString(body));
        record.setEvidenceSha256(prepared.evidenceSha256());
        record.setEvidence(prepared.evidence().toString());
        return summary(inputs.saveAndFlush(record));
    }

    @Transactional(readOnly = true)
    public List<PluginInputSummary> list(String sourceId) {
        sources.requireSource(sourceId);
        return inputs.findTop20BySourceIdOrderByCreatedAtDesc(sourceId).stream().map(this::summary).toList();
    }

    private byte[] decode(String encoded, boolean authorized) {
        requireAuthorization(authorized);
        if (encoded == null || encoded.length() > 1_398_104) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "PLUGIN_INPUT_SIZE_INVALID");
        }
        byte[] body;
        try {
            body = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PLUGIN_INPUT_BASE64_INVALID");
        }
        if (body.length == 0 || body.length > 1_048_576) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "PLUGIN_INPUT_SIZE_INVALID");
        }
        return body;
    }

    private void requireAuthorization(boolean authorized) {
        if (!authorized) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PLUGIN_PLAINTEXT_AUTHORIZATION_REQUIRED");
        }
    }

    private PluginInputInspector.Prepared inspectAt(byte[] body, String observationSha, int pluginId) {
        var observation = at.requireObservation(observationSha);
        var plugin = observation.plugins().stream().filter(item -> item.id() == pluginId).findFirst().orElseThrow();
        var prepared = inspect(body);
        if (!prepared.evidence().path("inputFormat").asText().equals("python")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AT_SOURCE_FORMAT_INVALID");
        }
        ObjectNode evidence = (ObjectNode) prepared.evidence().deepCopy();
        var provenance = evidence.putObject("atSource");
        provenance.put("instanceKey", observation.connection().instanceKey());
        provenance.put("observationSha256", observation.sha256());
        provenance.put("observedAt", observation.observedAt().toString());
        provenance.put("pluginId", pluginId);
        provenance.put("sourceLocatorSha256", plugin.sourceLocatorSha256());
        provenance.put("sourceSha256", Hashes.sha256(body));
        provenance.put("requestMethod", "GET");
        provenance.put("authority", at.sourceReadAuthority(pluginId));
        if (at.sourceReadAuthority(pluginId).equals("at_public_https_source_read")) {
            provenance.put("atCredentialsForwarded", false);
        }
        provenance.put("productionModified", false);
        return new PluginInputInspector.Prepared(prepared.content(), evidence, Hashes.sha256(evidence.toString()));
    }

    private PluginInputInspector.Prepared inspect(byte[] body) {
        try {
            return inspector.inspect(body);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PLUGIN_INPUT_PREPARATION_INTERRUPTED");
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PLUGIN_INPUT_PREPARATION_FAILED");
        }
    }

    private PluginInputSummary summary(PluginInput record) {
        if (!Hashes.sha256(record.getEvidence()).equals(record.getEvidenceSha256())) {
            throw new IllegalStateException("PLUGIN_INPUT_EVIDENCE_MISMATCH");
        }
        try {
            return new PluginInputSummary(record.getId(), record.getSourceId(), record.getArtifactSha256(),
                    record.getEvidenceSha256(), mapper.readTree(record.getEvidence()), record.getCreatedAt());
        } catch (IOException error) {
            throw new IllegalStateException("PLUGIN_INPUT_EVIDENCE_INVALID");
        }
    }
}
