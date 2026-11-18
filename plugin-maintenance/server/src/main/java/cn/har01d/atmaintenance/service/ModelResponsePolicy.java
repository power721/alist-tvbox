package cn.har01d.atmaintenance.service;

import cn.har01d.atmaintenance.dto.ModelReply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.SSLException;

/** Interpret bounded provider data; never persist provider messages, headers or endpoint URLs. */
final class ModelResponsePolicy {
    private ModelResponsePolicy() {}

    /** Only an explicit operator recovery may reserve a new logical call after these failures. */
    static boolean explicitlyRecoverable(String code) {
        return ModelClient.recoverableResponseFailure(code) || code != null && Set.of(
                "MODEL_HTTP_ERROR", "MODEL_RATE_LIMITED", "MODEL_SERVICE_UNAVAILABLE", "MODEL_CONNECTION_FAILED")
                .contains(code);
    }

    static ModelReply http(ObjectMapper mapper, int status, HttpHeaders headers, byte[] bytes, Instant now) {
        JsonNode error = mapper.missingNode();
        JsonNode reportedUsage = null;
        try {
            JsonNode root = mapper.readTree(bytes);
            if (root != null && root.isObject()) {
                error = root.has("error") ? root.path("error") : root;
                reportedUsage = root.get("usage");
            }
        } catch (java.io.IOException ignored) {
            // HTML and plain text errors still carry an authoritative HTTP status.
        }
        String code = error.path("code").asText("").toLowerCase(Locale.ROOT);
        String type = error.path("type").asText("").toLowerCase(Locale.ROOT);
        String message = (error.isTextual() ? error.asText() : error.path("message").asText(""));
        message = message.substring(0, Math.min(2048, message.length())).toLowerCase(Locale.ROOT);
        String category;
        if (matches(code, type, "invalid_api_key", "authentication_error",
                "permission_denied", "access_terminated", "token_expired")) category = "authentication";
        else if (status == 402 || matches(code, type, "insufficient_quota", "billing_hard_limit_reached",
                "billing_not_active", "credit_balance_exhausted", "balance_not_enough", "insufficient_balance",
                "organization_usage_limit_exceeded", "spend_limit_exceeded")
                || message.contains("insufficient quota") || message.contains("insufficient_quota")
                || message.contains("insufficient balance") || message.contains("credit balance")
                || message.contains("余额不足") || message.contains("额度已用尽")) category = "quota";
        else if (status == 401 || status == 403) category = "authentication";
        else if (matches(code, type, "model_not_found", "context_length_exceeded",
                "unsupported_model", "invalid_parameter", "content_policy_violation")
                || Set.of(400, 404, 405, 413, 415, 422).contains(status)) category = "request";
        else if (status == 429 || matches(code, type, "rate_limit_exceeded", "rate_limit_error", "slow_down",
                "too_many_requests")) category = "rate_limit";
        else if (Set.of(408, 409, 425, 500, 502, 503, 504, 529).contains(status)
                || matches(code, type, "server_error", "service_unavailable", "service_unavailable_error",
                        "server_is_overloaded", "overloaded_error"))
            category = "temporary_service";
        else if (status >= 300 && status < 400) category = "redirect";
        else category = "unclassified";
        String failure = switch (category) {
            case "authentication" -> "MODEL_AUTHENTICATION_REJECTED";
            case "quota" -> "MODEL_QUOTA_EXHAUSTED";
            case "request" -> "MODEL_REQUEST_REJECTED";
            case "rate_limit" -> "MODEL_RATE_LIMITED";
            case "temporary_service" -> "MODEL_SERVICE_UNAVAILABLE";
            case "redirect" -> "MODEL_REDIRECT_REFUSED";
            default -> "MODEL_HTTP_ERROR";
        };
        boolean retryable = category.equals("rate_limit") || category.equals("temporary_service");
        var diagnostic = mapper.createObjectNode().put("schemaVersion", 2).put("stage", failure)
                .put("httpStatus", status).put("failureCategory", category).put("retryable", retryable)
                .put("action", retryable ? "wait" : "pause").put("outcomeUnknown", false)
                .put("responseBytes", bytes.length).put("responseSha256", Hashes.sha256(bytes));
        Long retryAfter = retryAfter(headers.firstValue("Retry-After").orElse(""), now);
        if (retryAfter != null) diagnostic.put("retryAfterSeconds", retryAfter);
        JsonNode usage = null;
        if (reportedUsage != null && !reportedUsage.isNull()) {
            diagnostic.put("retryable", false).put("action", "pause")
                    .put("providerUsageReported", true).put("pauseReason", "MODEL_ERROR_USAGE_PRESENT");
            boolean valid = reportedUsage.isObject();
            for (String field : Set.of("prompt_tokens", "completion_tokens", "total_tokens")) {
                var value = reportedUsage.path(field);
                valid &= value.isIntegralNumber() && value.canConvertToLong()
                        && value.asLong() >= 0 && value.asLong() <= 100000000;
            }
            if (valid && reportedUsage.path("total_tokens").asLong() == reportedUsage.path("prompt_tokens").asLong()
                    + reportedUsage.path("completion_tokens").asLong())
                usage = mapper.createObjectNode().put("prompt_tokens", reportedUsage.path("prompt_tokens").asLong())
                        .put("completion_tokens", reportedUsage.path("completion_tokens").asLong())
                        .put("total_tokens", reportedUsage.path("total_tokens").asLong());
        }
        return new ModelReply(null, usage, failure, diagnostic);
    }

    static ModelReply transport(ObjectMapper mapper, Throwable failure) {
        boolean connection = false, tls = false, oversized = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            connection |= cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException || cause instanceof HttpConnectTimeoutException;
            tls |= cause instanceof SSLException;
            oversized |= "MODEL_RESPONSE_TOO_LARGE".equals(cause.getMessage());
        }
        String code = oversized ? "MODEL_RESPONSE_TOO_LARGE" : tls ? "MODEL_TLS_FAILED"
                : connection ? "MODEL_CONNECTION_FAILED" : "MODEL_OUTCOME_UNKNOWN";
        var diagnostic = mapper.createObjectNode().put("schemaVersion", 2).put("stage", code)
                .put("failureCategory", oversized ? "response_size" : tls ? "tls"
                        : connection ? "connection" : "outcome_unknown")
                .put("retryable", connection && !tls && !oversized)
                .put("action", connection && !tls && !oversized ? "retry" : "pause")
                .put("outcomeUnknown", !oversized && !tls && !connection);
        return new ModelReply(null, null, code, diagnostic);
    }

    static Long retryAfter(String value, Instant now) {
        if (value == null || value.isBlank() || value.length() > 128) return null;
        try {
            long seconds;
            if (value.trim().matches("[0-9]+")) seconds = Long.parseLong(value.trim());
            else {
                var delay = java.time.Duration.between(now,
                        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                seconds = Math.max(0, delay.getSeconds() + (delay.getNano() > 0 ? 1 : 0));
            }
            now.plusSeconds(seconds);
            return seconds;
        } catch (IllegalArgumentException | java.time.DateTimeException | ArithmeticException ignored) {
            return null;
        }
    }

    private static boolean matches(String code, String type, String... values) {
        for (String value : values) if (value.equals(code) || value.equals(type)) return true;
        return false;
    }
}
