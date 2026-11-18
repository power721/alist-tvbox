package cn.har01d.atmaintenance.dto;

import java.util.List;

/** Ordered loader observations, not current repository contents or health proof. */
public record AtRuntimeFiltersIdentity(int schemaVersion, String authority, String sourceIdentityKind,
                                       String configurationSha256, boolean configurationValid,
                                       boolean allConfiguredFiltersLoaded,
                                       List<AtRuntimeFilterIdentity> filters) {
    public AtRuntimeFiltersIdentity {
        filters = List.copyOf(filters);
    }
}
