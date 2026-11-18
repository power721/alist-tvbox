package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PluginInputInspector {
    private final ObjectMapper mapper;
    private final String python;
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final String processorSha256;

    @Autowired
    public PluginInputInspector(ObjectMapper mapper, @Value("${maintenance.python}") String python) throws IOException {
        this(mapper, python, bundledFiles());
    }

    PluginInputInspector(ObjectMapper mapper, String python, Map<String, byte[]> resources) {
        this.mapper = mapper;
        this.python = python;
        files.putAll(resources);
        processorSha256 = Hashes.sha256(files.get("prepare_plugin_source.py"));
    }

    static Map<String, byte[]> bundledFiles() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String name : List.of("prepare_plugin_source.py", "runtime/Atvp.py", "runtime/manifest.json")) {
            files.put(name, new ClassPathResource("worker/" + name).getContentAsByteArray());
        }
        return files;
    }

    Prepared inspect(byte[] body) throws IOException, InterruptedException {
        Path directory = Files.createTempDirectory("at-maintenance-plugin-input-");
        try {
            Files.createDirectory(directory.resolve("runtime"));
            for (var file : files.entrySet()) Files.write(directory.resolve(file.getKey()), file.getValue());
            JsonNode reply = mapper.readTree(PythonProcess.run(python,
                    directory.resolve("prepare_plugin_source.py"), directory, List.of("--worker", "--authorized"),
                    body, 15));
            if (reply.path("status").asText().equals("rejected")) {
                String code = reply.path("code").asText();
                if (!code.matches("PLUGIN_[A-Z_]{1,80}")) throw new IOException("PLUGIN_INPUT_REPLY_INVALID");
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
            }
            Path output = directory.resolve("source.py");
            if (!reply.path("status").asText().equals("prepared") || !reply.path("evidence").isObject()
                    || !Files.isRegularFile(output) || Files.size(output) > 1_048_576) {
                throw new IOException("PLUGIN_INPUT_REPLY_INVALID");
            }
            byte[] plaintext = Files.readAllBytes(output);
            ObjectNode evidence = ((ObjectNode) reply.get("evidence")).deepCopy();
            if (!Hashes.sha256(body).equals(evidence.path("inputSha256").asText())
                    || !Hashes.sha256(plaintext).equals(evidence.path("plaintextSha256").asText())
                    || evidence.path("inputBytes").asLong(-1) != body.length
                    || evidence.path("plaintextBytes").asLong(-1) != plaintext.length
                    || !evidence.path("sourceExecuted").isBoolean() || evidence.path("sourceExecuted").booleanValue()
                    || !evidence.path("syntaxCompiled").asBoolean()) {
                throw new IOException("PLUGIN_INPUT_IDENTITY_MISMATCH");
            }
            evidence.put("processorSha256", processorSha256);
            evidence.put("authority", "server_preparation_only");
            String content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plaintext)).toString();
            return new Prepared(content, evidence, Hashes.sha256(evidence.toString()));
        } finally {
            PythonProcess.deleteDirectory(directory);
        }
    }

    record Prepared(String content, JsonNode evidence, String evidenceSha256) {}
}
