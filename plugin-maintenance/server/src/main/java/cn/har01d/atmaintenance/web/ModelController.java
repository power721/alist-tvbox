package cn.har01d.atmaintenance.web;

import cn.har01d.atmaintenance.dto.*;
import cn.har01d.atmaintenance.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/plugin-maintenance")
@RequiredArgsConstructor
public class ModelController {
    private final ModelStore store;
    private final ModelClient client;
    private final ModelService service;
    private final ModelSubmissionService submissions;
    private final AutomaticRepairStore automatic;
    private final AutomaticRepairService automation;

    @GetMapping("/repairs/{id}/automatic")
    public AutomaticRepairSummary automatic(@PathVariable String id) { return automatic.get(id); }

    @PostMapping("/repairs/{id}/automatic")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AutomaticRepairSummary automatic(@PathVariable String id, @Valid @RequestBody StartModelCallRequest input) {
        return automation.start(id, input);
    }

    @PostMapping("/repairs/{id}/automatic/cancel")
    public AutomaticRepairSummary cancelAutomatic(@PathVariable String id) { return automation.cancel(id); }

    @GetMapping("/model/connection")
    public ModelConnection connection() { return client.connection(); }

    @GetMapping("/model/budget")
    public ModelBudgetSummary budget() { return store.budget(); }

    @GetMapping("/model/named-budget")
    public ModelNamedBudgetSummary namedBudget() { return store.namedBudget(); }

    @GetMapping("/sources/{id}/model-budget")
    public ModelSourceBudgetSummary sourceBudget(@PathVariable String id) { return store.sourceBudget(id); }

    @GetMapping("/sources/{id}/model-consent")
    public ModelConsent consent(@PathVariable String id) { return store.consent(id); }

    @PutMapping("/sources/{id}/model-consent")
    public ModelConsent consent(@PathVariable String id, @Valid @RequestBody UpdateModelConsentRequest input) {
        return store.consent(id, input);
    }

    @GetMapping("/repairs/{id}/model-calls")
    public List<ModelCallSummary> calls(@PathVariable String id) { return store.list(id); }

    @PostMapping("/repairs/{id}/model-calls")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ModelCallSummary propose(@PathVariable String id, @Valid @RequestBody StartModelCallRequest input) {
        return service.propose(id, input);
    }

    @GetMapping("/model-calls/{id}")
    public ModelCallSummary get(@PathVariable String id) { return store.get(id); }

    @PostMapping("/model-calls/{id}/cancel")
    public ModelCallSummary cancel(@PathVariable String id) { return service.cancel(id); }

    @PostMapping("/model-calls/{id}/submit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RepairCandidateSummary submit(@PathVariable String id, @Valid @RequestBody SubmitModelProposalRequest input) {
        return submissions.submit(id, input);
    }
}
