package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

@Service
@RequiredArgsConstructor
public class MediaProbeExportService {
    private final MediaProbeStore store;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true)
    public byte[] export(String id) throws IOException {
        var run = store.get(id);
        byte[] evidence = mapper.writeValueAsBytes(run);
        var manifest = Map.of("schemaVersion", 1, "authority", "operator_supplied_player",
                "sourceHealthEligible", false, "publishEligible", false, "files",
                Map.of("media-probe.json", Map.of("sha256", Hashes.sha256(evidence), "bytes", evidence.length)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("media-probe.json"));
            zip.write(evidence);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(mapper.writeValueAsBytes(manifest));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
