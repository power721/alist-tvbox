package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PreviewSourceImportRequest(@NotBlank @Size(max = 5_592_408) String archiveBase64) {
}
