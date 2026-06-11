package cn.har01d.alist_tvbox.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.io.IOException;

@Configuration
public class StaticResourceCharsetConfig {

    @Bean
    public FilterRegistrationBean<Filter> staticResourceCharsetFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/static/*", "/tvbox/*", "/files/*", "/cat/*", "/pg/*", "/zx/*");
        registration.setFilter((request, response, chain) -> {
            chain.doFilter(request, new CharsetAttributeWrapper((HttpServletResponse) response));
        });
        return registration;
    }

    private static class CharsetAttributeWrapper extends HttpServletResponseWrapper {

        public CharsetAttributeWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setContentType(String type) {
            if (type != null && type.startsWith("text/") && !type.contains("charset")) {
                type = type + "; charset=UTF-8";
            }
            super.setContentType(type);
        }
    }
}
