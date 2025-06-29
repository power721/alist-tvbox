package cn.har01d.alist_tvbox.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebDavProxyFilter implements Filter {
    private static final Logger logger = LoggerFactory.getLogger(WebDavProxyFilter.class);
    private final OkHttpClient okHttpClient;
    private final String backendUrl = "http://127.0.0.1:5244";

    public WebDavProxyFilter() {
        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        logger.debug("WebDavProxyFilter {}", backendUrl);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (!shouldProcessRequest(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        //logRequestDetails(httpRequest);

        try {
            String targetUrl = buildTargetUrl(httpRequest);

            Request proxyRequest = buildProxyRequest(httpRequest, targetUrl);

            try (Response backendResponse = okHttpClient.newCall(proxyRequest).execute()) {
                //logResponseDetails(backendResponse);

                ensureRequiredHeaders(httpResponse);

                if (backendResponse.code() == 405 && "OPTIONS".equals(httpRequest.getMethod())) {
                    handleOptionsResponse(httpResponse);
                    return;
                }

                copyResponse(backendResponse, httpResponse);
            }
        } catch (Exception e) {
            logger.warn("WebDAV proxy error: {}", e.getMessage());
            try {
                httpResponse.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            } catch (Exception ex) {
                logger.debug("sendError: {}", ex.getMessage());
            }
        }
    }

    private void logRequestDetails(HttpServletRequest request) {
        logger.debug("=== Incoming Request ===");
        logger.debug("Method: {}", request.getMethod());
        logger.debug("Request URI: {}", request.getRequestURI());
        logger.debug("Headers:");

        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            logger.debug("  {}: {}", name, request.getHeader(name));
        }

        if (request.getContentLength() > 0) {
            logger.debug("Content Type: {}", request.getContentType());
            logger.debug("Content Length: {}", request.getContentLength());
        }
    }

    private void logResponseDetails(Response response) {
        logger.debug("=== Backend Response ===");
        logger.debug("Status: {}", response.code());
        logger.debug("Message: {}", response.message());
        logger.debug("Headers:");

        response.headers().forEach(pair -> {
            logger.debug("  {}: {}", pair.getFirst(), pair.getSecond());
        });
    }

    private boolean shouldProcessRequest(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/dav");
    }

    private String buildTargetUrl(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String queryString = request.getQueryString();
        return backendUrl + path + (queryString != null ? "?" + queryString : "");
    }

    private Request buildProxyRequest(HttpServletRequest httpRequest, String targetUrl) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(targetUrl)
                .method(httpRequest.getMethod(), createRequestBody(httpRequest));

        Enumeration<String> headerNames = httpRequest.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (!skipHeader(name)) {
                Enumeration<String> values = httpRequest.getHeaders(name);
                while (values.hasMoreElements()) {
                    builder.addHeader(name, values.nextElement());
                }
            }
        }

        builder.addHeader("DAV", "1,2");

        if ("PROPFIND".equalsIgnoreCase(httpRequest.getMethod())) {
            builder.addHeader("Content-Type", "text/xml; charset=utf-8");
            if (httpRequest.getHeader("Depth") == null) {
                builder.addHeader("Depth", "1");
            }
        }

        return builder.build();
    }

    private boolean skipHeader(String headerName) {
        return headerName.equalsIgnoreCase("Content-Length") ||
                headerName.equalsIgnoreCase("Host");
    }

    private RequestBody createRequestBody(HttpServletRequest request) throws IOException {
        if (request.getContentLength() <= 0 ||
                "GET".equalsIgnoreCase(request.getMethod()) ||
                "HEAD".equalsIgnoreCase(request.getMethod())) {
            return null;
        }

        byte[] content = request.getInputStream().readAllBytes();
        MediaType mediaType = MediaType.parse(
                request.getContentType() != null ?
                        request.getContentType() :
                        "application/octet-stream"
        );

        return RequestBody.create(content, mediaType);
    }

    private void handleOptionsResponse(HttpServletResponse httpResponse) {
        httpResponse.setHeader("Allow", "OPTIONS, GET, HEAD, POST, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK");
        httpResponse.setHeader("DAV", "1,2");
        httpResponse.setStatus(HttpServletResponse.SC_OK);
    }

    private void copyResponse(Response source, HttpServletResponse target) throws IOException {
        target.setStatus(source.code());

        source.headers().forEach(pair -> {
            if (!skipResponseHeader(pair.getFirst())) {
                String headerName = pair.getFirst();
                String headerValue = pair.getSecond();

                if ("Content-Disposition".equalsIgnoreCase(headerName)) {
                    headerValue = normalizeContentDisposition(headerValue);
                }

                target.addHeader(headerName, headerValue);
            }
        });

        if (source.body() != null) {
            source.body().byteStream().transferTo(target.getOutputStream());
        }
    }

    private String normalizeContentDisposition(String original) {
        if (original.contains("filename*=UTF-8''")) {
            int start = original.indexOf("UTF-8''") + 7;
            String encodedFilename = original.substring(start);
            return "attachment; filename*=UTF-8''" + encodedFilename;
        }
        return original;
    }

    private void ensureRequiredHeaders(HttpServletResponse response) {
        if (!response.containsHeader("Content-Type")) {
            response.setContentType("text/xml; charset=utf-8");
        }
        if (!response.containsHeader("DAV")) {
            response.addHeader("DAV", "1,2");
        }
    }

    private boolean skipResponseHeader(String headerName) {
        return headerName.equalsIgnoreCase("Transfer-Encoding") ||
                headerName.equalsIgnoreCase("Connection");
    }
}
