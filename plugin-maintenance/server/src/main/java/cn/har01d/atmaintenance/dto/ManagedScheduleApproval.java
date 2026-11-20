package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ManagedScheduleApproval(
        @NotNull @Pattern(regexp = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") String approvedRunId,
        @NotNull @Min(0) Long expectedSourceRevision) {
}
