package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class StaticInspector {
    private final ObjectMapper mapper;
    private final String python;
    private final Path script;
    private final String sha256;

    @Autowired
    public StaticInspector(ObjectMapper mapper, @Value("${maintenance.python}") String python) throws IOException {
        this(mapper, python, new ClassPathResource("worker/inspect_source.py").getContentAsByteArray());
    }

    StaticInspector(ObjectMapper mapper, String python, byte[] scriptBytes) throws IOException {
        this.mapper = mapper;
        this.python = python;
        script = Files.createTempFile("at-maintenance-inspector-", ".py");
        sha256 = Hashes.sha256(scriptBytes);
        Files.write(script, scriptBytes);
    }

    public String sha256() {
        return sha256;
    }

    public JsonNode inspect(String content) throws IOException, InterruptedException {
        Path directory = Files.createTempDirectory("at-maintenance-static-");
        try {
            JsonNode result = mapper.readTree(PythonProcess.run(python, script, directory, List.of(),
                    mapper.writeValueAsBytes(Map.of("content", content)), 10));
            if (!Hashes.sha256(content).equals(result.path("sha256").asText())) {
                throw new IOException("INSPECTOR_IDENTITY_MISMATCH");
            }
            return result;
        } finally {
            PythonProcess.deleteDirectory(directory);
        }
    }

    @PreDestroy
    void close() throws IOException {
        Files.deleteIfExists(script);
    }
}
