package cn.har01d.alist_tvbox.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 注册 API 监控拦截器
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private ApiMonitorInterceptor apiMonitorInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiMonitorInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/health/**");  // 排除健康检查端点本身
    }
}
