package cn.har01d.atmaintenance.web;

import cn.har01d.atmaintenance.dto.AtRuntimeIdentity;
import cn.har01d.atmaintenance.service.EmbeddedAtRuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plugin-maintenance/at-native/plugins")
@PreAuthorize("hasAuthority('ADMIN')")
@RequiredArgsConstructor
public class EmbeddedAtRuntimeController {
    private final EmbeddedAtRuntimeService runtime;

    @GetMapping("/{id}/runtime-identity")
    public ResponseEntity<AtRuntimeIdentity> observe(@PathVariable int id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(runtime.observe(id));
    }
}
