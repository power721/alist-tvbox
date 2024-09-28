package cn.har01d.alist_tvbox.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/cat/**").addResourceLocations("file:/www/cat/");
        registry.addResourceHandler("/tvbox/**").addResourceLocations("file:/www/tvbox/");
        registry.addResourceHandler("/pg/**").addResourceLocations("file:/www/pg/");
        registry.addResourceHandler("/zx/**").addResourceLocations("file:/www/zx/");
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.setUseTrailingSlashMatch(true);
    }
}
