package cn.har01d.atmaintenance.web;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.service.AtPublicationService;
import cn.har01d.atmaintenance.service.AutomaticPublicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/plugin-maintenance/repair-candidates/{id}/at-publications")
@RequiredArgsConstructor
public class AtPublicationController {
    private final AtPublicationService publications;
    private final AutomaticPublicationService automatic;

    @GetMapping("/queue")
    public com.fasterxml.jackson.databind.node.ObjectNode queue(@PathVariable String id) { return automatic.status(id); }

    @PostMapping("/queue")
    public com.fasterxml.jackson.databind.node.ObjectNode queue(@PathVariable String id,
            @Valid @RequestBody PublicationQueueRequest input) { return automatic.queue(id, input); }

    @GetMapping
    public AtPublicationTarget target(@PathVariable String id) { return publications.target(id); }

    @PostMapping
    public AtPublicationSummary publish(@PathVariable String id, @Valid @RequestBody StartAtPublicationRequest input) {
        return publications.publish(id, input);
    }

    @PostMapping("/{publicationId}/name")
    public AtPublicationSummary rename(@PathVariable String id, @PathVariable String publicationId,
                                      @Valid @RequestBody RenameAtPublicationRequest input) {
        return publications.rename(id, publicationId, input);
    }

    @GetMapping("/{publicationId}/runtime")
    public AtPublicationRuntime runtime(@PathVariable String id, @PathVariable String publicationId) {
        return publications.runtime(id, publicationId);
    }

    @PostMapping("/{publicationId}/activate")
    public AtPublicationRuntime activate(@PathVariable String id, @PathVariable String publicationId,
            @Valid @RequestBody ActivateAtPublicationRequest input) {
        return publications.activate(id, publicationId, input);
    }
}
