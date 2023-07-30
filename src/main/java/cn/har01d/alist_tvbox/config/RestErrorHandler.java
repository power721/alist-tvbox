package cn.har01d.alist_tvbox.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.bind.annotation.ControllerAdvice;
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
}
