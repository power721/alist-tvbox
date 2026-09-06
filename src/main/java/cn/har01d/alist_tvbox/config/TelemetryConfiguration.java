package cn.har01d.alist_tvbox.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 * 上报端点注入点:CI 构建期(发布工作流)把 secrets.TELEMETRY_URL 写成
 * classpath:telemetry.properties(app.telemetry.url=...);文件缺省(源码自构建/
 * PR 构建)时该资源不存在,URL 保持空 → TelemetryService 完全静默。
 * 运行时环境变量/命令行优先级高于此文件,仍可覆盖。
 */
@Configuration
@PropertySource(value = "classpath:telemetry.properties", ignoreResourceNotFound = true)
public class TelemetryConfiguration {
}
