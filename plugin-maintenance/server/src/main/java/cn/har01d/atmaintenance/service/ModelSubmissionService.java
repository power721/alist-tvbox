package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ModelSubmissionService {
    private final ModelStore store;
    private final RepairService repairs;

    public synchronized RepairCandidateSummary submit(String id, SubmitModelProposalRequest input) {
        return repairs.enqueue(store.reserveSubmission(id, input.expectedProposalSha256(), false));
    }

    public synchronized void autoSubmit(String id) {
        var call = store.get(id);
        if (!"PENDING".equals(call.submissionStatus())) return;
        try {
            var candidate = store.reserveSubmission(id, call.proposalSha256(), true);
            if (candidate != null) repairs.enqueue(candidate);
        } catch (ResponseStatusException e) {
            // The reservation transaction has rolled back, or a linked candidate records queue rejection.
            store.failAutomaticSubmission(id, e.getReason());
        } catch (RuntimeException e) {
            store.failAutomaticSubmission(id, "MODEL_SUBMISSION_INTERNAL_ERROR");
            throw e;
        }
    }
}
