package cn.har01d.alist_tvbox.config;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@ControllerAdvice
public class RestErrorHandler extends ResponseEntityExceptionHandler {
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        log.warn("", ex);
        if (ex instanceof HttpMediaTypeException) {
            log.warn("body: {} {}, supportedMediaTypes: {}", ((HttpMediaTypeException) ex).getBody(), ((HttpMediaTypeException) ex).getSupportedMediaTypes());
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    @ExceptionHandler({BadRequestException.class})
    public ResponseEntity<Object> handleBadRequestException(Exception ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        HttpStatusCode status = HttpStatusCode.valueOf(400);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @ExceptionHandler({UserUnauthorizedException.class})
    public ResponseEntity<Object> handleUserUnauthorizedException(Exception ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        HttpStatusCode status = HttpStatusCode.valueOf(401);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @ExceptionHandler({NotFoundException.class})
    public ResponseEntity<Object> handleNotFoundException(Exception ex, WebRequest request) {
        HttpHeaders headers = new HttpHeaders();
        HttpStatusCode status = HttpStatusCode.valueOf(404);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * Catch-all exception handler to prevent sensitive information leakage.
     * Logs full exception details internally but returns sanitized message to client.
     */
    @ExceptionHandler({Exception.class})
    public ResponseEntity<Object> handleGenericException(Exception ex, WebRequest request) {
        // Log full exception with stack trace for debugging
        log.error("Unhandled exception in request: {}", request.getDescription(false), ex);

        HttpHeaders headers = new HttpHeaders();
        HttpStatusCode status = HttpStatusCode.valueOf(500);

        // Sanitize error message - never expose:
        // - Internal file paths
        // - Database schema/table names
        // - Stack traces
        // - Configuration details
        // - Third-party API keys/tokens
        String sanitizedMessage = sanitizeErrorMessage(ex.getMessage());

        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, sanitizedMessage);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * Sanitize error messages to prevent information disclosure.
     * Removes file paths, SQL details, and other sensitive information.
     */
    private String sanitizeErrorMessage(String message) {
        if (message == null) {
            return "An internal error occurred. Please contact support.";
        }

        // Remove file system paths (Unix and Windows)
        message = message.replaceAll("/[\\w/.-]+", "[PATH]");
        message = message.replaceAll("[A-Z]:\\\\[\\w\\\\.-]+", "[PATH]");

        // Remove SQL-related details
        message = message.replaceAll("(?i)table\\s+[\\w_]+", "table [REDACTED]");
        message = message.replaceAll("(?i)column\\s+[\\w_]+", "column [REDACTED]");
        message = message.replaceAll("(?i)database\\s+[\\w_]+", "database [REDACTED]");

        // Remove Java class names from stack traces
        message = message.replaceAll("\\w+(\\.\\w+)+Exception", "Exception");
        message = message.replaceAll("at\\s+[\\w.$]+\\([^)]+\\)", "");

        // If message is too technical or empty after sanitization, use generic message
        if (message.trim().isEmpty() || message.length() < 10) {
            return "An internal error occurred. Please contact support.";
        }

        return message.trim();
    }
}
