package cn.har01d.atmaintenance.dto;

import java.util.List;

/** Configuration equality tokens are comparable only within their declared scope. */
public record AtProtectedFiltersIdentity(int schemaVersion, String authority, String sourceIdentityKind,
                                        String fingerprintScheme, String fingerprintScope,
                                        String configurationFingerprint, boolean configurationValid,
                                        boolean allConfiguredFiltersLoaded,
                                        List<AtProtectedFilterIdentity> filters) {
    public AtProtectedFiltersIdentity {
        filters = List.copyOf(filters);
    }
}
