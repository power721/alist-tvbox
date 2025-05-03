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
}
