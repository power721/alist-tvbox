package cn.har01d.alist_tvbox.config;

import cn.har01d.alist_tvbox.web.HealthController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * API 监控拦截器
 * 记录所有 API 调用的耗时
 */
@Slf4j
@Component
public class ApiMonitorInterceptor implements HandlerInterceptor {

    private static final ThreadLocal<Long> startTime = new ThreadLocal<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        startTime.set(System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Long start = startTime.get();
        if (start != null) {
            long duration = System.currentTimeMillis() - start;
            String api = request.getMethod() + " " + request.getRequestURI();

            // 记录到健康检查端点
            HealthController.recordApiCall(api, duration);

            // 如果超过 3 秒，打印警告日志
            if (duration > 3000) {
                log.warn("⚠️ 慢请求: {} 耗时 {}ms", api, duration);
            }

            startTime.remove();
        }
    }
}
