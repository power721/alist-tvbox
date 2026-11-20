package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Bounded receipt projection and transport failure tests; no source or Engine execution. */
class ManagedSourceRuntimeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FixtureRuntime fixture = new FixtureRuntime(mapper, "never-run");
    private final String job = "b19d47a2-47de-4783-b8a6-233c4abf271a";
    private final String instance = "d0f4909a-5709-4e57-9a54-4ded3c19424a";
    private final String image = "sha256:" + "b".repeat(64);
    private final String source = "# opaque, never executed\n";

    ManagedSourceRuntimeTest() throws IOException {}

    @Test
    void interruptedLoaderProgressProjectsOnlyBoundedCompletedCallsAndNeverAcceptance() throws Exception {
        var runtime = runtime(true, "/run/managed/executor.sock");
        var input = mapper.createObjectNode().put("schemaVersion", 1).put("fullAcceptance", true)
                .put("inputSourceSha256", "a".repeat(64)).put("profileSha256", "b".repeat(64))
                .put("secret", "PRIVATE");
        var calls = input.putArray("completedCalls");
        for (int id = 1; id <= 40; id++)
            calls.addObject().put("id", id).put("method", "category").put("ok", true)
                    .put("inputSha256", "c".repeat(64)).put("outputSha256", "d".repeat(64))
                    .put("body", "PRIVATE");
        var safe = runtime.projectLoaderProgress(input);
        assertNotNull(safe);
        assertFalse(safe.path("fullAcceptance").asBoolean());
        assertEquals(40, safe.path("completedCalls").size());
        assertFalse(safe.toString().contains("PRIVATE"));
        ((ObjectNode) calls.get(1)).put("id", 99);
        assertNull(runtime.projectLoaderProgress(input));
        ((ObjectNode) calls.get(1)).put("id", 2);
        input.putObject("contentSnapshot").put("schemaVersion", 1);
        assertNull(runtime.projectLoaderProgress(input));
    }

    @Test
    void disabledSnapshotsNeverConnectAndHideSocketPathAndInvalidConfiguration() throws Exception {
        var runtime = runtime(false, "/private/executor.sock");
        assertEquals("MANAGED_EXECUTION_DISABLED", runtime.blockedReason(profile()));
        assertFalse(runtime.snapshot().toString().contains("/private"));
        assertEquals(runtime.policySha256(), Hashes.sha256(runtime.snapshot().toString()));
        assertEquals(runtime.sha256(), Hashes.sha256(runtime.manifest().toString()));
        assertEquals("MANAGED_EXECUTION_DISABLED", assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256())).getMessage());
        assertEquals("MANAGED_LOCAL_SOCKET_REQUIRED", runtime(true, "tcp://127.0.0.1").configurationBlockedReason());
        assertEquals("MANAGED_LOCAL_SOCKET_REQUIRED", runtime(true, "/run/../private/executor.sock").configurationBlockedReason());
        assertEquals("MANAGED_EXECUTION_ERROR", runtime.errorCode("PRIVATE_RAW_ERROR"));
        assertEquals("MANAGED_EXECUTION_ERROR", runtime.errorCode(null));
        assertEquals("MANAGED_LOCAL_SOCKET_REQUIRED", runtime.errorCode("MANAGED_LOCAL_SOCKET_REQUIRED"));
        assertEquals("EGRESS_IMAGE_BUNDLE_MISMATCH", runtime.errorCode("EGRESS_IMAGE_BUNDLE_MISMATCH"));
    }

    @Test
    void sourceProfileRuntimeAndPolicyMismatchDoNotReachTransport() throws Exception {
        var runtime = spy(runtime(true, "/run/managed/executor.sock"));
        var wrong = profile();
        wrong.put("runtime", "fongmi");
        assertEquals("MANAGED_T4_PROFILE_REQUIRED", runtime.blockedReason(wrong));
        wrong.put("runtime", "t4").put("extFingerprint", "private-reference");
        assertEquals("MANAGED_EXT_NOT_AVAILABLE", runtime.blockedReason(wrong));
        assertEquals("MANAGED_INPUT_MISMATCH", assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), "0".repeat(64), runtime.policySha256())).getMessage());
        assertEquals("MANAGED_INPUT_MISMATCH", assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), "0".repeat(64))).getMessage());
        verify(runtime, never()).control(any());
    }

    @Test
    void onlyAllowlistedControlsAndUntrustedStatusAreProjected() throws Exception {
        var runtime = spy(runtime(true, "/run/managed/executor.sock"));
        var row = row(runtime);
        ((ObjectNode) row.path("result")).put("secret", "PRIVATE_RAW_ERROR");
        ((ObjectNode) row.path("result").path("untrustedRuntimeSummary")).put("token", "PRIVATE_TOKEN");
        doAnswer(call -> reply(runtime, call.getArgument(0), row)).when(runtime).control(any());
        var result = runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256());
        assertEquals("init_returned", result.path("untrustedRuntimeSummary").path("status").asText());
        assertFalse(result.path("businessChainVerified").booleanValue());
        assertFalse(result.path("sourceHealthEligible").booleanValue());
        assertFalse(result.path("publishEligible").booleanValue());
        assertFalse(result.toString().contains("PRIVATE"));
        assertTrue(result.path("cleanup").path("confirmed").booleanValue());
        verify(runtime, never()).cancelAcceptance(anyString(), any());
    }

    @Test
    void forgedIdentityWeakenedControlsAndSecretStatusesCannotBecomeEvidence() throws Exception {
        for (String defect : List.of("source", "runtime", "controls", "network", "boolean",
                "status", "policy", "cleanup", "bytes", "fraction")) {
            var runtime = spy(runtime(true, "/run/managed/executor.sock"));
            var row = row(runtime);
            ObjectNode result = (ObjectNode) row.path("result");
            ObjectNode ready = (ObjectNode) result.path("preSourceReady");
            ObjectNode controls = (ObjectNode) ready.path("controls");
            switch (defect) {
                case "source" -> result.put("inputSourceSha256", "0".repeat(64));
                case "runtime" -> ready.put("runtimeSha256", "0".repeat(64));
                case "controls" -> controls.put("uid", 0);
                case "network" -> controls.putArray("interfaces").add("lo").add("eth0");
                case "boolean" -> result.put("rawOutputStored", "false");
                case "status" -> ((ObjectNode) result.path("untrustedRuntimeSummary")).put("status", "PRIVATE");
                case "policy" -> ((ObjectNode) result.path("policy")).put("network", "bridge");
                case "cleanup" -> ((ObjectNode) result.path("cleanup")).put("confirmed", false);
                case "bytes" -> result.put("untrustedStderrBytes", 196609);
                case "fraction" -> controls.put("uid", 65532.5);
                default -> throw new AssertionError();
            }
            doAnswer(call -> reply(runtime, call.getArgument(0), row)).when(runtime).control(any());
            assertThrows(IOException.class, () -> runtime.inspect(job, source, profile(), runtime.sha256(),
                    runtime.policySha256()), defect);
        }
    }

    @Test
    void lostStartReplyIsNotRetriedAndCancellationBarrierIsRequired() throws Exception {
        var runtime = spy(runtime(true, "/run/managed/executor.sock"));
        AtomicInteger starts = new AtomicInteger(), cancels = new AtomicInteger();
        doAnswer(call -> {
            ObjectNode request = call.getArgument(0);
            if (request.path("operation").asText().equals("start")) {
                starts.incrementAndGet();
                throw new IOException("MANAGED_TRANSPORT_TIMEOUT");
            }
            if (request.path("operation").asText().equals("cancel_acceptance")) cancels.incrementAndGet();
            return reply(runtime, request, row(runtime));
        }).when(runtime).control(any());
        assertEquals("MANAGED_TRANSPORT_TIMEOUT", assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256())).getMessage());
        assertEquals(1, starts.get());
        assertEquals(1, cancels.get());
    }

    @Test
    void unknownCleanupOrChangedRunnerCannotBeReportedAsCancellationSuccess() throws Exception {
        var runtime = spy(runtime(true, "/run/managed/executor.sock"));
        doThrow(new IOException("MANAGED_TRANSPORT_TIMEOUT")).when(runtime).control(any());
        assertEquals("MANAGED_CLEANUP_UNCONFIRMED", assertThrows(IOException.class,
                () -> runtime.cleanup(job, runtime.snapshot())).getMessage());
        var changed = (ObjectNode) runtime.snapshot();
        changed.put("instanceId", job);
        assertEquals("MANAGED_CLEANUP_UNCONFIRMED", assertThrows(IOException.class,
                () -> runtime.cleanup(job, changed)).getMessage());
        assertEquals("MANAGED_CLEANUP_UNCONFIRMED", assertThrows(IOException.class,
                () -> runtime.cleanup(job, null)).getMessage());
    }

    @Test
    void acceptanceCancellationWaitsForChildrenEvenWhenParentAlreadyCompleted() throws Exception {
        var runtime = spy(runtime(true, "/run/managed/executor.sock"));
        AtomicInteger calls = new AtomicInteger();
        doAnswer(call -> {
            ObjectNode request = call.getArgument(0);
            assertEquals("cancel_acceptance", request.path("operation").asText());
            var answer = (ObjectNode) reply(runtime, request, row(runtime));
            boolean finished = calls.incrementAndGet() == 2;
            ((ObjectNode) answer.path("acceptanceCleanup")).put("childJobs", 1)
                    .put("pendingJobs", finished ? 0 : 1).put("confirmed", finished);
            assertEquals("COMPLETED", answer.path("status").asText());
            return answer;
        }).when(runtime).control(any());
        runtime.cancelAcceptance(job, runtime.snapshot());
        assertEquals(2, calls.get());
    }

    @Test
    void acceptanceCancellationRejectsMissingSummaryOrUnconfirmedRecovery() throws Exception {
        var runtime = spy(runtime(true, "/run/managed/executor.sock"));
        doReturn(row(runtime)).when(runtime).control(any());
        assertEquals("MANAGED_CLEANUP_UNCONFIRMED", assertThrows(IOException.class,
                () -> runtime.cancelAcceptance(job, runtime.snapshot())).getMessage());
        ObjectNode request = mapper.createObjectNode().put("operation", "cancel_acceptance");
        var answer = (ObjectNode) reply(runtime, request, row(runtime));
        ((ObjectNode) answer.path("acceptanceCleanup")).put("recoveryRequired", true);
        doReturn(answer).when(runtime).control(any());
        assertEquals("MANAGED_CLEANUP_UNCONFIRMED", assertThrows(IOException.class,
                () -> runtime.cancelAcceptance(job, runtime.snapshot())).getMessage());
    }

    @Test
    void interruptedInspectionCancelsAcceptanceWithoutRetryingStart() throws Exception {
        var runtime = spy(runtime(true, "/run/managed/executor.sock"));
        AtomicInteger starts = new AtomicInteger(), cancels = new AtomicInteger();
        doAnswer(call -> {
            ObjectNode request = call.getArgument(0);
            if (request.path("operation").asText().equals("start")) {
                starts.incrementAndGet();
                throw new InterruptedException();
            }
            if (request.path("operation").asText().equals("cancel_acceptance")) cancels.incrementAndGet();
            return reply(runtime, request, row(runtime));
        }).when(runtime).control(any());
        assertThrows(InterruptedException.class, () -> runtime.inspect(job, source, profile(),
                runtime.sha256(), runtime.policySha256()));
        assertEquals(1, starts.get());
        assertEquals(1, cancels.get());
    }

    @Test
    void earlierOriginRefusalsRetainBoundedIdentityWithoutReclassifyingTheLastFetch() throws Exception {
        var runtime = runtime(true, "/run/managed/executor.sock");
        var input = diagnostic();
        var context = input.putObject("exchangeContext").put("phase", "frame").put("lastHttpId", 3)
                .put("loaderCallCount", 1).put("peerExitCode", 1).put("policyDenied", true)
                .put("lastFetchDenied", false).put("lastDeniedCode", "EGRESS_HEADER_ORIGIN_DENIED");
        var denials = context.putArray("denialObservations");
        var row = denials.addObject().put("httpId", 1).put("code", "EGRESS_HEADER_ORIGIN_DENIED")
                .put("url", "PRIVATE");
        var request = row.putObject("requestDiagnostic").put("kind", "header")
                .put("reason", "origin_not_allowed").put("headerName", "referer")
                .put("requestUrlSha256", "a".repeat(64)).put("requestOriginSha256", "b".repeat(64))
                .put("headerOriginSha256", "c".repeat(64)).put("value", "PRIVATE");
        var safe = runtime.projectDiagnostic(input).path("exchangeContext");
        assertFalse(safe.path("lastFetchDenied").asBoolean());
        assertFalse(safe.has("requestDiagnostic"));
        var detail = safe.path("denialObservations").get(0).path("requestDiagnostic");
        assertEquals("referer", detail.path("headerName").asText());
        assertEquals("a".repeat(64), detail.path("requestUrlSha256").asText());
        assertEquals("b".repeat(64), detail.path("requestOriginSha256").asText());
        assertEquals("c".repeat(64), detail.path("headerOriginSha256").asText());
        assertFalse(safe.toString().contains("PRIVATE"));
        request.put("headerOriginSha256", "PRIVATE");
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").path("denialObservations")
                .get(0).path("requestDiagnostic").has("headerOriginSha256"));
        row.put("httpId", 4);
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").has("denialObservations"));
        row.put("httpId", 1).put("code", "PRIVATE");
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").has("denialObservations"));
        row.put("code", "EGRESS_HEADER_ORIGIN_DENIED");
        denials.add(row.deepCopy());
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").has("denialObservations"));
        denials.remove(1);
        for (int i = 0; i < 64; i++) denials.add(row.deepCopy());
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").has("denialObservations"));
    }

    @Test
    void tokenRefusalProjectsRequestIdentityWithoutAuthorizationOrRawUrl() throws Exception {
        var runtime = runtime(true, "/run/managed/executor.sock");
        var input = diagnostic();
        var context = input.putObject("exchangeContext").put("phase", "frame").put("lastHttpId", 2)
                .put("loaderCallCount", 1).put("peerExitCode", 1).put("policyDenied", true)
                .put("lastFetchDenied", false).put("lastDeniedCode", "EGRESS_SESSION_TOKEN_DENIED");
        var request = context.putArray("denialObservations").addObject().put("httpId", 1)
                .put("code", "EGRESS_SESSION_TOKEN_DENIED").putObject("requestDiagnostic")
                .put("kind", "header").put("reason", "session_token_not_observed")
                .put("headerName", "authorization").put("requestUrlSha256", "a".repeat(64))
                .put("requestOriginSha256", "b".repeat(64)).put("authorization", "PRIVATE")
                .put("url", "PRIVATE");
        var safe = runtime.projectDiagnostic(input).path("exchangeContext");
        var detail = safe.path("denialObservations").get(0).path("requestDiagnostic");
        assertEquals("session_token_not_observed", detail.path("reason").asText());
        assertEquals("authorization", detail.path("headerName").asText());
        assertEquals("a".repeat(64), detail.path("requestUrlSha256").asText());
        assertEquals("b".repeat(64), detail.path("requestOriginSha256").asText());
        assertFalse(safe.toString().contains("PRIVATE"));
        request.put("requestUrlSha256", "PRIVATE");
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").path("denialObservations")
                .get(0).path("requestDiagnostic").has("requestUrlSha256"));
    }

    @Test
    void interruptedGatewayEvidenceKeepsTransportAndCountsWithoutAcceptanceOrSecrets() throws Exception {
        var runtime = runtime(true, "/run/managed/executor.sock");
        var input = diagnostic();
        var context = input.putObject("exchangeContext").put("phase", "fetch").put("lastHttpId", 2)
                .put("loaderCallCount", 1).putNull("peerExitCode").put("policyDenied", false)
                .put("completedHttpCount", 1);
        var event = context.putArray("completedHttpObservations").addObject().put("method", "GET")
                .put("requestUrlSha256", "a".repeat(64)).put("responseStatus", 200).put("responseBytes", 45157)
                .put("responseSha256", "b".repeat(64)).put("responseComplete", true)
                .put("responseContentType", "text/html").put("transport", "external_evidence_gateway")
                .put("representation", "gateway_rendered_document").put("gatewaySessionReused", true)
                .put("cookiesExported", false).put("gatewayOutcome", "response_returned")
                .put("cookie", "PRIVATE");
        var gateway = context.putObject("runtimeGateway").put("adapter", "flaresolverr-rs-alt")
                .put("sessionCount", 1).put("requestCount", 2).put("responseCount", 1).put("sessionReuseCount", 1)
                .put("cookiesExported", false).put("cleanupConfirmed", true).put("session", "PRIVATE");
        var safe = runtime.projectDiagnostic(input).path("exchangeContext");
        assertEquals("external_evidence_gateway", safe.at("/completedHttpObservations/0/transport").asText());
        assertEquals("response_returned", safe.at("/completedHttpObservations/0/gatewayOutcome").asText());
        assertEquals(2, safe.path("runtimeGateway").path("requestCount").asInt());
        assertFalse(safe.path("runtimeGateway").path("fullAcceptance").asBoolean());
        assertFalse(safe.toString().contains("PRIVATE"));
        event.put("gatewayOutcome", "PRIVATE");
        gateway.put("requestCount", 65);
        safe = runtime.projectDiagnostic(input).path("exchangeContext");
        assertFalse(safe.has("runtimeGateway"));
        assertFalse(safe.at("/completedHttpObservations/0").has("gatewayOutcome"));
        assertFalse(safe.toString().contains("PRIVATE"));
    }

    @Test
    void headerFailureAndCompletedHttpAreProjectedWithoutHeaderValuesOrRawBodies() throws Exception {
        var runtime = runtime(true, "/run/managed/executor.sock");
        var input = diagnostic();
        var context = input.putObject("exchangeContext").put("phase", "frame").put("lastHttpId", 2)
                .put("loaderCallCount", 0).put("peerExitCode", 1).put("policyDenied", true)
                .put("lastFetchDenied", true).put("lastDeniedCode", "EGRESS_HEADERS_DENIED")
                .put("completedHttpCount", 1);
        var header = context.putObject("requestDiagnostic").put("kind", "header")
                .put("reason", "header_not_allowed").put("headerName", "x-app-unknown")
                .put("value", "PRIVATE");
        var event = context.putArray("completedHttpObservations").addObject().put("method", "POST")
                .put("requestUrlSha256", "a".repeat(64)).put("responseStatus", 200)
                .put("responseBytes", 12).put("responseSha256", "b".repeat(64))
                .put("responseComplete", true).put("responseContentType", "application/json")
                .put("body", "PRIVATE").put("headers", "PRIVATE");
        var safe = runtime.projectDiagnostic(input).path("exchangeContext");
        assertEquals("x-app-unknown", safe.path("requestDiagnostic").path("headerName").asText());
        assertEquals(200, safe.path("completedHttpObservations").get(0).path("responseStatus").asInt());
        assertFalse(safe.toString().contains("PRIVATE"));
        header.put("reason", "cookie_not_observed").put("headerName", "cookie");
        var state = header.putObject("cookieState").put("storedCount", 2).put("expectedPresent", true)
                .put("suppliedParsed", true).put("expectedParsed", true).put("suppliedCount", 1)
                .put("expectedCount", 2).put("matchingStoredCount", 1).put("value", "PRIVATE");
        event.put("requestCookiePresent", true).put("receivedSetCookieCount", 2);
        safe = runtime.projectDiagnostic(input).path("exchangeContext");
        assertEquals(1, safe.path("requestDiagnostic").path("cookieState").path("matchingStoredCount").asInt());
        assertTrue(safe.path("completedHttpObservations").get(0).path("requestCookiePresent").asBoolean());
        assertEquals(2, safe.path("completedHttpObservations").get(0).path("receivedSetCookieCount").asInt());
        assertFalse(safe.toString().contains("PRIVATE"));
        state.put("storedCount", 33);
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext")
                .path("requestDiagnostic").has("cookieState"));
        header.put("headerName", "PRIVATE\r\nheader");
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext")
                .path("requestDiagnostic").has("headerName"));
        header.put("reason", "PRIVATE");
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").has("requestDiagnostic"));
        event.put("responseStatus", true);
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").has("completedHttpObservations"));
        event.put("responseStatus", 200);
        context.put("completedHttpCount", 0);
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").has("completedHttpObservations"));
    }

    @Test
    void urlRefusalProjectionRetainsReasonAndHashWithoutAddressOrUnknownFields() throws Exception {
        var runtime = runtime(true, "/run/managed/executor.sock");
        var input = diagnostic();
        var context = input.putObject("exchangeContext").put("phase", "frame").put("lastHttpId", 6)
                .put("loaderCallCount", 1).put("peerExitCode", 1).put("policyDenied", true)
                .put("lastDeniedCode", "EGRESS_URL_DENIED");
        var request = context.putObject("requestDiagnostic").put("kind", "url")
                .put("reason", "origin_not_registered").put("method", "GET")
                .put("requestUrlSha256", "a".repeat(64)).put("url", "PRIVATE")
                .put("requestScheme", "https").put("requestOriginSha256", "b".repeat(64))
                .put("query", "PRIVATE");
        var safe = runtime.projectDiagnostic(input).path("exchangeContext").path("requestDiagnostic");
        assertEquals("origin_not_registered", safe.path("reason").asText());
        assertEquals("a".repeat(64), safe.path("requestUrlSha256").asText());
        assertEquals("https", safe.path("requestScheme").asText());
        assertEquals("b".repeat(64), safe.path("requestOriginSha256").asText());
        assertFalse(safe.toString().contains("PRIVATE"));
        request.put("reason", "url_scheme").put("requestScheme", "PRIVATE")
                .put("requestOriginSha256", "PRIVATE");
        safe = runtime.projectDiagnostic(input).path("exchangeContext").path("requestDiagnostic");
        assertEquals("url_scheme", safe.path("reason").asText());
        assertFalse(safe.has("requestScheme"));
        assertFalse(safe.has("requestOriginSha256"));
        request.put("reason", "PRIVATE");
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").has("requestDiagnostic"));
    }

    @Test
    void diagnosticProjectionRejectsUnboundedFieldsAndHidesUnknownText() throws Exception {
        var runtime = runtime(true, "/run/managed/executor.sock");
        var input = diagnostic();
        input.put("secret", "PRIVATE_RAW_ERROR");
        input.put("primaryCode", "PRIVATE_UPPERCASE_SECRET");
        ((ObjectNode) input.path("calls").get(0)).put("stderr", "PRIVATE_STDERR");
        var safe = runtime.projectDiagnostic(input);
        assertNotNull(safe);
        assertFalse(safe.toString().contains("PRIVATE"));
        assertEquals("MANAGED_EXECUTION_ERROR", safe.path("primaryCode").asText());
        assertEquals(6030, safe.path("calls").get(0).path("elapsedMillis").asInt());
        for (var value : List.of(mapper.getNodeFactory().numberNode(-1),
                mapper.getNodeFactory().numberNode(0.5), mapper.getNodeFactory().booleanNode(true),
                mapper.getNodeFactory().numberNode(Long.MAX_VALUE))) {
            input.set("createAckBytes", value);
            assertNull(runtime.projectDiagnostic(input));
        }
    }

    @Test
    void lastFetchDenialReachesTheProductWithoutOverridingCleanupOrEarlierFailures() throws Exception {
        for (String denial : List.of("EGRESS_HEADERS_DENIED", "EGRESS_SESSION_TOKEN_DENIED",
                "EGRESS_DNS_NO_ADDRESS", "PRIVATE_RAW_ERROR")) {
            for (boolean lastFetchDenied : List.of(true, false)) {
                var runtime = spy(runtime(true, "/run/managed/executor.sock"));
                var row = row(runtime);
                row.put("status", "FAILED").put("code", "EGRESS_PROTOCOL_INVALID");
                var diagnostic = diagnostic();
                diagnostic.put("primaryCode", "EGRESS_PROTOCOL_INVALID");
                diagnostic.putObject("exchangeContext").put("phase", "frame").put("lastHttpId", 1)
                        .put("loaderCallCount", 2).put("peerExitCode", 1).put("policyDenied", true)
                        .put("lastFetchDenied", lastFetchDenied).put("lastDeniedCode", denial)
                        .put("cookie", "PRIVATE_COOKIE");
                row.set("diagnostics", diagnostic);
                doAnswer(call -> reply(runtime, call.getArgument(0), row)).when(runtime).control(any());
                var error = assertThrows(ManagedExecutionFailure.class, () -> runtime.inspect(
                        job, source, profile(), runtime.sha256(), runtime.policySha256()));
                assertEquals(lastFetchDenied && !denial.startsWith("PRIVATE") ? denial : "MANAGED_EXECUTION_ERROR",
                        error.getMessage());
                assertEquals("EGRESS_PROTOCOL_INVALID", error.diagnostic().path("primaryCode").asText());
                assertFalse(error.diagnostic().toString().contains("PRIVATE"));
                row.put("status", "CLEANUP_UNCONFIRMED").put("code", "MANAGED_CLEANUP_UNCONFIRMED");
                row.putObject("cleanup").put("confirmed", false).put("containerAbsent", false);
                assertEquals("MANAGED_CLEANUP_UNCONFIRMED", assertThrows(ManagedExecutionFailure.class,
                        () -> runtime.inspect(job, source, profile(), runtime.sha256(),
                                runtime.policySha256())).getMessage());
            }
        }
    }

    @Test
    void exchangeDiagnosticsKeepOnlyBoundedPhaseCountersAndKnownDenials() throws Exception {
        var runtime = runtime(true, "/run/managed/executor.sock");
        var input = diagnostic();
        var context = input.putObject("exchangeContext").put("phase", "frame").put("lastHttpId", 4)
                .put("loaderCallCount", 6).put("peerExitCode", 1).put("policyDenied", true)
                .put("lastDeniedCode", "EGRESS_URL_DENIED").put("url", "PRIVATE");
        var safe = runtime.projectDiagnostic(input);
        assertEquals("EGRESS_URL_DENIED", safe.path("exchangeContext").path("lastDeniedCode").asText());
        assertEquals(4, safe.path("exchangeContext").path("lastHttpId").asInt());
        assertFalse(safe.toString().contains("PRIVATE"));
        context.put("lastDeniedCode", "EGRESS_HEADER_ORIGIN_DENIED");
        assertEquals("EGRESS_HEADER_ORIGIN_DENIED",
                runtime.projectDiagnostic(input).path("exchangeContext").path("lastDeniedCode").asText());
        for (String code : List.of("EGRESS_HEADERS_DENIED", "EGRESS_SESSION_TOKEN_DENIED", "EGRESS_REDIRECT_DENIED",
                "EGRESS_DNS_FAILED", "EGRESS_DNS_NO_ADDRESS", "EGRESS_NONPUBLIC_DNS")) {
            context.put("lastDeniedCode", code);
            input.put("primaryCode", "EGRESS_PROTOCOL_INVALID");
            assertEquals(code, runtime.projectDiagnostic(input).path("exchangeContext").path("lastDeniedCode").asText());
            assertEquals("EGRESS_PROTOCOL_INVALID", runtime.projectDiagnostic(input).path("primaryCode").asText());
        }
        var redirect = context.putObject("redirectObservation").put("responseStatus", 302)
                .put("requestUrlSha256", "a".repeat(64)).put("targetUrlSha256", "b".repeat(64))
                .put("targetScheme", "https").put("targetOrigin", "https://canonical.example.com:9443")
                .put("location", "PRIVATE");
        var observed = runtime.projectDiagnostic(input).path("exchangeContext").path("redirectObservation");
        assertEquals("https://canonical.example.com:9443", observed.path("targetOrigin").asText());
        assertFalse(observed.toString().contains("PRIVATE"));
        redirect.put("targetOrigin", "https://user:PRIVATE@canonical.example.com/path?token=PRIVATE");
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").path("redirectObservation")
                .has("targetOrigin"));
        redirect.put("targetScheme", "PRIVATE");
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").has("redirectObservation"));
        context.put("completedHttpCount", 2);
        var http = context.putObject("httpObservation").put("method", "GET")
                .put("requestUrlSha256", "a".repeat(64)).put("requestNumber", 3)
                .put("responseStatus", 503).put("responseContentType", "application/octet-stream")
                .put("body", "PRIVATE");
        var dns = context.putObject("dnsObservation").put("dnsStatus", 3).put("answerCount", 0)
                .put("publicIpv4Count", 0).put("kind", "no_address").put("answer", "PRIVATE");
        var failed = runtime.projectDiagnostic(input).path("exchangeContext");
        assertEquals(2, failed.path("completedHttpCount").asInt());
        assertEquals(503, failed.path("httpObservation").path("responseStatus").asInt());
        assertEquals(3, failed.path("dnsObservation").path("dnsStatus").asInt());
        assertFalse(failed.toString().contains("PRIVATE"));
        dns.put("recordType", "AAAA").put("publicIpv6Count", 1);
        var ipv6 = runtime.projectDiagnostic(input).path("exchangeContext").path("dnsObservation");
        assertEquals("AAAA", ipv6.path("recordType").asText());
        assertEquals(1, ipv6.path("publicIpv6Count").asInt());
        assertFalse(ipv6.has("answer"));
        dns.put("publicIpv6Count", 17);
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").path("dnsObservation")
                .has("publicIpv6Count"));
        dns.put("recordType", "PRIVATE").put("publicIpv6Count", 0);
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").path("dnsObservation")
                .has("recordType"));
        assertTrue(RepairReport.networkUnavailable(mapper.createObjectNode()
                .set("deniedCodes", mapper.createArrayNode().add("EGRESS_DNS_NO_ADDRESS"))));
        assertTrue(RepairReport.networkUnavailable(mapper.createObjectNode()
                .set("deniedCodes", mapper.createArrayNode().add("EGRESS_NONPUBLIC_DNS"))));
        http.put("responseContentType", "PRIVATE;token=PRIVATE");
        dns.put("answerCount", -1);
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").has("httpObservation"));
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").has("dnsObservation"));
        context.put("lastDeniedCode", "PRIVATE");
        assertTrue(runtime.projectDiagnostic(input).path("exchangeContext").path("lastDeniedCode").isNull());
        context.put("peerExitCode", Long.MIN_VALUE);
        assertFalse(runtime.projectDiagnostic(input).has("exchangeContext"));
        context.put("peerExitCode", 1).put("lastHttpId", 65);
        assertFalse(runtime.projectDiagnostic(input).has("exchangeContext"));
    }

    @Test
    void retryAfterDiagnosticsKeepFullSafeTimingButNotHeadersOrUntrustedNumbers() throws Exception {
        var runtime = runtime(true, "/run/managed/executor.sock");
        var input = diagnostic();
        var context = input.putObject("exchangeContext").put("phase", "fetch").put("lastHttpId", 1)
                .put("loaderCallCount", 2).putNull("peerExitCode").put("policyDenied", true)
                .put("lastDeniedCode", "EGRESS_COOLDOWN_BUDGET");
        var rate = context.putObject("rateLimit").put("responseStatus", 429).put("automaticRetry", false)
                .put("headers", "PRIVATE");
        var timing = rate.putObject("retryAfter").put("kind", "delay")
                .put("seconds", new java.math.BigInteger("9".repeat(128)));
        var safe = runtime.projectDiagnostic(input);
        assertEquals("9".repeat(128), safe.path("exchangeContext").path("rateLimit")
                .path("retryAfter").path("seconds").asText());
        assertFalse(safe.toString().contains("PRIVATE"));
        assertEquals("EGRESS_COOLDOWN_BUDGET", runtime.errorCode("EGRESS_COOLDOWN_BUDGET"));
        for (var value : List.of(mapper.getNodeFactory().numberNode(-1),
                mapper.getNodeFactory().numberNode(0.5), mapper.getNodeFactory().booleanNode(true),
                mapper.getNodeFactory().numberNode(new java.math.BigInteger("9".repeat(129))))) {
            timing.set("seconds", value);
            assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").has("rateLimit"));
        }
        timing.put("kind", "invalid").putNull("seconds");
        assertTrue(runtime.projectDiagnostic(input).path("exchangeContext").has("rateLimit"));
    }

    @Test
    void byteBudgetDiagnosticsRetainLimitsAndLargerCompletedPagesWithoutRawContent() throws Exception {
        var runtime = runtime(true, "/run/managed/executor.sock");
        var input = diagnostic();
        var context = input.putObject("exchangeContext").put("phase", "frame").put("lastHttpId", 9)
                .put("loaderCallCount", 5).put("peerExitCode", 1).put("policyDenied", true)
                .put("lastFetchDenied", true).put("lastDeniedCode", "EGRESS_BYTE_BUDGET")
                .put("completedHttpCount", 1);
        context.putArray("completedHttpObservations").addObject().put("method", "GET")
                .put("requestUrlSha256", "a".repeat(64)).put("responseStatus", 200)
                .put("responseBytes", 3145728).put("responseSha256", "b".repeat(64))
                .put("responseComplete", true).put("responseContentType", "text/html").put("body", "PRIVATE");
        var detail = context.putObject("requestDiagnostic").put("kind", "byte_budget")
                .put("reason", "total_response_bytes").put("stage", "content_length")
                .put("maxResponseBytes", 2097152).put("maxTotalResponseBytes", 4194304)
                .put("totalResponseBytesBefore", 3005640).put("effectiveResponseLimitBytes", 1188664)
                .put("observedResponseBytes", 1600000).put("url", "PRIVATE");
        var denial = context.putArray("denialObservations").addObject().put("httpId", 9)
                .put("code", "EGRESS_BYTE_BUDGET");
        denial.set("requestDiagnostic", detail);
        var safe = runtime.projectDiagnostic(input).path("exchangeContext");
        assertEquals(3145728, safe.path("completedHttpObservations").get(0).path("responseBytes").asInt());
        assertEquals("total_response_bytes", safe.path("requestDiagnostic").path("reason").asText());
        assertEquals(1188664, safe.path("denialObservations").get(0)
                .path("requestDiagnostic").path("effectiveResponseLimitBytes").asInt());
        assertFalse(safe.toString().contains("PRIVATE"));
        detail.put("observedResponseBytes", -1);
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").has("requestDiagnostic"));
        detail.put("observedResponseBytes", 1600000).put("reason", "PRIVATE");
        assertFalse(runtime.projectDiagnostic(input).path("exchangeContext").has("requestDiagnostic"));
    }

    @Test
    void explicitLargePagePolicyProjectsBusinessEvidenceWithinItsConfiguredLimit() throws Exception {
        var policy = mapper.createObjectNode().put("maxRequests", 6).put("maxResponseBytes", 8388608)
                .put("maxTotalResponseBytes", 67108864).put("minIntervalSeconds", 1);
        String policySha = Hashes.sha256(policy.toString());
        var runtime = spy(new ManagedSourceRuntime(mapper, fixture, true, "/run/managed/executor.sock",
                instance, image, policySha, ""));
        var row = businessRow(runtime, policy);
        var event = (ObjectNode) row.path("result").path("httpObservations").get(0);
        event.put("responseBytes", 3145728);
        doAnswer(call -> {
            ObjectNode request = call.getArgument(0);
            JsonNode answer = reply(runtime, request, row);
            if (request.path("operation").asText().equals("identity"))
                ((ObjectNode) answer).put("egressPolicySha256", policySha);
            return answer;
        }).when(runtime).control(any());
        var evidence = runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256());
        assertEquals(67108864, evidence.path("egressLimits").path("maxTotalResponseBytes").asInt());
        assertEquals(3145728, evidence.path("httpObservations").get(0).path("responseBytes").asInt());
        event.put("responseBytes", 8388609);
        assertThrows(IOException.class, () -> runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256()));
        event.put("responseBytes", 3145728);
        ((ObjectNode) row.path("result").path("policy")).put("maxTotalResponseBytes", 67108865);
        assertThrows(IOException.class, () -> runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256()));
    }

    @Test
    void failedCleanupPreservesTheSafePrimaryFailureWithoutChangingTheSafetyVerdict() throws Exception {
        var runtime = spy(runtime(true, "/run/managed/executor.sock"));
        var row = row(runtime);
        row.put("status", "CLEANUP_UNCONFIRMED").put("code", "MANAGED_CLEANUP_UNCONFIRMED");
        row.putObject("cleanup").put("confirmed", false).put("containerAbsent", false);
        row.set("diagnostics", diagnostic());
        doAnswer(call -> {
            ObjectNode request = call.getArgument(0);
            return request.path("operation").asText().equals("identity") ? reply(runtime, request, row) : row;
        }).when(runtime).control(any());
        var error = assertThrows(ManagedExecutionFailure.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256()));
        assertEquals("MANAGED_CLEANUP_UNCONFIRMED", error.getMessage());
        assertEquals("ENGINE_TIMEOUT", error.diagnostic().path("primaryCode").asText());
        assertFalse(error.diagnostic().path("createConfirmed").booleanValue());
        var changed = (ObjectNode) error.diagnostic();
        changed.put("primaryCode", "PRIVATE");
        assertEquals("ENGINE_TIMEOUT", error.diagnostic().path("primaryCode").asText());
    }

    @Test
    void businessCountsAndHttpAreBoundToPolicyAndCannotCarryRawSourceFields() throws Exception {
        var policy = mapper.createObjectNode().put("maxRequests", 6).put("maxResponseBytes", 2097152)
                .put("maxTotalResponseBytes", 4194304).put("minIntervalSeconds", 1);
        String policySha = Hashes.sha256(policy.toString());
        var runtime = spy(new ManagedSourceRuntime(mapper, fixture, true, "/run/managed/executor.sock",
                instance, image, policySha, ""));
        assertEquals(60, runtime.snapshot().path("policy").path("runtimeTimeoutSeconds").asInt());
        assertEquals(20, runtime(true, "/run/managed/executor.sock").snapshot()
                .path("policy").path("runtimeTimeoutSeconds").asInt());
        var row = businessRow(runtime, policy);
        doAnswer(call -> {
            ObjectNode request = call.getArgument(0);
            assertEquals(policySha, request.path("operation").asText().equals("identity")
                    ? policySha : request.path("egressPolicySha256").asText());
            if (request.path("operation").asText().equals("start"))
                assertEquals(profile().toString(), request.path("loaderProfileJson").asText());
            JsonNode reply = reply(runtime, request, row);
            if (request.path("operation").asText().equals("identity"))
                ((ObjectNode) reply).put("egressPolicySha256", policySha);
            return reply;
        }).when(runtime).control(any());
        var evidence = runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256());
        assertEquals(20, evidence.path("untrustedRuntimeSummary").path("homeItems").intValue());
        assertEquals(1, evidence.path("httpObservations").size());
        assertFalse(evidence.toString().contains("PRIVATE"));
        assertFalse(evidence.path("sourceHealthEligible").booleanValue());
        assertEquals("t4_business_sample", evidence.path("executionKind").asText());
        ((ObjectNode) row.path("result").path("policy")).put("maxRequests", 7);
        assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256()));
        ((ObjectNode) row.path("result").path("policy")).put("maxRequests", 6);
        ((ObjectNode) row.path("result").path("untrustedRuntimeSummary")).put("homeItems", 1.5);
        assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256()));
    }

    @Test
    void emptyHttpEventAssociationsRequireTheExactCompletedLoaderCall() throws Exception {
        var policy = httpEgressPolicy();
        var runtime = httpRuntime(policy);
        var profile = httpProfile(runtime);
        var row = httpRow(runtime, policy, profile);
        var event = (ObjectNode) row.at("/result/httpObservations/0");
        event.put("responseStatus", 444).put("responseBytes", 0).put("responseContentType", "")
                .put("responseComplete", true);
        event.remove("responseClass");
        var association = event.putObject("observedDuringCall").put("id", 1).put("method", "init")
                .put("inputSha256", "d".repeat(64));
        doAnswer(call -> httpReply(runtime, call.getArgument(0), row, policy)).when(runtime).control(any());
        var evidence = runtime.inspect(job, source, profile, runtime.sha256(), runtime.policySha256());
        assertEquals(association, evidence.at("/httpObservations/0/observedDuringCall"));
        for (String field : List.of("id", "method", "inputSha256", "extra")) {
            var original = association.deepCopy();
            if (field.equals("id")) association.put(field, 2);
            else association.put(field, "PRIVATE");
            var projected = runtime.inspect(job, source, profile, runtime.sha256(), runtime.policySha256());
            assertFalse(projected.at("/httpObservations/0").has("observedDuringCall"), field);
            assertFalse(projected.toString().contains("PRIVATE"));
            association.removeAll().setAll(original);
        }
        ((ObjectNode) row.path("result")).remove("loaderValidation");
        assertFalse(runtime.inspect(job, source, profile, runtime.sha256(), runtime.policySha256())
                .at("/httpObservations/0").has("observedDuringCall"));
    }

    @Test
    void sharedGatewaySettingsAreFrozenInHostRequestAndRedactedFromRuntimeEvidence() throws Exception {
        var policy = mapper.createObjectNode().put("maxRequests", 6).put("maxResponseBytes", 2097152)
                .put("maxTotalResponseBytes", 4194304).put("minIntervalSeconds", 1);
        var runtime = spy(new ManagedSourceRuntime(mapper, fixture, true, "/run/managed/executor.sock",
                instance, image, Hashes.sha256(policy.toString()), ""));
        var connections = mock(ConnectionSettingsStore.class);
        when(connections.evidenceGateway()).thenReturn(new cn.har01d.atmaintenance.dto.EvidenceGatewaySettings(
                true, "trawl", "http://private-gateway.invalid:8191", 30000));
        runtime.configureGateway(connections);
        var row = businessRow(runtime, policy);
        var event = (ObjectNode) row.at("/result/httpObservations/0");
        event.put("transport", "external_evidence_gateway").put("representation", "gateway_rendered_document")
                .put("cookiesExported", false).put("gatewaySessionReused", true);
        var execution = event.putObject("gatewayExecution").put("tier", 1).put("sessionCached", true)
                .put("cookie", "PRIVATE");
        var gatewaySummary = ((ObjectNode) row.path("result")).putObject("runtimeGateway").put("adapter", "trawl")
                .put("sessionCount", 1).put("requestCount", 2).put("responseCount", 2).put("sessionReuseCount", 1)
                .put("sessionRecoveryCount", 1).put("recoveryAttemptCount", 1)
                .put("cookiesExported", false).put("cleanupConfirmed", true).put("sessionScope", "task_and_origin")
                .put("targetAddressPolicy", "controller_public_preflight_gateway_managed_network")
                .put("session", "PRIVATE_SESSION");
        var executionCounts = gatewaySummary.putObject("executionCounts").put("tier1", 1).put("tier2", 0)
                .put("tier3", 0).put("tier4", 0).put("unknown", 1).put("cachedSession", 1).put("unknownCache", 1)
                .put("cookie", "PRIVATE");
        var lightweight = gatewaySummary.putObject("lightweightCounts").put("attempts", 1)
                .put("httpAccepted", 1).put("rejected", 0).put("cookie", "PRIVATE");
        doAnswer(call -> {
            ObjectNode request = call.getArgument(0);
            if (request.path("operation").asText().equals("start")) {
                var gateway = mapper.readTree(request.path("evidenceGatewayJson").asText());
                assertEquals("trawl", gateway.path("adapter").asText());
                assertFalse(request.path("loaderProfileJson").asText().contains("private-gateway"));
                row.put("evidenceGatewaySettingsSha256", Hashes.sha256(request.path("evidenceGatewayJson").asText()));
            }
            var result = reply(runtime, request, row);
            if (request.path("operation").asText().equals("identity"))
                ((ObjectNode) result).put("egressPolicySha256", Hashes.sha256(policy.toString()));
            return result;
        }).when(runtime).control(any());
        var evidence = runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256());
        assertEquals("external_evidence_gateway", evidence.at("/httpObservations/0/transport").asText());
        assertEquals(1, evidence.at("/runtimeGateway/sessionReuseCount").asInt());
        assertEquals(1, evidence.at("/runtimeGateway/sessionRecoveryCount").asInt());
        assertEquals(1, evidence.at("/runtimeGateway/executionCounts/unknown").asInt());
        assertEquals(1, evidence.at("/runtimeGateway/lightweightCounts/httpAccepted").asInt());
        assertEquals(1, evidence.at("/httpObservations/0/gatewayExecution/tier").asInt());
        assertTrue(evidence.at("/httpObservations/0/gatewayExecution/sessionCached").asBoolean());
        assertFalse(evidence.toString().contains("private-gateway"));
        assertFalse(evidence.toString().contains("PRIVATE"));
        lightweight.put("attempts", 2);
        assertThrows(IOException.class, () -> runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256()));
        lightweight.put("attempts", 1);
        for (String outcome : List.of("EVIDENCE_GATEWAY_SESSION_LOST", "EVIDENCE_GATEWAY_READ_TIMEOUT",
                "EVIDENCE_GATEWAY_CONNECT_TIMEOUT", "EVIDENCE_GATEWAY_CONNECTION_FAILED",
                "EVIDENCE_GATEWAY_CLEANUP_UNCONFIRMED", "target_rate_limited")) {
            event.put("gatewayOutcome", outcome);
            assertEquals(outcome, runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256())
                    .at("/httpObservations/0/gatewayOutcome").asText());
        }
        event.put("gatewayOutcome", "request_not_reproducible").put("challengePage", true);
        for (String reason : List.of("request_method", "request_body", "host_credentials", "request_headers",
                "response_content_type")) {
            event.put("gatewaySkipReason", reason);
            assertEquals(reason, runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256())
                    .at("/httpObservations/0/gatewaySkipReason").asText());
        }
        event.put("gatewaySkipReason", "PRIVATE");
        assertThrows(IOException.class, () -> runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256()));
        event.put("gatewaySkipReason", "request_headers").put("gatewayOutcome", "response_returned");
        assertThrows(IOException.class, () -> runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256()));
        event.remove("gatewaySkipReason");
        event.put("gatewayOutcome", "EVIDENCE_GATEWAY_READ_TIMEOUT");
        var recovery = event.putObject("gatewayRecovery").put("phase", "read").put("action", "reset_after_cooldown")
                .put("remainingWaitMilliseconds", 2000).put("taskBudgetRemainingMilliseconds", 10000)
                .put("fitsTaskBudget", true).put("errorMessage", "PRIVATE");
        evidence = runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256());
        assertEquals("reset_after_cooldown", evidence.at("/httpObservations/0/gatewayRecovery/action").asText());
        assertFalse(evidence.toString().contains("PRIVATE"));
        recovery.put("fitsTaskBudget", false);
        assertThrows(IOException.class, () -> runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256()));
        event.remove("gatewayRecovery");
        execution.put("tier", 5);
        assertThrows(IOException.class, () -> runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256()));
        execution.putNull("tier").putNull("sessionCached");
        assertTrue(runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256())
                .at("/httpObservations/0/gatewayExecution/tier").isNull());
        executionCounts.put("tier1", 2);
        assertThrows(IOException.class, () -> runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256()));
        executionCounts.put("tier1", 1);
        event.put("connectedAddressIndex", 0);
        assertThrows(IOException.class, () -> runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256()));
        event.remove(List.of("transport", "representation", "gatewayExecution", "gatewaySessionReused"));
        event.put("lightweightCookieReuse", "http_accepted");
        assertEquals("http_accepted", runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256())
                .at("/httpObservations/0/lightweightCookieReuse").asText());
        event.put("lightweightCookieReuse", "PRIVATE");
        assertThrows(IOException.class, () -> runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256()));
        event.put("lightweightCookieReuse", "rejected");
        event.remove("lightweightCookieReuse");
        event.put("lightweightCookieSkip", "scope_or_expiry");
        assertEquals("scope_or_expiry", runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256())
                .at("/httpObservations/0/lightweightCookieSkip").asText());
        event.put("lightweightCookieSkip", "PRIVATE");
        assertThrows(IOException.class, () -> runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256()));
        event.put("lightweightCookieSkip", "observed_cookie_collision");
        ((ObjectNode) row.path("result").path("runtimeGateway")).put("cleanupConfirmed", false);
        assertThrows(IOException.class, () -> runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256()));
    }

    @Test
    void runtimeStageProjectionMatchesCallsAndKeepsOnlyValidBudgetSnapshots() throws Exception {
        var policy = httpEgressPolicy();
        var runtime = httpRuntime(policy);
        var profile = httpProfile(runtime);
        var row = httpRow(runtime, policy, profile);
        var result = (ObjectNode) row.path("result");
        var association = mapper.createObjectNode().put("id", 1).put("method", "init")
                .put("inputSha256", "d".repeat(64));
        var network = result.putArray("networkObservations").addObject().put("requestUrlSha256", "a".repeat(64))
                .put("requestNumber", 1).put("addressIndex", 0).put("stage", "tcp_connect")
                .put("kind", "timeout").putNull("errno").put("elapsedMilliseconds", 5000);
        network.set("observedDuringCall", association);
        result.put("networkObservationsDropped", 0).put("dnsObservationsDropped", 0);
        var dns = result.putArray("dnsObservations").addObject().put("dnsStatus", 0).put("answerCount", 1)
                .put("publicIpv4Count", 1).put("kind", "success").put("recordType", "A")
                .put("resolver", "alidns").put("cacheHit", false).put("elapsedMilliseconds", 25)
                .put("rawAddress", "PRIVATE");
        dns.set("observedDuringCall", association);
        var rate = result.putArray("rateLimitObservations").addObject().put("responseStatus", 429)
                .put("automaticRetry", false).put("observedAtEpochMilliseconds", 1795000000000L);
        rate.putObject("retryAfter").put("kind", "delay").put("seconds", 120);
        var snapshot = rate.putObject("cooldownContext").put("phase", "receipt")
                .put("recordedAtEpochMilliseconds", 1795000002000L).put("remainingWaitMilliseconds", 118000)
                .put("taskBudgetRemainingMilliseconds", 5000).put("fitsTaskBudget", false).put("raw", "PRIVATE");
        rate.set("observedDuringCall", association);
        doAnswer(call -> httpReply(runtime, call.getArgument(0), row, policy)).when(runtime).control(any());
        var evidence = runtime.inspect(job, source, profile, runtime.sha256(), runtime.policySha256());
        for (String field : List.of("networkObservations", "dnsObservations", "rateLimitObservations"))
            assertEquals(association, evidence.path(field).get(0).path("observedDuringCall"));
        assertEquals(25, evidence.at("/dnsObservations/0/elapsedMilliseconds").asInt());
        assertEquals(118000, evidence.at("/rateLimitObservations/0/cooldownContext/remainingWaitMilliseconds").asInt());
        assertFalse(evidence.toString().contains("PRIVATE"));
        association.put("inputSha256", "f".repeat(64));
        snapshot.put("fitsTaskBudget", true);
        dns.put("cacheHit", true);
        evidence = runtime.inspect(job, source, profile, runtime.sha256(), runtime.policySha256());
        for (String field : List.of("networkObservations", "dnsObservations", "rateLimitObservations"))
            assertFalse(evidence.path(field).get(0).has("observedDuringCall"));
        assertFalse(evidence.at("/rateLimitObservations/0").has("cooldownContext"));
        assertFalse(evidence.at("/dnsObservations/0").has("elapsedMilliseconds"));
    }

    @Test
    void networkDiagnosticsUseClosedStagesAndNeverExposeExceptionText() throws Exception {
        var policy = mapper.createObjectNode().put("maxRequests", 6).put("maxResponseBytes", 2097152)
                .put("maxTotalResponseBytes", 4194304).put("minIntervalSeconds", 1);
        String policySha = Hashes.sha256(policy.toString());
        var runtime = spy(new ManagedSourceRuntime(mapper, fixture, true, "/run/managed/executor.sock",
                instance, image, policySha, ""));
        var row = businessRow(runtime, policy);
        var result = (ObjectNode) row.path("result");
        result.put("networkObservationsDropped", 3);
        var observation = result.putArray("networkObservations").addObject()
                .put("requestUrlSha256", "a".repeat(64)).put("requestNumber", 1).put("addressIndex", 2)
                .put("stage", "tls_handshake").put("kind", "certificate").put("errno", 1)
                .put("elapsedMilliseconds", 5040).put("exceptionMessage", "PRIVATE");
        ((ObjectNode) result.path("httpObservations").get(0)).put("connectedAddressIndex", 3);
        doAnswer(call -> {
            ObjectNode request = call.getArgument(0);
            JsonNode answer = reply(runtime, request, row);
            if (request.path("operation").asText().equals("identity"))
                ((ObjectNode) answer).put("egressPolicySha256", policySha);
            return answer;
        }).when(runtime).control(any());
        var evidence = runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256());
        assertEquals(3, evidence.path("networkObservationsDropped").asInt());
        assertEquals("tls_handshake", evidence.path("networkObservations").get(0).path("stage").asText());
        assertEquals(3, evidence.path("httpObservations").get(0).path("connectedAddressIndex").asInt());
        assertFalse(evidence.toString().contains("PRIVATE"));
        for (String field : java.util.List.of("stage", "kind", "requestUrlSha256")) {
            String previous = observation.path(field).asText();
            observation.put(field, "PRIVATE");
            assertThrows(IOException.class, () -> runtime.inspect(
                    job, source, profile(), runtime.sha256(), runtime.policySha256()));
            observation.put(field, previous);
        }
        observation.put("addressIndex", 16);
        assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256()));
        observation.put("addressIndex", 2);
        var diagnostic = mapper.createObjectNode().put("schemaVersion", 1)
                .put("authority", "managed_source_broker_observed").put("createIssued", true)
                .put("createConfirmed", true).put("createAckBytes", 1);
        diagnostic.putArray("calls");
        var context = diagnostic.putObject("exchangeContext").put("phase", "fetch")
                .put("lastHttpId", 1).put("loaderCallCount", 1).put("policyDenied", true)
                .putNull("peerExitCode");
        context.set("networkObservation", observation.deepCopy());
        var projected = runtime.projectDiagnostic(diagnostic);
        assertEquals("tls_handshake", projected.path("exchangeContext").path("networkObservation")
                .path("stage").asText());
        assertFalse(projected.toString().contains("PRIVATE"));
        ((ObjectNode) context.path("networkObservation")).put("stage", "PRIVATE");
        assertFalse(runtime.projectDiagnostic(diagnostic).path("exchangeContext").has("networkObservation"));
    }

    @Test
    void responseUrlRegistrationsKeepOnlyHashesUnderTheFrozenPolicy() throws Exception {
        var policy = mapper.createObjectNode().put("schemaVersion", 3).put("maxRequests", 12)
                .put("maxResponseBytes", 2097152).put("maxTotalResponseBytes", 4194304).put("minIntervalSeconds", 1);
        policy.putArray("methods").add("GET").add("HEAD");
        var route = policy.putArray("routes").addObject().put("origin", "https://example.com");
        route.putArray("exactUrls").add("https://example.com/detail?id=1");
        route.putArray("pinnedIpv4").add("93.184.216.34");
        String policySha = Hashes.sha256(policy.toString());
        var runtime = spy(new ManagedSourceRuntime(mapper, fixture, true, "/run/managed/executor.sock",
                instance, image, policySha, ""));
        var row = businessRow(runtime, policy);
        var event = (ObjectNode) row.path("result").path("httpObservations").get(0);
        var registered = event.putArray("registeredUrlSha256").add("d".repeat(64));
        event.put("registeredUrl", "https://PRIVATE/?token=PRIVATE");
        doAnswer(call -> {
            ObjectNode request = call.getArgument(0);
            JsonNode answer = reply(runtime, request, row);
            if (request.path("operation").asText().equals("identity"))
                ((ObjectNode) answer).put("egressPolicySha256", policySha);
            return answer;
        }).when(runtime).control(any());
        var evidence = runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256());
        assertEquals("d".repeat(64), evidence.path("httpObservations").get(0)
                .path("registeredUrlSha256").get(0).asText());
        assertFalse(evidence.toString().contains("PRIVATE"));
        assertFalse(evidence.path("publishEligible").asBoolean());
        for (int i = 1; i < 20; i++) registered.add("e".repeat(64));
        assertEquals(20, runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256())
                .path("httpObservations").get(0).path("registeredUrlSha256").size());
        registered.add("f".repeat(64));
        assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256()));
        registered.remove(20);
        event.put("method", "HEAD").put("responseStatus", 404).put("responseBytes", 0)
                .put("responseClass", "head_not_found");
        assertEquals("head_not_found", runtime.inspect(job, source, profile(), runtime.sha256(),
                runtime.policySha256()).path("httpObservations").get(0).path("responseClass").asText());
        event.put("method", "GET");
        assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256()));
        event.put("method", "HEAD").put("responseBytes", 1);
        assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256()));
        event.put("method", "GET").put("responseStatus", 200).put("responseBytes", 0)
                .put("responseClass", "http_200");
        registered.add("PRIVATE");
        assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256()));
    }

    @Test
    void playerAndMediaObservationsHaveClosedVocabularyAndNoRawPlaybackAddress() throws Exception {
        var policy = mapper.createObjectNode().put("maxRequests", 6).put("maxResponseBytes", 2097152)
                .put("maxTotalResponseBytes", 4194304).put("minIntervalSeconds", 1);
        String policySha = Hashes.sha256(policy.toString());
        var runtime = spy(new ManagedSourceRuntime(mapper, fixture, true, "/run/managed/executor.sock",
                instance, image, policySha, ""));
        var row = businessRow(runtime, policy);
        var counts = (ObjectNode) row.path("result").path("untrustedRuntimeSummary");
        counts.put("playerStatus", "returned").put("playerUrlKind", "https")
                .put("playerParse", "direct").put("mediaStatus", "prefix_returned")
                .put("url", "https://PRIVATE/?token=PRIVATE");
        var event = (ObjectNode) row.path("result").path("httpObservations").get(0);
        event.put("responseBytes", 65536).put("rangeRequested", true).put("prefixFormat", "mp4")
                .put("resourceComplete", false);
        doAnswer(call -> {
            ObjectNode request = call.getArgument(0);
            JsonNode answer = reply(runtime, request, row);
            if (request.path("operation").asText().equals("identity"))
                ((ObjectNode) answer).put("egressPolicySha256", policySha);
            return answer;
        }).when(runtime).control(any());
        var evidence = runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256());
        assertEquals("returned", evidence.path("untrustedRuntimeSummary").path("playerStatus").asText());
        assertEquals("mp4", evidence.path("httpObservations").get(0).path("prefixFormat").asText());
        assertTrue(evidence.path("httpObservations").get(0).path("resourceComplete").isBoolean());
        assertFalse(evidence.path("httpObservations").get(0).path("resourceComplete").booleanValue());
        assertFalse(evidence.toString().contains("PRIVATE"));
        assertFalse(evidence.path("publishEligible").booleanValue());
        counts.put("playerStatus", "PRIVATE");
        assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256()));
        counts.put("playerStatus", "returned");
        event.put("resourceComplete", "PRIVATE");
        assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256()));
        event.remove("resourceComplete");
        assertFalse(runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256())
                .path("httpObservations").get(0).has("resourceComplete"));
        event.put("prefixFormat", "mpeg_ts");
        assertEquals("mpeg_ts", runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256())
                .path("httpObservations").get(0).path("prefixFormat").asText());
        counts.put("playerStatus", "returned");
        event.put("responseBytes", 65537);
        assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256()));
    }

    @Test
    void networkFailureCallProjectionBindsOnlyUniqueNetworkDenialIndexes() throws Exception {
        var policy = mapper.createObjectNode().put("maxRequests", 6).put("maxResponseBytes", 2097152)
                .put("maxTotalResponseBytes", 4194304).put("minIntervalSeconds", 1);
        String policySha = Hashes.sha256(policy.toString());
        var runtime = spy(new ManagedSourceRuntime(mapper, fixture, true, "/run/managed/executor.sock",
                instance, image, policySha, ""));
        var row = businessRow(runtime, policy);
        var result = (ObjectNode) row.path("result");
        result.withArray("deniedCodes").add("EGRESS_NONPUBLIC_DNS");
        var calls = result.putArray("networkFailureCalls");
        var call = calls.addObject().put("denialIndex", 0).put("method", "home");
        doAnswer(invocation -> {
            ObjectNode request = invocation.getArgument(0);
            JsonNode answer = reply(runtime, request, row);
            if (request.path("operation").asText().equals("identity"))
                ((ObjectNode) answer).put("egressPolicySha256", policySha);
            return answer;
        }).when(runtime).control(any());
        assertEquals(calls, runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256())
                .path("networkFailureCalls"));
        assertTrue(RepairReport.egressUnresolved(runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256())));
        for (int index : List.of(-1, 1, 64)) {
            call.put("denialIndex", index);
            assertThrows(IOException.class, () -> runtime.inspect(
                    job, source, profile(), runtime.sha256(), runtime.policySha256()));
        }
        call.put("denialIndex", 0);
        calls.add(call.deepCopy());
        assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256()));
        calls.remove(1);
        result.putArray("deniedCodes").add("EGRESS_URL_DENIED");
        assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256()));
    }

    @Test
    void httpFailureClassificationIsProjectedWithoutPromotingItToSuccess() throws Exception {
        var policy = mapper.createObjectNode().put("maxRequests", 6).put("maxResponseBytes", 2097152)
                .put("maxTotalResponseBytes", 4194304).put("minIntervalSeconds", 1);
        String policySha = Hashes.sha256(policy.toString());
        var runtime = spy(new ManagedSourceRuntime(mapper, fixture, true, "/run/managed/executor.sock",
                instance, image, policySha, ""));
        var row = businessRow(runtime, policy);
        var event = (ObjectNode) row.path("result").path("httpObservations").get(0);
        event.put("responseStatus", 502).put("responseClass", "textual_server_error").put("responseComplete", true)
                .put("body", "PRIVATE");
        doAnswer(call -> {
            ObjectNode request = call.getArgument(0);
            JsonNode answer = reply(runtime, request, row);
            if (request.path("operation").asText().equals("identity"))
                ((ObjectNode) answer).put("egressPolicySha256", policySha);
            return answer;
        }).when(runtime).control(any());
        var evidence = runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256());
        var safeEvent = evidence.path("httpObservations").get(0);
        assertEquals(502, safeEvent.path("responseStatus").asInt());
        assertEquals("textual_server_error", safeEvent.path("responseClass").asText());
        assertTrue(safeEvent.path("responseComplete").asBoolean());
        assertFalse(evidence.toString().contains("PRIVATE"));
        assertFalse(evidence.path("publishEligible").asBoolean());
        ((ObjectNode) row.path("result")).withArray("deniedCodes").add("EGRESS_RATE_LIMITED");
        assertEquals("EGRESS_RATE_LIMITED", runtime.inspect(job, source, profile(), runtime.sha256(),
                runtime.policySha256()).path("deniedCodes").get(0).asText());
        assertEquals("EGRESS_RATE_LIMITED", ManagedLoaderReceipt.safeReason("EGRESS_RATE_LIMITED"));
        assertEquals("COVER_BYTES_INVALID", ManagedLoaderReceipt.safeReason("COVER_BYTES_INVALID"));
        event.put("method", "POST").put("responseStatus", 200).put("responseClass", "http_200");
        assertEquals("POST", runtime.inspect(job, source, profile(), runtime.sha256(),
                runtime.policySha256()).path("httpObservations").get(0).path("method").asText());
        for (int status : List.of(401, 403, 404)) {
            event.put("method", "GET").put("responseStatus", status).put("responseClass", "textual_client_error");
            assertEquals("textual_client_error", runtime.inspect(job, source, profile(), runtime.sha256(),
                    runtime.policySha256()).path("httpObservations").get(0).path("responseClass").asText());
        }
        for (int status : List.of(401, 403, 502, 503)) {
            String classification = status >= 500 ? "server_error" : "client_error";
            event.put("responseStatus", status).put("responseClass", classification)
                    .put("responseContentType", "application/octet-stream");
            var observed = runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256())
                    .path("httpObservations").get(0);
            assertEquals(classification, observed.path("responseClass").asText());
            assertEquals("application/octet-stream", observed.path("responseContentType").asText());
        }
        event.put("responseStatus", 204).put("responseClass", "http_2xx").put("responseContentType", "");
        assertEquals("http_2xx", runtime.inspect(job, source, profile(), runtime.sha256(),
                runtime.policySha256()).path("httpObservations").get(0).path("responseClass").asText());
        assertEquals("EGRESS_DNS_NO_ADDRESS", ManagedLoaderReceipt.safeReason("EGRESS_DNS_NO_ADDRESS"));
        for (int status : List.of(301, 302, 303, 307, 308)) {
            event.put("responseStatus", status).put("responseClass", "http_redirect").put("location", "PRIVATE");
            var redirect = runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256());
            assertEquals("http_redirect", redirect.path("httpObservations").get(0).path("responseClass").asText());
            assertFalse(redirect.toString().contains("PRIVATE"));
            assertFalse(redirect.path("publishEligible").asBoolean());
        }
        event.put("responseStatus", 502).put("responseClass", "textual_server_error");
        for (String invalid : List.of("http_200", "PRIVATE")) {
            event.put("responseClass", invalid);
            assertThrows(IOException.class, () -> runtime.inspect(
                    job, source, profile(), runtime.sha256(), runtime.policySha256()));
        }
        event.put("responseClass", "textual_server_error").put("responseComplete", "true");
        assertThrows(IOException.class, () -> runtime.inspect(
                job, source, profile(), runtime.sha256(), runtime.policySha256()));
    }

    @Test
    void unconfiguredRuntimeCannotSilentlyUseAnEgressEnabledBroker() throws Exception {
        var runtime = spy(runtime(true, "/run/managed/executor.sock"));
        doAnswer(call -> {
            ObjectNode request = call.getArgument(0);
            JsonNode reply = reply(runtime, request, row(runtime));
            if (request.path("operation").asText().equals("identity"))
                ((ObjectNode) reply).put("egressPolicySha256", "a".repeat(64));
            return reply;
        }).when(runtime).control(any());
        assertEquals("MANAGED_RUNNER_IDENTITY_MISMATCH", assertThrows(IOException.class,
                () -> runtime.inspect(job, source, profile(), runtime.sha256(), runtime.policySha256())).getMessage());
    }

    @Test
    void formalLoaderCompletesBeforeHttpAndOnlyTheVerifiedMatrixAdvancesR04() throws Exception {
        var policy = httpEgressPolicy();
        var runtime = httpRuntime(policy);
        var profile = httpProfile(runtime);
        var row = httpRow(runtime, policy, profile);
        var operations = new java.util.ArrayList<String>();
        doAnswer(call -> {
            ObjectNode request = call.getArgument(0);
            String operation = request.path("operation").asText();
            operations.add(operation);
            if (operation.equals("start_http")) {
                assertEquals(List.of("identity", "start", "start_http"), operations);
                assertEquals(profile.toString(), request.path("loaderProfileJson").asText());
                assertEquals(Hashes.sha256(source), request.path("sourceSha256").asText());
                var pending = row.deepCopy();
                ((ObjectNode) pending.path("httpAcceptance")).put("status", "RUNNING")
                        .putObject("cleanup").put("confirmed", false).put("containerAbsent", false);
                return pending;
            }
            return httpReply(runtime, request, row, policy);
        }).when(runtime).control(any());
        var evidence = runtime.inspect(job, source, profile, runtime.sha256(), runtime.policySha256());
        var loader = evidence.path("loaderValidation");
        assertEquals("pass", loader.path("nodes").get(1).path("status").asText());
        assertEquals(36, loader.path("nodes").get(1).path("httpCalls").asInt());
        assertTrue(loader.path("nodes").get(1).path("currentChildJournalsVerified").asBoolean());
        assertEquals("not_tested", loader.path("nodes").get(2).path("status").asText());
        assertFalse(loader.path("successful").asBoolean());
        assertFalse(evidence.path("publishEligible").asBoolean());
        assertEquals(List.of("identity", "start", "start_http", "status", "cancel"), operations);
        verify(runtime, never()).cancelAcceptance(anyString(), any());
    }

    @Test
    void lostHttpStartAndInterruptedMatrixCancelTheWholeAcceptanceWithoutReplay() throws Exception {
        for (boolean interrupted : List.of(false, true)) {
            var policy = httpEgressPolicy();
            var runtime = httpRuntime(policy);
            var profile = httpProfile(runtime);
            var row = httpRow(runtime, policy, profile);
            var operations = new java.util.ArrayList<String>();
            doAnswer(call -> {
                ObjectNode request = call.getArgument(0);
                String operation = request.path("operation").asText();
                operations.add(operation);
                if (operation.equals("start_http")) {
                    if (interrupted) throw new InterruptedException();
                    throw new IOException("MANAGED_TRANSPORT_TIMEOUT");
                }
                return httpReply(runtime, request, row, policy);
            }).when(runtime).control(any());
            if (interrupted) assertThrows(InterruptedException.class, () -> runtime.inspect(
                    job, source, profile, runtime.sha256(), runtime.policySha256()));
            else assertEquals("MANAGED_TRANSPORT_TIMEOUT", assertThrows(IOException.class, () -> runtime.inspect(
                    job, source, profile, runtime.sha256(), runtime.policySha256())).getMessage());
            assertEquals(List.of("identity", "start", "start_http", "cancel_acceptance"), operations);
        }
    }

    @Test
    void failedHttpStageRemainsBlockedAndCannotBorrowLoaderOrR05Passes() throws Exception {
        var policy = httpEgressPolicy();
        var runtime = httpRuntime(policy);
        var profile = httpProfile(runtime);
        var row = httpRow(runtime, policy, profile);
        var phase = (ObjectNode) row.path("httpAcceptance");
        phase.put("status", "FAILED").put("code", "ENGINE_TIMEOUT").remove("observation");
        doAnswer(call -> httpReply(runtime, call.getArgument(0), row, policy)).when(runtime).control(any());
        var loader = runtime.inspect(job, source, profile, runtime.sha256(), runtime.policySha256())
                .path("loaderValidation");
        assertEquals("blocked", loader.path("nodes").get(1).path("status").asText());
        assertEquals("T4_HTTP_ACCEPTANCE_FAILED", loader.path("nodes").get(1).path("reason").asText());
        assertEquals("not_tested", loader.path("nodes").get(2).path("status").asText());
        assertFalse(loader.has("t4HttpObservation"));
        assertFalse(loader.path("successful").asBoolean());
    }

    @Test
    void changedBindingUnverifiedChildrenAndUnconfirmedDriverCleanupRejectTheHttpReceipt() throws Exception {
        for (String defect : List.of("source", "profile", "parent", "child", "duplicate", "cleanup", "verified")) {
            var policy = httpEgressPolicy();
            var runtime = httpRuntime(policy);
            var profile = httpProfile(runtime);
            var row = httpRow(runtime, policy, profile);
            var phase = (ObjectNode) row.path("httpAcceptance");
            var observed = (ObjectNode) phase.path("observation");
            switch (defect) {
                case "source" -> observed.put("sourceSha256", "0".repeat(64));
                case "profile" -> observed.put("inputProfileSha256", "0".repeat(64));
                case "parent" -> observed.put("jobId", instance);
                case "child" -> ((ObjectNode) observed.path("calls").get(0)).put("brokerJobId", job);
                case "duplicate" -> ((ObjectNode) observed.path("calls").get(1))
                        .set("brokerJobId", observed.path("calls").get(0).path("brokerJobId"));
                case "cleanup" -> ((ObjectNode) phase.path("cleanup")).put("containerAbsent", false);
                case "verified" -> phase.remove("currentChildJournalsVerified");
            }
            doAnswer(call -> httpReply(runtime, call.getArgument(0), row, policy)).when(runtime).control(any());
            assertThrows(IOException.class, () -> runtime.inspect(
                    job, source, profile, runtime.sha256(), runtime.policySha256()), defect);
            verify(runtime).cancelAcceptance(eq(job), any());
        }
    }

    @Test
    void initializationFailureDoesNotStartHttpEvenWithAnHttpImageConfigured() throws Exception {
        var policy = httpEgressPolicy();
        var runtime = httpRuntime(policy);
        var profile = httpProfile(runtime);
        var row = httpRow(runtime, policy, profile);
        ((ObjectNode) row.path("result").path("loaderValidation").path("nodes").get(0))
                .put("status", "blocked").put("reason", "LOADER_INIT_FAILED");
        doAnswer(call -> {
            ObjectNode request = call.getArgument(0);
            assertNotEquals("start_http", request.path("operation").asText());
            return httpReply(runtime, request, row, policy);
        }).when(runtime).control(any());
        assertFalse(runtime.inspect(job, source, profile, runtime.sha256(), runtime.policySha256())
                .path("loaderValidation").has("t4HttpObservation"));
    }

    private ObjectNode httpEgressPolicy() {
        return mapper.createObjectNode().put("maxRequests", 6).put("maxResponseBytes", 2097152)
                .put("maxTotalResponseBytes", 4194304).put("minIntervalSeconds", 1);
    }

    @Test
    void searchReceiptRequiresActualSearchAndMatchingDetailCallReferences() throws Exception {
        var policy = httpEgressPolicy();
        var runtime = httpRuntime(policy);
        var profile = profile().put("schemaVersion", 1);
        profile.putArray("requiredNodes").add("S06").add("C05");
        var loader = (ObjectNode) httpRow(runtime, policy, profile).path("result").path("loaderValidation");
        loader.withArray("nodes").remove(2);
        var search = (ObjectNode) loader.path("nodes").get(1);
        search.removeAll();
        search.put("id", "C05").put("status", "pass").put("reason", "SEARCH_FIRST_PAGE_TO_DETAIL_CONTRACT")
                .put("items", 2).put("searchCallId", 2).put("detailCallId", 3).put("keywordSha256", "a".repeat(64));
        search.putObject("identityEvidence").put("selectedIdSha256", "b".repeat(64))
                .put("returnedIdSha256", "b".repeat(64)).put("exactMatch", true).put("guidCoverMatch", false)
                .put("atvpDetailPrefixMatch", false).put("nameMatch", true).put("coverMatch", true);
        for (String method : List.of("search", "detail"))
            loader.withArray("calls").addObject().put("id", loader.path("calls").size())
                    .put("method", method).put("ok", true).put("inputSha256", "c".repeat(64))
                    .put("outputSha256", "d".repeat(64)).put("elapsedMillis", 1);
        loader.put("callsSha256", Hashes.sha256(mapper.writer()
                .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(mapper.convertValue(loader.path("calls"), Object.class))));
        var receipt = new ManagedLoaderReceipt(mapper);
        assertEquals("pass", receipt.project(loader, profile, Hashes.sha256(source), Hashes.sha256(source), fixture.sha256())
                .path("nodes").get(1).path("status").asText());
        search.put("detailCallId", 2);
        assertThrows(IOException.class, () -> receipt.project(loader, profile,
                Hashes.sha256(source), Hashes.sha256(source), fixture.sha256()));
    }

    @Test
    void coverProxyObservationMustMatchTheCurrentWorkerCall() throws Exception {
        var policy = httpEgressPolicy();
        var runtime = httpRuntime(policy);
        var profile = profile().put("schemaVersion", 1);
        profile.putArray("requiredNodes").add("S06").add("C09");
        var row = httpRow(runtime, policy, profile);
        var loader = (ObjectNode) row.path("result").path("loaderValidation");
        var nodes = loader.withArray("nodes");
        nodes.remove(2);
        var cover = (ObjectNode) nodes.get(1);
        cover.put("id", "C09").put("status", "pass").put("reason", "COVER_BYTES_DECODED")
                .put("responseSha256", "f".repeat(64)).put("bytes", 160000).put("width", 200).put("height", 300);
        var proxy = cover.putObject("proxyObservation").put("workerCallId", 2)
                .put("paramsSha256", "a".repeat(64)).put("resultSha256", "b".repeat(64))
                .put("targetSha256", "c".repeat(64));
        loader.withArray("calls").addObject().put("id", 2).put("method", "proxy").put("ok", true)
                .put("inputSha256", "a".repeat(64)).put("outputSha256", "b".repeat(64)).put("elapsedMillis", 1);
        loader.put("callsSha256", Hashes.sha256(mapper.writer()
                .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(mapper.convertValue(loader.path("calls"), Object.class))));
        var receipt = new ManagedLoaderReceipt(mapper);
        var safe = receipt.project(loader, profile, Hashes.sha256(source), Hashes.sha256(source), fixture.sha256());
        assertEquals(proxy, safe.path("nodes").get(1).path("proxyObservation"));
        for (String field : List.of("workerCallId", "paramsSha256", "resultSha256", "targetSha256")) {
            var invalid = loader.deepCopy();
            var changed = (ObjectNode) invalid.path("nodes").get(1).path("proxyObservation");
            if (field.equals("workerCallId")) changed.put(field, 1);
            else changed.put(field, field.equals("targetSha256") ? "PRIVATE" : "e".repeat(64));
            assertThrows(IOException.class, () -> receipt.project(invalid, profile,
                    Hashes.sha256(source), Hashes.sha256(source), fixture.sha256()), field);
        }
    }

    @Test
    void resourceActionsUseValidatedLoaderCallsBeforeBusinessProjection() throws Exception {
        var policy = mapper.createObjectNode().put("maxRequests", 64).put("maxResponseBytes", 1_048_576)
                .put("maxTotalResponseBytes", 4_194_304).put("minIntervalSeconds", 1);
        var runtime = httpRuntime(policy);
        var profile = httpProfile(runtime);
        var row = httpRow(runtime, policy, profile);
        var result = (ObjectNode) row.path("result");
        var resources = ManagedResourceEvidenceTest.fixture(mapper, Hashes.sha256(source));
        resources.put("targetNode", "C07").putNull("image");
        var events = ManagedResourceEvidenceTest.events(mapper);
        ((ObjectNode) events.get(1)).put("responseStatus", 403);
        var page = (ObjectNode) resources.path("resources").get(0);
        page.put("responseStatus", 403);
        page.putObject("observedDuringCall").put("id", 1).put("method", "init")
                .put("inputSha256", "d".repeat(64));
        result.set("resourceEvidence", resources);
        result.set("contentResourceEvidence", resources.deepCopy());
        result.set("httpObservations", events);
        doAnswer(call -> httpReply(runtime, call.getArgument(0), row, policy)).when(runtime).control(any());
        var evidence = runtime.inspect(job, source, profile, runtime.sha256(), runtime.policySha256());
        assertEquals(resources, evidence.path("resourceEvidence"));
        assertEquals(resources, evidence.path("contentResourceEvidence"));
        assertEquals(403, evidence.path("resourceEvidence").path("resources").get(0).path("responseStatus").asInt());
        assertEquals("init", evidence.path("resourceEvidence").path("resources").get(0)
                .path("observedDuringCall").path("method").asText());
        assertFalse(evidence.path("publishEligible").asBoolean());
        result.remove("resourceEvidence");
        ((ObjectNode) result.path("contentResourceEvidence").path("resources").get(0)
                .path("observedDuringCall")).put("inputSha256", "0".repeat(64));
        assertEquals("MANAGED_RESOURCE_EVIDENCE_INVALID", assertThrows(IOException.class,
                () -> runtime.inspect(job, source, profile, runtime.sha256(), runtime.policySha256())).getMessage());
        result.set("resourceEvidence", resources);
        result.remove("contentResourceEvidence");
        page.withObject("observedDuringCall").put("inputSha256", "0".repeat(64));
        assertEquals("MANAGED_RESOURCE_EVIDENCE_INVALID", assertThrows(IOException.class,
                () -> runtime.inspect(job, source, profile, runtime.sha256(), runtime.policySha256())).getMessage());
    }

    private ManagedSourceRuntime httpRuntime(ObjectNode policy) throws IOException {
        return spy(new ManagedSourceRuntime(mapper, fixture, true, "/run/managed/executor.sock",
                instance, image, Hashes.sha256(policy.toString()), "sha256:" + "e".repeat(64)));
    }

    private ObjectNode httpProfile(ManagedSourceRuntime runtime) {
        var value = profile().put("schemaVersion", 1)
                .put("runtimeRevision", runtime.manifest().path("runtimeSha256").asText());
        value.putArray("requiredNodes").add("S06").add("R04").add("R05");
        return value;
    }

    private JsonNode httpReply(ManagedSourceRuntime runtime, ObjectNode request, ObjectNode row, ObjectNode policy) {
        var value = (ObjectNode) reply(runtime, request, row);
        if (request.path("operation").asText().equals("identity"))
            value.put("egressPolicySha256", Hashes.sha256(policy.toString()))
                    .put("httpImageId", "sha256:" + "e".repeat(64));
        return value;
    }

    private ObjectNode httpRow(ManagedSourceRuntime runtime, ObjectNode policy, ObjectNode profile) throws Exception {
        var row = businessRow(runtime, policy);
        row.put("sourceSha256", Hashes.sha256(source)).put("loaderProfileSha256", Hashes.sha256(profile.toString()));
        var loader = ((ObjectNode) row.path("result")).putObject("loaderValidation")
                .put("schemaVersion", 1).put("authority", "host_loader_contract_observed")
                .put("scope", ManagedLoaderReceipt.SCOPE).put("requirementsFrozen", true)
                .put("inputProfileSha256", Hashes.sha256(profile.toString()))
                .put("inputSourceSha256", Hashes.sha256(source)).put("loadedTextSha256", Hashes.sha256(source))
                .put("sourceDataPresent", !SourceConfig.read(profile).isEmpty())
                .put("sourceDataSha256", SourceConfig.sha256(SourceConfig.read(profile)))
                .put("runtimeSha256", fixture.sha256()).put("profileSha256", "c".repeat(64));
        loader.set("requiredNodes", profile.path("requiredNodes").deepCopy());
        var nodes = loader.putArray("nodes");
        nodes.addObject().put("id", "S06").put("status", "pass").put("reason", "PRE_EXEC_SOURCE_SHA_AND_INIT_RETURNED");
        for (String node : List.of("R04", "R05"))
            nodes.addObject().put("id", node).put("status", "not_tested").put("reason", "NODE_NOT_VALIDATED_BY_THIS_RUNNER");
        var calls = loader.putArray("calls");
        calls.addObject().put("id", 1).put("method", "init").put("ok", true)
                .put("inputSha256", "d".repeat(64)).put("outputSha256", "e".repeat(64)).put("elapsedMillis", 1);
        loader.put("callsSha256", Hashes.sha256(mapper.writer()
                .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(mapper.convertValue(calls, Object.class))));
        var phase = row.putObject("httpAcceptance").put("schemaVersion", 1)
                .put("authority", "managed_t4_http_acceptance_observed").put("status", "COMPLETED")
                .put("imageId", "sha256:" + "e".repeat(64)).put("currentChildJournalsVerified", true);
        phase.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
        var observed = phase.putObject("observation").put("schemaVersion", 1)
                .put("authority", "at_t4_http_owner_observed").put("scope", "t4-http-parameters-v1")
                .put("jobId", job).put("instanceId", instance).put("imageId", image)
                .put("bundleSha256", runtime.sha256()).put("runtimeSha256", fixture.sha256())
                .put("executionPolicySha256", runtime.policySha256())
                .put("egressPolicySha256", Hashes.sha256(policy.toString())).put("sourceSha256", Hashes.sha256(source))
                .put("inputProfileSha256", Hashes.sha256(profile.toString())).put("allPlannedCallsObserved", true)
                .put("rawOutputStored", false).put("formalAcceptanceIntegrated", false)
                .put("productNodePassesAdded", 0).put("publishEligible", false);
        var httpCalls = observed.putArray("calls");
        for (int i = 0; i < 36; i++)
            httpCalls.addObject().put("ordinal", i + 1).put("shape", List.of("get", "form", "json").get((i / 6) % 3))
                    .put("playType", i < 18 ? "t4" : "t4-direct")
                    .put("method", List.of("home", "category", "category", "detail", "search", "play").get(i % 6))
                    .put("httpStatus", 200).put("t4CallSha256", "d".repeat(64))
                    .put("brokerJobId", String.format("00000000-0000-4000-8000-%012d", i + 1))
                    .put("resultSha256", "e".repeat(64)).put("httpResponseSha256", "f".repeat(64))
                    .put("httpResponseBytes", 2).put("workerReturnedAfterCleanup", true);
        return row;
    }

    private ObjectNode businessRow(ManagedSourceRuntime runtime, ObjectNode policy) {
        var row = row(runtime);
        var result = (ObjectNode) row.path("result");
        result.set("policy", policy.deepCopy());
        result.put("egressPolicySha256", Hashes.sha256(policy.toString())).put("executionKind", "t4_business_sample");
        var ready = (ObjectNode) result.path("preSourceReady");
        ready.remove(List.of("entrySha256", "payloadSha256"));
        ready.set("files", runtime.manifest().path("egressFiles"));
        var counts = (ObjectNode) result.path("untrustedRuntimeSummary");
        counts.put("status", "detail_returned").put("PRIVATE_TOKEN", "PRIVATE");
        for (String field : List.of("homeClasses", "homeItems", "homeCovers", "categoryItems", "categoryCovers",
                "page2Items", "detailItems", "detailCovers", "playGroups", "playEpisodes")) counts.put(field, 20);
        result.putArray("httpObservations").addObject().put("method", "GET").put("pathSha256", "a".repeat(64))
                .put("responseSha256", "b".repeat(64)).put("responseStatus", 200)
                .put("responseBytes", 240799).put("elapsedMilliseconds", 100).put("cookie", "PRIVATE");
        result.putArray("deniedCodes");
        return row;
    }

    private ObjectNode diagnostic() {
        var value = mapper.createObjectNode().put("schemaVersion", 1)
                .put("authority", "managed_source_broker_observed").put("failureStage", "container_create")
                .put("primaryCode", "ENGINE_TIMEOUT").putNull("cleanupCode")
                .put("createIssued", true).put("createConfirmed", false).put("createAckBytes", 0);
        value.putArray("calls").addObject().put("operation", "container_create").put("outcome", "failed")
                .put("code", "ENGINE_TIMEOUT").put("elapsedMillis", 6030);
        return value;
    }

    private JsonNode reply(ManagedSourceRuntime runtime, ObjectNode request, ObjectNode row) {
        if (request.path("operation").asText().equals("identity"))
            return mapper.createObjectNode().put("authority", "managed_source_broker_configuration")
                    .put("instanceId", instance).put("imageId", image).put("bundleSha256", runtime.sha256())
                    .put("recoveryRequired", false);
        if (List.of("cancel", "cancel_acceptance").contains(request.path("operation").asText())) {
            var cancelled = row.deepCopy();
            if (cancelled.path("status").asText().equals("RUNNING")) {
                cancelled.put("status", "CANCELLED");
                cancelled.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
            }
            if (request.path("operation").asText().equals("cancel_acceptance")) {
                boolean confirmed = cancelled.path("cleanup").path("confirmed").asBoolean()
                        && cancelled.path("cleanup").path("containerAbsent").asBoolean();
                cancelled.put("acceptanceCancelled", true);
                cancelled.putObject("acceptanceCleanup").put("confirmed", confirmed)
                        .put("childJobs", 0).put("pendingJobs", 0).put("recoveryRequired", !confirmed);
            }
            return cancelled;
        }
        return row;
    }

    private ObjectNode row(ManagedSourceRuntime runtime) {
        ObjectNode row = mapper.createObjectNode().put("schemaVersion", 1)
                .put("authority", "managed_source_broker_observed").put("jobId", job)
                .put("instanceId", instance).put("imageId", image).put("bundleSha256", runtime.sha256())
                .put("executionPolicySha256", runtime.policySha256()).put("status", "COMPLETED");
        row.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
        ObjectNode result = row.putObject("result").put("schemaVersion", 1).put("authority", "managed_source_host_observed")
                .put("jobId", job).put("imageId", image).put("containerId", "c".repeat(64))
                .put("inputSourceSha256", Hashes.sha256(source)).put("inputSourceBytes", source.length())
                .put("sourceSentAfterControlAck", true).put("rawOutputStored", false)
                .put("businessChainVerified", false).put("untrustedStdoutBytes", 1).put("untrustedStderrBytes", 0);
        result.set("policy", runtime.manifest().path("policy").deepCopy());
        result.putObject("cleanup").put("confirmed", true).put("containerAbsent", true);
        result.putObject("containerState").put("status", "exited").put("exitCode", 0).put("oomKilled", false);
        result.putObject("untrustedRuntimeSummary").put("status", "init_returned")
                .put("evidenceClass", "untrusted_source_output");
        ObjectNode ready = result.putObject("preSourceReady").put("schemaVersion", 1).put("phase", "ready_before_source")
                .put("jobId", job).put("runtimeSha256", fixture.sha256())
                .put("entrySha256", runtime.manifest().path("entrySha256").asText())
                .put("payloadSha256", runtime.manifest().path("payloadSha256").asText());
        ObjectNode controls = ready.putObject("controls").put("uid", 65532).put("gid", 65532)
                .put("capEffective", 0).put("noNewPrivileges", 1).put("seccomp", 2)
                .put("memoryMax", 268435456).put("swapMax", 0).put("pidsMax", 64)
                .put("cpuQuota", 50000).put("cpuPeriod", 100000).put("workSizeBytes", 33554432)
                .put("rootReadOnly", true).put("allChecksPassed", true);
        controls.putArray("interfaces").add("lo");
        controls.putArray("coreLimit").add(0).add(0);
        controls.putArray("openFileLimit").add(128).add(128);
        controls.putArray("workMountOptions").add("rw").add("nosuid").add("nodev").add("noexec").add("relatime");
        return row;
    }

    private ObjectNode profile() { return mapper.createObjectNode().put("runtime", "t4").putNull("extFingerprint"); }
    private ManagedSourceRuntime runtime(boolean enabled, String socket) throws IOException {
        return new ManagedSourceRuntime(mapper, fixture, enabled, socket, instance, image, "", "");
    }
}
