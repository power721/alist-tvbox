package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.*;

public record SubmitModelProposalRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedProposalSha256) {
}
