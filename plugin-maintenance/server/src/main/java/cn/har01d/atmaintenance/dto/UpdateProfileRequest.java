package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateProfileRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedSha256,
        @NotBlank @Pattern(regexp = "atvp|t4|fongmi") String runtime,
        @NotBlank @Size(max = 200) String runtimeRevision,
        @NotNull @Size(max = 44) List<@NotBlank String> requiredNodes,
        @NotNull @Size(max = 44) List<@NotBlank String> optionalNodes,
        JsonNode ext,
        JsonNode publicSourceData,
        JsonNode sourceConfig,
        Integer httpPlaybackGroup,
        String httpDetailSample,
        String paginationMode,
        String sourcePurpose,
        String searchKeyword) {

    public UpdateProfileRequest(String expectedSha256, String runtime, String runtimeRevision,
                                List<String> requiredNodes, List<String> optionalNodes,
                                JsonNode ext, JsonNode publicSourceData, JsonNode sourceConfig,
                                Integer httpPlaybackGroup, String httpDetailSample, String paginationMode) {
        this(expectedSha256, runtime, runtimeRevision, requiredNodes, optionalNodes,
                ext, publicSourceData, sourceConfig, httpPlaybackGroup, httpDetailSample, paginationMode, null, null);
    }

    public UpdateProfileRequest(String expectedSha256, String runtime, String runtimeRevision,
                                List<String> requiredNodes, List<String> optionalNodes,
                                JsonNode ext, JsonNode publicSourceData, JsonNode sourceConfig,
                                Integer httpPlaybackGroup, String httpDetailSample) {
        this(expectedSha256, runtime, runtimeRevision, requiredNodes, optionalNodes,
                ext, publicSourceData, sourceConfig, httpPlaybackGroup, httpDetailSample, null);
    }

    public UpdateProfileRequest(String expectedSha256, String runtime, String runtimeRevision,
                                List<String> requiredNodes, List<String> optionalNodes,
                                JsonNode ext, JsonNode publicSourceData, JsonNode sourceConfig,
                                Integer httpPlaybackGroup) {
        this(expectedSha256, runtime, runtimeRevision, requiredNodes, optionalNodes,
                ext, publicSourceData, sourceConfig, httpPlaybackGroup, null);
    }

    public UpdateProfileRequest(String expectedSha256, String runtime, String runtimeRevision,
                                List<String> requiredNodes, List<String> optionalNodes,
                                JsonNode ext, JsonNode publicSourceData, JsonNode sourceConfig) {
        this(expectedSha256, runtime, runtimeRevision, requiredNodes, optionalNodes,
                ext, publicSourceData, sourceConfig, null, null);
    }

    public UpdateProfileRequest(String expectedSha256, String runtime, String runtimeRevision,
                                List<String> requiredNodes, List<String> optionalNodes,
                                JsonNode ext, JsonNode publicSourceData) {
        this(expectedSha256, runtime, runtimeRevision, requiredNodes, optionalNodes,
                ext, publicSourceData, null, null, null);
    }
}
