package cn.har01d.atmaintenance.web;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.service.SourceCredentialStore;
import cn.har01d.atmaintenance.service.ProbeStore;
import cn.har01d.atmaintenance.service.SourceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/plugin-maintenance/sources/{id}/credentials")
@RequiredArgsConstructor
public class SourceCredentialController {
    private final SourceService sources;
    private final SourceCredentialStore credentials;
    private final ProbeStore probes;

    @GetMapping
    public ResponseEntity<SourceCredentialSummary> get(@PathVariable String id) {
        sources.requireSource(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(credentials.summary(id));
    }

    @PutMapping
    public ResponseEntity<SourceCredentialSummary> update(@PathVariable String id,
            @Valid @RequestBody UpdateSourceCredentialsRequest input) throws IOException {
        sources.requireSource(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(credentials.update(id, input));
    }

    @PostMapping("/observations")
    public ResponseEntity<SourceCredentialSummary> observe(@PathVariable String id,
            @Valid @RequestBody ObserveSourceCredentialsRequest input) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(probes.observeCredentials(id, input));
    }
}
