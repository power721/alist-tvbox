package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.config.ModelSettings;
import cn.har01d.atmaintenance.dto.RepairSummary;
import cn.har01d.atmaintenance.dto.RepairCandidateSummary;
import cn.har01d.atmaintenance.dto.ModelReply;
import cn.har01d.atmaintenance.dto.ModelCallSummary;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ModelClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final jakarta.validation.Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void originDiscoveryJsonModeUsesAnExplicitJsonInstructionAndParsesAnEmptyObjectResult() throws Exception {
        var received = new AtomicReference<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                var request = mapper.readTree(exchange.getRequestBody().readAllBytes());
                received.set(request);
                boolean accepted = !request.has("response_format") || request.path("messages").valueStream()
                        .anyMatch(message -> message.path("content").asText().contains("JSON"));
                byte[] body = (accepted ? response("{\"candidates\":[]}")
                        : "{\"error\":{\"type\":\"invalid_request_error\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(accepted ? 200 : 400, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var search = mapper.createObjectNode().put("status", "results_observed");
            search.putArray("results").addObject().put("url", "https://mirror.example/")
                    .put("origin", "https://mirror.example");
            var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                    "STOPPED", "REPEATED_FAILURE", 2, 3, null, Instant.now(), mapper.createObjectNode(),
                    "current", List.of());
            for (String mode : List.of("json_object", "none")) {
                var settings = enabled();
                settings.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
                settings.setJsonMode(mode);
                var client = new ModelClient(mapper, validator, settings);
                try {
                    String encoded = client.originDiscoveryRequest(repair, search);
                    var request = mapper.readTree(encoded);
                    assertTrue(ModelClient.discoveryRequest(encoded));
                    var data = mapper.readTree(request.path("messages").get(1).path("content").asText());
                    assertEquals(search, data.path("searchEvidence"));
                    assertEquals(2, repair.usedRounds());
                    ModelReply reply = client.generate(encoded);
                    assertNull(reply.errorCode());
                    assertEquals(0, reply.responseDiagnostics().path("originCandidates").size());
                    assertNull(reply.proposal());
                    assertEquals(mode.equals("json_object"), received.get().has("response_format"));
                    assertTrue(received.get().path("messages").get(0).path("content").asText()
                            .contains("{\"candidates\":[]}"));
                } finally { client.close(); }
            }
        } finally { server.stop(0); }
    }

    @Test
    void historicalReverseRequestsArePackedWithoutShorteningCurrentContractsSourceOrStoredHistory() throws Exception {
        var settings = enabled();
        settings.setMaxRequestBytes(65536);
        var client = new ModelClient(mapper, validator, settings);
        try {
            var frozen = mapper.createObjectNode();
            frozen.putObject("policy").put("scope", "static_candidate_validator").putArray("targetNodes").add("S03");
            var latest = frozen.putObject("reverseAnalysis").put("mode", "reverse_analysis_v1")
                    .put("repairId", "repair").put("runId", "run").put("artifactSha256", "a".repeat(64))
                    .put("cleanupConfirmed", true);
            latest.putArray("requests").addObject().put("requestIdSha256", "current").put("status", 200);
            latest.putArray("networkContracts").addObject().put("requestIdSha256", "current")
                    .put("businessSuccessVerified", false).putObject("response").put("status", 200);
            var history = latest.putArray("previousReports");
            for (int n = 0; n < 3; n++) {
                var report = history.addObject().put("taskId", "task-" + n);
                report.putArray("scripts").addObject().putObject("excerpt").put("text", "prior-playback-module");
                report.putArray("networkContracts").addObject().put("requestIdSha256", "request-" + n + "-63");
                var rows = report.putArray("requests");
                for (int index = 0; index < 64; index++) rows.addObject()
                        .put("requestIdSha256", "request-" + n + "-" + index)
                        .put("targetMatched", index == 48).put("status", index == 47 ? 403 : 200)
                        .put("metadata", "observed".repeat(100)).putObject("http").put("responseComplete", true);
            }
            var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                    "STOPPED", "REPEATED_FAILURE", 3, 3, null, Instant.now(), frozen, "current", List.of());
            String original = frozen.toString(), source = "# approved source";
            String encoded = client.request(repair, source, List.of(), true);
            var data = mapper.readTree(mapper.readTree(encoded).path("messages").get(1).path("content").asText());
            assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= settings.getMaxRequestBytes());
            assertEquals(source, data.path("source").asText());
            assertEquals(mapper.readTree("[\"S03\"]"), data.path("validationTargets"));
            assertEquals(latest.path("requests"), data.path("reverseAnalysis").path("requests"));
            assertEquals(latest.path("networkContracts"), data.path("reverseAnalysis").path("networkContracts"));
            assertEquals(3, data.path("contextPacking").path("omittedReverseRequestSets").size());
            for (var report : data.path("reverseAnalysis").path("previousReports")) {
                assertEquals(8, report.path("requests").size());
                assertEquals(64, report.path("requestContextPacking").path("completeHttpCount").asInt());
                assertEquals(56, report.path("requestContextPacking").path("omittedCount").asInt());
                assertTrue(report.path("requests").toString().contains(report.path("networkContracts").get(0)
                        .path("requestIdSha256").asText()));
                assertTrue(report.path("requests").valueStream().anyMatch(row -> row.path("targetMatched").asBoolean()));
                assertTrue(report.path("requests").valueStream().anyMatch(row -> row.path("status").asInt() == 403));
                assertEquals("prior-playback-module", report.path("scripts").get(0).path("excerpt").path("text").asText());
            }
            assertEquals(original, frozen.toString());
            assertEquals(3, repair.usedRounds());
        } finally { client.close(); }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MAINTENANCE_REVERSE_FIXTURE", matches = ".+")
    void existingPlatformReverseHistoryFitsDefaultModelCapacityWithoutSendingAModelCall() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var receipt = json.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                System.getenv("MAINTENANCE_REVERSE_FIXTURE"))));
        var repair = json.treeToValue(receipt.path("repair"), RepairSummary.class);
        String original = repair.frozen().toString();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(System.getenv("MAINTENANCE_REVERSE_SOURCE")),
                StandardCharsets.UTF_8);
        assertEquals(repair.baselineArtifactSha256(), Hashes.sha256(source));
        var candidates = new ArrayList<RepairCandidateSummary>();
        for (var row : receipt.path("candidates")) candidates.add(json.treeToValue(row, RepairCandidateSummary.class));
        var settings = enabled();
        var client = new ModelClient(mapper, validator, settings);
        try {
            String encoded = client.request(repair, source, candidates, true);
            var data = mapper.readTree(mapper.readTree(encoded).path("messages").get(1).path("content").asText());
            assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= settings.getMaxRequestBytes());
            assertEquals(source, data.path("source").asText());
            assertEquals(repair.frozen().path("policy").path("targetNodes"), data.path("validationTargets"));
            var reverse = data.path("reverseAnalysis");
            assertEquals(repair.frozen().path("reverseAnalysis").path("networkContracts"), reverse.path("networkContracts"));
            assertEquals(repair.frozen().path("reverseAnalysis").path("requests"), reverse.path("requests"));
            assertFalse(data.path("contextPacking").path("omittedReverseRequestSets").isEmpty());
            assertEquals(original, repair.frozen().toString());
            String destination = System.getenv("MAINTENANCE_MODEL_CONTEXT_RECEIPT");
            if (destination != null) java.nio.file.Files.writeString(java.nio.file.Path.of(destination),
                mapper.createObjectNode().put("requestBytes", encoded.getBytes(StandardCharsets.UTF_8).length)
                    .put("requestSha256", Hashes.sha256(encoded)).put("maxRequestBytes", settings.getMaxRequestBytes())
                    .put("reports", reverse.path("previousReports").size() + 1)
                    .put("compactedHistoryReports", data.path("contextPacking").path("omittedReverseRequestSets").size())
                    .put("currentContracts", reverse.path("networkContracts").size()).put("sourceUnchanged", true)
                    .put("storedHistoryUnchanged", true).put("modelCalls", 0).toPrettyString(), StandardCharsets.UTF_8);
        } finally { client.close(); }
    }

    @Test
    void repairableBlockedPlayerCanReachTheModelButAccessFailuresCannot() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            var frozen = mapper.createObjectNode();
            frozen.putObject("policy").put("scope", "managed_loader_candidate")
                    .putArray("targetNodes").add("R01");
            var baseline = frozen.putObject("baseline").put("profileSha256", "b".repeat(64));
            baseline.putObject("profile").putArray("requiredNodes").add("R01");
            var receipt = baseline.putObject("result").putObject("managedSourceEvidence")
                    .putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION)
                    .put("inputSourceSha256", "a".repeat(64)).put("inputProfileSha256", "b".repeat(64));
            var node = receipt.putArray("nodes").addObject().put("id", "R01").put("status", "blocked")
                    .put("reason", "PLAYER_EXTERNAL_PARSER_REQUIRED");
            var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "c".repeat(64),
                    "AWAITING_PROPOSAL", null, 0, 3, null, Instant.now(), frozen, "current", List.of());
            var request = mapper.readTree(client.request(repair, "# source"));
            var data = mapper.readTree(request.path("messages").get(1).path("content").asText());
            assertEquals(mapper.readTree("[\"R01\"]"), data.path("validationTargets"));
            assertEquals("blocked", data.path("loaderFailures").get(0).path("status").asText());
            for (String reason : List.of("SOURCE_CREDENTIALS_REQUIRED", "EGRESS_HTTP_FAILED")) {
                node.put("reason", reason);
                assertEquals("MODEL_REPAIR_SCOPE_UNSUPPORTED", assertThrows(ResponseStatusException.class,
                        () -> client.request(repair, "# source")).getReason());
            }
        } finally { client.close(); }
    }

    @Test
    void enhancedModeIsOptInAndKeepsTheSameProposalTargetsAndSource() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        var frozen = mapper.createObjectNode();
        frozen.putObject("policy").put("scope", "static_candidate_validator").putArray("targetNodes").add("S03");
        frozen.putObject("baseline").putObject("context").put("authority", "data_only");
        var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                "AWAITING_PROPOSAL", null, 2, 3, null, Instant.now(), frozen, "current", List.of());
        frozen.putObject("reverseAnalysis").put("mode", "reverse_analysis_v1").put("repairId", "repair")
                .put("runId", "run").put("artifactSha256", "a".repeat(64)).put("cleanupConfirmed", true)
                .putArray("scripts").addObject().put("sourceSha256", "c".repeat(64));
        frozen.withObject("reverseAnalysis").putArray("previousReports")
                .addObject().put("taskId", "earlier-page");
        String ordinary = client.request(repair, "# reviewed source");
        String enhanced = client.request(repair, "# reviewed source", List.of(), true);
        assertFalse(ModelClient.enhancedRequest(mapper, ordinary));
        assertTrue(ModelClient.enhancedRequest(mapper, enhanced));
        var ordinaryData = mapper.readTree(mapper.readTree(ordinary).path("messages").get(1).path("content").asText());
        var enhancedData = mapper.readTree(mapper.readTree(enhanced).path("messages").get(1).path("content").asText());
        assertEquals(ordinaryData.path("source"), enhancedData.path("source"));
        assertEquals(ordinaryData.path("validationTargets"), enhancedData.path("validationTargets"));
        assertEquals(2, enhancedData.path("enhancedDiagnostics").path("usedRounds").asInt());
        assertFalse(enhancedData.path("enhancedDiagnostics").path("bypassImplemented").asBoolean());
        assertEquals(frozen.path("reverseAnalysis"), enhancedData.path("reverseAnalysis"));
        assertFalse(ordinaryData.has("reverseAnalysis"));
        assertEquals(ordinary, client.request(repair, "# reviewed source", List.of(), false));
        assertFalse(ModelClient.enhancedRequest(mapper, null));
        client.close();
    }

    @Test
    void enhancedModelContextIncludesBoundExecutionFailureWithoutRawDiagnosticFields() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            var frozen = mapper.createObjectNode();
            frozen.putObject("policy").put("scope", "static_candidate_validator").putArray("targetNodes").add("S03");
            frozen.putObject("baseline").put("runtimeSha256", "c".repeat(64))
                    .put("profileSha256", "b".repeat(64)).put("executionPolicySha256", "f".repeat(64));
            var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                    "BLOCKED", "EGRESS_BYTE_BUDGET", 1, 3, null, Instant.now(), frozen, "current", List.of());
            var result = mapper.createObjectNode().put("artifactSha256", "d".repeat(64)).put("runtimeRequested", true);
            result.putObject("managedExecutionFailure").put("candidateId", "candidate").put("jobId", "candidate")
                    .put("inputSourceSha256", "d".repeat(64)).put("inputProfileSha256", "b".repeat(64))
                    .put("bundleSha256", "c".repeat(64)).put("executionPolicySha256", "f".repeat(64))
                    .put("code", "EGRESS_BYTE_BUDGET").putObject("diagnostic")
                    .put("authority", "managed_source_broker_observed").put("rawCookie", "PRIVATE");
            var candidate = new RepairCandidateSummary("candidate", repair.id(), 1, "d".repeat(64), "e".repeat(64),
                    "FAILED", "EGRESS_BYTE_BUDGET", Instant.now(), Instant.now(), null, result, null, null, null);
            var request = client.request(repair, "# approved baseline", List.of(candidate), true);
            var data = mapper.readTree(mapper.readTree(request).path("messages").get(1).path("content").asText());
            var triage = data.path("enhancedDiagnostics");
            assertEquals("candidate", triage.path("candidateId").asText());
            assertTrue(triage.path("hostFailureDiagnosticMatched").asBoolean());
            assertFalse(triage.path("loaderReceiptMatched").asBoolean());
            assertTrue(triage.path("routes").toString().contains("request_budget"));
            assertFalse(data.toString().contains("PRIVATE"));
            assertEquals(1, triage.path("usedRounds").asInt());
        } finally { client.close(); }
    }

    @Test
    void deliveryNameIsChineseMetadataAndOldProposalEncodingRemainsUnchanged() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        var original = validProposal();
        var old = client.parse(response(original.toString()).getBytes(StandardCharsets.UTF_8)).proposal();
        assertNotNull(old);
        assertNull(old.deliveryName());
        assertEquals(original, mapper.readTree(new RepairPatch(mapper).encode(old)));
        var named = original.deepCopy().put("deliveryName", "艾克斯视频");
        var parsed = client.parse(response(named.toString()).getBytes(StandardCharsets.UTF_8)).proposal();
        assertNotNull(parsed);
        assertEquals("艾克斯视频", parsed.deliveryName());
        assertEquals(old.edits(), parsed.edits());
        assertNull(client.parse(response(original.deepCopy().put("deliveryName", "Xvideos_TuT").toString())
                .getBytes(StandardCharsets.UTF_8)).proposal());
        assertNull(client.parse(response(original.deepCopy().put("deliveryName", "影视源_Xvideos_TuT").toString())
                .getBytes(StandardCharsets.UTF_8)).proposal());
    }

    @Test
    void disabledConfigurationCannotBuildRequestsAndNeedsNoSecretOrEndpoint() {
        var client = new ModelClient(mapper, validator, new ModelSettings());
        assertFalse(client.connection().enabled());
        assertEquals("MODEL_DISABLED", assertThrows(ResponseStatusException.class, client::requireEnabled).getReason());
    }

    @Test
    void sourceLimitsAreValidatedAndEveryLimitChangesConfigurationIdentity() {
        var defaults = new ModelClient(mapper, validator, new ModelSettings()).connection();
        assertEquals(0, defaults.sourceDailyCallLimit());
        assertEquals(0, defaults.sourceDailyInputBytes());
        assertEquals(0, defaults.sourceDailyOutputTokens());
        assertEquals(0, defaults.dailyCallLimit());
        assertEquals(0, defaults.dailyInputBytes());
        assertEquals(0, defaults.dailyOutputTokens());
        for (int mode = 0; mode < 6; mode++) {
            var settings = new ModelSettings();
            switch (mode) {
                case 0 -> settings.setSourceDailyCallLimit(-1);
                case 1 -> settings.setSourceDailyCallLimit(101);
                case 2 -> settings.setSourceDailyInputBytes(65535);
                case 3 -> settings.setSourceDailyInputBytes(26214401);
                case 4 -> settings.setSourceDailyOutputTokens(2047);
                case 5 -> settings.setSourceDailyOutputTokens(1638401);
            }
            assertEquals("INVALID_MODEL_LIMITS", assertThrows(IllegalArgumentException.class,
                    () -> new ModelClient(mapper, validator, settings)).getMessage());
        }
        for (int mode = 0; mode < 3; mode++) {
            var settings = new ModelSettings();
            if (mode == 0) settings.setSourceDailyCallLimit(2);
            if (mode == 1) settings.setSourceDailyInputBytes(settings.getMaxRequestBytes());
            if (mode == 2) settings.setSourceDailyOutputTokens(4096);
            assertNotEquals(defaults.configurationSha256(),
                    new ModelClient(mapper, validator, settings).connection().configurationSha256());
        }
    }

    @Test
    void modelLimitsAreValidatedAndChangeConfigurationIdentityWithoutChangingModelName() {
        var defaults = new ModelClient(mapper, validator, new ModelSettings()).connection();
        assertEquals(0, defaults.modelDailyCallLimit());
        assertEquals(0, defaults.modelDailyInputBytes());
        assertEquals(0, defaults.modelDailyOutputTokens());
        for (int mode = 0; mode < 6; mode++) {
            var settings = new ModelSettings();
            switch (mode) {
                case 0 -> settings.setModelDailyCallLimit(-1);
                case 1 -> settings.setModelDailyCallLimit(101);
                case 2 -> settings.setModelDailyInputBytes(65535);
                case 3 -> settings.setModelDailyInputBytes(26214401);
                case 4 -> settings.setModelDailyOutputTokens(2047);
                case 5 -> settings.setModelDailyOutputTokens(1638401);
            }
            assertEquals("INVALID_MODEL_LIMITS", assertThrows(IllegalArgumentException.class,
                    () -> new ModelClient(mapper, validator, settings)).getMessage());
        }
        for (int mode = 0; mode < 3; mode++) {
            var settings = new ModelSettings();
            if (mode == 0) settings.setModelDailyCallLimit(2);
            if (mode == 1) settings.setModelDailyInputBytes(settings.getMaxRequestBytes());
            if (mode == 2) settings.setModelDailyOutputTokens(4096);
            var updated = new ModelClient(mapper, validator, settings).connection();
            assertEquals(defaults.model(), updated.model());
            assertNotEquals(defaults.configurationSha256(), updated.configurationSha256());
        }
    }

    @Test
    void endpointValidationRejectsCredentialQueriesNonLoopbackHttpAndWrongPathsWithoutEcho() {
        for (String address : List.of("http://example.invalid/v1/chat/completions",
                "http://localhost/v1/chat/completions", "https://user:PRIVATE@example.invalid/v1/chat/completions",
                "https://example.invalid/v1/chat/completions?key=PRIVATE",
                "https://example.invalid/v1/chat/completions#PRIVATE", "http://127.0.0.1/v1/responses",
                "http://127.0.0.1/v1/chat/completions/extra",
                "not-an-address", "http://127.0.0.1:0/v1/chat/completions", "https://example.invalid/v1/chat/completions")) {
            var settings = enabled();
            settings.setEndpoint(address);
            var error = assertThrows(IllegalArgumentException.class, () -> new ModelClient(mapper, validator, settings));
            assertEquals("INVALID_MODEL_CONNECTION", error.getMessage());
        }
        var settings = enabled();
        settings.setKeyEnv("PRIVATE=VALUE");
        assertEquals("INVALID_MODEL_CONNECTION", assertThrows(IllegalArgumentException.class,
                () -> new ModelClient(mapper, validator, settings)).getMessage());
    }

    @Test
    void relayBaseUrlAndOptionalDialectFieldsReachTheConfiguredRequest() throws Exception {
        var receivedPath = new AtomicReference<String>();
        var receivedBody = new AtomicReference<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                receivedPath.set(exchange.getRequestURI().getPath());
                receivedBody.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
                byte[] response = response(validProposal().toString()).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        var settings = enabled();
        settings.setEndpoint("");
        settings.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/relay/openai/v1");
        settings.setMaxTokenParameter("max_tokens");
        settings.setJsonMode("none");
        settings.setIncludeStoreParameter(false);
        settings.setModel("[relay] model/alias-\u6d4b\u8bd5");
        var client = new ModelClient(mapper, validator, settings);
        try {
            var frozen = mapper.createObjectNode();
            frozen.putObject("policy").put("scope", "static_candidate_validator")
                    .putArray("targetNodes").add("S03");
            frozen.putObject("baseline").putObject("context").put("authority", "data_only");
            var repair = new RepairSummary("id", "source", "run", "a".repeat(64), "b".repeat(64),
                    "AWAITING_PROPOSAL", null, 0, 3, null, Instant.now(), frozen, "current", List.of());

            ModelReply reply = client.generate(client.request(repair, "# reviewed source"));

            assertNotNull(reply.proposal());
            assertEquals("/relay/openai/v1/chat/completions", receivedPath.get());
            assertEquals("[relay] model/alias-\u6d4b\u8bd5", receivedBody.get().path("model").asText());
            assertEquals(settings.getMaxCompletionTokens(), receivedBody.get().path("max_tokens").asInt());
            assertFalse(receivedBody.get().has("max_completion_tokens"));
            assertFalse(receivedBody.get().has("response_format"));
            assertFalse(receivedBody.get().has("store"));
            assertTrue(receivedBody.get().path("stream").isBoolean());
            var defaults = new ModelClient(mapper, validator, new ModelSettings()).connection();
            assertNotEquals(defaults.configurationSha256(), client.connection().configurationSha256());
        } finally {
            client.close();
            server.stop(0);
        }
    }

    @Test
    void relayDialectSettingsAreAllowlistedAndPartOfConfigurationIdentity() {
        var defaults = new ModelClient(mapper, validator, new ModelSettings()).connection();
        var legacyTokens = new ModelSettings();
        legacyTokens.setMaxTokenParameter("max_tokens");
        assertNotEquals(defaults.configurationSha256(),
                new ModelClient(mapper, validator, legacyTokens).connection().configurationSha256());
        var noJsonMode = new ModelSettings();
        noJsonMode.setJsonMode("none");
        assertNotEquals(defaults.configurationSha256(),
                new ModelClient(mapper, validator, noJsonMode).connection().configurationSha256());
        var noStore = new ModelSettings();
        noStore.setIncludeStoreParameter(false);
        assertNotEquals(defaults.configurationSha256(),
                new ModelClient(mapper, validator, noStore).connection().configurationSha256());

        for (int mode = 0; mode < 2; mode++) {
            var invalid = enabled();
            if (mode == 0) invalid.setMaxTokenParameter("max_tokens,temperature");
            else invalid.setJsonMode("json_schema");
            assertEquals("INVALID_MODEL_REQUEST_OPTIONS", assertThrows(IllegalArgumentException.class,
                    () -> new ModelClient(mapper, validator, invalid)).getMessage());
        }
        var ambiguous = enabled();
        ambiguous.setBaseUrl("https://relay.example/v1");
        assertEquals("INVALID_MODEL_CONNECTION", assertThrows(IllegalArgumentException.class,
                () -> new ModelClient(mapper, validator, ambiguous)).getMessage());
    }

    @Test
    void baseUrlPreservesPrefixesAndTrailingSlashWithoutGuessingVersion() throws Exception {
        for (String prefix : List.of("", "/", "/v1", "/v1/", "/api/relay/v4", "/api/relay/v4/")) {
            var settings = enabled();
            settings.setEndpoint("");
            settings.setBaseUrl("http://127.0.0.1:9" + prefix);
            var base = new ModelClient(mapper, validator, settings);
            settings.setBaseUrl("");
            settings.setEndpoint("http://127.0.0.1:9"
                    + (prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix)
                    + "/chat/completions");
            var full = new ModelClient(mapper, validator, settings);
            try {
                assertEquals(full.connection().configurationSha256(), base.connection().configurationSha256());
            } finally {
                base.close();
                full.close();
            }
        }
        for (String address : List.of("http://relay.invalid/v1", "https://user:PRIVATE@relay.invalid/v1",
                "https://relay.invalid/v1?key=PRIVATE", "https://relay.invalid/v1#PRIVATE")) {
            var settings = enabled();
            settings.setEndpoint("");
            settings.setBaseUrl(address);
            assertEquals("INVALID_MODEL_CONNECTION", assertThrows(IllegalArgumentException.class,
                    () -> new ModelClient(mapper, validator, settings)).getMessage());
        }
    }

    @Test
    void jsonBoundaryRejectsDuplicateFieldsCoercionNullWrongTargetsAndUnboundedUsage() throws Exception {
        var client = new ModelClient(mapper, validator, new ModelSettings());
        String proposal = validProposal().toString();
        for (String text : List.of("null", "[]", proposal + " {}",
                proposal.replace("\"schemaVersion\":1", "\"schemaVersion\":null"),
                proposal.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                proposal.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                proposal.replace("\"diagnosis\":\"reason\"", "\"diagnosis\":123"),
                proposal.replace("\"oldText\":\"unique\"", "\"oldText\":null"),
                proposal.replace("\"validationTargets\":[\"S03\"]", "\"validationTargets\":[\"R11\"]"),
                proposal.replace("\"targetFile\":\"source.py\"", "\"targetFile\":\"/etc/passwd\""),
                proposal.replace("\"diagnosis\":\"reason\"", "\"diagnosis\":\"\""))) {
            assertInvalid(client, response(text));
        }
        for (String envelope : List.of("null", "[]", response(proposal) + "{}",
                response(proposal).replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"tool_calls\""),
                response(proposal).replace("\"role\":\"assistant\"", "\"role\":\"user\""),
                response(proposal).replace("\"content\":", "\"refusal\":\"refused\",\"content\":"),
                response(proposal).replace("\"content\":", "\"tool_calls\":[],\"content\":"),
                response(proposal).replace("\"completion_tokens\":2", "\"completion_tokens\":2049"),
                response(proposal).replace("\"total_tokens\":3", "\"total_tokens\":4"),
                response(proposal).replace("\"prompt_tokens\":1", "\"prompt_tokens\":-1"),
                response(proposal).replace("\"prompt_tokens\":1", "\"prompt_tokens\":\"1\""))) {
            assertInvalid(client, envelope);
        }
        assertNotNull(client.parse(response(proposal).getBytes(StandardCharsets.UTF_8)).proposal());
    }

    @Test
    void requestBytesAreBoundedAndKnownDiagnosticMetadataIsNotForwarded() {
        var settings = enabled();
        settings.setMaxRequestBytes(65536);
        var client = new ModelClient(mapper, validator, settings);
        try {
            var frozen = mapper.createObjectNode();
            frozen.putObject("baseline").put("originUrl", "PRIVATE_URL").put("binding", "PRIVATE_TOKEN")
                    .putObject("context").put("authority", "data_only");
            frozen.putObject("policy").put("scope", "static_candidate_validator")
                    .putArray("targetNodes").add("S03");
            var repair = new RepairSummary("id", "source", "run", "a".repeat(64), "b".repeat(64),
                    "AWAITING_PROPOSAL", null, 0, 3, null, Instant.now(), frozen, "current", List.of());
            String request = client.request(repair, "# reviewed source");
            assertFalse(request.contains("PRIVATE"));
            assertFalse(request.contains("maxRounds"));
            assertEquals("MODEL_INPUT_TOO_LARGE", assertThrows(ResponseStatusException.class,
                    () -> client.request(repair, "中".repeat(30000))).getReason());
        } finally { client.close(); }
    }

    @Test
    void defaultRequestCapacityKeepsLargeMultisourcePluginAndFrozenTargetsIntact() throws Exception {
        var settings = enabled();
        var client = new ModelClient(mapper, validator, settings);
        try {
            String source = "# \u6765\u6e90 \"quoted\" \\\n".repeat(5000) + "# bundled module\n".repeat(25000);
            var repair = coverRepair(source);
            String stored = repair.frozen().toString();
            String encoded = client.request(repair, source);
            var data = mapper.readTree(mapper.readTree(encoded).path("messages").get(1).path("content").asText());
            assertEquals(1048576, settings.getMaxRequestBytes());
            assertTrue(source.getBytes(StandardCharsets.UTF_8).length > 432635);
            assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= settings.getMaxRequestBytes());
            assertEquals(source, data.path("source").asText());
            assertEquals(repair.baselineArtifactSha256(), data.path("expectedBaselineSha256").asText());
            assertEquals(mapper.readTree("[\"C09\"]"), data.path("validationTargets"));
            assertEquals(mapper.readTree("[\"S06\",\"C09\"]"), data.path("requiredNodes"));
            assertEquals(stored, repair.frozen().toString());
            assertEquals("MODEL_INPUT_TOO_LARGE", assertThrows(ResponseStatusException.class,
                    () -> client.request(repair, source.repeat(3))).getReason());
        } finally { client.close(); }
    }

    @Test
    void homeRepairUsesFixedRuntimeTargetsAndOnlyBoundedNonSecretObservations() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            var frozen = mapper.createObjectNode();
            frozen.putObject("policy").put("scope", "managed_home_candidate")
                    .putArray("targetNodes").add("C01").add("C02");
            var baseline = frozen.putObject("baseline");
            baseline.putObject("context").put("authority", "data_only");
            var evidence = baseline.putObject("result").putObject("managedSourceEvidence");
            evidence.put("rawHttpBody", "PRIVATE_HTTP_BODY").put("token", "PRIVATE_TOKEN");
            var observation = evidence.putObject("untrustedRuntimeSummary").put("status", "home_empty")
                    .put("homeItems", 0).put("categoryItems", 20).put("detailItems", "PRIVATE_COUNT")
                    .put("playEpisodes", 10001).put("mediaUrl", "PRIVATE_SIGNED_URL");
            var repair = new RepairSummary("id", "source", "run", "a".repeat(64), "b".repeat(64),
                    "AWAITING_PROPOSAL", null, 0, 3, null, Instant.now(), frozen, "current", List.of());
            var request = mapper.readTree(client.request(repair, "# source"));
            String instruction = request.path("messages").get(0).path("content").asText();
            assertTrue(instruction.contains("managed_home_candidate"));
            assertFalse(instruction.contains("Only remove duplicate"));
            var data = mapper.readTree(request.path("messages").get(1).path("content").asText());
            assertEquals(List.of("C01", "C02"), mapper.convertValue(data.path("validationTargets"), List.class));
            assertEquals("T4_HOME_EMPTY", data.path("failureCodes").get(0).asText());
            assertEquals(0, data.path("runtimeObservation").path("homeItems").asInt());
            assertEquals(20, data.path("runtimeObservation").path("categoryItems").asInt());
            assertFalse(data.path("runtimeObservation").has("detailItems"));
            assertFalse(data.path("runtimeObservation").has("playEpisodes"));
            assertFalse(request.toString().contains("PRIVATE"));
            observation.put("homeItems", false);
            var changed = mapper.readTree(client.request(repair, "# source"));
            assertFalse(mapper.readTree(changed.path("messages").get(1).path("content").asText())
                    .path("runtimeObservation").has("homeItems"));
            frozen.withObject("/policy").withArray("targetNodes").add("S03");
            assertEquals("MODEL_REPAIR_SCOPE_UNSUPPORTED", assertThrows(ResponseStatusException.class,
                    () -> client.request(repair, "# source")).getReason());
        } finally { client.close(); }
    }

    @Test
    void coverRepairReceivesAutomaticallyCollectedPublicExcerptsButRejectsUnboundEvidence() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            var frozen = mapper.createObjectNode();
            frozen.putObject("policy").put("scope", "managed_loader_candidate").putArray("targetNodes").add("C09");
            var baseline = frozen.putObject("baseline").put("profileSha256", "c".repeat(64));
            baseline.putObject("profile").putArray("requiredNodes").add("S06").add("C09");
            var evidence = baseline.putObject("result").putObject("managedSourceEvidence");
            evidence.putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION)
                    .put("inputSourceSha256", "a".repeat(64)).put("inputProfileSha256", "c".repeat(64))
                    .putArray("nodes").addObject().put("id", "C09").put("status", "fail").put("reason", "COVER_BYTES_INVALID");
            evidence.set("resourceEvidence", ManagedResourceEvidenceTest.fixture(mapper, "a".repeat(64)));
            evidence.set("httpObservations", ManagedResourceEvidenceTest.events(mapper));
            evidence.put("rawBody", "PRIVATE");
            var repair = new RepairSummary("id", "source", "run", "a".repeat(64), "b".repeat(64),
                    "AWAITING_PROPOSAL", null, 0, 3, null, Instant.now(), frozen, "current", List.of());
            var request = mapper.readTree(client.request(repair, "# source"));
            var data = mapper.readTree(request.path("messages").get(1).path("content").asText());
            assertEquals("decrypt(x); ", data.path("resourceEvidence").path("resources").get(1)
                    .path("excerpts").get(0).path("text").asText());
            assertEquals("additive_preserve_bindings", data.path("candidateImportPolicy").path("mode").asText());
            assertTrue(data.path("candidateImportPolicy").path("runtimeValidationRequired").asBoolean());
            assertEquals("localProxy", data.path("imageDelivery").path("callback").asText());
            assertFalse(data.path("imageDelivery").path("plainSpiderFactoryInjected").asBoolean(true));
            assertFalse(data.path("imageDelivery").path("baseSpiderFactoryInjected").asBoolean(true));
            assertFalse(data.path("imageDelivery").path("nativeT4BaseFactorySupported").asBoolean(true));
            assertTrue(data.path("imageDelivery").path("sameSpiderInstance").asBoolean());
            assertFalse(data.path("imageDelivery").path("inlineDataUriSupported").asBoolean());
            assertFalse(data.toString().contains("PRIVATE"));
            ((ObjectNode) evidence.path("resourceEvidence")).put("artifactSha256", "f".repeat(64));
            assertEquals("MODEL_RESOURCE_EVIDENCE_INVALID", assertThrows(ResponseStatusException.class,
                    () -> client.request(repair, "# source")).getReason());
        } finally { client.close(); }
    }

    @Test
    void loaderFeedbackRetainsTheVerifiedLatestPatchAndActualImageDeliveryBranch() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            String source = "value = 1\n";
            var repair = coverRepair(source);
            var candidate = coverCandidate(repair, source);
            var request = mapper.readTree(client.request(repair, source, List.of(candidate)));
            var data = mapper.readTree(request.path("messages").get(1).path("content").asText());
            var feedback = data.path("previousCandidates").get(0);
            assertEquals(candidate.proposal(), feedback.path("proposal"));
            assertEquals("direct_http", feedback.path("imageDeliveryObservation").path("delivery").asText());
            assertEquals(0, feedback.path("imageDeliveryObservation").path("proxyCalls").asInt());
            assertEquals("COVER_BYTES_INVALID",
                    feedback.path("imageDeliveryObservation").path("reason").asText());
            assertFalse(request.toString().contains("PRIVATE"));

            ((ObjectNode) candidate.proposal()).put("expectedBaselineSha256", "f".repeat(64));
            data = mapper.readTree(mapper.readTree(client.request(repair, source, List.of(candidate)))
                    .path("messages").get(1).path("content").asText());
            assertFalse(data.path("previousCandidates").get(0).has("proposal"));
            ((ObjectNode) candidate.result().path("runtimeEvidence").path("loaderValidation"))
                    .put("inputProfileSha256", "f".repeat(64));
            data = mapper.readTree(mapper.readTree(client.request(repair, source, List.of(candidate)))
                    .path("messages").get(1).path("content").asText());
            assertFalse(data.path("previousCandidates").get(0).has("imageDeliveryObservation"));
        } finally { client.close(); }
    }

    @Test
    void baselineAndCandidateReceiveTheirOwnJsonStructureNotRawPayloads() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            String source = "value = 1\n";
            var repair = coverRepair(source);
            ((ObjectNode) repair.frozen().path("policy")).putArray("targetNodes").add("R01");
            var baseline = (ObjectNode) repair.frozen().path("baseline");
            baseline.withObject("/profile").putArray("requiredNodes").add("S06").add("R01");
            var evidence = (ObjectNode) baseline.path("result").path("managedSourceEvidence");
            var candidate = coverCandidate(repair, source);
            var runtime = (ObjectNode) candidate.result().path("runtimeEvidence");
            for (ObjectNode observed : List.of(evidence, runtime)) {
                observed.set("resourceEvidence", ManagedResourceEvidenceTest.jsonFixture(mapper, observed.path("inputSourceSha256").asText()));
                observed.set("httpObservations", ManagedResourceEvidenceTest.jsonEvents(mapper));
                ((ObjectNode) observed.path("loaderValidation")).set("calls", ManagedResourceEvidenceTest.jsonCalls(mapper));
                ((ObjectNode) observed.path("loaderValidation").path("nodes").get(0))
                        .put("id", "R01").put("reason", "PLAYER_URL_EMPTY");
            }
            String before = repair.frozen().toString(), candidateBefore = candidate.result().toString();
            var request = mapper.readTree(client.request(repair, source, List.of(candidate)));
            var data = mapper.readTree(request.path("messages").get(1).path("content").asText());
            assertEquals(evidence.path("resourceEvidence"), data.path("resourceEvidence"));
            assertEquals(runtime.path("resourceEvidence"), data.path("previousCandidates").get(0).path("resourceEvidence"));
            assertTrue(request.path("messages").get(0).path("content").asText().contains("do not replay POSTs"));
            assertFalse(request.toString().contains("PRIVATE"));
            assertEquals(before, repair.frozen().toString());
            assertEquals(candidateBefore, candidate.result().toString());
        } finally { client.close(); }
    }

    @Test
    void categoryRepairAndFeedbackReceiveTheirOwnBoundPageEvidenceWithoutImageContract() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            String source = "value = 1\n";
            var repair = coverRepair(source);
            ((ObjectNode) repair.frozen().path("policy")).putArray("targetNodes").add("C03");
            var baseline = (ObjectNode) repair.frozen().path("baseline");
            baseline.withObject("/profile").putArray("requiredNodes").add("S06").add("C03");
            var evidence = (ObjectNode) baseline.path("result").path("managedSourceEvidence");
            ((ObjectNode) evidence.path("resourceEvidence")).put("targetNode", "C03").putNull("image");
            ((ObjectNode) evidence.path("loaderValidation").path("nodes").get(0))
                    .put("id", "C03").put("reason", "CONTENT_LIST_EMPTY_OR_INVALID");
            var candidate = coverCandidate(repair, source);
            var runtime = (ObjectNode) candidate.result().path("runtimeEvidence");
            ((ObjectNode) runtime.path("resourceEvidence")).put("targetNode", "C03").putNull("image");
            var request = mapper.readTree(client.request(repair, source, List.of(candidate)));
            var data = mapper.readTree(request.path("messages").get(1).path("content").asText());
            assertEquals(evidence.path("resourceEvidence"), data.path("resourceEvidence"));
            assertEquals(runtime.path("resourceEvidence"),
                    data.path("previousCandidates").get(0).path("resourceEvidence"));
            assertFalse(data.has("imageDelivery"));
            assertFalse(request.toString().contains("PRIVATE"));
            ((ObjectNode) runtime.path("resourceEvidence")).put("artifactSha256", "f".repeat(64));
            data = mapper.readTree(mapper.readTree(client.request(repair, source, List.of(candidate)))
                    .path("messages").get(1).path("content").asText());
            assertFalse(data.path("previousCandidates").get(0).has("resourceEvidence"));
        } finally { client.close(); }
    }

    @Test
    void continuationBeyondThirdRoundKeepsLatestFeedbackAndDiagnostics() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            String source = "value = 1\n";
            var base = coverRepair(source);
            var repair = new RepairSummary(base.id(), base.sourceId(), base.baselineRunId(), base.baselineArtifactSha256(),
                    base.faultSha256(), "AWAITING_PROPOSAL", null, 4, 4, null, base.createdAt(), base.frozen(), "current", List.of());
            var original = coverCandidate(repair, source);
            var candidates = new ArrayList<RepairCandidateSummary>();
            for (int round = 1; round <= 4; round++) candidates.add(new RepairCandidateSummary("candidate" + round,
                    repair.id(), round, original.sha256(), original.proposalSha256(), original.status(), original.errorCode(),
                    original.createdAt(), original.finishedAt(), original.proposal(), original.result(), null, null, null));
            var data = mapper.readTree(mapper.readTree(client.request(repair, source, candidates))
                    .path("messages").get(1).path("content").asText());
            assertEquals(3, data.path("previousCandidates").size());
            assertEquals(2, data.path("previousCandidates").get(0).path("round").asInt());
            assertEquals(4, data.path("previousCandidates").get(2).path("round").asInt());
            assertEquals(original.proposal(), data.path("previousCandidates").get(2).path("proposal"));
            assertEquals("candidate4", data.path("repairDiagnostics").path("candidateId").asText());
        } finally { client.close(); }
    }

    @Test
    void oversizedFollowupPacksExcerptsWithoutChangingSourceLatestPatchOrStoredReports() throws Exception {
        var settings = enabled();
        settings.setMaxRequestBytes(16000);
        var client = new ModelClient(mapper, validator, settings);
        try {
            String source = "value = 1\n";
            var repair = coverRepair(source);
            var candidate = coverCandidate(repair, source);
            for (var evidence : List.of(
                    repair.frozen().path("baseline").path("result").path("managedSourceEvidence"),
                    candidate.result().path("runtimeEvidence"))) {
                var resources = (ObjectNode) evidence.path("resourceEvidence");
                var excerpts = ((ObjectNode) resources.path("resources").get(1)).putArray("excerpts");
                for (int i = 0; i < 3; i++)
                    excerpts.addObject().put("offset", 0).put("text", "x".repeat(4000))
                            .put("textSha256", Hashes.sha256("x".repeat(4000)));
                resources.put("textBytes", 12000);
            }
            String before = repair.frozen().toString();
            String candidateBefore = candidate.result().toString();
            String encoded = client.request(repair, source, List.of(candidate));
            assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= settings.getMaxRequestBytes());
            var data = mapper.readTree(mapper.readTree(encoded).path("messages").get(1).path("content").asText());
            assertEquals(source, data.path("source").asText());
            assertEquals(candidate.proposal(), data.path("previousCandidates").get(0).path("proposal"));
            assertFalse(data.path("contextPacking").path("omittedResourceExcerpts").isEmpty());
            assertTrue(data.path("previousCandidates").get(0).path("resourceEvidence").path("textBytes").asInt() > 0);
            var retained = data.path("previousCandidates").get(0).path("resourceEvidence").path("resources").get(1)
                    .path("excerpts").get(0);
            assertTrue(retained.path("contextShortened").asBoolean());
            assertEquals(Hashes.sha256("x".repeat(4000)), retained.path("originalTextSha256").asText());
            assertEquals(Hashes.sha256(retained.path("text").asText()), retained.path("textSha256").asText());
            assertEquals(256, retained.path("text").asText().length());
            assertEquals(before, repair.frozen().toString());
            assertEquals(candidateBefore, candidate.result().toString());
        } finally { client.close(); }
    }

    @Test
    void repairAndFollowupCarryObservedErrorBodyStatusAndSourceAction() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            String source = "value = 1\n";
            var repair = coverRepair(source);
            ((ObjectNode) repair.frozen().path("policy")).putArray("targetNodes").add("C03");
            var baseline = (ObjectNode) repair.frozen().path("baseline");
            baseline.withObject("/profile").putArray("requiredNodes").add("S06").add("C03");
            var evidence = (ObjectNode) baseline.path("result").path("managedSourceEvidence");
            ((ObjectNode) evidence.path("loaderValidation").path("nodes").get(0))
                    .put("id", "C03").put("reason", "CONTENT_LIST_EMPTY_OR_INVALID");
            var candidate = coverCandidate(repair, source);
            var runtime = (ObjectNode) candidate.result().path("runtimeEvidence");
            String text = "<html>Access denied</html>";
            for (ObjectNode observed : List.of(evidence, runtime)) {
                var resources = (ObjectNode) observed.path("resourceEvidence");
                resources.put("targetNode", "C03").putNull("image").put("textBytes", 12 + text.length());
                var page = (ObjectNode) resources.path("resources").get(0);
                page.put("responseStatus", 403).put("responseSha256", Hashes.sha256(text))
                        .put("responseBytes", text.length());
                page.putArray("excerpts").addObject().put("offset", 0).put("text", text)
                        .put("textSha256", Hashes.sha256(text));
                var action = page.putObject("observedDuringCall").put("id", 4).put("method", "category")
                        .put("inputSha256", "7".repeat(64));
                ((ObjectNode) observed.path("loaderValidation")).putArray("calls").add(action.deepCopy());
                ((ObjectNode) observed.path("httpObservations").get(1)).put("responseStatus", 403)
                        .put("responseSha256", Hashes.sha256(text)).put("responseBytes", text.length());
            }
            var request = mapper.readTree(client.request(repair, source, List.of(candidate)));
            var data = mapper.readTree(request.path("messages").get(1).path("content").asText());
            assertEquals(evidence.path("resourceEvidence"), data.path("resourceEvidence"));
            assertEquals(runtime.path("resourceEvidence"), data.path("previousCandidates").get(0).path("resourceEvidence"));
            assertFalse(request.toString().contains("PRIVATE"));
        } finally { client.close(); }
    }

    @Test
    void requestPackingDropsReferenceRulesWithoutTruncatingSourcePatchOrImageEvidence() throws Exception {
        var settings = enabled();
        settings.setMaxRequestBytes(16000);
        var client = new ModelClient(mapper, validator, settings);
        try {
            String source = "value = 1\n";
            var repair = coverRepair(source);
            var reference = ((ObjectNode) repair.frozen().path("baseline")).putObject("context");
            reference.put("authority", "data_only").put("omittedRules", 0).put("ruleBytes", 24000)
                    .putArray("rules").addObject().put("id", "broad-reference")
                    .put("guidance", "\u4e2d\"\\\n".repeat(6000));
            var candidate = coverCandidate(repair, source);
            String frozenBefore = repair.frozen().toString();
            String encoded = client.request(repair, source, List.of(candidate));
            assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= settings.getMaxRequestBytes());
            var data = mapper.readTree(mapper.readTree(encoded).path("messages").get(1).path("content").asText());
            assertEquals(source, data.path("source").asText());
            assertEquals(candidate.proposal(), data.path("previousCandidates").get(0).path("proposal"));
            assertEquals(repair.frozen().path("baseline").path("result")
                    .path("managedSourceEvidence").path("resourceEvidence"), data.path("resourceEvidence"));
            assertEquals("broad-reference", data.path("contextPacking").path("omittedReferenceRules")
                    .get(0).path("id").asText());
            assertEquals(1, data.path("referenceMaterial").path("omittedRules").asInt());
            assertEquals(frozenBefore, repair.frozen().toString());
            assertEquals("MODEL_INPUT_TOO_LARGE", assertThrows(ResponseStatusException.class,
                    () -> client.request(repair, "x".repeat(30000), List.of(candidate))).getReason());
        } finally { client.close(); }
    }

    private RepairSummary coverRepair(String source) {
        var frozen = mapper.createObjectNode();
        frozen.putObject("policy").put("scope", "managed_loader_candidate").putArray("targetNodes").add("C09");
        var baseline = frozen.putObject("baseline").put("profileSha256", "c".repeat(64))
                .put("runtimeSha256", "e".repeat(64));
        baseline.putObject("profile").putArray("requiredNodes").add("S06").add("C09");
        var evidence = baseline.putObject("result").putObject("managedSourceEvidence")
                .put("inputSourceSha256", Hashes.sha256(source)).put("bundleSha256", "e".repeat(64));
        evidence.putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION)
                .put("inputSourceSha256", Hashes.sha256(source)).put("inputProfileSha256", "c".repeat(64))
                .putArray("nodes").addObject().put("id", "C09").put("status", "fail").put("reason", "COVER_BYTES_INVALID");
        evidence.set("resourceEvidence", ManagedResourceEvidenceTest.fixture(mapper, Hashes.sha256(source)));
        evidence.set("httpObservations", ManagedResourceEvidenceTest.events(mapper));
        return new RepairSummary("repair", "source", "run", Hashes.sha256(source), "b".repeat(64),
                "AWAITING_PROPOSAL", null, 1, 3, null, Instant.now(), frozen, "current", List.of());
    }

    @Test
    void contentLossFeedbackCarriesOnlyMatchedCountsAndCategoryHashes() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            String source = "value = 1\n";
            var repair = coverRepair(source);
            var candidate = coverCandidate(repair, source);
            var result = (ObjectNode) candidate.result();
            var content = result.putObject("contentAcceptance").put("policy", "category_detail_preservation_v1")
                    .put("status", "regressed_or_incomplete").put("successful", false).put("rawUrl", "PRIVATE");
            content.putArray("comparison").addObject().put("categoryIdSha256", "d".repeat(64))
                    .put("detailComparable", true).put("beforeItems", 20).put("afterItems", 1)
                    .put("beforeEpisodes", 12).put("afterEpisodes", 1).put("title", "PRIVATE");
            content.putArray("regressions").add("CATEGORY_ITEMS_COLLAPSED:" + "d".repeat(64)).add("PRIVATE");
            result.putArray("observedRegressions").add("CATEGORY_ITEMS_COLLAPSED:" + "d".repeat(64));
            var request = mapper.readTree(client.request(repair, source, List.of(candidate)));
            var data = mapper.readTree(request.path("messages").get(1).path("content").asText());
            var feedback = data.path("previousCandidates").get(0).path("contentAcceptance");
            assertEquals(1, feedback.path("regressions").size());
            assertEquals(12, feedback.path("comparison").get(0).path("beforeEpisodes").asInt());
            assertFalse(feedback.toString().contains("PRIVATE"));
            var findings = data.path("repairDiagnostics").path("findings");
            assertTrue(findings.valueStream().anyMatch(row -> row.path("reason").asText().equals("CATEGORY_ITEMS_COLLAPSED")
                    && row.path("contentRegression").path("before").asInt() == 20
                    && !row.path("matchedResourceEvidenceAvailable").asBoolean(true)));
            assertTrue(request.path("messages").get(0).path("content").asText()
                    .contains("neither restore recommendation/footer links"));
            ((ObjectNode) result.path("runtimeEvidence")).put("inputSourceSha256", "f".repeat(64));
            request = mapper.readTree(client.request(repair, source, List.of(candidate)));
            data = mapper.readTree(request.path("messages").get(1).path("content").asText());
            assertFalse(data.path("previousCandidates").get(0).has("contentAcceptance"));
        } finally { client.close(); }
    }

    @Test
    void candidateDetailResourcesReachModelWithoutPlaybackResourcesAndRejectChangedIdentity() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            String source = "value = 1\n";
            var repair = coverRepair(source);
            var candidate = coverCandidate(repair, source);
            var runtime = (ObjectNode) candidate.result().path("runtimeEvidence");
            runtime.remove("resourceEvidence");
            var resources = ManagedResourceEvidenceTest.fixture(mapper, candidate.sha256())
                    .put("targetNode", "C10").putNull("image");
            runtime.set("contentResourceEvidence", resources);
            var data = mapper.readTree(mapper.readTree(client.request(repair, source, List.of(candidate)))
                    .path("messages").get(1).path("content").asText());
            assertEquals(resources, data.path("previousCandidates").get(0).path("contentResourceEvidence"));
            assertFalse(data.path("previousCandidates").get(0).has("resourceEvidence"));
            assertFalse(data.toString().contains("PRIVATE"));
            resources.put("artifactSha256", "f".repeat(64));
            data = mapper.readTree(mapper.readTree(client.request(repair, source, List.of(candidate)))
                    .path("messages").get(1).path("content").asText());
            assertFalse(data.path("previousCandidates").get(0).has("contentResourceEvidence"));
        } finally { client.close(); }
    }

    private RepairCandidateSummary coverCandidate(RepairSummary repair, String source) {
        var proposal = validProposal().put("expectedBaselineSha256", Hashes.sha256(source));
        proposal.putArray("validationTargets").add("C09");
        proposal.putArray("edits").addObject().put("oldText", "1").put("newText", "2");
        String sha = Hashes.sha256(source.replace("1", "2"));
        var result = mapper.createObjectNode().put("artifactSha256", sha)
                .put("runtimeRequested", true).put("staticValidationPassed", true);
        var runtime = result.putObject("runtimeEvidence").put("inputSourceSha256", sha)
                .put("bundleSha256", "e".repeat(64)).put("rawBody", "PRIVATE");
        var loader = runtime.putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION)
                .put("inputSourceSha256", sha).put("inputProfileSha256", "c".repeat(64));
        loader.putArray("nodes").addObject().put("id", "C09").put("status", "fail")
                .put("reason", "COVER_BYTES_INVALID");
        loader.putArray("calls").addObject().put("method", "detail").put("ok", true);
        runtime.set("resourceEvidence", ManagedResourceEvidenceTest.fixture(mapper, sha));
        runtime.set("httpObservations", ManagedResourceEvidenceTest.events(mapper));
        return new RepairCandidateSummary("candidate", repair.id(), 1, sha, Hashes.sha256(proposal.toString()),
                "REJECTED", null, Instant.now(), Instant.now(), proposal, result, null, null, null);
    }

    @Test
    void ordinaryRepairReceivesBoundDiagnosticDetailsAndCandidateRetestFeedback() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            String source = "def cover():\n    return 1\n";
            var repair = coverRepair(source);
            var frozen = (ObjectNode) repair.frozen();
            frozen.withObject("policy").putArray("targetNodes").add("C07");
            frozen.withObject("/baseline/profile").putArray("requiredNodes").add("S06").add("C07");
            var baselineEvidence = frozen.withObject("/baseline/result/managedSourceEvidence");
            baselineEvidence.remove("resourceEvidence");
            baselineEvidence.putObject("untrustedRuntimeSummary").put("homeItems", 20);
            var node = (ObjectNode) baselineEvidence.path("loaderValidation").path("nodes").get(0);
            node.put("id", "C07").put("reason", "DETAIL_IDENTITY_MISMATCH");
            node.putObject("identityEvidence").put("exactMatch", false).put("coverMatch", true)
                    .put("selectedIdSha256", "PRIVATE");
            var candidate = coverCandidate(repair, source);
            var result = (ObjectNode) candidate.result();
            var runtime = result.withObject("runtimeEvidence");
            runtime.remove("resourceEvidence");
            runtime.putObject("untrustedRuntimeSummary").put("homeItems", 0);
            result.putArray("observedRegressions").add("homeItems").add("PRIVATE");
            var after = (ObjectNode) runtime.path("loaderValidation").path("nodes").get(0);
            after.put("id", "C07").put("reason", "LOADER_METHOD_FAILED").put("failureType", "KeyError")
                    .put("error", "PRIVATE");
            after.putObject("failureContext").put("sourceLine", 27).put("message", "PRIVATE");
            after.putObject("identityEvidence").put("exactMatch", true).put("coverMatch", true);
            result.putObject("runtimeAcceptance").putArray("nodes").addObject()
                    .put("id", "C07").put("status", "fail").put("reason", "LOADER_METHOD_FAILED");
            var request = client.request(repair, source, List.of(candidate));
            var data = mapper.readTree(mapper.readTree(request).path("messages").get(1).path("content").asText());
            assertFalse(ModelClient.enhancedRequest(mapper, request));
            assertFalse(data.has("enhancedDiagnostics"));
            assertTrue(data.path("repairDiagnostics").path("loaderReceiptMatched").asBoolean());
            assertEquals(candidate.id(), data.path("repairDiagnostics").path("candidateId").asText());
            assertEquals("KeyError", data.path("repairDiagnostics").path("findings").get(0).path("failureType").asText());
            assertFalse(data.path("loaderFailures").get(0).path("contractObservations")
                    .path("identityEvidence").path("exactMatch").asBoolean(true));
            var unresolved = data.path("previousCandidates").get(0).path("unresolvedNodes").get(0);
            assertEquals("KeyError", unresolved.path("failureType").asText());
            assertEquals(27, unresolved.at("/contractObservations/failureContext/sourceLine").asInt());
            var regression = data.path("previousCandidates").get(0).path("regressedObservations").get(0);
            assertEquals("homeItems", regression.path("field").asText());
            assertEquals(20, regression.path("before").asInt());
            assertEquals(0, regression.path("after").asInt());
            assertEquals("untrusted_source_output_count", regression.path("basis").asText());
            assertTrue(unresolved.path("contractObservations").path("identityEvidence").path("exactMatch").asBoolean());
            assertFalse(request.contains("PRIVATE"));
            for (String field : List.of("projection", "inputSourceSha256", "inputProfileSha256")) {
                var loader = runtime.withObject("loaderValidation");
                String original = loader.path(field).asText();
                loader.put(field, "mismatch");
                request = client.request(repair, source, List.of(candidate));
                data = mapper.readTree(mapper.readTree(request).path("messages").get(1).path("content").asText());
                assertFalse(data.path("previousCandidates").get(0).path("runtimeEvidenceMatched").asBoolean(), field);
                assertTrue(data.path("previousCandidates").get(0).path("unresolvedNodes").isEmpty(), field);
                assertTrue(data.path("repairDiagnostics").path("latestCandidateEvidenceUnavailable").asBoolean(), field);
                loader.put(field, original);
            }
        } finally { client.close(); }
    }

    @Test
    void playerOutputShapesReachOrdinaryAndEnhancedRequestsWithoutRawValues() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            String source = "def player():\n    return 1\n";
            var repair = coverRepair(source);
            var frozen = (ObjectNode) repair.frozen();
            frozen.withObject("policy").putArray("targetNodes").add("R01");
            frozen.withObject("/baseline/profile").putArray("requiredNodes").add("S06").add("R01");
            var baseline = frozen.withObject("/baseline/result/managedSourceEvidence");
            baseline.remove("resourceEvidence");
            var node = (ObjectNode) baseline.path("loaderValidation").path("nodes").get(0);
            node.put("id", "R01").put("reason", "PLAYER_URL_INVALID");
            node.putObject("playerContract").put("detailCallId", 5).put("playCallId", 7)
                    .put("returnedUrlShape", "opaque").put("returnedUrlMatchesIdentifier", true).put("rawUrl", "PRIVATE");
            var candidate = coverCandidate(repair, source);
            var result = (ObjectNode) candidate.result();
            var runtime = result.withObject("runtimeEvidence");
            runtime.remove("resourceEvidence");
            var after = (ObjectNode) runtime.path("loaderValidation").path("nodes").get(0);
            after.put("id", "R01").put("status", "blocked").put("reason", "PLAYER_URL_INVALID");
            after.putObject("playerContract").put("detailCallId", 5).put("playCallId", 7)
                    .put("returnedUrlShape", "base64_encoded_url").put("returnedUrlMatchesIdentifier", true)
                    .put("rawUrl", "PRIVATE");
            runtime.set("resourceEvidence", ManagedResourceEvidenceTest.playerFixture(mapper, candidate.sha256()));
            runtime.set("httpObservations", ManagedResourceEvidenceTest.jsonEvents(mapper));
            runtime.withObject("loaderValidation").set("calls", ManagedResourceEvidenceTest.playerCalls(mapper));
            result.putObject("runtimeAcceptance").putArray("nodes").addObject()
                    .put("id", "R01").put("status", "blocked").put("reason", "PLAYER_URL_INVALID");
            for (boolean enhanced : List.of(false, true)) {
                var request = client.request(repair, source, List.of(candidate), enhanced);
                var data = mapper.readTree(mapper.readTree(request).path("messages").get(1).path("content").asText());
                assertEquals("opaque", data.path("loaderFailures").get(0).path("contractObservations")
                        .path("playerContract").path("returnedUrlShape").asText());
                var observed = data.path("previousCandidates").get(0).path("unresolvedNodes").get(0)
                        .path("contractObservations").path("playerContract");
                assertEquals("base64_encoded_url", observed.path("returnedUrlShape").asText());
                assertTrue(observed.path("returnedUrlMatchesIdentifier").asBoolean());
                var diagnostic = data.path(enhanced ? "enhancedDiagnostics" : "repairDiagnostics");
                assertEquals("percent_decode", diagnostic.at(
                        "/findings/0/playerValueEvidence/matches/0/playerValueMatches/returnedUrl").asText());
                assertFalse(request.contains("PRIVATE"));
            }
        } finally { client.close(); }
    }

    @Test
    void ordinaryAndEnhancedRequestsKeepActionHttpEvidenceWithoutChangingTargets() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            String source = "def detail():\n    return 1\n";
            var repair = coverRepair(source);
            var frozen = (ObjectNode) repair.frozen();
            frozen.withObject("policy").putArray("targetNodes").add("C07");
            frozen.withObject("/baseline/profile").putArray("requiredNodes").add("S06").add("C07");
            var evidence = frozen.withObject("/baseline/result/managedSourceEvidence");
            evidence.remove("resourceEvidence");
            evidence.putArray("httpObservations");
            ((ObjectNode) evidence.path("loaderValidation").path("nodes").get(0))
                    .put("id", "C07").put("reason", "DETAIL_OUTPUT_INVALID");
            RepairDiagnosticsTest.actionResponse(mapper, evidence, repair.baselineArtifactSha256(), "home", 2, 200);
            RepairDiagnosticsTest.actionResponse(mapper, evidence, repair.baselineArtifactSha256(), "detail", 3, 503);
            RepairDiagnosticsTest.transportObservation(evidence);
            evidence.putArray("rateLimitObservations").addObject().put("responseStatus", 429).put("automaticRetry", false)
                    .put("rawHeader", "PRIVATE").putObject("retryAfter").put("kind", "delay")
                    .put("seconds", new java.math.BigInteger("9".repeat(128)));
            var association = mapper.createObjectNode().put("id", 3).put("method", "detail");
            association.set("inputSha256", evidence.path("loaderValidation").path("calls").get(1).path("inputSha256"));
            ((ObjectNode) evidence.path("httpObservations").get(1)).put("responseBytes", 0)
                    .put("responseContentType", "").set("observedDuringCall", association);
            evidence.remove("resourceEvidence");
            ((ObjectNode) evidence.path("networkObservations").get(0)).set("observedDuringCall", association);
            var dns = evidence.putArray("dnsObservations").addObject().put("dnsStatus", 0).put("answerCount", 1)
                    .put("publicIpv4Count", 1).put("kind", "success").put("resolver", "alidns")
                    .put("recordType", "A").put("elapsedMilliseconds", 25).put("cacheHit", false);
            dns.set("observedDuringCall", association);
            var rate = (ObjectNode) evidence.path("rateLimitObservations").get(0);
            rate.set("observedDuringCall", association);
            rate.put("observedAtEpochMilliseconds", 1795000000000L).putObject("cooldownContext")
                    .put("phase", "receipt").put("recordedAtEpochMilliseconds", 1795000002000L)
                    .put("remainingWaitMilliseconds", 118000).put("taskBudgetRemainingMilliseconds", 5000)
                    .put("fitsTaskBudget", false);
            String before = frozen.toString();
            for (boolean enhanced : List.of(false, true)) {
                var request = mapper.readTree(client.request(repair, source, List.of(), enhanced));
                var data = mapper.readTree(request.path("messages").get(1).path("content").asText());
                var diagnostic = data.path(enhanced ? "enhancedDiagnostics" : "repairDiagnostics");
                assertEquals("network", diagnostic.path("findings").get(0).path("route").asText());
                assertEquals(3, diagnostic.path("findings").get(0).path("httpEvidence").path("calls").get(0).path("id").asInt());
                assertEquals("matched_http_call_interval", diagnostic.at("/findings/0/httpEvidence/basis").asText());
                assertEquals("tls_handshake", diagnostic.path("transportEvidence").path("observations").get(0).path("stage").asText());
                assertEquals(3, diagnostic.path("transportEvidence").path("observationsDropped").asInt());
                assertEquals("9".repeat(128), diagnostic.path("cooldownEvidence").path("observations").get(0)
                        .path("retryAfter").path("seconds").textValue());
                assertFalse(diagnostic.path("cooldownEvidence").path("currentRemainingWaitKnown").asBoolean(true));
                for (String field : List.of("transportEvidence", "dnsEvidence", "cooldownEvidence"))
                    assertEquals(association, diagnostic.path(field).path("observations").get(0).path("observedDuringCall"));
                assertEquals(25, diagnostic.at("/dnsEvidence/observations/0/elapsedMilliseconds").asInt());
                assertEquals("118000", diagnostic.at("/cooldownEvidence/observations/0/cooldownContext/remainingWaitMilliseconds").asText());
                assertFalse(request.toString().contains("PRIVATE"));
                assertEquals(mapper.createArrayNode().add("C07"), data.path("validationTargets"));
                assertTrue(request.path("messages").get(0).path("content").asText().contains("completeCallCoverage=false"));
                assertTrue(request.path("messages").get(0).path("content").asText().contains("NOT the current remaining wait"));
            }
            assertEquals(before, frozen.toString());
        } finally { client.close(); }
    }

    @Test
    void ordinaryAndEnhancedModelsReceiveInterruptedExecutionDetailsAsHistoricalObservations() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            String source = "def cover():\n    return 1\n";
            var repair = coverRepair(source);
            ((ObjectNode) repair.frozen()).withObject("baseline").put("executionPolicySha256", "f".repeat(64));
            var candidate = coverCandidate(repair, source);
            var result = (ObjectNode) candidate.result();
            result.remove("runtimeEvidence");
            var baseline = repair.frozen().path("baseline");
            var failure = result.putObject("managedExecutionFailure").put("candidateId", candidate.id())
                    .put("jobId", candidate.id()).put("inputSourceSha256", candidate.sha256())
                    .put("inputProfileSha256", baseline.path("profileSha256").asText())
                    .put("bundleSha256", baseline.path("runtimeSha256").asText())
                    .put("executionPolicySha256", baseline.path("executionPolicySha256").asText())
                    .put("code", "EGRESS_URL_DENIED");
            failure.putObject("diagnostic").put("authority", "managed_source_broker_observed")
                    .set("exchangeContext", RepairDiagnosticsTest.interruptedContext(mapper));
            for (boolean enhanced : List.of(false, true)) {
                var request = mapper.readTree(client.request(repair, source, List.of(candidate), enhanced));
                var data = mapper.readTree(request.path("messages").get(1).path("content").asText());
                var diagnostic = data.path(enhanced ? "enhancedDiagnostics" : "repairDiagnostics");
                assertTrue(diagnostic.path("hostFailureDiagnosticMatched").asBoolean());
                assertEquals("no_address", diagnostic.path("dnsEvidence").path("observations").get(0).path("kind").asText());
                assertEquals("last_recorded_observation", diagnostic.path("transportEvidence").path("scope").asText());
                assertEquals("12", diagnostic.path("cooldownEvidence").path("observations").get(0).path("retryAfter").path("seconds").textValue());
                assertFalse(diagnostic.path("dnsEvidence").path("failureCauseEstablished").asBoolean(true));
                assertFalse(data.toString().contains("PRIVATE"));
                assertTrue(request.path("messages").get(0).path("content").asText().contains("not necessarily the request that caused termination"));
            }
        } finally { client.close(); }
    }

    @Test
    void loaderRequestPinsFailedNodesKeepsCompleteRequirementsAndExcludesRawEvidence() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            var frozen = mapper.createObjectNode();
            frozen.putObject("policy").put("scope", "managed_loader_candidate")
                    .putArray("targetNodes").add("C03").add("C07");
            var baseline = frozen.putObject("baseline").put("profileSha256", "c".repeat(64));
            baseline.putObject("profile").putArray("requiredNodes").add("S06").add("C03").add("C07").add("M05");
            var receipt = baseline.putObject("result").putObject("managedSourceEvidence")
                    .putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION)
                    .put("inputSourceSha256", "a".repeat(64)).put("inputProfileSha256", "c".repeat(64))
                    .put("rawBody", "PRIVATE");
            var nodes = receipt.putArray("nodes");
            nodes.addObject().put("id", "C07").put("status", "fail").put("reason", "PRIVATE")
                    .put("url", "https://PRIVATE/?token=PRIVATE");
            nodes.addObject().put("id", "C03").put("status", "fail").put("reason", "PAGINATION_METADATA_INVALID");
            nodes.addObject().put("id", "M05").put("status", "blocked").put("reason", "EGRESS_URL_DENIED");
            nodes.addObject().put("id", "C05").put("status", "fail").put("reason", "OPTIONAL_NODE");
            var repair = new RepairSummary("id", "source", "run", "a".repeat(64), "b".repeat(64),
                    "AWAITING_PROPOSAL", null, 0, 3, null, Instant.now(), frozen, "current", List.of());
            var request = mapper.readTree(client.request(repair, "# source"));
            var data = mapper.readTree(request.path("messages").get(1).path("content").asText());
            String instruction = request.path("messages").get(0).path("content").asText();
            assertTrue(instruction.contains("managed_loader_candidate"));
            assertTrue(instruction.contains("observed remaining"));
            assertTrue(instruction.contains("changing\nparse/jx flags alone does not resolve"));
            assertFalse(instruction.contains("Only remove duplicate"));
            assertEquals(List.of("C03", "C07"), mapper.convertValue(data.path("validationTargets"), List.class));
            assertEquals(baseline.path("profile").path("requiredNodes"), data.path("requiredNodes"));
            assertEquals(2, data.path("loaderFailures").size());
            assertEquals("LOADER_NODE_UNRESOLVED", data.path("loaderFailures").get(0).path("reason").asText());
            assertFalse(request.toString().contains("PRIVATE"));
            baseline.put("runtimeSha256", "e".repeat(64));
            var result = mapper.createObjectNode().put("artifactSha256", "d".repeat(64))
                    .put("runtimeProgressObserved", false);
            result.putObject("runtimeEvidence").put("inputSourceSha256", "d".repeat(64))
                    .put("bundleSha256", "e".repeat(64))
                    .putObject("loaderValidation").put("projection", ManagedLoaderReceipt.PROJECTION)
                    .put("inputSourceSha256", "d".repeat(64)).put("inputProfileSha256", "c".repeat(64));
            var progress = result.putObject("runtimeNodeProgress").put("policy", "frozen_required_nodes_v1")
                    .put("baselineVerified", true).put("candidateVerified", true).put("rawBody", "PRIVATE");
            progress.putArray("recovered").add("C03").add("PRIVATE");
            progress.putArray("regressed").add("M05").add("PRIVATE");
            var comparison = progress.putArray("comparison");
            comparison.addObject().put("id", "C03").put("before", "fail").put("after", "pass")
                    .put("beforeReason", "PAGINATION_METADATA_INVALID").put("afterReason", "PRIVATE");
            comparison.addObject().put("id", "M05").put("before", "pass").put("after", "blocked")
                    .put("beforeReason", "PRIVATE").put("afterReason", "EGRESS_URL_DENIED");
            comparison.addObject().put("id", "C07").put("before", "PRIVATE").put("after", "pass");
            comparison.addObject().put("id", "PRIVATE").put("before", "fail").put("after", "pass");
            var candidate = new RepairCandidateSummary("candidate", repair.id(), 1, "d".repeat(64), "f".repeat(64),
                    "REJECTED", null, Instant.now(), Instant.now(), mapper.createObjectNode(), result, null, null, null);
            request = mapper.readTree(client.request(repair, "# source", List.of(candidate)));
            data = mapper.readTree(request.path("messages").get(1).path("content").asText());
            var feedback = data.path("previousCandidates").get(0);
            assertEquals(mapper.readTree("[\"C03\"]"), feedback.path("recoveredNodes"));
            assertEquals(mapper.readTree("[\"M05\"]"), feedback.path("regressedNodes"));
            assertEquals(2, feedback.path("nodeComparison").size());
            assertEquals("pass", feedback.path("nodeComparison").get(0).path("after").asText());
            assertEquals("LOADER_NODE_UNRESOLVED", feedback.path("nodeComparison").get(0).path("afterReason").asText());
            assertFalse(feedback.path("runtimeProgressObserved").asBoolean(true));
            assertTrue(request.path("messages").get(0).path("content").asText().contains("retain the useful prior fixes"));
            assertFalse(request.toString().contains("PRIVATE"));
            progress.put("candidateVerified", false);
            request = mapper.readTree(client.request(repair, "# source", List.of(candidate)));
            data = mapper.readTree(request.path("messages").get(1).path("content").asText());
            assertFalse(data.path("previousCandidates").get(0).has("nodeComparison"));
            progress.put("candidateVerified", true);
            result.withObject("runtimeEvidence").put("inputSourceSha256", "f".repeat(64));
            request = mapper.readTree(client.request(repair, "# source", List.of(candidate)));
            data = mapper.readTree(request.path("messages").get(1).path("content").asText());
            assertFalse(data.path("previousCandidates").get(0).has("nodeComparison"));
            for (String field : List.of("projection", "inputSourceSha256", "inputProfileSha256")) {
                String original = receipt.path(field).asText();
                receipt.put(field, "changed");
                assertEquals("MODEL_REPAIR_SCOPE_UNSUPPORTED", assertThrows(ResponseStatusException.class,
                        () -> client.request(repair, "# source")).getReason());
                receipt.put(field, original);
            }
            frozen.withObject("/policy").withArray("targetNodes").remove(1);
            assertEquals("MODEL_REPAIR_SCOPE_UNSUPPORTED", assertThrows(ResponseStatusException.class,
                    () -> client.request(repair, "# source")).getReason());
        } finally { client.close(); }
    }

    @Test
    void priorCandidateFeedbackIsBoundedShaBoundAndContainsNoRawOutputOrProposal() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            var frozen = mapper.createObjectNode();
            frozen.putObject("policy").put("scope", "static_candidate_validator").putArray("targetNodes").add("S03");
            frozen.putObject("baseline").put("runtimeSha256", "c".repeat(64));
            var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                    "AWAITING_PROPOSAL", null, 1, 3, null, Instant.now(), frozen, "current", List.of());
            var result = mapper.createObjectNode().put("artifactSha256", "d".repeat(64))
                    .put("runtimeRequested", true).put("staticValidationPassed", false).put("rawBody", "PRIVATE");
            result.putObject("inspection").putArray("issues").addObject()
                    .put("code", "invalid_syntax").put("message", "PRIVATE");
            result.withObject("inspection").withArray("issues").addObject().put("code", "PRIVATE");
            result.putArray("contractViolations").add("METHOD_CONTRACT_CHANGED:PRIVATE").add("PRIVATE");
            result.putObject("runtimeEvidence").put("inputSourceSha256", "d".repeat(64))
                    .put("bundleSha256", "c".repeat(64)).put("cookie", "PRIVATE");
            var nodes = result.putObject("runtimeAcceptance").putArray("nodes");
            nodes.addObject().put("id", "C03").put("status", "fail").put("reason", "PAGINATION_METADATA_INVALID");
            nodes.addObject().put("id", "M01").put("status", "blocked").put("reason", "PRIVATE");
            nodes.addObject().put("id", "C01").put("status", "pass").put("reason", "PRIVATE");
            nodes.addObject().put("id", "PRIVATE").put("status", "fail");
            var candidate = new RepairCandidateSummary("candidate", repair.id(), 1, "d".repeat(64), "e".repeat(64),
                    "REJECTED", "PRIVATE", Instant.now(), Instant.now(),
                    mapper.createObjectNode().put("newText", "PRIVATE"), result, null, null, null);
            String request = client.request(repair, "# approved baseline", List.of(candidate));
            var data = mapper.readTree(mapper.readTree(request).path("messages").get(1).path("content").asText());
            var feedback = data.path("previousCandidates").get(0);
            assertEquals("d".repeat(64), feedback.path("candidateSha256").asText());
            assertEquals(mapper.readTree("[\"invalid_syntax\"]"), feedback.path("staticIssues"));
            assertEquals(mapper.readTree("[\"METHOD_CONTRACT_CHANGED\"]"), feedback.path("contractViolations"));
            assertTrue(feedback.path("runtimeEvidenceMatched").asBoolean());
            assertEquals(2, feedback.path("unresolvedNodes").size());
            assertEquals("LOADER_NODE_UNRESOLVED", feedback.path("unresolvedNodes").get(1).path("reason").asText());
            assertFalse(request.contains("PRIVATE"));
            result.withObject("runtimeEvidence").put("inputSourceSha256", "f".repeat(64));
            request = client.request(repair, "# baseline", List.of(candidate));
            data = mapper.readTree(mapper.readTree(request).path("messages").get(1).path("content").asText());
            assertTrue(data.path("previousCandidates").get(0).path("unresolvedNodes").isEmpty());
            assertFalse(data.path("previousCandidates").get(0).path("runtimeEvidenceMatched").asBoolean());
            result.put("artifactSha256", "f".repeat(64));
            request = client.request(repair, "# baseline", List.of(candidate));
            data = mapper.readTree(mapper.readTree(request).path("messages").get(1).path("content").asText());
            assertTrue(data.path("previousCandidates").isEmpty());
        } finally { client.close(); }
    }

    @Test
    void pricingIsOptionalPairedBoundedDecimalAndCanonicalizedForConfigurationIdentity() {
        var defaults = new ModelClient(mapper, validator, new ModelSettings()).connection();
        assertNull(defaults.inputPriceUsdPerMillion());
        assertNull(defaults.outputPriceUsdPerMillion());
        for (String price : List.of("-0.01", "1000000.00000001", "0.000000001")) {
            var settings = new ModelSettings();
            settings.setInputPriceUsdPerMillion(new BigDecimal(price));
            settings.setOutputPriceUsdPerMillion(BigDecimal.ZERO);
            assertEquals("INVALID_MODEL_PRICING", assertThrows(IllegalArgumentException.class,
                    () -> new ModelClient(mapper, validator, settings)).getMessage());
        }
        var settings = new ModelSettings();
        settings.setInputPriceUsdPerMillion(BigDecimal.ZERO);
        assertEquals("INVALID_MODEL_PRICING", assertThrows(IllegalArgumentException.class,
                () -> new ModelClient(mapper, validator, settings)).getMessage());
        settings.setOutputPriceUsdPerMillion(new BigDecimal("1.25000000"));
        var first = new ModelClient(mapper, validator, settings).connection();
        assertNotEquals(defaults.configurationSha256(), first.configurationSha256());
        settings.setOutputPriceUsdPerMillion(new BigDecimal("1.25"));
        assertEquals(first.configurationSha256(),
                new ModelClient(mapper, validator, settings).connection().configurationSha256());
        settings.setOutputPriceUsdPerMillion(BigDecimal.ZERO);
        assertNotEquals(first.configurationSha256(),
                new ModelClient(mapper, validator, settings).connection().configurationSha256());
    }

    @Test
    void hardCostBudgetsRequirePairedPricingAndAreBoundedAndPartOfConfigurationIdentity() {
        var defaults = new ModelClient(mapper, validator, new ModelSettings()).connection();
        assertNull(defaults.dailyCostUsd());
        assertNull(defaults.sourceDailyCostUsd());
        assertNull(defaults.modelDailyCostUsd());
        for (String value : List.of("-0.01", "1000000000000.000000001", "0.0000000000000000001")) {
            var settings = enabled();
            settings.setDailyCostUsd(new BigDecimal(value));
            settings.setInputPriceUsdPerMillion(BigDecimal.ONE);
            settings.setOutputPriceUsdPerMillion(BigDecimal.ONE);
            assertEquals("INVALID_MODEL_COST_BUDGET", assertThrows(IllegalArgumentException.class,
                    () -> new ModelClient(mapper, validator, settings)).getMessage());
        }
        var missingPrice = enabled();
        missingPrice.setDailyCostUsd(BigDecimal.ONE);
        assertEquals("INVALID_MODEL_COST_BUDGET_PRICING", assertThrows(IllegalArgumentException.class,
                () -> new ModelClient(mapper, validator, missingPrice)).getMessage());
        var first = enabled();
        first.setInputPriceUsdPerMillion(BigDecimal.ZERO);
        first.setOutputPriceUsdPerMillion(BigDecimal.ONE);
        first.setDailyCostUsd(new BigDecimal("1.00000000"));
        first.setSourceDailyCostUsd(new BigDecimal("2.00000000"));
        first.setModelDailyCostUsd(new BigDecimal("3.00000000"));
        assertEquals("INVALID_MODEL_INPUT_TOKEN_BOUND", assertThrows(IllegalArgumentException.class,
                () -> new ModelClient(mapper, validator, first)).getMessage());
        first.setInputTokenBound("request_utf8_bytes_v1");
        var updated = new ModelClient(mapper, validator, first).connection();
        assertEquals(new BigDecimal("1"), updated.dailyCostUsd());
        assertEquals(new BigDecimal("2"), updated.sourceDailyCostUsd());
        assertEquals(new BigDecimal("3"), updated.modelDailyCostUsd());
        assertNotEquals(defaults.configurationSha256(), updated.configurationSha256());
        for (String contract : Arrays.asList(null, "automatic_tokenizer", "REQUEST_UTF8_BYTES_V1")) {
            first.setInputTokenBound(contract);
            assertEquals("INVALID_MODEL_INPUT_TOKEN_BOUND", assertThrows(IllegalArgumentException.class,
                    () -> new ModelClient(mapper, validator, first)).getMessage());
        }
        var optional = new ModelSettings();
        optional.setInputTokenBound("request_utf8_bytes_v1");
        assertNotEquals(defaults.configurationSha256(),
                new ModelClient(mapper, validator, optional).connection().configurationSha256());
    }

    @Test
    void invalidProposalRetainsOnlyValidatedUsageAndMissingOrInvalidUsageStaysUnknown() throws Exception {
        var client = new ModelClient(mapper, validator, new ModelSettings());
        var body = (ObjectNode) mapper.readTree(response("not-json"));
        ((ObjectNode) body.get("usage")).put("providerSecret", "PRIVATE");
        var rejected = client.parse(mapper.writeValueAsBytes(body));
        assertEquals("MODEL_RESPONSE_PROPOSAL_JSON_INVALID", rejected.errorCode());
        assertNull(rejected.proposal());
        assertEquals(3, rejected.usage().size());
        assertFalse(rejected.usage().toString().contains("PRIVATE"));
        body.remove("usage");
        assertNull(client.parse(mapper.writeValueAsBytes(body)).usage());
        body.set("usage", mapper.createObjectNode().put("prompt_tokens", 1)
                .put("completion_tokens", 2).put("total_tokens", 999));
        assertNull(client.parse(mapper.writeValueAsBytes(body)).usage());
    }

    @Test
    void responseDiagnosticsIdentifyStagesWithoutSavingProviderContent() throws Exception {
        var client = new ModelClient(mapper, validator, new ModelSettings());
        String proposal = validProposal().toString();
        var examples = new LinkedHashMap<String, String>();
        examples.put("MODEL_RESPONSE_JSON_INVALID", "{PRIVATE");
        examples.put("MODEL_RESPONSE_ENVELOPE_INVALID", "[]");
        examples.put("MODEL_RESPONSE_USAGE_INVALID", response(proposal).replace("\"total_tokens\":3", "\"total_tokens\":4"));
        examples.put("MODEL_RESPONSE_OUTPUT_LIMIT_EXCEEDED", response(proposal)
                .replace("\"completion_tokens\":2", "\"completion_tokens\":2049")
                .replace("\"total_tokens\":3", "\"total_tokens\":2050"));
        examples.put("MODEL_RESPONSE_CHOICES_INVALID", "{\"choices\":[]}");
        examples.put("MODEL_RESPONSE_OUTPUT_TRUNCATED", response(proposal).replace("\"stop\"", "\"length\""));
        examples.put("MODEL_RESPONSE_OUTPUT_FILTERED", response(proposal).replace("\"stop\"", "\"content_filter\""));
        examples.put("MODEL_RESPONSE_REFUSED", response(proposal).replace("\"content\":", "\"refusal\":\"PRIVATE\",\"content\":"));
        examples.put("MODEL_RESPONSE_MESSAGE_INVALID", response(proposal).replace("\"assistant\"", "\"user\""));
        examples.put("MODEL_RESPONSE_PROPOSAL_JSON_INVALID", response("```json\nPRIVATE\n```"));
        examples.put("MODEL_RESPONSE_PROPOSAL_SCHEMA_INVALID", response(proposal.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"")));
        examples.put("MODEL_RESPONSE_PROPOSAL_CONSTRAINT_INVALID", response(proposal.replace("\"diagnosis\":\"reason\"", "\"diagnosis\":\"\"")));
        examples.put("MODEL_RESPONSE_PROPOSAL_TOO_LARGE", response("X".repeat(32769)));
        for (var example : examples.entrySet()) {
            var result = client.parse(example.getValue().getBytes(StandardCharsets.UTF_8));
            assertEquals(example.getKey(), result.errorCode());
            assertNull(result.proposal());
            assertFalse(result.toString().contains("PRIVATE"));
            assertEquals(example.getKey(), result.responseDiagnostics().path("stage").asText());
            assertEquals(Hashes.sha256(example.getValue()), result.responseDiagnostics().path("responseSha256").asText());
        }
        assertFalse(ModelClient.recoverableResponseFailure("MODEL_OUTCOME_UNKNOWN"));
        assertFalse(ModelClient.recoverableResponseFailure("MODEL_RESPONSE_REFUSED"));
        assertFalse(ModelClient.recoverableResponseFailure("MODEL_RESPONSE_OUTPUT_FILTERED"));
        assertTrue(ModelClient.recoverableResponseFailure("MODEL_RESPONSE_INVALID"));
    }

    @Test
    void constraintDiagnosticsContainFieldPathsAndCountsButNoRejectedValues() throws Exception {
        var client = new ModelClient(mapper, validator, new ModelSettings());
        try {
            var proposal = validProposal().put("targetFile", "../PRIVATE.txt").put("diagnosis", "");
            proposal.withArray("validationTargets").add("PRIVATE");
            ((ObjectNode) proposal.withArray("edits").get(0)).put("oldText", "").putNull("newText");
            String content = proposal.toString();
            var reply = client.parse(response(content).getBytes(StandardCharsets.UTF_8));
            assertEquals("MODEL_RESPONSE_PROPOSAL_CONSTRAINT_INVALID", reply.errorCode());
            assertNull(reply.proposal());
            var diagnostics = reply.responseDiagnostics();
            assertEquals(Hashes.sha256(content), diagnostics.path("proposalSha256").asText());
            assertEquals(1, diagnostics.path("editCount").asInt());
            assertEquals(2, diagnostics.path("validationTargetCount").asInt());
            var fields = new HashSet<String>();
            diagnostics.path("violations").forEach(value -> fields.add(value.path("field").asText()));
            assertTrue(fields.containsAll(Set.of("targetFile", "diagnosis", "validationTargets[1]",
                    "edits[0].oldText", "edits[0].newText")), fields.toString());
            assertFalse(reply.toString().contains("PRIVATE"));
            assertEquals(3, reply.usage().path("total_tokens").asInt());
            proposal = validProposal();
            proposal.withArray("edits").removeAll();
            reply = client.parse(response(proposal.toString()).getBytes(StandardCharsets.UTF_8));
            assertEquals(0, reply.responseDiagnostics().path("editCount").asInt());
            assertEquals(mapper.readTree("[{\"field\":\"edits\",\"constraint\":\"Size\"}]"),
                    reply.responseDiagnostics().path("violations"));
        } finally { client.close(); }
    }

    @Test
    void previousResponseFailuresSurvivePromptChangesButStayInTheSameRepair() throws Exception {
        var client = new ModelClient(mapper, validator, enabled());
        try {
            var frozen = mapper.createObjectNode();
            frozen.putObject("policy").put("scope", "static_candidate_validator").putArray("targetNodes").add("S03");
            var repair = new RepairSummary("repair", "source", "run", "a".repeat(64), "b".repeat(64),
                    "AWAITING_PROPOSAL", null, 0, 3, null, Instant.now(), frozen, "current", List.of());
            var past = mapper.createObjectNode().put("repairId", repair.id()).put("attemptNumber", 1)
                    .put("status", "FAILED").put("configurationSha256", "f".repeat(64))
                    .put("requestSha256", "c".repeat(64)).put("errorCode", "MODEL_RESPONSE_PROPOSAL_CONSTRAINT_INVALID");
            var calls = new ArrayList<ModelCallSummary>();
            calls.add(mapper.treeToValue(past, ModelCallSummary.class));
            past.put("attemptNumber", 2);
            past.putObject("responseDiagnostics").put("stage", "MODEL_RESPONSE_PROPOSAL_CONSTRAINT_INVALID")
                    .putArray("violations").addObject().put("field", "edits").put("constraint", "Size");
            calls.add(mapper.treeToValue(past, ModelCallSummary.class));
            past.put("repairId", "another-repair");
            calls.add(mapper.treeToValue(past, ModelCallSummary.class));
            past.put("repairId", repair.id()).put("errorCode", "MODEL_RESPONSE_REFUSED");
            calls.add(mapper.treeToValue(past, ModelCallSummary.class));
            var request = mapper.readTree(client.request(repair, "# baseline", List.of(), false, calls));
            var data = mapper.readTree(request.path("messages").get(1).path("content").asText());
            var feedback = data.path("previousModelFailures");
            assertEquals(2, feedback.size());
            assertFalse(feedback.get(0).path("diagnosticsAvailable").asBoolean());
            assertFalse(feedback.get(0).has("responseDiagnostics"));
            assertTrue(feedback.get(1).path("diagnosticsAvailable").asBoolean());
            assertEquals("edits", feedback.get(1).path("responseDiagnostics").path("violations").get(0).path("field").asText());
            assertTrue(request.path("messages").get(0).path("content").asText().contains("Include 1 to 20 real edits"));
        } finally { client.close(); }
    }

    private ModelSettings enabled() {
        var settings = new ModelSettings();
        settings.setEnabled(true);
        settings.setEndpoint("http://127.0.0.1:9/v1/chat/completions");
        settings.setModel("fixture-model");
        return settings;
    }
    private ObjectNode validProposal() {
        var proposal = mapper.createObjectNode().put("schemaVersion", 1)
                .put("expectedBaselineSha256", "a".repeat(64)).put("targetFile", "source.py")
                .put("diagnosis", "reason").put("expectedEffect", "static target");
        proposal.putArray("validationTargets").add("S03");
        proposal.putArray("edits").addObject().put("oldText", "unique").put("newText", "");
        return proposal;
    }
    private String response(String content) {
        var result = mapper.createObjectNode();
        var choice = result.putArray("choices").addObject().put("finish_reason", "stop");
        choice.putObject("message").put("role", "assistant").put("content", content);
        result.putObject("usage").put("prompt_tokens", 1).put("completion_tokens", 2).put("total_tokens", 3);
        return result.toString();
    }
    private void assertInvalid(ModelClient client, String response) {
        var rejected = client.parse(response.getBytes(StandardCharsets.UTF_8));
        assertTrue(rejected.errorCode().startsWith("MODEL_RESPONSE_"), rejected.errorCode());
        assertNull(rejected.proposal());
    }
}
