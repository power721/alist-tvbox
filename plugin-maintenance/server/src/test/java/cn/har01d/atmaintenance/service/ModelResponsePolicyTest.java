package cn.har01d.atmaintenance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpHeaders;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelResponsePolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-11-16T01:00:00Z");
    private final HttpHeaders empty = HttpHeaders.of(Map.of(), (name, value) -> true);

    @Test
    void rateLimitsAndQuotaHaveDifferentRecovery() {
        var rate = http(429, "{\"error\":{\"type\":\"rate_limit_error\",\"code\":\"slow_down\"}}");
        assertEquals("MODEL_RATE_LIMITED", rate.errorCode());
        assertTrue(rate.responseDiagnostics().path("retryable").asBoolean());
        for (String code : List.of("insufficient_quota", "credit_balance_exhausted", "organization_usage_limit_exceeded")) {
            var quota = http(429, "{\"error\":{\"code\":\"" + code + "\"}}");
            assertEquals("MODEL_QUOTA_EXHAUSTED", quota.errorCode());
            assertFalse(quota.responseDiagnostics().path("retryable").asBoolean());
        }
        assertEquals("MODEL_QUOTA_EXHAUSTED", http(200, "{\"error\":\"余额不足\"}").errorCode());
    }

    @Test
    void permanentErrorsAndRedirectsPause() {
        for (int status : List.of(401, 403, 402, 400, 404, 413, 422, 302, 418)) {
            var reply = http(status, "<html>private-provider-token</html>");
            assertFalse(reply.responseDiagnostics().path("retryable").asBoolean());
            assertEquals(status, reply.responseDiagnostics().path("httpStatus").asInt());
            assertEquals("pause", reply.responseDiagnostics().path("action").asText());
            assertFalse(reply.responseDiagnostics().toString().contains("private-provider-token"));
        }
    }

    @Test
    void transientHtmlAndRelay200ErrorsCanRetry() {
        for (int status : List.of(408, 409, 425, 429, 500, 502, 503, 504, 529))
            assertTrue(http(status, "unavailable").responseDiagnostics().path("retryable").asBoolean());
        assertEquals("MODEL_SERVICE_UNAVAILABLE", http(200,
                "{\"error\":{\"type\":\"service_unavailable_error\",\"message\":\"private-url\"}}").errorCode());
        assertEquals("MODEL_REQUEST_REJECTED", http(200,
                "{\"error\":{\"code\":\"model_not_found\"}}").errorCode());
    }

    @Test
    void retryAfterAcceptsSecondsDateAndDoesNotInventAnEarlierDeadline() {
        assertEquals(120L, ModelResponsePolicy.retryAfter("120", now));
        assertEquals(7200L, ModelResponsePolicy.retryAfter("Mon, 16 Nov 2026 03:00:00 GMT", now));
        assertEquals(7200L, ModelResponsePolicy.retryAfter("Mon, 16 Nov 2026 03:00:00 GMT", now.plusMillis(900)));
        assertEquals(0L, ModelResponsePolicy.retryAfter("Mon, 16 Nov 2026 00:00:00 GMT", now));
        for (String value : List.of("-1", "tomorrow", "9999999999999999999999999999", "9223372036854775807"))
            assertNull(ModelResponsePolicy.retryAfter(value, now));
        var headers = HttpHeaders.of(Map.of("Retry-After", List.of("3600")), (name, value) -> true);
        var reply = ModelResponsePolicy.http(mapper, 503, headers, new byte[0], now);
        assertEquals(3600, reply.responseDiagnostics().path("retryAfterSeconds").asLong());
    }

    @Test
    void preConnectionFailuresDifferFromUnknownReadTimeoutAndTls() {
        for (Throwable cause : List.of(new ConnectException("private-host"), new UnknownHostException("private-host"))) {
            var reply = ModelResponsePolicy.transport(mapper, new ExecutionException(cause));
            assertEquals("MODEL_CONNECTION_FAILED", reply.errorCode());
            assertTrue(reply.responseDiagnostics().path("retryable").asBoolean());
            assertFalse(reply.responseDiagnostics().toString().contains("private-host"));
        }
        var unknown = ModelResponsePolicy.transport(mapper, new HttpTimeoutException("private-body"));
        assertEquals("MODEL_OUTCOME_UNKNOWN", unknown.errorCode());
        assertTrue(unknown.responseDiagnostics().path("outcomeUnknown").asBoolean());
        assertFalse(unknown.responseDiagnostics().path("retryable").asBoolean());
        var tls = ModelResponsePolicy.transport(mapper, new SSLHandshakeException("private-cert"));
        assertEquals("MODEL_TLS_FAILED", tls.errorCode());
        assertFalse(tls.responseDiagnostics().path("retryable").asBoolean());
    }

    @Test
    void errorWithReportedUsageCannotAutomaticallyResendAndOnlyCountersSurvive() {
        var reply = http(503, "{\"error\":{\"type\":\"server_error\"},\"usage\":{\"prompt_tokens\":10,"
                + "\"completion_tokens\":20,\"total_tokens\":30,\"secret\":\"private-token\"}}");
        assertFalse(reply.responseDiagnostics().path("retryable").asBoolean());
        assertEquals(30, reply.usage().path("total_tokens").asInt());
        assertFalse(reply.usage().toString().contains("private-token"));
        assertFalse(http(503, "{\"usage\":\"unknown\"}").responseDiagnostics().path("retryable").asBoolean());
    }

    private cn.har01d.atmaintenance.dto.ModelReply http(int status, String body) {
        return ModelResponsePolicy.http(mapper, status, empty, body.getBytes(StandardCharsets.UTF_8), now);
    }
}
