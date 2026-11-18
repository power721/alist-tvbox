package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record RepairProposal(@Min(1) @Max(1) int schemaVersion,
                             @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedBaselineSha256,
                             @NotBlank @Pattern(regexp = "source\\.py") String targetFile,
                             @NotBlank @Size(max = 2000) String diagnosis,
                             @NotBlank @Size(max = 2000) String expectedEffect,
                             @NotNull @Size(min = 1, max = 44) List<@Pattern(regexp = "S0[1-8]|C0[1-9]|C1[01]|R0[1-9]|R10|M0[1-9]|M10|P0[1-5]") @NotNull String> validationTargets,
                             @NotNull @Size(min = 1, max = 20) List<@Valid @NotNull RepairEdit> edits,
                             @JsonInclude(JsonInclude.Include.NON_NULL)
                             @Size(min = 1, max = 60)
                             @Pattern(regexp = "(?s)(?=.*\\p{IsHan})[^<>:\"/\\\\|?*\\p{Cntrl}]+")
                             String deliveryName) {
}
