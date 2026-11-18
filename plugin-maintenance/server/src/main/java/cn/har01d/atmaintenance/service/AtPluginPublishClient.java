package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AtPluginPublishClient {
    private final ConnectionSettingsStore settings;
    private final ObjectMapper mapper;
    @Autowired(required = false) private AtHostPublication host;

    Session open() { return new Session(settings.at()); }

    String instanceKey() {
        try (var session = open()) { return session.instanceKey(); }
    }

    final class Session implements AutoCloseable {
        private final AtConnectionSettings connection;
        private final URI origin;
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3)).build();
        private JsonNode csrf;
        private final AtHostPublication nativeHost;

        Session(AtConnectionSettings connection) {
            this.connection = connection;
            nativeHost = host != null && (Boolean.TRUE.equals(connection.useEmbedded())
                    || connection.useEmbedded() == null && connection.origin().isBlank()) ? host : null;
            if (nativeHost != null) { origin = URI.create("http://embedded.invalid/"); return; }
            try {
                URI value = URI.create(connection.origin());
                require(Set.of("http", "https").contains(Objects.toString(value.getScheme(), ""))
                        && value.getHost() != null && !connection.instanceId().isBlank(),
                        "AT_PUBLISH_CONNECTION_CHANGED");
                int port = value.getPort();
                if (port == ("https".equals(value.getScheme()) ? 443 : 80)) port = -1;
                origin = new URI(value.getScheme(), null, value.getHost().toLowerCase(Locale.ROOT),
                        port, "/", null, null);
            } catch (IllegalArgumentException | java.net.URISyntaxException error) {
                throw failure("AT_PUBLISH_CONNECTION_CHANGED");
            }
        }

        String instanceKey() {
            if (nativeHost != null) return nativeHost.instanceKey();
            return Hashes.sha256(connection.instanceId() + "\n" + Hashes.sha256(origin.toString()));
        }

        JsonNode plugins() {
            if (nativeHost != null) return nativeHost.plugins();
            try {
                var value = mapper.readTree(send("GET", "/api/plugins", null, null, false));
                require(value != null && value.isArray(), "AT_PUBLISH_RESPONSE_INVALID");
                return value;
            } catch (java.io.IOException error) { throw failure("AT_PUBLISH_RESPONSE_INVALID"); }
        }

        String customPath(JsonNode plugin) {
            return pluginPath(plugin, "/static/plugins/");
        }

        String originalSourcePath(JsonNode plugin) {
            return pluginPath(plugin, "/static/");
        }

        private String pluginPath(JsonNode plugin, String prefix) {
            try {
                String locator = plugin.path("url").asText();
                URI value;
                try { value = URI.create(locator); }
                catch (IllegalArgumentException error) { value = new URI(null, null, locator, null); }
                URI uri = origin.resolve(value);
                String path = uri.getPath();
                require(Objects.equals(origin.getScheme(), uri.getScheme())
                        && Objects.equals(origin.getHost(), uri.getHost())
                        && effectivePort(origin) == effectivePort(uri) && uri.getRawUserInfo() == null
                        && uri.getRawQuery() == null && uri.getRawFragment() == null
                        && path != null && path.startsWith(prefix) && path.endsWith(".py")
                        && !path.contains("\\") && !path.contains("%") && !path.contains("//")
                        && path.codePoints().noneMatch(c -> c < 32 || c == 127 || c == '"')
                        && Arrays.stream(path.split("/")).noneMatch(part -> part.equals(".") || part.equals("..")),
                        "AT_PUBLISH_CUSTOM_PLUGIN_REQUIRED");
                return path;
            } catch (java.net.URISyntaxException | IllegalArgumentException error) {
                throw failure("AT_PUBLISH_CUSTOM_PLUGIN_REQUIRED");
            }
        }

        byte[] source(String path, boolean absentAllowed) {
            if (nativeHost != null) return nativeHost.source(path, absentAllowed);
            return send("GET", path, null, null, absentAllowed);
        }

        void upload(String path, byte[] content) {
            if (nativeHost != null) { nativeHost.upload(path, content); return; }
            int slash = path.lastIndexOf('/');
            String directory = path.substring("/static/".length(), slash);
            String filename = path.substring(slash + 1);
            String boundary = "maintenance-" + UUID.randomUUID();
            var body = new ByteArrayOutputStream();
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + filename + "\"\r\nContent-Type: text/x-python\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.writeBytes(content);
            body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            send("POST", "/api/static-files/upload?extract=false&dir="
                    + URLEncoder.encode(directory, StandardCharsets.UTF_8), body.toByteArray(),
                    "multipart/form-data; boundary=" + boundary, false);
        }

        void refresh(int pluginId) {
            if (nativeHost != null) nativeHost.refresh(pluginId);
            else send("POST", "/api/plugins/" + pluginId + "/refresh", new byte[0], null, false);
        }

        void rename(JsonNode plugin, String name, JsonNode original) {
            if (nativeHost != null) { nativeHost.rename(plugin.path("id").asInt(), name, original); return; }
            var body = mapper.createObjectNode().put("name", name)
                    .put("url", plugin.path("url").asText()).put("enabled", original.path("enabled").asBoolean(true));
            body.set("extend", original.path("extend").isMissingNode() ? mapper.nullNode() : original.path("extend"));
            send("PUT", "/api/plugins/" + plugin.path("id").asInt(), body.toString().getBytes(StandardCharsets.UTF_8),
                    "application/json", false);
        }

        AtSavedRuntimeTarget savedRuntime(int pluginId) {
            if (nativeHost != null) return nativeHost.savedRuntime(pluginId);
            byte[] body = send("GET", "/api/plugin-maintenance/at-native/plugins/" + pluginId + "/saved-runtime",
                    null, null, true);
            if (body == null) return null;
            try { return mapper.readValue(body, AtSavedRuntimeTarget.class); }
            catch (java.io.IOException error) { throw failure("AT_PUBLISH_RESPONSE_INVALID"); }
        }

        AtRuntimeActivation activate(int pluginId, StartAtRuntimeActivationRequest input, String activationId) {
            if (nativeHost != null) return nativeHost.activate(pluginId, input, activationId);
            if (csrf == null) {
                try { csrf = mapper.readTree(send("GET", "/api/plugin-maintenance/csrf", null, null, false)); }
                catch (java.io.IOException error) { throw failure("AT_PUBLISH_RESPONSE_INVALID"); }
                require(csrf.path("headerName").asText().equals("X-CSRF-TOKEN")
                        && !csrf.path("token").asText().isBlank(), "AT_PUBLISH_RESPONSE_INVALID");
            }
            var body = mapper.valueToTree(input);
            String path = "/api/plugin-maintenance/at-native/" + (activationId == null
                    ? "plugins/" + pluginId + "/activations" : "activations/" + activationId + "/resume");
            if (activationId != null) body = mapper.createObjectNode()
                    .put("expectedWorkerGeneration", input.expectedWorkerGeneration())
                    .put("interruptIssuedPlayback", input.interruptIssuedPlayback());
            try { return mapper.readValue(send("POST", path, body.toString().getBytes(StandardCharsets.UTF_8),
                    "application/json", false, 60), AtRuntimeActivation.class); }
            catch (java.io.IOException error) { throw failure("AT_PUBLISH_RESPONSE_INVALID"); }
        }

        private byte[] send(String method, String path, byte[] body, String type, boolean absentAllowed) {
            return send(method, path, body, type, absentAllowed, 10);
        }

        private byte[] send(String method, String path, byte[] body, String type, boolean absentAllowed, int seconds) {
            var request = HttpRequest.newBuilder(origin.resolve(asciiPath(path))).timeout(Duration.ofSeconds(seconds))
                    .header("api_key".equals(connection.authMode()) ? "X-API-KEY" : "Authorization", connection.credential())
                    .header("Accept", "*/*");
            if (csrf != null && !"GET".equals(method) && path.startsWith("/api/plugin-maintenance/"))
                request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
            if (type != null) request.header("Content-Type", type);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            var pending = http.sendAsync(request.build(), ignored -> new LimitedHttpBody(2_097_152));
            try {
                var response = pending.get(seconds + 1, TimeUnit.SECONDS);
                int status = response.statusCode();
                if (status == 404 && absentAllowed) return null;
                if (status == 401 || status == 403) throw failure("AT_PUBLISH_WRITE_PERMISSION_REQUIRED");
                require(status >= 200 && status < 300, "AT_PUBLISH_HTTP_ERROR");
                return response.body();
            } catch (ResponseStatusException error) { throw error; }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw failure("AT_PUBLISH_INTERRUPTED");
            } catch (Exception error) { throw failure("AT_PUBLISH_IO_ERROR"); }
            finally { pending.cancel(true); }
        }

        @Override public void close() { http.shutdownNow(); }
    }

    private static String asciiPath(String path) {
        try {
            int query = path.indexOf('?');
            return query < 0 ? new URI(null, null, path, null).toASCIIString()
                    : new URI(null, null, path.substring(0, query), null).toASCIIString() + path.substring(query);
        } catch (java.net.URISyntaxException error) { throw failure("AT_PUBLISH_PATH_INVALID"); }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
    }

    private static void require(boolean condition, String code) { if (!condition) throw failure(code); }
    private static ResponseStatusException failure(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
}
