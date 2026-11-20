package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Anonymous public input reads use the existing pinned HTTPS mediator, not the AT session client. */
@Service
public class PublicPythonSourceReader {
    private final ObjectMapper mapper;
    private final String python;

    public PublicPythonSourceReader(ObjectMapper mapper, @Value("${maintenance.python}") String python) {
        this.mapper = mapper;
        this.python = python;
    }

    byte[] read(URI location, int maxBytes) {
        try {
            Path directory = Files.createTempDirectory("at-maintenance-public-input-");
            try {
                for (String name : List.of("read_public_python_source.py", "source_egress.py")) {
                    Files.write(directory.resolve(name), new ClassPathResource("worker/" + name).getContentAsByteArray());
                }
                var input = mapper.createObjectNode().put("url", location.toASCIIString()).put("maxBytes", maxBytes);
                var reply = mapper.readTree(PythonProcess.run(python, directory.resolve("read_public_python_source.py"),
                        directory, List.of(), mapper.writeValueAsBytes(input), 35));
                if (reply.path("status").asText().equals("rejected")) {
                    String code = reply.path("code").asText();
                    if (!code.matches("(?:AT|EGRESS)_[A-Z_]{1,80}")) throw new IOException("AT_PUBLIC_SOURCE_REPLY_INVALID");
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, code);
                }
                Path output = directory.resolve("source.py");
                if (!reply.path("status").asText().equals("read") || !Files.isRegularFile(output)
                        || Files.size(output) == 0 || Files.size(output) > maxBytes) {
                    throw new IOException("AT_PUBLIC_SOURCE_REPLY_INVALID");
                }
                byte[] body = Files.readAllBytes(output);
                if (reply.path("bytes").asLong(-1) != body.length
                        || !reply.path("sha256").asText().equals(Hashes.sha256(body))) {
                    throw new IOException("AT_PUBLIC_SOURCE_REPLY_INVALID");
                }
                return body;
            } finally {
                PythonProcess.deleteDirectory(directory);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AT_READ_INTERRUPTED");
        } catch (IOException error) {
            String code = switch (String.valueOf(error.getMessage())) {
                case "PYTHON_TIMEOUT" -> "AT_PUBLIC_SOURCE_TIMEOUT";
                case "PYTHON_FAILED" -> "AT_PUBLIC_SOURCE_WORKER_FAILED";
                case "AT_PUBLIC_SOURCE_REPLY_INVALID" -> "AT_PUBLIC_SOURCE_REPLY_INVALID";
                default -> "AT_PUBLIC_SOURCE_READ_FAILED";
            };
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, code);
        }
    }
}
