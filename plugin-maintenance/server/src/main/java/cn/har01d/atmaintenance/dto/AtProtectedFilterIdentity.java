package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record AtProtectedFilterIdentity(int index, String configurationFingerprint,
                                       String loadState,
                                       @JsonInclude(JsonInclude.Include.ALWAYS) String sourceSha256) {
}
