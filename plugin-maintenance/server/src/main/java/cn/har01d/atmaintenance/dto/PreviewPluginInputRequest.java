package cn.har01d.atmaintenance.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PreviewPluginInputRequest(
        @NotBlank @Size(max = 1_398_104) String inputBase64,
        @AssertTrue boolean authorized) {
}
