package cn.har01d.atmaintenance.web;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.service.SourceImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/plugin-maintenance")
@RequiredArgsConstructor
public class SourceImportController {
    private final SourceImportService imports;

    @PostMapping("/source-imports/preview")
    public SourceImportPreview preview(@Valid @RequestBody PreviewSourceImportRequest input) {
        return imports.preview(input);
    }

    @PostMapping("/source-imports")
    @ResponseStatus(HttpStatus.CREATED)
    public SourceImportSummary confirm(@Valid @RequestBody ConfirmSourceImportRequest input) {
        return imports.confirm(input);
    }

    @GetMapping("/source-imports/{id}")
    public SourceImportSummary get(@PathVariable String id) {
        return imports.get(id);
    }

    @GetMapping("/sources/{id}/imports")
    public List<SourceImportSummary> list(@PathVariable String id) {
        return imports.list(id);
    }

    @GetMapping("/sources/{id}/imported-candidates")
    public List<ImportedCandidateSummary> candidates(@PathVariable String id) {
        return imports.candidates(id);
    }

    @GetMapping("/source-imports/{id}/archive")
    public ResponseEntity<byte[]> archive(@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"imported-source-archive.zip\"")
                .body(imports.archive(id));
    }
}
