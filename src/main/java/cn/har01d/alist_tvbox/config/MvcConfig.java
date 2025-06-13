package cn.har01d.alist_tvbox.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import cn.har01d.alist_tvbox.util.Utils;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/cat/**").addResourceLocations("file:" + Utils.getWebPath("cat"));
        registry.addResourceHandler("/tvbox/**").addResourceLocations("file:" + Utils.getWebPath("tvbox"));
        registry.addResourceHandler("/files/**").addResourceLocations("file:" + Utils.getWebPath("files"));
        registry.addResourceHandler("/pg/**").addResourceLocations("file:" + Utils.getWebPath("pg"));
        registry.addResourceHandler("/zx/**").addResourceLocations("file:" + Utils.getWebPath("zx"));
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.setUseTrailingSlashMatch(true);
    }
}
