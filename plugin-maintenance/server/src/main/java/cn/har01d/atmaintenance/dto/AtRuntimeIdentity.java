package cn.har01d.atmaintenance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Read-only identity returned by an explicitly configured AT runtime endpoint.
 *
 * The endpoint must never return source text, EXT values, credentials or locator
 * URLs.  A missing/blocked load is still useful evidence, but it is not an
 * admission proof.
 */
public record AtRuntimeIdentity(int pluginId, String siteKey, String sourceSha256,
                                String runtimeRevision, String loadState, boolean loaded,
                                String workerGeneration, Instant observedAt,
                                String authority, String boundary, String sourceIdentityKind,
                                @JsonInclude(JsonInclude.Include.NON_NULL)
                                AtRuntimeFiltersIdentity filterIdentity,
                                @JsonInclude(JsonInclude.Include.NON_NULL)
                                AtProtectedFiltersIdentity protectedFilterIdentity) {
}
