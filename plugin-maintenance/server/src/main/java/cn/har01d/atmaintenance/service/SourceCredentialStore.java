package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.SourceCredentialSummary;
import cn.har01d.atmaintenance.dto.UpdateSourceCredentialsRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

/** Administrator secrets stay outside source artifacts, profiles and model context. */
@Service
@RequiredArgsConstructor
public class SourceCredentialStore {
    private final ConnectionSettingsStore settings;
    private final ObjectMapper mapper;

    private JsonNode read(String id) { return settings.sourceCredentials(id); }

    public synchronized String revision(String id) { return read(id).path("revision").asText(""); }

    public synchronized SourceCredentialSummary summary(String id) {
        JsonNode value = read(id);
        boolean cookie = !value.path("cookie").asText().isEmpty();
        boolean authorization = !value.path("authorization").asText().isEmpty();
        boolean configured = cookie || authorization;
        boolean required = value.path("required").asBoolean();
        boolean rejected = value.path("rejected").asBoolean();
        return new SourceCredentialSummary(required, configured,
                rejected ? "rejected" : required && !configured ? "missing" : configured ? "configured" : "optional",
                value.path("origin").asText(""), cookie, authorization, revision(id),
                rejected ? "SOURCE_AUTHENTICATION_REJECTED" : required && !configured ? "SOURCE_CREDENTIALS_REQUIRED" : null);
    }

    public synchronized SourceCredentialSummary update(String id, UpdateSourceCredentialsRequest input) throws IOException {
        if (!revision(id).equals(input.expectedRevision()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SOURCE_CREDENTIALS_CHANGED");
        JsonNode previous = read(id);
        String cookie = input.clearCookie() ? "" : input.cookie().isEmpty()
                ? previous.path("cookie").asText("") : input.cookie().trim();
        String authorization = input.clearAuthorization() ? "" : input.authorization().isEmpty()
                ? previous.path("authorization").asText("") : input.authorization().trim();
        if (!cookie.matches("[\\x20-\\x7e]{0,8192}") || !authorization.matches("[\\x20-\\x7e]{0,8192}")
                || input.clearCookie() && !input.cookie().isEmpty()
                || input.clearAuthorization() && !input.authorization().isEmpty())
            throw invalid();
        String origin = input.origin().trim();
        if (!cookie.isEmpty() || !authorization.isEmpty() || !origin.isEmpty()) {
            try {
                URI uri = URI.create(origin);
                if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                        || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                        || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                        || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) throw invalid();
                origin = uri.getScheme() + "://" + uri.getRawAuthority().toLowerCase(java.util.Locale.ROOT);
            } catch (IllegalArgumentException error) { throw invalid(); }
        }
        // Changing the destination cannot silently carry an existing secret to another host.
        if (!previous.path("origin").asText("").equals(origin)
                && (!previous.path("cookie").asText("").isEmpty() && input.cookie().isEmpty() && !input.clearCookie()
                || !previous.path("authorization").asText("").isEmpty()
                    && input.authorization().isEmpty() && !input.clearAuthorization())) throw invalid();
        ObjectNode value = mapper.createObjectNode().put("required", input.required())
                .put("origin", origin).put("cookie", cookie).put("authorization", authorization)
                .put("revision", UUID.randomUUID().toString()).put("rejected", false);
        settings.saveSourceCredentials(id, value);
        return summary(id);
    }

    public synchronized String blockedReason(String id) {
        var summary = summary(id);
        return summary.status().equals("missing") || summary.status().equals("rejected") ? summary.reason() : null;
    }

    public synchronized JsonNode execution(String id, String expectedRevision) throws IOException {
        if (!revision(id).equals(expectedRevision == null ? "" : expectedRevision))
            throw new IOException("SOURCE_CREDENTIALS_CHANGED");
        String blocked = blockedReason(id);
        if (blocked != null) throw new IOException(blocked);
        JsonNode value = read(id);
        if (!summary(id).configured()) return null;
        return mapper.createObjectNode().put("origin", value.path("origin").asText())
                .put("cookie", value.path("cookie").asText()).put("authorization", value.path("authorization").asText());
    }

    public synchronized void observe(String id, String expectedRevision, String artifactSha, JsonNode evidence) throws IOException {
        if (!revision(id).equals(expectedRevision == null ? "" : expectedRevision) || evidence == null) return;
        var responses = RepairReport.nonRedirectResponses(evidence).stream()
                .filter(row -> row.path("responseComplete").asBoolean()).toList();
        // 403 alone can be a WAF or site policy, not proof of missing user credentials.
        boolean rejected = !responses.isEmpty()
                && responses.stream().allMatch(row -> row.path("responseStatus").asInt() == 401
                    || row.path("responseStatus").asInt() == 403)
                && responses.stream().anyMatch(row -> row.path("responseStatus").asInt() == 401);
        if (!rejected && SourceAuthenticationEvidence.detect(mapper, artifactSha, evidence) == null) return;
        JsonNode previous = read(id);
        ObjectNode value = previous.isObject() ? (ObjectNode) previous.deepCopy() : mapper.createObjectNode();
        value.put("required", true).put("rejected", summary(id).configured());
        settings.saveSourceCredentials(id, value);
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "SOURCE_CREDENTIALS_INVALID");
    }
}
