package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateSourceRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 200) String externalId,
        @NotBlank @Pattern(regexp = "[^/\\\\]+\\.py") @Size(max = 200) String filename,
        @Size(max = 2000) String originUrl,
        @NotBlank @Size(max = 1_048_576) String content) {
}
