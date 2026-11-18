package cn.har01d.atmaintenance.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

public record UpdateScheduleRequest(@Pattern(regexp = "[a-f0-9]{64}") String expectedSha256,
                                    boolean enabled,
                                    @NotBlank @Pattern(regexp = "static|offline_fixture|managed_source") String mode,
                                    @Min(1800) @Max(604800) int intervalSeconds,
                                    @Min(1) @Max(24) int dailyLimit,
                                    @Valid ManagedScheduleApproval approval,
                                    @Valid ScheduleRepairApproval automaticRepair,
                                    Instant firstRunAt) {
    public UpdateScheduleRequest(String expectedSha256, boolean enabled, String mode,
                                 int intervalSeconds, int dailyLimit, ManagedScheduleApproval approval,
                                 ScheduleRepairApproval automaticRepair) {
        this(expectedSha256, enabled, mode, intervalSeconds, dailyLimit, approval, automaticRepair, null);
    }
    public UpdateScheduleRequest(String expectedSha256, boolean enabled, String mode,
                                 int intervalSeconds, int dailyLimit, ManagedScheduleApproval approval) {
        this(expectedSha256, enabled, mode, intervalSeconds, dailyLimit, approval, null, null);
    }
}
